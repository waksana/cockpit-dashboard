package io.github.waksana.cockpitdashboard;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class HostClient {
    static final int MAX_RESPONSE = 4 * 1024 * 1024;
    static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    final OkHttpClient http;
    final HttpUrl origin;
    final String sessionId;
    private final String authorization;
    private final GateSession gateSession;
    private final DeviceCredentials device;

    static class Rejected extends IOException {
        Rejected(String message) { super(message); }
    }

    static final class AuthenticationRequired extends Rejected {
        AuthenticationRequired(String message) { super(message); }
    }

    HostClient(String address, String sessionId, String authorization) {
        this(address, sessionId, authorization, null);
    }

    HostClient(String address, String sessionId, String authorization, GateSession gateSession) {
        this(HttpUrl.get(address), sessionId, authorization, newHttp(), gateSession);
    }

    static OkHttpClient newHttp() {
        return transport(new OkHttpClient.Builder()
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS).build());
    }

    HostClient(HttpUrl origin, String sessionId, String authorization, OkHttpClient http) {
        this(origin, sessionId, authorization, http, null);
    }

    HostClient(HttpUrl origin, String sessionId, String authorization, OkHttpClient http, GateSession gateSession) {
        this(origin, sessionId, authorization, http, gateSession, null);
    }

    HostClient(String address, String sessionId, String authorization, GateSession gateSession,
            DeviceCredentials device) {
        this(HttpUrl.get(address), sessionId, authorization, newHttp(), gateSession, device);
    }

    HostClient(HttpUrl origin, String sessionId, String authorization, OkHttpClient http,
            GateSession gateSession, DeviceCredentials device) {
        this.origin = origin;
        this.sessionId = sessionId;
        this.authorization = authorization;
        this.gateSession = gateSession;
        this.device = device;
        this.http = transport(http);
    }

    static OkHttpClient transport(OkHttpClient http) {
        return http.newBuilder().retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).addNetworkInterceptor(chain -> {
                    Response response = chain.proceed(chain.request());
                    // OkHttp otherwise repeats 503 + Retry-After: 0 even with connection retries off.
                    return response.code() == 503
                            ? response.newBuilder().header("Retry-After", "1").build() : response;
                }).build();
    }

    static String normalizeAddress(String entered) {
        String address = entered.trim();
        if (address.isEmpty()) throw new IllegalArgumentException("请输入 Cockpit 域名");
        if (!address.contains("://")) address = "https://" + address;
        validateSettings(address, "settings", "");
        return HttpUrl.get(address).toString();
    }

    static void validateSettings(String address, String sessionId, String authorization) {
        HttpUrl url = HttpUrl.get(address);
        if (!url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()
                || !url.encodedPath().equals("/") || url.query() != null || url.fragment() != null) {
            throw new IllegalArgumentException("请输入 HTTPS 站点根地址，不含用户名、路径、查询参数");
        }
        if (!sessionId.matches("[A-Za-z0-9_-]{1,200}")) {
            throw new IllegalArgumentException("请输入一个已有常规 session 的 ID");
        }
        if (authorization.contains("\r") || authorization.contains("\n")
                || (!authorization.isEmpty() && !authorization.matches("(Basic|Bearer) [!-~]+"))) {
            throw new IllegalArgumentException("鉴权格式为 Basic 或 Bearer 加凭据；无需鉴权时留空");
        }
    }

    JSONObject request(String path, JSONObject body) throws IOException, JSONException {
        return request(path, body, null);
    }

    private JSONObject request(String path, JSONObject body, String moduleDigest) throws IOException, JSONException {
        HttpUrl url = origin.resolve(path);
        if (url == null || !url.scheme().equals(origin.scheme()) || !url.host().equals(origin.host())
                || url.port() != origin.port()) throw new Rejected("拒绝跨站请求");
        Request.Builder builder = new Request.Builder().url(url).header("Accept", "application/json");
        if (moduleDigest != null) builder.header("X-Cockpit-Module-Digest", moduleDigest);
        if (device != null) {
            builder.header("Authorization", device.header(url, token -> new DeviceOAuth(origin, http).refresh(token)));
        } else {
            if (!authorization.isEmpty()) builder.header("Authorization", authorization);
            if (gateSession != null) builder.header("Cookie", gateSession.header(url, System.currentTimeMillis()));
        }
        if (body != null) builder.post(RequestBody.create(body.toString(), JSON));
        try (Response response = http.newCall(builder.build()).execute()) {
            HttpUrl redirect = response.header("Location") == null ? null : url.resolve(response.header("Location"));
            if (response.code() == 401 || (response.isRedirect() && redirect != null
                    && redirect.scheme().equals(origin.scheme()) && redirect.host().equals(origin.host())
                    && redirect.port() == origin.port() && redirect.encodedPath().equals("/_gate/login"))) {
                throw new AuthenticationRequired("网关要求登录：按返回键选择扫码登录，或检查 Authorization");
            }
            // A proxy/5xx can fail after the mutation reached the host; never call it rejected.
            if (!response.isSuccessful()) {
                if (moduleDigest != null && response.code() == 409) {
                    throw new Rejected("Speech 模块请求冲突（HTTP 409），请检查 App 与模块版本；未上传录音");
                }
                String message = "HTTP " + response.code() + "，请检查连接、鉴权及 Cockpit 状态";
                if (response.code() >= 400 && response.code() < 500 && response.code() != 408) {
                    throw new Rejected(message);
                }
                throw new IOException(message);
            }
            return readJSON(response, MAX_RESPONSE);
        }
    }

    static JSONObject readJSON(Response response, int limit) throws IOException, JSONException {
        if (response.body() == null) throw new IOException("后端响应为空");
        if (response.body().contentLength() > limit) throw new IOException("响应过大");
        response.body().source().request(limit + 1L);
        if (response.body().source().getBuffer().size() > limit) throw new IOException("响应过大");
        return new JSONObject(response.body().source().getBuffer().readUtf8());
    }

    JSONObject intent(String name, JSONObject body) throws IOException, JSONException {
        return request("/intent/" + name, body);
    }

    SessionDirectory directory(String cursor) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("limit", SessionDirectory.PAGE_SIZE);
        if (cursor != null) body.put("cursor", cursor);
        return new SessionDirectory(intent("session/directory", body));
    }

    JSONObject meta() throws IOException, JSONException {
        JSONObject result = intent("session/get", new JSONObject().put("sessionId", sessionId));
        JSONObject meta = result.optJSONObject("meta");
        if (meta == null) throw new Rejected("未找到指定 session；请在设置中填写已有 session ID");
        if (!sessionId.equals(meta.getString("sessionId"))) throw new IOException("session 身份不匹配");
        return meta;
    }

    void capabilities() throws IOException, JSONException {
        for (String name : new String[]{"session/get", "session/chat", "prompt", "respondAsk"}) {
            JSONObject result = request("/capabilities?name=" + name, null);
            if (!name.equals(result.optString("name"))) throw new IOException("缺少所需公开 API：" + name);
        }
    }

    JSONObject page(boolean live, String direction, String cursor, boolean bootstrap)
            throws IOException, JSONException {
        JSONObject body = new JSONObject().put("sessionId", sessionId)
                .put("source", live ? "live" : "persisted").put("direction", direction)
                .put("max", 32).put("waitMs", 0).put("bootstrap", bootstrap);
        if (cursor != null) body.put("cursor", cursor);
        if (live) {
            body.put("agentScope", "primary").put("types", new JSONArray()
                    .put("user.message").put("assistant.message").put("assistant.message_delta")
                    .put("user_input.requested").put("user_input.completed").put("session.error"));
            if ("forward".equals(direction)) body.put("includeEphemeral", true);
        }
        JSONObject result = intent("session/chat", body);
        if (!sessionId.equals(result.getString("sessionId"))
                || !direction.equals(result.getString("direction"))
                || !(live ? "live" : "persisted").equals(result.getString("source"))) {
            throw new IOException("历史响应范围不匹配");
        }
        if (!"ok".equals(result.getString("cursorStatus"))) throw new IOException("历史游标已失效，请重新连接");
        result.getJSONArray("events");
        result.getString("cursor");
        return result;
    }

    JSONObject speechCredential() throws IOException, JSONException {
        JSONArray modules = request("/_modules", null).getJSONArray("modules");
        for (int i = 0; i < modules.length(); i++) {
            JSONObject module = modules.getJSONObject(i);
            if (!"cockpit-speech".equals(module.optString("id"))) continue;
            String path = module.getString("apiBase");
            String digest = module.optString("digest", "");
            if (!digest.matches("[a-f0-9]{64}")
                    || !path.equals("/_modules/cockpit-speech/" + digest + "/api")) {
                throw new IOException("Speech 模块发现返回了无效或不匹配的版本路径");
            }
            return request(path + "/session", new JSONObject(), digest);
        }
        throw new Rejected("目标 Cockpit 未启用 Speech；未上传录音");
    }

    JSONObject send(String text, JSONObject target) throws IOException, JSONException {
        if (text.trim().isEmpty()) throw new Rejected("空消息不能发送");
        JSONObject body = new JSONObject().put("sessionId", sessionId);
        JSONObject result;
        if (target != null) {
            JSONObject current = meta().optJSONObject("ask");
            if (current == null || !target.getString("requestId").equals(current.getString("requestId"))) {
                throw new Rejected("原问题已结束或变化，草稿保留；不会转发成新消息");
            }
            boolean choice = false;
            JSONArray choices = current.optJSONArray("choices");
            if (choices != null) {
                for (int i = 0; i < choices.length(); i++) if (text.equals(choices.getString(i))) choice = true;
            }
            if (!choice && !current.optBoolean("allowFreeform", true)) {
                throw new Rejected("该问题只接受列出的原文，请说出完整选项；不会替你猜选项");
            }
            result = intent("respondAsk", body.put("requestId", target.getString("requestId"))
                    .put("answer", text).put("wasFreeform", !choice));
        } else {
            result = intent("prompt", body.put("text", text).put("mode", "enqueue"));
        }
        Object accepted = result.get("ok");
        if (!(accepted instanceof Boolean)) throw new JSONException("Invalid acceptance receipt");
        if (!((Boolean) accepted)) throw new Rejected("后端未受理，草稿保留");
        return result;
    }
}
