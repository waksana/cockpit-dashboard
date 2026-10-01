package io.github.waksana.cockpitdashboard;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.EventListener;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class DeviceOAuth {
    static final int MAX_RESPONSE = 32 * 1024;
    static final int MAX_TOKEN = 4096;
    // Bound all server-supplied lifetimes to one year before converting to milliseconds.
    static final long MAX_SECONDS = 365L * 24 * 60 * 60;
    private static final String CLIENT = "cockpit-dashboard";
    private static final String SCOPE = "host-access";
    private final HttpUrl origin;
    private final OkHttpClient http;
    private final Set<Call> calls = new HashSet<>();
    private boolean cancelled;

    static final class Error extends IOException {
        final String code;
        Error(String code) {
            super("Device authorization rejected");
            this.code = code;
        }
    }

    static final class Unavailable extends IOException {
        final int status;
        final long retryAfterSeconds;
        Unavailable(int status, String retryAfter) {
            super("Device authorization temporarily unavailable");
            this.status = status;
            retryAfterSeconds = retryAfter != null && retryAfter.matches("[0-9]{1,5}")
                    ? Math.min(86400, Long.parseLong(retryAfter)) : 0;
        }
    }

    static final class Grant {
        final String deviceCode, userCode, verificationUri, verificationUriComplete;
        final long expiresIn, interval;

        Grant(String deviceCode, String userCode, String verificationUri,
                String verificationUriComplete, long expiresIn, long interval) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.verificationUriComplete = verificationUriComplete;
            this.expiresIn = expiresIn;
            this.interval = interval;
        }
    }

    static final class Tokens {
        final String accessToken, refreshToken;
        final long expiresIn;

        Tokens(String accessToken, String refreshToken, long expiresIn) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresIn = expiresIn;
        }

        JSONObject toJSON() throws JSONException {
            return new JSONObject().put("access_token", accessToken).put("refresh_token", refreshToken)
                    .put("token_type", "Bearer").put("expires_in", expiresIn).put("scope", SCOPE);
        }
    }

    DeviceOAuth(String address) {
        this(parseOrigin(address), new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS).build());
    }

    DeviceOAuth(HttpUrl origin, OkHttpClient transport) {
        validateOrigin(origin);
        this.origin = origin;
        OkHttpClient.Builder builder = transport.newBuilder();
        builder.interceptors().clear();
        builder.networkInterceptors().clear();
        http = builder.retryOnConnectionFailure(false).followRedirects(false)
                .followSslRedirects(false).authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES)
                .cache(null).eventListener(EventListener.NONE).build();
    }

    private static HttpUrl parseOrigin(String address) {
        try {
            URI raw = new URI(address);
            if (address.length() > 2048 || raw.getRawAuthority() == null
                    || raw.getRawAuthority().contains("@") || raw.getRawFragment() != null
                    || raw.getRawQuery() != null
                    || !(raw.getRawPath().isEmpty() || raw.getRawPath().equals("/"))) {
                throw new IllegalArgumentException("Invalid HTTPS origin");
            }
            return HttpUrl.get(address);
        } catch (URISyntaxException | NullPointerException error) {
            throw new IllegalArgumentException("Invalid HTTPS origin");
        }
    }

    private static void validateOrigin(HttpUrl origin) {
        if (origin == null || !origin.isHttps() || !origin.username().isEmpty()
                || !origin.password().isEmpty() || !origin.encodedPath().equals("/")
                || origin.query() != null || origin.fragment() != null
                || origin.toString().length() > 2048) {
            throw new IllegalArgumentException("Invalid HTTPS origin");
        }
    }

    Grant authorize() throws IOException, JSONException {
        JSONObject value = request("/_gate/oauth/device_authorization",
                form().add("scope", SCOPE).build(), false);
        String device = text(value, "device_code", MAX_TOKEN);
        String user = text(value, "user_code", 128);
        String verification = text(value, "verification_uri", 2048);
        HttpUrl base = verificationUrl(verification);
        if (base.query() != null) throw invalid();
        // RFC 8628 makes the complete URI optional. The plain verification page remains
        // a usable QR destination: the controller also displays the user code.
        String complete = verification;
        if (value.has("verification_uri_complete")) {
            complete = text(value, "verification_uri_complete", 2048);
            HttpUrl target = verificationUrl(complete);
            if (!target.host().equals(base.host()) || target.port() != base.port()
                    || !target.encodedPath().equals(base.encodedPath())
                    || target.querySize() != 1
                    || !"user_code".equals(target.queryParameterName(0))
                    || !user.equals(target.queryParameterValue(0))) throw invalid();
        }
        return new Grant(device, user, verification, complete, seconds(value, "expires_in"),
                value.has("interval") ? seconds(value, "interval") : 5);
    }

    String resourceOrigin() { return origin.toString(); }

    Tokens poll(String deviceCode) throws IOException, JSONException {
        safeText(deviceCode, MAX_TOKEN);
        return tokens(request("/_gate/oauth/token", form()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("device_code", deviceCode).build(), false));
    }

    Tokens refresh(String refreshToken) throws IOException, JSONException {
        safeText(refreshToken, MAX_TOKEN);
        return tokens(request("/_gate/oauth/token", form().add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken).build(), false));
    }

    void revoke(String refreshToken) throws IOException, JSONException {
        safeText(refreshToken, MAX_TOKEN);
        request("/_gate/oauth/revoke", form().add("token", refreshToken)
                .add("token_type_hint", "refresh_token").build(), true);
    }

    synchronized void cancel() {
        cancelled = true;
        for (Call call : calls) call.cancel();
    }

    private FormBody.Builder form() { return new FormBody.Builder().add("client_id", CLIENT); }

    private JSONObject request(String path, FormBody form, boolean revocation)
            throws IOException, JSONException {
        // A one-shot body also blocks OkHttp's status-based follow-ups (notably 503
        // with Retry-After: 0). Disabling connection retries alone does not do that.
        RequestBody body = new RequestBody() {
            @Override public MediaType contentType() { return form.contentType(); }
            @Override public long contentLength() { return form.contentLength(); }
            @Override public boolean isOneShot() { return true; }
            @Override public void writeTo(BufferedSink sink) throws IOException { form.writeTo(sink); }
        };
        Call call;
        synchronized (this) {
            if (cancelled) throw new IOException("Device authorization cancelled");
            call = http.newCall(new Request.Builder().url(origin.resolve(path))
                    .header("Accept", "application/json").post(body).build());
            calls.add(call);
        }
        try (Response response = call.execute()) {
            if (response.code() == 429 || response.code() == 503) {
                throw new Unavailable(response.code(), response.header("Retry-After"));
            }
            if (response.code() != 200 && response.code() != 400) {
                throw new IOException("Device authorization HTTP " + response.code());
            }
            byte[] bytes = readBounded(response);
            synchronized (this) {
                if (cancelled) throw new IOException("Device authorization cancelled");
            }
            if (revocation && response.code() == 200) return new JSONObject();
            JSONObject value = parse(bytes);
            if (response.code() == 400) {
                String code = text(value, "error", 128);
                if (!code.matches("[A-Za-z0-9_.-]+")) throw invalid();
                throw new Error(code);
            }
            return value;
        } finally {
            synchronized (this) { calls.remove(call); }
        }
    }

    private static byte[] readBounded(Response response) throws IOException {
        if (response.body() == null || response.body().contentLength() > MAX_RESPONSE) {
            throw new IOException("Invalid device authorization response size");
        }
        response.body().source().request(MAX_RESPONSE + 1L);
        if (response.body().source().getBuffer().size() > MAX_RESPONSE) {
            throw new IOException("Invalid device authorization response size");
        }
        return response.body().source().getBuffer().readByteArray();
    }

    private static JSONObject parse(byte[] bytes) throws JSONException {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            validateStrings(text);
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setLenient(false);
                Object value = readValue(reader, 0);
                if (!(value instanceof JSONObject) || reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
                return (JSONObject) value;
            }
        } catch (IOException | IllegalArgumentException | JSONException error) {
            // Never expose parser exceptions: they can quote secret response content.
            throw invalid();
        }
    }

    private static void validateStrings(String text) throws JSONException {
        // Android's JsonReader accepts raw controls and unknown escapes inside strings
        // even in strict mode. Check string syntax before its structural validation.
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (quoted) {
                if (c < 0x20) throw invalid();
                if (c == '\\') {
                    if (++i >= text.length()) throw invalid();
                    char escape = text.charAt(i);
                    if (escape == 'u') {
                        if (i + 4 >= text.length()) throw invalid();
                        for (int n = 0; n < 4; n++) {
                            char hex = text.charAt(++i);
                            if (!(hex >= '0' && hex <= '9') && !(hex >= 'a' && hex <= 'f')
                                    && !(hex >= 'A' && hex <= 'F')) throw invalid();
                        }
                    } else if ("\"\\/bfnrt".indexOf(escape) < 0) throw invalid();
                }
            }
        }
        if (quoted) throw invalid();
    }

    private static Object readValue(JsonReader reader, int depth) throws IOException, JSONException {
        if (depth > 16) throw invalid();
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JSONObject object = new JSONObject();
                Set<String> names = new HashSet<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) throw invalid();
                    object.put(name, readValue(reader, depth + 1));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JSONArray array = new JSONArray();
                reader.beginArray();
                while (reader.hasNext()) array.put(readValue(reader, depth + 1));
                reader.endArray();
                return array;
            case STRING: return reader.nextString();
            case NUMBER:
                // Keep JSON number type and exact precision; never coerce strings to numbers.
                return new BigDecimal(reader.nextString());
            case BOOLEAN: return reader.nextBoolean();
            case NULL:
                reader.nextNull();
                return JSONObject.NULL;
            default: throw invalid();
        }
    }

    private static Tokens tokens(JSONObject value) throws JSONException {
        if (!"Bearer".equalsIgnoreCase(text(value, "token_type", 16))
                || !SCOPE.equals(text(value, "scope", 128))) throw invalid();
        return new Tokens(text(value, "access_token", MAX_TOKEN),
                text(value, "refresh_token", MAX_TOKEN), seconds(value, "expires_in"));
    }

    private static long seconds(JSONObject value, String name) throws JSONException {
        Object number = value.opt(name);
        if (!(number instanceof BigDecimal)) throw invalid();
        try {
            long seconds = ((BigDecimal) number).longValueExact();
            if (seconds < 1 || seconds > MAX_SECONDS) throw invalid();
            return seconds;
        } catch (ArithmeticException error) {
            throw invalid();
        }
    }

    private static String text(JSONObject value, String name, int max) throws JSONException {
        Object text = value.opt(name);
        if (!(text instanceof String)) throw invalid();
        safeText((String) text, max);
        return (String) text;
    }

    private static void safeText(String value, int max) throws JSONException {
        if (value == null || value.isEmpty() || value.length() > max) throw invalid();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < 0x21 || character > 0x7e) throw invalid();
        }
    }

    private static HttpUrl verificationUrl(String value) throws JSONException {
        try {
            URI raw = new URI(value);
            if (!"https".equalsIgnoreCase(raw.getScheme()) || raw.getRawAuthority() == null
                    || raw.getRawAuthority().contains("@") || raw.getRawFragment() != null
                    || raw.getHost() == null || raw.getRawPath().contains("\\")) throw invalid();
            HttpUrl url = HttpUrl.get(value);
            // Decoded controls are unsafe too, even when a URL parser accepts the escape.
            for (int i = 0; i < value.length() - 2; i++) {
                if (value.charAt(i) == '%') {
                    int decoded = Integer.parseInt(value.substring(i + 1, i + 3), 16);
                    if (decoded <= 0x20 || decoded == 0x7f) throw invalid();
                }
            }
            return url;
        } catch (URISyntaxException | IllegalArgumentException error) {
            throw invalid();
        }
    }

    private static JSONException invalid() { return new JSONException("Invalid device authorization response"); }
}
