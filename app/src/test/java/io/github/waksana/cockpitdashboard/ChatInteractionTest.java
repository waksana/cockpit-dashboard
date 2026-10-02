package io.github.waksana.cockpitdashboard;

import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.HorizontalScrollView;
import android.content.Intent;
import java.time.Duration;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 28}, shadows = ChatInteractionTest.MemoryStore.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ChatInteractionTest {
    @Implements(PrivateStore.class)
    public static class MemoryStore {
        static String saved = "{}";
        @Implementation protected JSONObject read() throws Exception { return new JSONObject(saved); }
        @Implementation protected void write(JSONObject value) { saved = value.toString(); }
    }

    @Before public void reset() { MemoryStore.saved = "{}"; }

    private Object field(MainActivity activity, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }

    private void set(MainActivity activity, String name, Object value) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(activity, value);
    }

    private void render(MainActivity activity) throws Exception {
        Method render = MainActivity.class.getDeclaredMethod("renderState");
        render.setAccessible(true);
        render.invoke(activity);
    }

    private JSONObject ask(String id) throws Exception {
        return new JSONObject().put("requestId", id).put("question", "Choose one")
                .put("choices", new JSONArray().put("First").put("Second")).put("allowFreeform", true);
    }

    private void ready(MainActivity activity, JSONObject ask) throws Exception {
        set(activity, "foreground", true);
        set(activity, "connected", true);
        set(activity, "meta", new JSONObject().put("ask", ask).put("status", "idle"));
        render(activity);
    }

    private void key(MainActivity activity, int action, int key) {
        activity.dispatchKeyEvent(new KeyEvent(action, key));
    }

    @Test public void arrowsSelectShortConfirmPreviewsAndLeftCancelsWithoutSubmitting() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            ready(activity, ask("q1"));
            ActiveQuestionView card = (ActiveQuestionView) field(activity, "activeQuestion");
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
            assertEquals("First", card.selectedChoice());
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
            assertEquals("Second", card.selectedChoice());
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER);
            key(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER);
            RemoteState state = (RemoteState) field(activity, "state");
            assertEquals("Second", state.draft);
            assertEquals(RemoteState.Phase.DRAFT, state.phase);
            assertEquals("q1", ((JSONObject) field(activity, "target")).getString("requestId"));
            assertNull(field(activity, "client"));
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT);
            assertEquals(RemoteState.Phase.IDLE, state.phase);
            assertEquals("", state.draft);
        }
    }

    @Test public void replacedAskAndBackgroundCancelPendingShortConfirm() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            ready(activity, ask("old"));
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER);
            ready(activity, ask("new"));
            key(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER);
            assertEquals("", ((RemoteState) field(activity, "state")).draft);
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER);
            activity.onPause();
            shadowOf(Looper.getMainLooper()).idle();
            key(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER);
            assertEquals("", ((RemoteState) field(activity, "state")).draft);
            assertEquals(-1, field(activity, "previewKey"));
        }

    }

    @Test public void heldChoiceConfirmDoesNotPreviewOrSendAndRetainsDraftOnRecordingFailure() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            ready(activity, ask("q1"));
            RemoteState state = (RemoteState) field(activity, "state");
            state.transcript("manually edited");
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER);
            set(activity, "connected", false);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout()));
            key(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER);
            assertEquals("manually edited", state.draft);
            assertEquals(RemoteState.Phase.DRAFT, state.phase);
            assertEquals(-1, field(activity, "previewKey"));
            assertNull(field(activity, "client"));
        }
    }

    private void layout(MainActivity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 480, 360);
    }

    private void shortConfirm(MainActivity activity) {
        key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER);
        key(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER);
    }

    @Test public void remoteReadingOpensSelectedLinkAndScrollsTableWithoutRecordingOrSending() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            set(activity, "foreground", true);
            ViewGroup conversation = (ViewGroup) field(activity, "conversation");
            conversation.removeAllViews();
            ViewGroup body = (ViewGroup) new ChatMarkdown(activity).render(
                    "[First](https://example.com/first) [Second](https://example.com/second)\n\n"
                    + "| One | Two | Three | Four |\n| --- | --- | --- | --- |\n| A | B | C | D |");
            conversation.addView(body);
            layout(activity);
            MarkdownNavigation navigation = (MarkdownNavigation) field(activity, "markdownNavigation");
            shortConfirm(activity);
            assertTrue(navigation.active());
            assertNull(shadowOf(activity).getNextStartedActivity());
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
            shortConfirm(activity);
            Intent opened = shadowOf(activity).getNextStartedActivity();
            assertNotNull(opened);
            assertEquals("https://example.com/second", opened.getDataString());
            assertNull(shadowOf(activity).getNextStartedActivity());
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
            HorizontalScrollView table = (HorizontalScrollView) body.getChildAt(1);
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT);
            assertTrue(table.getScrollX() > 0);
            key(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT);
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT);
            assertEquals(0, table.getScrollX());
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
            key(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK);
            assertFalse(navigation.active());
            assertFalse((Boolean) field(activity, "dialog"));
            assertEquals(RemoteState.Phase.IDLE, ((RemoteState) field(activity, "state")).phase);
        }
    }

    @Test public void holdingSelectedLinkRecordsInsteadOfOpeningAndRebuildInvalidatesSelection() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            set(activity, "foreground", true);
            ViewGroup conversation = (ViewGroup) field(activity, "conversation");
            conversation.removeAllViews();
            conversation.addView(new ChatMarkdown(activity).render("[Link](https://example.com)"));
            layout(activity);
            shortConfirm(activity);
            MarkdownNavigation navigation = (MarkdownNavigation) field(activity, "markdownNavigation");
            assertTrue(navigation.active());
            key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER);
            activity.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 2));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout()));
            key(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER);
            assertFalse(navigation.active());
            assertNull(shadowOf(activity).getNextStartedActivity());
            assertEquals(RemoteState.Phase.IDLE, ((RemoteState) field(activity, "state")).phase);
            shortConfirm(activity);
            assertTrue(navigation.active());
            Method renderMessages = MainActivity.class.getDeclaredMethod("renderMessages", boolean.class);
            renderMessages.setAccessible(true);
            renderMessages.invoke(activity, false);
            assertFalse(navigation.active());
        }
    }

    private String visibleText(View view) {
        if (view.getVisibility() != View.VISIBLE) return "";
        StringBuilder text = new StringBuilder();
        if (view instanceof TextView) text.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) text.append(visibleText(group.getChildAt(i)));
        }
        return text.toString();
    }

    @Test public void durableQuestionAndAnswerRenderAfterRecreationWithoutActionableHistory() throws Exception {
        JSONArray journal = new JSONArray("["
                + "{\"id\":\"start\",\"type\":\"tool.execution_start\",\"data\":{\"toolCallId\":\"native-call\","
                + "\"toolName\":\"ask_user\",\"arguments\":{\"question\":\"**Choose?**\",\"choices\":[\"Yes\",\"No\"]}}},"
                + "{\"id\":\"result\",\"type\":\"tool.execution_complete\",\"data\":{\"toolCallId\":\"native-call\","
                + "\"success\":true,\"result\":{\"content\":\"User selected: Yes\"}}}]");
        String before = null;
        for (int restart = 0; restart < 2; restart++) {
            try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
                MainActivity activity = controller.get();
                set(activity, "foreground", true);
                set(activity, "connected", true);
                set(activity, "meta", new JSONObject().put("ask", ask("stale-callback"))
                        .put("decisions", new JSONArray()));
                ((ChatProjection) field(activity, "projection")).accept(journal, false);
                Method renderMessages = MainActivity.class.getDeclaredMethod("renderMessages", boolean.class);
                renderMessages.setAccessible(true);
                renderMessages.invoke(activity, false);
                render(activity);
                String contents = visibleText((View) field(activity, "conversation"));
                assertTrue(contents.contains("历史问题 · 已回答"));
                assertTrue(contents.contains("Choose?"));
                assertFalse(contents.contains("**Choose?**"));
                assertTrue(contents.contains("1. Yes"));
                assertTrue(contents.contains("你 · 问题回复"));
                assertTrue(contents.contains("\nYes\n"));
                assertEquals(View.GONE, ((View) field(activity, "activeQuestion")).getVisibility());
                assertNull(field(activity, "target"));
                key(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT);
                assertEquals(RemoteState.Phase.IDLE, ((RemoteState) field(activity, "state")).phase);
                if (before != null) assertEquals(before, contents);
                before = contents;
            }
        }
    }

    @Test public void updateDiagnosticsAreBoundedPersistedAndAbsentFromChatUnlessFailed() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            AppUpdater updater = (AppUpdater) field(activity, "updater");
            Field listenerField = AppUpdater.class.getDeclaredField("listener");
            listenerField.setAccessible(true);
            AppUpdater.Listener listener = (AppUpdater.Listener) listenerField.get(updater);
            for (int i = 0; i < 25; i++) listener.status("应用更新检查完成 " + i);
            JSONObject settings = (JSONObject) field(activity, "settings");
            assertEquals(16, settings.getJSONArray("updateDiagnostics").length());
            String status = ((TextView) field(activity, "status")).getText().toString();
            assertFalse(status.contains("按住"));
            assertFalse(status.contains("更新检查完成"));
            listener.failure("APK_DOWNLOAD/HTTP HTTP=403 API=23");
            assertTrue(((TextView) field(activity, "status")).getText().toString().contains("应用更新失败"));
            assertFalse(((TextView) field(activity, "status")).getText().toString().contains("HTTP=403"));
        }
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            JSONObject settings = (JSONObject) field(controller.get(), "settings");
            JSONArray logs = settings.getJSONArray("updateDiagnostics");
            assertEquals(16, logs.length());
            assertTrue(logs.getString(15).contains("HTTP=403"));
        }
    }
}
