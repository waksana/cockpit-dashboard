package io.github.waksana.cockpitdashboard;

import android.view.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MainActivityTest {
    private RemoteState state(MainActivity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("state");
        field.setAccessible(true);
        return (RemoteState) field.get(activity);
    }

    @Test public void confirmWithoutConnectionDoesNotCapture() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)));
            assertEquals(RemoteState.Phase.IDLE, state(activity).phase);
            assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)));
            assertEquals(RemoteState.Phase.IDLE, state(activity).phase);
        }
    }

    @Test public void focusLossCancelsRecordingButRetainsCompletedDraft() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            RemoteState state = state(activity);
            state.down(23, 0);
            activity.onWindowFocusChanged(false);
            assertEquals(RemoteState.Phase.IDLE, state.phase);
            assertEquals(RemoteState.Action.NONE, state.up(23));
            state.transcript("retain");
            activity.onWindowFocusChanged(false);
            assertEquals(RemoteState.Phase.DRAFT, state.phase);
            assertEquals("retain", state.draft);
        }
    }

    @Test public void detachedReplyOrRepeatCannotSendDraft() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            RemoteState state = state(activity);
            state.transcript("draft");
            activity.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 2));
            assertEquals(RemoteState.Phase.DRAFT, state.phase);
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
            assertEquals(RemoteState.Phase.DRAFT, state.phase);
            assertEquals("draft", state.draft);
        }
    }

    @Test public void releaseBeforeUsbReadyCancelsInsteadOfWaitingForever() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            RemoteState state = state(activity);
            state.down(KeyEvent.KEYCODE_DPAD_CENTER, 0);
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
            assertEquals(RemoteState.Phase.IDLE, state.phase);
            assertEquals("", state.draft);
        }

    }

    @Test public void appendWithoutConnectionOrCancelledSegmentKeepsDraftAndOriginalAsk() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            RemoteState state = state(activity);
            JSONObject target = new JSONObject().put("requestId", "old-question");
            set(activity, "target", target);
            state.transcript("edited original");
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
            assertEquals(RemoteState.Phase.DRAFT, state.phase);
            assertEquals("edited original", state.draft);
            state.down(KeyEvent.KEYCODE_DPAD_CENTER, 0);
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
            assertEquals("edited original", state.draft);
            assertEquals(RemoteState.Phase.DRAFT, state.phase);
            assertFalse(state.finishSegment("late result"));
            Field field = MainActivity.class.getDeclaredField("target");
            field.setAccessible(true);
            assertSame(target, field.get(activity));
        }
    }

    @Test public void closingAndBackgroundKeepLoginAndUnknownDraft() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            Field field = MainActivity.class.getDeclaredField("settings");
            field.setAccessible(true);
            JSONObject settings = (JSONObject) field.get(activity);
            JSONObject session = new GateSession("https://example.com/", "A".repeat(43),
                    System.currentTimeMillis() + 60000).toJSON();
            settings.put("gateSession", session);
            state(activity).restore("possibly sent", true);
            activity.onPause();
            activity.finish();
            assertSame(session, settings.getJSONObject("gateSession"));
            assertEquals("possibly sent", state(activity).draft);
            assertEquals(RemoteState.Phase.UNKNOWN, state(activity).phase);
        }
    }

    private void set(MainActivity activity, String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(activity, value);
    }

    private boolean allowed(MainActivity activity, String method) throws Exception {
        Method declared = MainActivity.class.getDeclaredMethod(method);
        declared.setAccessible(true);
        return (Boolean) declared.invoke(activity);
    }

    @Test public void reauthenticationKeepsUnknownTargetButCannotSwitchHostOrSession() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            set(activity, "foreground", true);
            set(activity, "storageFailed", false);
            JSONObject target = new JSONObject().put("requestId", "original-question");
            set(activity, "target", target);
            state(activity).restore("possibly sent", true);
            assertTrue(allowed(activity, "authenticationAvailable"));
            assertFalse(allowed(activity, "canSwitchSession"));
            assertEquals(RemoteState.Phase.UNKNOWN, state(activity).phase);
            assertEquals("possibly sent", state(activity).draft);
            Field field = MainActivity.class.getDeclaredField("target");
            field.setAccessible(true);
            assertSame(target, field.get(activity));
            state(activity).phase = RemoteState.Phase.SENDING;
            assertFalse(allowed(activity, "authenticationAvailable"));
            assertFalse(allowed(activity, "canSwitchSession"));
        }
    }

    @Test public void recordingAndRevocationBlockLoginChanges() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            set(activity, "foreground", true);
            set(activity, "storageFailed", false);
            for (RemoteState.Phase phase : new RemoteState.Phase[]{RemoteState.Phase.RECORDING,
                    RemoteState.Phase.TRANSCRIBING}) {
                state(activity).phase = phase;
                assertFalse(allowed(activity, "authenticationAvailable"));
            }
            state(activity).phase = RemoteState.Phase.IDLE;
            set(activity, "revoking", true);
            assertFalse(allowed(activity, "authenticationAvailable"));
        }
    }
}
