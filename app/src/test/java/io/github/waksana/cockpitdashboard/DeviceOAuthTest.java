package io.github.waksana.cockpitdashboard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.Buffer;
import org.json.JSONArray;
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
public class DeviceOAuthTest {
    private MockWebServer server;
    private OkHttpClient transport;
    private DeviceOAuth api;
    private JSONObject fixture;

    @Before public void setup() throws Exception {
        HeldCertificate cert = new HeldCertificate.Builder().commonName("localhost")
                .addSubjectAlternativeName("localhost").build();
        HandshakeCertificates tls = new HandshakeCertificates.Builder().heldCertificate(cert).build();
        HandshakeCertificates trust = new HandshakeCertificates.Builder()
                .addTrustedCertificate(cert.certificate()).build();
        server = new MockWebServer();
        server.useHttps(tls.sslSocketFactory(), false);
        server.start();
        transport = new OkHttpClient.Builder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager())
                .readTimeout(1, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build();
        api = new DeviceOAuth(server.url("/"), transport);
        try (InputStream input = getClass().getResourceAsStream("/device-oauth-v1.json")) {
            assertNotNull(input);
            fixture = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @After public void close() throws Exception { server.shutdown(); }

    private JSONObject grant() throws JSONException {
        return new JSONObject(fixture.getJSONObject("grant").toString());
    }

    private JSONObject tokens() throws JSONException {
        return new JSONObject(fixture.getJSONObject("tokens").toString());
    }

    private void enqueue(JSONObject value) { server.enqueue(new MockResponse().setBody(value.toString())); }

    private HttpUrl form(RecordedRequest request, String path) {
        assertEquals("POST", request.getMethod());
        assertEquals(path, request.getPath());
        assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type"));
        assertEquals("application/json", request.getHeader("Accept"));
        assertNull(request.getHeader("Authorization"));
        assertNull(request.getHeader("Cookie"));
        HttpUrl values = HttpUrl.get("https://form.example/?" + request.getBody().readUtf8());
        assertEquals("cockpit-dashboard", values.queryParameter("client_id"));
        return values;
    }

    @Test public void fixtureAuthorizePreservesQrUriAndUsesFixedForm() throws Exception {
        enqueue(grant());
        DeviceOAuth.Grant result = api.authorize();
        assertEquals(grant().getString("device_code"), result.deviceCode);
        assertEquals("ABCD-EFGH", result.userCode);
        assertEquals("https://auth.example.test/device", result.verificationUri);
        assertEquals(grant().getString("verification_uri_complete"), result.verificationUriComplete);
        assertEquals(300, result.expiresIn);
        assertEquals(5, result.interval);
        HttpUrl values = form(server.takeRequest(), "/_gate/oauth/device_authorization");
        assertEquals("host-access", values.queryParameter("scope"));
        assertEquals(2, values.querySize());
    }

    @Test public void defaultsAndOptionalCompleteUriFollowRfc8628() throws Exception {
        JSONObject value = grant();
        value.remove("interval");
        value.remove("verification_uri_complete");
        enqueue(value);
        DeviceOAuth.Grant result = api.authorize();
        assertEquals(5, result.interval);
        assertEquals(result.verificationUri, result.verificationUriComplete);
    }

    private JSONObject gateFixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/gate-device-wire-v1.json")) {
            assertNotNull(input);
            return new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void assertWire(JSONObject operation) throws Exception {
        HttpUrl values = form(server.takeRequest(), operation.getString("path"));
        JSONObject expected = operation.getJSONObject("request");
        assertEquals(expected.length(), values.querySize());
        Iterator<String> keys = expected.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            assertEquals(expected.getString(key), values.queryParameter(key));
        }
    }

    @Test public void actualGateFixtureRoundTripsAllFourOperations() throws Exception {
        JSONObject gate = gateFixture();
        assertTrue(gate.getBoolean("synthetic_only"));
        JSONObject authorization = gate.getJSONObject("device_authorization");
        enqueue(authorization.getJSONObject("response"));
        DeviceOAuth.Grant grant = api.authorize();
        assertWire(authorization);
        assertEquals(300, grant.expiresIn);
        assertEquals("https://auth.example.com/_gate/device?user_code=ABCD-EFGH", grant.verificationUriComplete);
        JSONObject issuance = gate.getJSONObject("token");
        enqueue(issuance.getJSONObject("response"));
        DeviceOAuth.Tokens tokens = api.poll(grant.deviceCode);
        assertWire(issuance);
        JSONObject refresh = gate.getJSONObject("refresh");
        enqueue(refresh.getJSONObject("response"));
        DeviceOAuth.Tokens rotated = api.refresh(tokens.refreshToken);
        assertWire(refresh);
        assertNotEquals(tokens.refreshToken, rotated.refreshToken);
        JSONObject revoke = gate.getJSONObject("revoke");
        server.enqueue(new MockResponse().setResponseCode(revoke.getInt("status")).setBody(revoke.getString("response_body")));
        api.revoke(rotated.refreshToken);
        assertWire(revoke);
        assertEquals(4, server.getRequestCount());
    }

    @Test public void actualGateErrorsAndShortFamilyRemainderAreAcceptedWithoutReplay() throws Exception {
        JSONObject gate = gateFixture();
        JSONArray errors = gate.getJSONArray("errors");
        for (int i = 0; i < errors.length(); i++) {
            JSONObject entry = errors.getJSONObject(i);
            MockResponse response = new MockResponse().setResponseCode(entry.getInt("status"))
                    .setBody(entry.getJSONObject("response").toString());
            if (entry.has("retry_after")) response.setHeader("Retry-After", entry.getInt("retry_after"));
            server.enqueue(response);
            if (entry.getInt("status") == 400) {
                assertEquals(entry.getJSONObject("response").getString("error"),
                        assertThrows(DeviceOAuth.Error.class, () -> api.poll("synthetic-device")).code);
            } else {
                DeviceOAuth.Unavailable error = assertThrows(DeviceOAuth.Unavailable.class,
                        () -> api.poll("synthetic-device"));
                assertEquals(entry.getInt("status"), error.status);
                assertEquals(entry.optInt("retry_after"), error.retryAfterSeconds);
            }
        }
        enqueue(gate.getJSONObject("refresh").getJSONObject("response").put("expires_in", 1));
        assertEquals(1, api.refresh("synthetic-refresh").expiresIn);
        assertEquals(errors.length() + 1, server.getRequestCount());
    }

    @Test public void pollRefreshAndRevokeUseEncodedBoundedForms() throws Exception {
        enqueue(tokens());
        DeviceOAuth.Tokens result = api.poll("synthetic+device/=&");
        HttpUrl values = form(server.takeRequest(), "/_gate/oauth/token");
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", values.queryParameter("grant_type"));
        assertEquals("synthetic+device/=&", values.queryParameter("device_code"));
        assertEquals(3, values.querySize());
        assertEquals(tokens().getString("access_token"), result.accessToken);
        assertEquals(tokens().getString("refresh_token"), result.refreshToken);
        assertEquals(900, result.expiresIn);
        assertEquals("host-access", result.toJSON().getString("scope"));

        enqueue(tokens());
        api.refresh("synthetic+refresh/=&");
        values = form(server.takeRequest(), "/_gate/oauth/token");
        assertEquals("refresh_token", values.queryParameter("grant_type"));
        assertEquals("synthetic+refresh/=&", values.queryParameter("refresh_token"));
        assertEquals(3, values.querySize());

        server.enqueue(new MockResponse().setResponseCode(200));
        api.revoke("synthetic+refresh/=&");
        values = form(server.takeRequest(), "/_gate/oauth/revoke");
        assertEquals("refresh_token", values.queryParameter("token_type_hint"));
        assertEquals("synthetic+refresh/=&", values.queryParameter("token"));
        assertEquals(3, values.querySize());
    }

    @Test public void oauthErrorsRemainStructuredIncludingUnknownCodes() throws Exception {
        JSONArray errors = fixture.getJSONArray("errors");
        for (int i = 0; i < errors.length(); i++) {
            String code = errors.getString(i);
            server.enqueue(new MockResponse().setResponseCode(400)
                    .setBody(new JSONObject().put("error", code)
                            .put("error_description", "sensitive response must not escape").toString()));
            DeviceOAuth.Error error = assertThrows(DeviceOAuth.Error.class, () -> api.poll("device"));
            assertEquals(code, error.code);
            assertFalse(error.toString().contains("sensitive"));
            assertNull(error.getCause());
        }
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid_grant\"}"));
        assertEquals("invalid_grant", assertThrows(DeviceOAuth.Error.class, () -> api.refresh("refresh")).code);
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid_token\"}"));
        assertEquals("invalid_token", assertThrows(DeviceOAuth.Error.class, () -> api.revoke("refresh")).code);
    }

    @Test public void tokenFieldsMustHaveCorrectTypesAndScope() throws Exception {
        for (String key : new String[]{"access_token", "refresh_token", "token_type", "scope"}) {
            for (Object bad : new Object[]{JSONObject.NULL, 123, true, new JSONArray(), "", "a b", "x\r\nHeader:y"}) {
                enqueue(tokens().put(key, bad));
                assertThrows(key + " accepted " + bad, JSONException.class, () -> api.poll("device"));
            }
            JSONObject missing = tokens();
            missing.remove(key);
            enqueue(missing);
            assertThrows(JSONException.class, () -> api.poll("device"));
        }
        for (String scope : new String[]{"admin", "host-access admin", "HOST-ACCESS", "host-access,admin"}) {
            enqueue(tokens().put("scope", scope));
            assertThrows(JSONException.class, () -> api.refresh("refresh"));
        }
        enqueue(tokens().put("token_type", "Basic"));
        assertThrows(JSONException.class, () -> api.poll("device"));
        enqueue(tokens().put("token_type", "bEaReR"));
        assertEquals(900, api.poll("device").expiresIn);
    }

    @Test public void lifetimesAndIntervalsAreStrictPositiveBoundedNumbers() throws Exception {
        for (Object bad : new Object[]{JSONObject.NULL, "900", true, 0, -1, 1.5, DeviceOAuth.MAX_SECONDS + 1}) {
            enqueue(tokens().put("expires_in", bad));
            assertThrows(JSONException.class, () -> api.poll("device"));
            enqueue(grant().put("expires_in", bad));
            assertThrows(JSONException.class, () -> api.authorize());
            enqueue(grant().put("interval", bad));
            assertThrows(JSONException.class, () -> api.authorize());
        }
        for (String number : new String[]{"1e999", "9223372036854775808", "NaN", "Infinity", "01", "+1"}) {
            server.enqueue(new MockResponse().setBody(tokens().toString().replace("\"expires_in\":900",
                    "\"expires_in\":" + number)));
            assertThrows(JSONException.class, () -> api.poll("device"));
        }
        enqueue(tokens().put("expires_in", DeviceOAuth.MAX_SECONDS));
        assertEquals(DeviceOAuth.MAX_SECONDS, api.poll("device").expiresIn);
    }

    @Test public void tokenAndInputSizesAreBoundedWithNoHeaderControls() throws Exception {
        enqueue(tokens().put("access_token", "a".repeat(4096)).put("refresh_token", "r".repeat(4096)));
        assertEquals(4096, api.poll("d".repeat(4096)).accessToken.length());
        for (String bad : new String[]{"a".repeat(4097), "secret\u007f", "secret\u0085", "secret\u2028"}) {
            enqueue(tokens().put("access_token", bad));
            assertThrows(JSONException.class, () -> api.poll("device"));
            enqueue(tokens().put("refresh_token", bad));
            assertThrows(JSONException.class, () -> api.refresh("refresh"));
        }
        int sent = server.getRequestCount();
        for (String bad : new String[]{null, "", "a".repeat(4097), "has space", "secret\n", "秘密"}) {
            assertThrows(JSONException.class, () -> api.poll(bad));
            assertThrows(JSONException.class, () -> api.refresh(bad));
            assertThrows(JSONException.class, () -> api.revoke(bad));
        }
        assertEquals(sent, server.getRequestCount());
    }

    @Test public void grantStringsHaveStrictTypesAndBounds() throws Exception {
        for (String key : new String[]{"device_code", "user_code", "verification_uri", "verification_uri_complete"}) {
            for (Object bad : new Object[]{JSONObject.NULL, true, 42, "", "secret\n", "x".repeat(4097)}) {
                enqueue(grant().put(key, bad));
                assertThrows(JSONException.class, () -> api.authorize());
            }
        }
        enqueue(grant().put("user_code", "a".repeat(129)));
        assertThrows(JSONException.class, () -> api.authorize());
    }

    @Test public void duplicateKeysIncludingNestedAndEscapedNamesAreRejected() throws Exception {
        for (String suffix : new String[]{
                ",\"access_token\":\"other\"",
                ",\"access_\\u0074oken\":\"other\"",
                ",\"extra\":{\"same\":1,\"same\":2}",
                ",\"extra\":[{\"same\":1,\"same\":2}]"}) {
            String value = tokens().toString();
            server.enqueue(new MockResponse().setBody(value.substring(0, value.length() - 1) + suffix + "}"));
            assertThrows(JSONException.class, () -> api.poll("device"));
        }
        server.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"error\":\"authorization_pending\",\"error\":\"slow_down\"}"));
        assertThrows(JSONException.class, () -> api.poll("device"));
    }

    @Test public void malformedJsonAndInvalidUtf8NeverExposeResponse() throws Exception {
        for (String bad : new String[]{"", "[]", "{\"secret\":", "{}{}", "/*secret*/{}", "{\"secret\":NaN}",
                "{\"secret\":1,}", "{secret:1}", "{\"secret\":\"bad\\q\"}",
                "{\"secret\":\"raw\nnewline\"}", "{\"secret\":\"raw\t tab\"}",
                "{\"secret\":\"raw\u0001control\"}"}) {
            server.enqueue(new MockResponse().setBody(bad));
            JSONException error = assertThrows(JSONException.class, () -> api.poll("device"));
            assertFalse(error.toString().contains("secret"));
            assertNull(error.getCause());
        }
        server.enqueue(new MockResponse().setBody(new Buffer().write(new byte[]{(byte) 0xc3, 0x28})));
        assertThrows(JSONException.class, () -> api.poll("device"));
    }

    @Test public void nestingIsBoundedAndErrorCodeTypesAreStrict() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"extra\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}"));
        assertThrows(JSONException.class, () -> api.poll("device"));
        for (Object bad : new Object[]{JSONObject.NULL, true, 42, "", "with space", "secret\r\n", "x".repeat(129)}) {
            server.enqueue(new MockResponse().setResponseCode(400)
                    .setBody(new JSONObject().put("error", bad).toString()));
            assertThrows(JSONException.class, () -> api.poll("device"));
        }

        server.enqueue(new MockResponse().setResponseCode(400).setBody("{}"));
        assertThrows(JSONException.class, () -> api.poll("device"));
    }

    @Test public void malformedUnknownStringFieldsCannotBypassStrictParsing() throws Exception {
        for (String suffix : new String[]{",\"extra\":\"raw\nnewline\"", ",\"extra\":\"raw\t tab\"",
                ",\"extra\":\"raw\u0001control\"", ",\"extra\":\"bad\\q\""}) {
            String value = tokens().toString();
            server.enqueue(new MockResponse().setBody(value.substring(0, value.length() - 1) + suffix + "}"));
            assertThrows(JSONException.class, () -> api.poll("device"));
        }
    }

    @Test public void responsesAreBoundedIncludingChunkedAndRevocationBodies() throws Exception {
        server.enqueue(new MockResponse().setBody("x".repeat(DeviceOAuth.MAX_RESPONSE + 1)));
        assertThrows(IOException.class, () -> api.poll("device"));
        server.enqueue(new MockResponse().setChunkedBody("x".repeat(DeviceOAuth.MAX_RESPONSE + 1), 1024));
        assertThrows(IOException.class, () -> api.authorize());
        server.enqueue(new MockResponse().setBody("x".repeat(DeviceOAuth.MAX_RESPONSE + 1)));
        assertThrows(IOException.class, () -> api.revoke("refresh"));
        String value = tokens().toString();
        server.enqueue(new MockResponse().setBody(value + " ".repeat(DeviceOAuth.MAX_RESPONSE - value.length())));
        assertEquals(900, api.poll("device").expiresIn);
    }

    @Test public void verificationUrlsCannotCarryCredentialsOrChangeVerificationDestination() throws Exception {
        for (String bad : new String[]{
                "http://auth.example.test/device?user_code=ABCD-EFGH",
                "/device?user_code=ABCD-EFGH",
                "https://other.example.test/device?user_code=ABCD-EFGH",
                "https://auth.example.test:444/device?user_code=ABCD-EFGH",
                "https://auth.example.test/other?user_code=ABCD-EFGH",
                "https://user@auth.example.test/device?user_code=ABCD-EFGH",
                "https://@auth.example.test/device?user_code=ABCD-EFGH",
                "https://auth.example.test/device?user_code=ABCD-EFGH#fragment",
                "https://auth.example.test/device?user_code=wrong",
                "https://auth.example.test/device",
                "https://auth.example.test/device?device_code=ABCD-EFGH",
                "https://auth.example.test/device?token=ABCD-EFGH",
                "https://auth.example.test/device?user_code=ABCD-EFGH&token=x",
                "https://auth.example.test/device?user_code=ABCD-EFGH&user_code=ABCD-EFGH",
                "https://auth.example.test/device?user_code=ABCD-EFGH%0a",
                "https://auth.example.test/device?user_code=ABCD-EFGH%7f",
                "https://auth.example.test\\device?user_code=ABCD-EFGH",
                "https://auth.example.test/device?user_code=" + "x".repeat(2048)}) {
            enqueue(grant().put("verification_uri_complete", bad));
            assertThrows(bad, JSONException.class, () -> api.authorize());
        }
        for (String bad : new String[]{"http://auth.example.test/device", "https://user@auth.example.test/device",
                "https://auth.example.test/device#x", "https://auth.example.test/device?token=x",
                "https://auth.example.test/de%0avice", "https://auth.example.test/de%7fvice"}) {
            JSONObject value = grant().put("verification_uri", bad);
            value.remove("verification_uri_complete");
            enqueue(value);
            assertThrows(JSONException.class, () -> api.authorize());
        }
    }

    @Test public void originsMustBeHttpsRootWithoutCredentialsQueryFragmentOrNormalizationTricks() {
        for (String bad : new String[]{"http://example.test", "https://user:pass@example.test",
                "https://@example.test", "https://example.test/path", "https://example.test/?q=x",
                "https://example.test/#x", "https://example.test/foo/../", " https://example.test",
                "https://example.test\\", "https://example.test/\n"}) {
            assertThrows(bad, IllegalArgumentException.class, () -> new DeviceOAuth(bad));
        }
        assertNotNull(new DeviceOAuth("https://example.test"));
        assertNotNull(new DeviceOAuth("https://example.test:8443/"));
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceOAuth(HttpUrl.get("http://example.test/"), transport));
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceOAuth(server.url("/path"), transport));
    }

    @Test public void injectedInterceptorsCookiesAndAuthenticatorsAreNotUsed() throws Exception {
        AtomicInteger hooks = new AtomicInteger();
        OkHttpClient dirty = transport.newBuilder()
                .addInterceptor(chain -> {
                    hooks.incrementAndGet();
                    return chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer unsafe").build());
                })
                .addNetworkInterceptor(chain -> {
                    hooks.incrementAndGet();
                    return chain.proceed(chain.request().newBuilder().header("Cookie", "unsafe=1").build());
                })
                .authenticator((route, response) -> { hooks.incrementAndGet(); return null; })
                .proxyAuthenticator((route, response) -> { hooks.incrementAndGet(); return null; })
                .cookieJar(new CookieJar() {
                    @Override public void saveFromResponse(HttpUrl url, java.util.List<Cookie> cookies) {
                        hooks.incrementAndGet();
                    }
                    @Override public java.util.List<Cookie> loadForRequest(HttpUrl url) {
                        hooks.incrementAndGet();
                        return Collections.singletonList(new Cookie.Builder()
                                .name("unsafe").value("1").domain("localhost").build());
                    }
                }).build();
        DeviceOAuth clean = new DeviceOAuth(server.url("/"), dirty);
        enqueue(tokens());
        clean.poll("device");
        form(server.takeRequest(), "/_gate/oauth/token");
        server.enqueue(new MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=test"));
        assertThrows(IOException.class, () -> clean.refresh("refresh"));
        assertEquals(0, hooks.get());
    }

    @Test public void redirects503AndOtherStatusesNeverReplayAnyOperation() throws Exception {
        for (int status : new int[]{201, 204, 301, 302, 307, 308, 401, 403, 408, 429, 500, 503}) {
            for (int operation = 0; operation < 4; operation++) {
                server.enqueue(new MockResponse().setResponseCode(status)
                        .setHeader("Location", server.url("/replay")).setHeader("Retry-After", "0"));
                int count = server.getRequestCount();
                int selected = operation;
                assertThrows(IOException.class, () -> runOperation(api, selected));
                assertEquals("status " + status, count + 1, server.getRequestCount());
            }
        }
    }

    @Test public void disconnectedResponsesDoNotReplayAnyOperation() throws Exception {
        for (int operation = 0; operation < 4; operation++) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
            int count = server.getRequestCount();
            int selected = operation;
            assertThrows(IOException.class, () -> runOperation(api, selected));
            assertEquals(count + 1, server.getRequestCount());
        }
    }

    @Test public void cancellationIsOneShotAndCancelsInflightRequest() throws Exception {
        api.cancel();
        for (int operation = 0; operation < 4; operation++) {
            int selected = operation;
            assertThrows(IOException.class, () -> runOperation(api, selected));
        }
        assertEquals(0, server.getRequestCount());
        DeviceOAuth active = new DeviceOAuth(server.url("/"), transport);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = worker.submit(() -> assertThrows(IOException.class, () -> active.poll("device")));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            active.cancel();
            result.get(2, TimeUnit.SECONDS);
            assertThrows(IOException.class, () -> active.authorize());
            assertEquals(1, server.getRequestCount());
        } finally {
            active.cancel();
            worker.shutdownNow();
        }
    }

    private static void runOperation(DeviceOAuth api, int operation) throws IOException, JSONException {
        switch (operation) {
            case 0: api.authorize(); break;
            case 1: api.poll("device"); break;
            case 2: api.refresh("refresh"); break;
            default: api.revoke("refresh");
        }
    }
}
