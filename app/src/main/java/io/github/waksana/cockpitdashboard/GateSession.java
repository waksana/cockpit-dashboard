package io.github.waksana.cockpitdashboard;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.json.JSONException;
import org.json.JSONObject;

final class GateSession {
    static final String COOKIE = "__Host-pg_session";
    final String origin;
    final String value;
    final long expiresAt;

    GateSession(String origin, String value, long expiresAt) {
        HttpUrl url = HttpUrl.get(origin);
        if (!url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()
                || !url.encodedPath().equals("/") || url.query() != null || url.fragment() != null
                || !url.toString().equals(origin) || !value.matches("[A-Za-z0-9_-]{43}") || expiresAt <= 0) {
            throw new IllegalArgumentException("无效的 Passkey 登录状态，请重新登录");
        }
        this.origin = origin;
        this.value = value;
        this.expiresAt = expiresAt;
    }

    static GateSession fromJSON(JSONObject saved) throws JSONException {
        return saved == null ? null : new GateSession(saved.getString("origin"),
                saved.getString("value"), saved.getLong("expiresAt"));
    }

    static GateSession fromCookie(HttpUrl origin, Cookie cookie) {
        if (!COOKIE.equals(cookie.name()) || !cookie.secure() || !cookie.httpOnly()
                || !cookie.hostOnly() || !cookie.path().equals("/") || !cookie.persistent()
                || !cookie.domain().equals(origin.host())) {
            throw new IllegalArgumentException("网关返回了不安全的登录 Cookie");
        }
        return new GateSession(origin.toString(), cookie.value(), cookie.expiresAt());
    }

    JSONObject toJSON() throws JSONException {
        return new JSONObject().put("origin", origin).put("value", value).put("expiresAt", expiresAt);
    }

    String header(HttpUrl target, long now) throws HostClient.AuthenticationRequired {
        if (!origin.equals(target.newBuilder().encodedPath("/").query(null).fragment(null).build().toString())
                || now >= expiresAt) {
            throw new HostClient.AuthenticationRequired("Passkey 登录已过期或站点已更换，请重新登录");
        }
        return COOKIE + "=" + value;
    }
}
