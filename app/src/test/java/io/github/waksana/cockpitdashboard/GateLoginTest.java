package io.github.waksana.cockpitdashboard;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
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
public class GateLoginTest {
    private static final String TOKEN = "A".repeat(43);
    private MockWebServer server;
    private OkHttpClient transport;
    private GateApi gate;

    @Before public void setup() throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder().commonName("localhost")
                .addSubjectAlternativeName("localhost").build();
        HandshakeCertificates serverTls = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates clientTls = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        server = new MockWebServer();
        server.useHttps(serverTls.sslSocketFactory(), false);
        server.start();
        transport = new OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager())
                .readTimeout(1, TimeUnit.SECONDS).build();
        gate = new GateApi(server.url("/"), transport);
    }

    @After public void close() throws Exception { gate.cancel(); server.shutdown(); }

    private void options(String rp, String uv) {
        server.enqueue(new MockResponse().setBody("<html>login</html>")
                .addHeader("Set-Cookie", "__Host-pg_client=synthetic; Path=/; Secure; HttpOnly; Max-Age=300"));
        server.enqueue(new MockResponse().setBody("{\"publicKey\":{\"rpId\":\"" + rp + "\","
                + "\"challenge\":\"synthetic-challenge\",\"userVerification\":\"" + uv + "\"}}")
                .addHeader("Set-Cookie", "__Host-pg_flow=one-flow; Path=/; Secure; HttpOnly; Max-Age=300"));
    }

    private MockResponse successCookie(String extra) {
        return new MockResponse().setBody("{\"redirect\":\"/\"}").addHeader("Set-Cookie",
                GateSession.COOKIE + "=" + TOKEN + "; Path=/; Secure; HttpOnly; Max-Age=300" + extra);
    }

    @Test public void exactCeremonyThenPersistedSessionRestoresWithoutRelogin() throws Exception {
        options("localhost", "required");
        assertEquals("localhost", gate.options().getString("rpId"));
        server.enqueue(successCookie(""));
        GateSession loggedIn = gate.finish("{\"id\":\"assertion\"}");
        RecordedRequest loginPage = server.takeRequest();
        RecordedRequest options = server.takeRequest();
        RecordedRequest finish = server.takeRequest();
        assertEquals("/_gate/login", loginPage.getPath());
        assertEquals("/_gate/auth/options", options.getPath());
        assertEquals("{}", options.getBody().readUtf8());
        assertTrue(options.getHeader("Cookie").contains("__Host-pg_client=synthetic"));
        assertEquals("/_gate/auth/finish", finish.getPath());
        assertEquals("{\"id\":\"assertion\"}", finish.getBody().readUtf8());
        assertTrue(finish.getHeader("Cookie").contains("__Host-pg_flow=one-flow"));
        assertNull(finish.getHeader("Authorization"));
        assertEquals(server.url("/").toString().replaceAll("/$", ""), finish.getHeader("Origin"));

        JSONObject stored = new JSONObject().put("gateSession", loggedIn.toJSON()).put("draft", "keep");
        GateSession restored = GateSession.fromJSON(new JSONObject(stored.toString()).getJSONObject("gateSession"));
        HostClient client = new HostClient(server.url("/"), "id", "", transport, restored);
        server.enqueue(new MockResponse().setBody("{\"ok\":true}"));
        client.send("one", null);
        RecordedRequest prompt = server.takeRequest();
        assertEquals("/intent/prompt", prompt.getPath());
        assertEquals(GateSession.COOKIE + "=" + TOKEN, prompt.getHeader("Cookie"));
        assertEquals(4, server.getRequestCount());
    }

    @Test public void sessionExpiresAndNeverCrossesHostPortOrScheme() throws Exception {
        GateSession session = new GateSession("https://example.com/", TOKEN, 1000);
        assertEquals(GateSession.COOKIE + "=" + TOKEN, session.header(HttpUrl.get("https://example.com/intent/prompt"), 999));
        for (String url : List.of("http://example.com/", "https://example.com:444/", "https://sub.example.com/", "https://other.test/")) {
            assertThrows(HostClient.AuthenticationRequired.class, () -> session.header(HttpUrl.get(url), 999));
        }
        assertThrows(HostClient.AuthenticationRequired.class, () -> session.header(HttpUrl.get("https://example.com/"), 1000));
        assertThrows(IllegalArgumentException.class, () -> new GateSession("http://example.com/", TOKEN, 1000));
        assertThrows(IllegalArgumentException.class, () -> new GateSession("https://example.com/", "x;\r\nCookie: x", 1000));
    }

    @Test public void cookiesAreEphemeralScopedAndRejectUnsafeAttributes() {
        HttpUrl origin = HttpUrl.get("https://example.com/");
        GateApi.LoginCookies cookies = new GateApi.LoginCookies(origin);
        for (String raw : List.of(
                GateSession.COOKIE + "=" + TOKEN + "; Path=/; HttpOnly",
                GateSession.COOKIE + "=" + TOKEN + "; Path=/; Secure",
                GateSession.COOKIE + "=" + TOKEN + "; Path=/; Secure; HttpOnly; Domain=example.com",
                GateSession.COOKIE + "=" + TOKEN + "; Path=/sub; Secure; HttpOnly",
                "other=secret; Path=/; Secure; HttpOnly")) {
            cookies.saveFromResponse(origin, List.of(Cookie.parse(origin, raw)));
        }
        assertTrue(cookies.loadForRequest(origin).isEmpty());
        Cookie valid = Cookie.parse(origin, GateSession.COOKIE + "=" + TOKEN + "; Path=/; Secure; HttpOnly; Max-Age=300");
        cookies.saveFromResponse(origin, List.of(valid));
        assertEquals(1, cookies.loadForRequest(origin).size());
        assertTrue(cookies.loadForRequest(HttpUrl.get("https://example.com:444/")).isEmpty());
        assertTrue(cookies.loadForRequest(HttpUrl.get("https://sub.example.com/")).isEmpty());
        assertTrue(new GateApi.LoginCookies(origin).loadForRequest(origin).isEmpty());
        cookies.saveFromResponse(origin, List.of(Cookie.parse(origin,
                GateSession.COOKIE + "=deleted; Path=/; Secure; HttpOnly; Max-Age=0")));
        assertNull(cookies.session());
    }

    @Test public void malformedOptionsNeverReachFinish() throws Exception {
        options("evil.test", "required");
        assertThrows(HostClient.Rejected.class, gate::options);
        options("localhost", "preferred");
        assertThrows(HostClient.Rejected.class, gate::options);
        assertEquals(4, server.getRequestCount());
    }

    @Test public void noCookieAndDomainCookieAreNotSuccessfulLogin() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"redirect\":\"/\"}"));
        assertThrows(IOException.class, () -> gate.finish("{}"));
        server.enqueue(successCookie("; Domain=localhost"));
        assertThrows(IOException.class, () -> gate.finish("{}"));
    }

    @Test public void redirectAndRetryAfterNeverReplayAssertionOrLeakToOtherSite() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "https://other.test/"));
        assertThrows(IOException.class, () -> gate.finish("{}"));
        server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
        assertThrows(IOException.class, () -> gate.finish("{}"));
        assertEquals(2, server.getRequestCount());
    }

    @Test public void disconnectedFinishNeverRetriesAndCancellationSendsNothing() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        assertThrows(IOException.class, () -> gate.finish("{}"));
        assertEquals(1, server.getRequestCount());
        gate.cancel();
        assertThrows(IOException.class, gate::options);
        assertEquals(1, server.getRequestCount());
    }

    @Test public void expiredRevokedAndGatewayLoginRedirectRequireExplicitLogin() throws Exception {
        GateSession expired = new GateSession(server.url("/").toString(), TOKEN, 1);
        HostClient expiredClient = new HostClient(server.url("/"), "id", "", transport, expired);
        assertThrows(HostClient.AuthenticationRequired.class, () -> expiredClient.send("draft", null));
        assertEquals(0, server.getRequestCount());
        HostClient activeClient = new HostClient(server.url("/"), "id", "", transport,
                new GateSession(server.url("/").toString(), TOKEN, System.currentTimeMillis() + 60000));
        for (MockResponse response : List.of(new MockResponse().setResponseCode(401),
                new MockResponse().setResponseCode(302).setHeader("Location", "/_gate/login?return=%2F"))) {
            server.enqueue(response);
            assertThrows(HostClient.AuthenticationRequired.class, () -> activeClient.send("draft", null));
        }
        assertEquals(2, server.getRequestCount());
    }
}
