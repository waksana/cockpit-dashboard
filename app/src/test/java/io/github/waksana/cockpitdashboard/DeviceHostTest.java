package io.github.waksana.cockpitdashboard;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.json.JSONException;
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
public class DeviceHostTest {
    private MockWebServer server;
    private OkHttpClient http;
    private DeviceCredentials credentials;
    private DeviceCredentials.Storage storage;
    private JSONObject saved;
    private boolean failNextRead;

    @Before public void setup() throws Exception {
        HeldCertificate cert = new HeldCertificate.Builder().commonName("localhost")
                .addSubjectAlternativeName("localhost").build();
        HandshakeCertificates tls = new HandshakeCertificates.Builder().heldCertificate(cert).build();
        HandshakeCertificates trust = new HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate()).build();
        server = new MockWebServer();
        server.useHttps(tls.sslSocketFactory(), false);
        server.start();
        http = new OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager())
                .readTimeout(1, TimeUnit.SECONDS).build();
        saved = new JSONObject();
        storage = new DeviceCredentials.Storage() {
            @Override public JSONObject read() throws IOException, JSONException {
                if (failNextRead) {
                    failNextRead = false;
                    throw new IOException("synthetic read failure");
                }
                return new JSONObject(saved.toString());
            }
            @Override public void write(JSONObject value) throws JSONException { saved = new JSONObject(value.toString()); }
        };
        credentials = new DeviceCredentials(storage, System::currentTimeMillis);
    }

    @After public void close() throws Exception { server.shutdown(); }

    private HostClient client(long expiry) throws Exception {
        credentials.install(server.url("/").toString(), new DeviceOAuth.Tokens("synthetic-access", "synthetic-refresh", expiry));
        return new HostClient(server.url("/"), "session", "Basic old-auth", http,
                new GateSession(server.url("/").toString(), "A".repeat(43), System.currentTimeMillis() + 60000),
                credentials);
    }

    private MockResponse token() {
        return new MockResponse().setBody("{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\","
                + "\"token_type\":\"Bearer\",\"expires_in\":900,\"scope\":\"host-access\"}");
    }

    @Test public void bearerOverridesLegacyOnlyOnHostRequestsAndNeverContaminatesTransport() throws Exception {
        HostClient client = client(900);
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        client.send("one", null);
        RecordedRequest request = server.takeRequest();
        assertEquals("Bearer synthetic-access", request.getHeader("Authorization"));
        assertNull(request.getHeader("Cookie"));
        server.enqueue(new MockResponse().setBody("{}"));
        try (okhttp3.Response response = client.http.newCall(new okhttp3.Request.Builder()
                .url(server.url("/external-speech-or-update")).build()).execute()) {
            assertEquals(200, response.code());
        }
        request = server.takeRequest();
        assertNull(request.getHeader("Authorization"));
        assertNull(request.getHeader("Cookie"));
        assertThrows(HostClient.Rejected.class, () -> client.request("https://other.example/intent/prompt", new JSONObject()));
        assertEquals(2, server.getRequestCount());
    }

    @Test public void refreshBeforeMutationThenOnePromptAndNoCredentialOnRefresh() throws Exception {
        HostClient client = client(60);
        server.enqueue(token());
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        client.send("one", null);
        RecordedRequest refresh = server.takeRequest();
        assertEquals("/_gate/oauth/token", refresh.getPath());
        assertTrue(refresh.getBody().readUtf8().contains("grant_type=refresh_token"));
        assertNull(refresh.getHeader("Authorization"));
        assertNull(refresh.getHeader("Cookie"));
        RecordedRequest prompt = server.takeRequest();
        assertEquals("/intent/prompt", prompt.getPath());
        assertEquals("Bearer new-access", prompt.getHeader("Authorization"));
        assertEquals(2, server.getRequestCount());
    }

    @Test public void failedRefreshRejectsBeforePromptAndCannotBeReplayed() throws Exception {
        HostClient client = client(60);
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid_grant\"}"));
        assertThrows(HostClient.AuthenticationRequired.class, () -> client.send("draft", null));
        assertThrows(HostClient.AuthenticationRequired.class, () -> client.send("draft", null));
        assertEquals("/_gate/oauth/token", server.takeRequest().getPath());
        assertEquals(1, server.getRequestCount());
    }

    @Test public void bearerMutation401And503NeverRefreshOrReplay() throws Exception {
        HostClient client = client(900);
        server.enqueue(new MockResponse().setResponseCode(401));
        assertThrows(HostClient.AuthenticationRequired.class, () -> client.send("first", null));
        server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
        assertThrows(IOException.class, () -> client.send("second", null));
        assertEquals(2, server.getRequestCount());
        assertEquals("/intent/prompt", server.takeRequest().getPath());
        assertEquals("/intent/prompt", server.takeRequest().getPath());
    }

    @Test public void revokeAcknowledgementClearsAndUncertainRevokeStopsCredentialUse() throws Exception {
        client(900);
        server.enqueue(new MockResponse().setResponseCode(200));
        credentials.revoke(server.url("/").toString(), new DeviceOAuth(server.url("/"), http));
        assertFalse(credentials.present(server.url("/").toString()));
        RecordedRequest revoke = server.takeRequest();
        assertEquals("/_gate/oauth/revoke", revoke.getPath());
        assertTrue(revoke.getBody().readUtf8().contains("token_type_hint=refresh_token"));
        assertNull(revoke.getHeader("Authorization"));
        assertNull(revoke.getHeader("Cookie"));
        HostClient client = client(900);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        assertThrows(IOException.class,
                () -> credentials.revoke(server.url("/").toString(), new DeviceOAuth(server.url("/"), http)));
        assertThrows(HostClient.AuthenticationRequired.class, () -> client.send("draft", null));
        assertTrue(credentials.present(server.url("/").toString()));
        assertEquals(2, server.getRequestCount());
        credentials.clear();
        assertFalse(credentials.present(server.url("/").toString()));
    }

    @Test public void anotherActivityRevocationInvalidatesLateRefreshEvenWhenRemoteResultIsUnknown() throws Exception {
        client(60);
        DeviceCredentials recreated = new DeviceCredentials(storage, System::currentTimeMillis);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        assertThrows(HostClient.AuthenticationRequired.class, () -> credentials.header(server.url("/"), old -> {
            assertThrows(IOException.class,
                    () -> recreated.revoke(server.url("/").toString(), new DeviceOAuth(server.url("/"), http)));
            return new DeviceOAuth.Tokens("late-access", "late-refresh", 900);
        }));
        assertTrue(saved.getBoolean("revoking"));
        assertEquals("synthetic-refresh", saved.getString("refresh"));
        DeviceCredentials again = new DeviceCredentials(storage, System::currentTimeMillis);
        assertThrows(HostClient.AuthenticationRequired.class,
                () -> again.header(server.url("/"), t -> { throw new AssertionError("must not refresh"); }));
        assertEquals(1, server.getRequestCount());
    }

    @Test public void anotherActivitySuccessfulRevocationCannotBeUndoneByLateRefresh() throws Exception {
        client(60);
        DeviceCredentials recreated = new DeviceCredentials(storage, System::currentTimeMillis);
        server.enqueue(new MockResponse().setResponseCode(200));
        assertThrows(HostClient.AuthenticationRequired.class, () -> credentials.header(server.url("/"), old -> {
            assertTrue(recreated.revoke(server.url("/").toString(), new DeviceOAuth(server.url("/"), http)));
            return new DeviceOAuth.Tokens("late-access", "late-refresh", 900);
        }));
        assertEquals(0, saved.length());
        assertFalse(recreated.present(server.url("/").toString()));
        assertEquals(1, server.getRequestCount());
    }

    @Test public void revokeReadFailureStillInvalidatesOtherActivityRefresh() throws Exception {
        client(60);
        DeviceCredentials recreated = new DeviceCredentials(storage, System::currentTimeMillis);
        assertThrows(HostClient.AuthenticationRequired.class, () -> credentials.header(server.url("/"), old -> {
            failNextRead = true;
            assertThrows(IOException.class,
                    () -> recreated.revoke(server.url("/").toString(), new DeviceOAuth(server.url("/"), http)));
            return new DeviceOAuth.Tokens("late-access", "late-refresh", 900);
        }));
        assertThrows(HostClient.AuthenticationRequired.class,
                () -> new DeviceCredentials(storage, System::currentTimeMillis)
                        .header(server.url("/"), t -> { throw new AssertionError("must not refresh"); }));
        assertEquals(0, server.getRequestCount());
    }

    @Test public void revokeReceiptDoesNotClaimToClearNewlyInstalledCredentials() throws Exception {
        client(900);
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    new DeviceCredentials(storage, System::currentTimeMillis).install(server.url("/").toString(),
                            new DeviceOAuth.Tokens("new-scan-access", "new-scan-refresh", 900));
                    return new MockResponse().setResponseCode(200);
                } catch (IOException | JSONException error) { throw new AssertionError(error); }
            }
        });
        assertFalse(credentials.revoke(server.url("/").toString(), new DeviceOAuth(server.url("/"), http)));
        assertEquals("Bearer new-scan-access",
                credentials.header(server.url("/"), t -> { throw new AssertionError("must not refresh"); }));
    }
}
