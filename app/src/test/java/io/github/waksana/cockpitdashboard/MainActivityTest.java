package io.github.waksana.cockpitdashboard;

import android.view.KeyEvent;
import java.lang.reflect.Field;
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
            activity.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 2));
            assertEquals(RemoteState.Phase.DRAFT, state.phase);
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
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
}
