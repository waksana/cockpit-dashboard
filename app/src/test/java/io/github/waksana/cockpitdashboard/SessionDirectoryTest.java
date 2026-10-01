package io.github.waksana.cockpitdashboard;

import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class SessionDirectoryTest {
    private JSONObject entry(String id, String title) throws JSONException {
        return new JSONObject().put("sessionId", id).put("title", title).put("cwd", "/project");
    }

    @Test public void directoryPreservesAllRolesAndStatusesAndDuplicateTitles() throws Exception {
        JSONArray rows = new JSONArray().put(entry("ordinary", "Same").put("roles", new JSONArray()))
                .put(entry("assistant", "Same").put("roles", new JSONArray().put(
                        new JSONObject().put("moduleId", "assistant").put("roleId", "assistant"))))
                .put(entry("unloaded", "").put("loaded", false))
                .put(entry("error", "Error").put("status", "error"));
        SessionDirectory page = new SessionDirectory(new JSONObject().put("sessions", rows).put("cursor", "opaque"));
        assertEquals(4, page.entries.size());
        assertEquals("ordinary", page.entries.get(0).id);
        assertEquals("assistant", page.entries.get(1).id);
        assertNotEquals(page.entries.get(0).label("ordinary"), page.entries.get(1).label("ordinary"));
        assertTrue(page.entries.get(0).label("ordinary").startsWith("✓"));
        assertTrue(page.entries.get(2).label("").contains("未命名会话"));
        assertEquals("opaque", page.next);
    }

    @Test public void emptyLastPageAndMalformedResponsesAreExplicit() throws Exception {
        SessionDirectory empty = new SessionDirectory(new JSONObject().put("sessions", new JSONArray()));
        assertTrue(empty.entries.isEmpty());
        assertNull(empty.next);
        assertThrows(JSONException.class, () -> new SessionDirectory(new JSONObject().put("sessions",
                new JSONArray().put(entry("same", "A")).put(entry("same", "B")))));
        assertThrows(JSONException.class, () -> new SessionDirectory(new JSONObject().put("sessions",
                new JSONArray().put(entry("bad/id", "A")))));
        assertThrows(JSONException.class, () -> new SessionDirectory(new JSONObject().put("sessions",
                new JSONArray()).put("cursor", "")));
    }

    @Test public void directoryUsesOnlyPassiveBoundedEndpointWithOpaqueContinuation() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            HostClient client = new HostClient(server.url("/"), "", "", new OkHttpClient());
            server.enqueue(new MockResponse().setBody(new JSONObject().put("sessions",
                    new JSONArray().put(entry("pick-me", "Hello"))).put("cursor", "next").toString()));
            server.enqueue(new MockResponse().setBody("{\"sessions\":[]}"));
            assertEquals("next", client.directory(null).next);
            assertNull(client.directory("next").next);
            RecordedRequest first = server.takeRequest(), second = server.takeRequest();
            assertEquals("/intent/session/directory", first.getPath());
            assertEquals("/intent/session/directory", second.getPath());
            assertEquals("{\"limit\":50}", first.getBody().readUtf8());
            JSONObject next = new JSONObject(second.getBody().readUtf8());
            assertEquals("next", next.getString("cursor"));
            assertFalse(next.has("roles"));
            assertFalse(next.has("sessionId"));
            assertEquals(2, server.getRequestCount());
        }
    }

    private Object get(MainActivity activity, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }

    private boolean hasInput(View view) {
        if (view instanceof EditText) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (hasInput(group.getChildAt(i))) return true;
        }
        return false;
    }

    private int inputCount(View view) {
        if (view instanceof EditText) return 1;
        int result = 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) result += inputCount(group.getChildAt(i));
        }
        return result;
    }

    @Test public void freshLaunchRequiresOnlyHostAndHasNoBundledPersonalAddressOrSessionId() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create().start()) {
            MainActivity activity = controller.get();
            Field checked = MainActivity.class.getDeclaredField("checkedForUpdates");
            checked.setAccessible(true);
            checked.set(activity, true);
            controller.resume();
            JSONObject settings = (JSONObject) get(activity, "settings");
            assertEquals("", settings.optString("address"));
            assertFalse(settings.has("sessionId"));
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertEquals("保存并扫码登录", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals(1, inputCount(dialog.getWindow().getDecorView()));
        }
    }

    @Test public void hostnameDefaultsToHttpsButUnsafeSchemesAndPathsStayRejected() {
        assertEquals("https://cockpit.example.com/", HostClient.normalizeAddress(" cockpit.example.com "));
        assertEquals("https://cockpit.example.com/", HostClient.normalizeAddress("https://cockpit.example.com"));
        for (String bad : new String[]{"", "http://example.com", "example.com/path", "user@example.com", "example.com?token=x"}) {
            assertThrows(IllegalArgumentException.class, () -> HostClient.normalizeAddress(bad));
        }
    }

    @Test public void menuContinuationRunsAfterDismissAndDoesNotClearReplacement() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            Field foreground = MainActivity.class.getDeclaredField("foreground");
            foreground.setAccessible(true);
            foreground.set(activity, true);
            Method menu = MainActivity.class.getDeclaredMethod("showMenu");
            menu.setAccessible(true);
            menu.invoke(activity);
            AlertDialog old = ShadowAlertDialog.getLatestAlertDialog();
            old.getListView().performItemClick(null, 3, 3);
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog advanced = ShadowAlertDialog.getLatestAlertDialog();
            assertNotSame(old, advanced);
            assertFalse(old.isShowing());
            assertTrue(advanced.isShowing());
            assertSame(advanced, get(activity, "navigation"));
            assertEquals(true, get(activity, "dialog"));
            activity.onPause();
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse(advanced.isShowing());
            assertNull(get(activity, "navigation"));
            assertEquals(false, get(activity, "dialog"));
        }
    }

    @Test public void loadingDismissCanReplaceDialogWithoutLosingNewReference() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            Field foreground = MainActivity.class.getDeclaredField("foreground");
            foreground.setAccessible(true);
            foreground.set(activity, true);
            Method show = MainActivity.class.getDeclaredMethod("showNavigation",
                    AlertDialog.class, Runnable[].class, Runnable.class);
            show.setAccessible(true);
            AlertDialog first = new AlertDialog.Builder(activity).setMessage("loading").create();
            AlertDialog second = new AlertDialog.Builder(activity).setMessage("page").create();
            Runnable next = () -> {
                try { show.invoke(activity, second, new Runnable[1], (Runnable) () -> {}); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            };
            show.invoke(activity, first, new Runnable[]{next}, (Runnable) () -> {});
            first.dismiss();
            shadowOf(Looper.getMainLooper()).idle();
            assertSame(second, get(activity, "navigation"));
            assertTrue(second.isShowing());
            assertEquals(true, get(activity, "dialog"));
            activity.onPause();
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    @Test public void draftSendingAndUnknownBlockSelectionWithoutChangingDestination() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            RemoteState state = (RemoteState) get(activity, "state");
            JSONObject settings = (JSONObject) get(activity, "settings");
            settings.put("sessionId", "original");
            Method allowed = MainActivity.class.getDeclaredMethod("canSwitchSession");
            allowed.setAccessible(true);
            for (RemoteState.Phase phase : new RemoteState.Phase[]{
                    RemoteState.Phase.DRAFT, RemoteState.Phase.SENDING, RemoteState.Phase.UNKNOWN}) {
                state.phase = phase;
                state.draft = "retain";
                assertEquals(false, allowed.invoke(activity));
                assertEquals("original", settings.getString("sessionId"));
                assertEquals("retain", state.draft);
            }
            state.clear();
            assertEquals(true, allowed.invoke(activity));
        }
    }
}
