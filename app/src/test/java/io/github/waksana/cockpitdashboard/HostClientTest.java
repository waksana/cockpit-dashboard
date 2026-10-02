package io.github.waksana.cockpitdashboard;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HostClientTest {
    private MockWebServer server;
    private HostClient client;
    @Before public void setup() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new HostClient(server.url("/"), "single-session", "Bearer synthetic",
                new OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
                        .readTimeout(1, TimeUnit.SECONDS).build());
    }
    @After public void close() throws Exception { server.shutdown(); }

    @Test public void promptUsesExactSessionEnqueueAndOneRequest() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"queued\":true}"));
        assertTrue(client.send("hello", null).getBoolean("queued"));
        RecordedRequest request = server.takeRequest();
        assertEquals("/intent/prompt", request.getPath());
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        assertEquals("single-session", body.getString("sessionId"));
        assertEquals("enqueue", body.getString("mode"));
        assertEquals("hello", body.getString("text"));
        assertEquals(1, server.getRequestCount());
    }

    @Test public void exactNativeAskUsesRequestIdAndFreeformFlag() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"meta\":{\"sessionId\":\"single-session\","
                + "\"ask\":{\"requestId\":\"ask-1\",\"choices\":[\"Yes\"],\"allowFreeform\":false}}}"));
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        client.send("Yes", new JSONObject().put("requestId", "ask-1"));
        server.takeRequest();
        RecordedRequest request = server.takeRequest();
        assertEquals("/intent/respondAsk", request.getPath());
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        assertFalse(body.getBoolean("wasFreeform"));
        assertEquals("ask-1", body.getString("requestId"));
    }

    @Test public void staleAskAndInexactChoiceNeverFallBackToPrompt() throws Exception {
        for (String ask : new String[]{"null", "{\"requestId\":\"ask-1\",\"choices\":[\"Yes\"],\"allowFreeform\":false}"}) {
            server.enqueue(new MockResponse().setBody("{\"meta\":{\"sessionId\":\"single-session\",\"ask\":" + ask + "}}"));
            assertThrows(HostClient.Rejected.class, () -> client.send("yes", new JSONObject().put("requestId", "ask-1")));
        }
        assertEquals(2, server.getRequestCount());
    }

    @Test public void disconnectAfterWriteIsUnknownAndNeverRetried() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        assertThrows(IOException.class, () -> client.send("only once", null));
        assertEquals(1, server.getRequestCount());
    }

    @Test public void missingReceiptOrServerErrorIsNotClaimedRejected() throws Exception {
        for (MockResponse response : new MockResponse[]{
                new MockResponse().setBody("{}"),
                new MockResponse().setResponseCode(500)}) {
            server.enqueue(response);
            Exception error = assertThrows(Exception.class, () -> client.send("hello", null));
            assertFalse(error instanceof HostClient.Rejected);
        }
    }

    @Test public void documentedOkWithoutOptionalMessageIdIsAccepted() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        assertTrue(client.send("hello", null).getBoolean("ok"));
    }

    @Test public void retryAfterZeroDoesNotCauseMutationFollowup() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
        server.enqueue(new MockResponse().setBody("{\"ok\":true,\"messageId\":\"must-not-send\"}"));
        assertThrows(IOException.class, () -> client.send("only once", null));
        assertEquals(1, server.getRequestCount());
    }

    @Test public void speechPathComesFromDiscoveryNotKnownDigest() throws Exception {
        String digest = "c".repeat(64);
        String path = "/_modules/cockpit-speech/" + digest + "/api";
        // Mirror the Host module router: a digest in the URL alone is insufficient.
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if ("/_modules".equals(request.getPath())) {
                    return new MockResponse().setBody(discovery(digest, path));
                }
                if (!(path + "/session").equals(request.getPath())) return new MockResponse().setBody("{}");
                if (!digest.equals(request.getHeader("X-Cockpit-Module-Digest"))) {
                    return new MockResponse().setResponseCode(409).setBody("{\"code\":\"MODULE_VERSION_MISMATCH\"}");
                }
                return new MockResponse().setBody("{\"clientSecret\":\"synthetic\"}");
            }
        });
        assertEquals("synthetic", client.speechCredential().getString("clientSecret"));
        RecordedRequest discovery = server.takeRequest();
        assertEquals("/_modules", discovery.getPath());
        assertNull(discovery.getHeader("X-Cockpit-Module-Digest"));
        RecordedRequest credential = server.takeRequest();
        assertEquals(path + "/session", credential.getPath());
        assertEquals("POST", credential.getMethod());
        assertEquals(digest, credential.getHeader("X-Cockpit-Module-Digest"));
        assertEquals(discovery.getHeader("Authorization"), credential.getHeader("Authorization"));
        assertEquals("{}", credential.getBody().readUtf8());
        client.request("/capabilities", null);
        assertNull(server.takeRequest().getHeader("X-Cockpit-Module-Digest"));
    }

    private static String discovery(String digest, String path) {
        return "{\"modules\":[{\"id\":\"cockpit-speech\",\"digest\":\"" + digest
                + "\",\"apiBase\":\"" + path + "\"}]}";
    }

    @Test public void speechRejectsMissingInvalidOrMismatchedDigestBeforePost() throws Exception {
        String path = "/_modules/cockpit-speech/" + "c".repeat(64) + "/api";
        for (String digest : new String[]{"", "x".repeat(64), "C".repeat(64), "d".repeat(64)}) {
            server.enqueue(new MockResponse().setBody(discovery(digest, path)));
            assertThrows(IOException.class, () -> client.speechCredential());
            assertEquals("/_modules", server.takeRequest().getPath());
        }
        server.enqueue(new MockResponse().setBody("{\"modules\":[{\"id\":\"cockpit-speech\",\"apiBase\":\"" + path + "\"}]}"));
        assertThrows(IOException.class, () -> client.speechCredential());
        assertEquals("/_modules", server.takeRequest().getPath());
        assertEquals(5, server.getRequestCount());
    }

    @Test public void speechConflictDoesNotRetryOrClaimAuthenticationFailure() throws Exception {
        String digest = "a".repeat(64);
        String path = "/_modules/cockpit-speech/" + digest + "/api";
        server.enqueue(new MockResponse().setBody(discovery(digest, path)));
        server.enqueue(new MockResponse().setResponseCode(409).setBody("{\"code\":\"MODULE_VERSION_MISMATCH\"}"));
        HostClient.Rejected error = assertThrows(HostClient.Rejected.class, () -> client.speechCredential());
        assertFalse(error instanceof HostClient.AuthenticationRequired);
        assertTrue(error.getMessage().contains("模块"));
        assertFalse(error.getMessage().contains("鉴权"));
        assertEquals(2, server.getRequestCount());
        server.takeRequest();
        server.takeRequest();
        String next = "b".repeat(64);
        server.enqueue(new MockResponse().setBody(discovery(next, "/_modules/cockpit-speech/" + next + "/api")));
        server.enqueue(new MockResponse().setBody("{\"clientSecret\":\"synthetic-next\"}"));
        assertEquals("synthetic-next", client.speechCredential().getString("clientSecret"));
        server.takeRequest();
        assertEquals(next, server.takeRequest().getHeader("X-Cockpit-Module-Digest"));
    }

    @Test public void speechRedirectAndAuthenticationFailuresRemainFailClosed() throws Exception {
        String digest = "a".repeat(64);
        String path = "/_modules/cockpit-speech/" + digest + "/api";
        for (int status : new int[]{302, 401}) {
            server.enqueue(new MockResponse().setBody(discovery(digest, path)));
            server.enqueue(new MockResponse().setResponseCode(status).setHeader("Location", "https://example.invalid"));
            IOException error = assertThrows(IOException.class, () -> client.speechCredential());
            assertEquals(status == 401, error instanceof HostClient.AuthenticationRequired);
        }
        assertEquals(4, server.getRequestCount());
    }

    @Test public void unsafeConfigurationAndCrossOriginModuleAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> HostClient.validateSettings("http://example.invalid", "id", ""));
        assertThrows(IllegalArgumentException.class, () -> HostClient.validateSettings("https://u:p@example.invalid", "id", ""));
        assertThrows(IllegalArgumentException.class, () -> HostClient.validateSettings("https://example.invalid/a", "id", ""));
        assertThrows(IllegalArgumentException.class, () -> HostClient.validateSettings("https://example.invalid", "id", "Bearer x\nX: a"));
        String digest = "a".repeat(64);
        String base = "/_modules/cockpit-speech/" + digest + "/api";
        for (String path : new String[]{"https://example.invalid" + base, "//example.invalid" + base,
                base + "?redirect=1", base + "/../api", base + "#fragment", base.replace("cockpit-speech", "other")}) {
            server.enqueue(new MockResponse().setBody(discovery(digest, path)));
            assertThrows(IOException.class, () -> client.speechCredential());
            assertEquals("/_modules", server.takeRequest().getPath());
        }
        assertEquals(6, server.getRequestCount());
    }

    @Test public void historyKeepsSourceFiltersCursorAndDoesNotLoad() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"sessionId\":\"single-session\",\"source\":\"live\","
                + "\"direction\":\"forward\",\"events\":[],\"cursor\":\"next\",\"cursorStatus\":\"ok\",\"hasMore\":false}"));
        client.page(true, "forward", "opaque", false);
        RecordedRequest request = server.takeRequest();
        assertEquals("/intent/session/chat", request.getPath());
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        assertEquals("opaque", body.getString("cursor"));
        assertTrue(body.getBoolean("includeEphemeral"));
        assertEquals("primary", body.getString("agentScope"));
    }
}
