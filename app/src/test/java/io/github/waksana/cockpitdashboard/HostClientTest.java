package io.github.waksana.cockpitdashboard;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
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
        String path = "/_modules/cockpit-speech/" + "c".repeat(64) + "/api";
        server.enqueue(new MockResponse().setBody("{\"modules\":[{\"id\":\"cockpit-speech\",\"apiBase\":\"" + path + "\"}]}"));
        server.enqueue(new MockResponse().setBody("{\"clientSecret\":\"synthetic\"}"));
        client.speechCredential();
        assertEquals("/_modules", server.takeRequest().getPath());
        RecordedRequest credential = server.takeRequest();
        assertEquals(path + "/session", credential.getPath());
        assertEquals("{}", credential.getBody().readUtf8());
    }

    @Test public void unsafeConfigurationAndCrossOriginModuleAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> HostClient.validateSettings("http://example.invalid", "id", ""));
        assertThrows(IllegalArgumentException.class, () -> HostClient.validateSettings("https://u:p@example.invalid", "id", ""));
        assertThrows(IllegalArgumentException.class, () -> HostClient.validateSettings("https://example.invalid/a", "id", ""));
        assertThrows(IllegalArgumentException.class, () -> HostClient.validateSettings("https://example.invalid", "id", "Bearer x\nX: a"));
        server.enqueue(new MockResponse().setBody("{\"modules\":[{\"id\":\"cockpit-speech\",\"apiBase\":\"https://example.invalid\"}]}"));
        assertThrows(IOException.class, () -> client.speechCredential());
        assertEquals(1, server.getRequestCount());
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
