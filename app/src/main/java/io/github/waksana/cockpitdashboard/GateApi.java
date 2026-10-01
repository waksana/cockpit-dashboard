package io.github.waksana.cockpitdashboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;

final class GateApi {
    private final HttpUrl origin;
    private final LoginCookies cookies;
    private final OkHttpClient http;
    private volatile Call active;
    private volatile boolean cancelled;

    GateApi(String address) {
        this(HttpUrl.get(address), HostClient.newHttp());
        if (origin.port() != 443) throw new IllegalArgumentException("Passkey Gate 需要标准 HTTPS 端口");
    }

    GateApi(HttpUrl origin, OkHttpClient transport) {
        HostClient.validateSettings(origin.toString(), "login", "");
        this.origin = origin;
        this.cookies = new LoginCookies(origin);
        this.http = HostClient.transport(transport).newBuilder().cookieJar(cookies).build();
    }

    JSONObject options() throws IOException, JSONException {
        try (Response response = execute("/_gate/login", null)) {
            requireSuccess(response);
        }
        try (Response response = execute("/_gate/auth/options", new JSONObject())) {
            requireSuccess(response);
            JSONObject options = HostClient.readJSON(response, 64 * 1024).getJSONObject("publicKey");
            String rp = options.getString("rpId");
            if (rp.isEmpty() || (!origin.host().equals(rp) && !origin.host().endsWith("." + rp))
                    || !rp.matches("[a-z0-9]+(?:[.-][a-z0-9]+)*")
                    || !"required".equals(options.optString("userVerification"))
                    || options.getString("challenge").isEmpty()) {
                throw new HostClient.Rejected("网关返回了不匹配的 RP ID 或用户验证要求");
            }
            return options;
        }
    }

    GateSession finish(String credential) throws IOException, JSONException {
        try (Response response = execute("/_gate/auth/finish", new JSONObject(credential))) {
            requireSuccess(response);
            JSONObject result = HostClient.readJSON(response, 64 * 1024);
            if (!"/".equals(result.optString("redirect"))) throw new IOException("无效的登录回执");
            Cookie cookie = cookies.session();
            if (cookie == null) throw new IOException("登录未返回有效 Cookie");
            GateSession session = GateSession.fromCookie(origin, cookie);
            session.header(origin, System.currentTimeMillis());
            return session;
        }
    }

    private Response execute(String path, JSONObject body) throws IOException {
        if (cancelled) throw new IOException("Login cancelled");
        Request.Builder request = new Request.Builder().url(origin.resolve(path))
                .header("Origin", origin.toString().substring(0, origin.toString().length() - 1))
                .header("Accept", "application/json");
        if (body != null) request.post(RequestBody.create(body.toString(), HostClient.JSON));
        Call call = http.newCall(request.build());
        active = call;
        if (cancelled) call.cancel();
        return call.execute();
    }

    void cancel() {
        cancelled = true;
        Call call = active;
        if (call != null) call.cancel();
    }

    private static void requireSuccess(Response response) throws IOException {
        if (!response.isSuccessful()) {
            throw new HostClient.Rejected("Passkey 网关 HTTP " + response.code()
                    + "；请检查 App 来源白名单、域名关联与网关状态");
        }
    }

    static final class LoginCookies implements CookieJar {
        private final HttpUrl origin;
        private final Map<String, Cookie> values = new LinkedHashMap<>();
        LoginCookies(HttpUrl origin) { this.origin = origin; }

        private boolean sameOrigin(HttpUrl url) {
            return origin.scheme().equals(url.scheme()) && origin.host().equals(url.host()) && origin.port() == url.port();
        }

        @Override public synchronized void saveFromResponse(HttpUrl url, List<Cookie> received) {
            if (!sameOrigin(url)) return;
            for (Cookie cookie : received) {
                if (!cookie.name().equals("__Host-pg_client") && !cookie.name().equals("__Host-pg_flow")
                        && !cookie.name().equals(GateSession.COOKIE)) continue;
                if (!cookie.secure() || !cookie.httpOnly() || !cookie.hostOnly()
                        || !cookie.domain().equals(origin.host()) || !cookie.path().equals("/")) continue;
                if (cookie.expiresAt() <= System.currentTimeMillis()) values.remove(cookie.name());
                else values.put(cookie.name(), cookie);
            }
        }

        @Override public synchronized List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> result = new ArrayList<>();
            if (sameOrigin(url)) {
                for (Cookie cookie : values.values()) {
                    if (cookie.expiresAt() > System.currentTimeMillis() && cookie.matches(url)) result.add(cookie);
                }
            }
            return result;
        }

        synchronized Cookie session() { return values.get(GateSession.COOKIE); }
    }
}
