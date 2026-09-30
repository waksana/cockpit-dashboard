package io.github.waksana.cockpitdashboard;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * One instance per transcription; call start/cancel on the main thread.
 * start takes ownership of PCM (including on failure).
 * Listener calls are main-thread only; cancel suppresses even already-posted callbacks.
 * No Host client interceptors, cookies, authenticators or redirects reach Azure.
 */
public final class SpeechTranscriber {
    public interface Listener {
        void onComplete(String text);
        void onError(String message);
    }

    static final String FINAL_COMMIT = "dashboard-final-commit";
    static final int MAX_EVENT = 262_144;
    static final long QUEUE_LIMIT = 196_608;
    private static final Pattern SOCKET = Pattern.compile(
            "^wss://[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.openai\\.azure\\.com"
                    + "/openai/v1/realtime\\?intent=transcription$");
    private static final Pattern DEPLOYMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "dashboard-speech");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean used = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean incomingRejected = new AtomicBoolean();
    private final AtomicInteger queuedChars = new AtomicInteger();
    private final AtomicInteger queuedEvents = new AtomicInteger();
    private final Transcript transcript = new Transcript();
    private final WebSocket.Factory factory;
    private final OkHttpClient independentClient;
    private volatile WebSocket socket;
    private volatile byte[] pcm;
    private volatile boolean done;
    private String deployment;
    private int offset;
    private boolean configured;
    private boolean commitSent;
    private boolean clearSent;
    private boolean emptyCommit;
    private ScheduledFuture<?> setupTimer;
    private ScheduledFuture<?> finalTimer;
    private ScheduledFuture<?> pump;

    public SpeechTranscriber(OkHttpClient hostClient, Listener listener) {
        this.listener = listener;
        // Deliberately do not clone hostClient: it may contain Host credentials or logging.
        independentClient = new OkHttpClient.Builder()
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS).build();
        factory = independentClient;
        worker.setRemoveOnCancelPolicy(true);
    }

    /** Test seam: no production endpoint allowlist bypass. */
    SpeechTranscriber(WebSocket.Factory factory, Listener listener) {
        this.listener = listener;
        this.factory = factory;
        independentClient = null;
        worker.setRemoveOnCancelPolicy(true);
    }

    public synchronized void start(JSONObject credential, byte[] audio) {
        if (!used.compareAndSet(false, true) || cancelled.get()) {
            if (audio != null) Arrays.fill(audio, (byte) 0);
            return;
        }
        pcm = audio;
        execute(() -> {
            if (!active()) { erase(); return; }
            try {
                if (audio == null || audio.length < 4_800 || audio.length > AudioCapture.MAX_BYTES
                        || audio.length % 2 != 0) {
                    fail("录音长度或格式不正确，请重新录音（0.1～120 秒）。");
                    return;
                }
                Credential validated = validateCredential(credential, System.currentTimeMillis() / 1000);
                deployment = validated.deployment;
                Request request = new Request.Builder().url(validated.url)
                        .header("Authorization", "Bearer " + validated.secret).build();
                setupTimer = worker.schedule(() -> fail("语音服务连接或配置超时，请重新录音。"),
                        30, TimeUnit.SECONDS);
                if (!active()) return;
                WebSocket opened = factory.newWebSocket(request, callbacks);
                socket = opened;
                if (!active()) opened.cancel();
            } catch (JSONException | IllegalArgumentException failure) {
                fail("语音临时凭据无效或已过期，请重新获取。");
            } catch (RuntimeException failure) {
                fail("无法连接语音服务，请稍后重新录音。");
            }
        });
    }

    public synchronized void cancel() {
        cancelled.set(true);
        WebSocket current = socket;
        if (current != null) current.cancel();
        erase();
        execute(this::finish);
    }

    private final WebSocketListener callbacks = new WebSocketListener() {
        @Override public void onOpen(WebSocket webSocket, Response response) {
            execute(() -> {
                if (!active()) { webSocket.cancel(); return; }
                socket = webSocket;
                try { send(configuration(deployment).toString()); }
                catch (JSONException failure) { fail("语音服务配置失败。"); }
            });
        }

        @Override public void onMessage(WebSocket webSocket, String text) {
            if (!active() || incomingRejected.get()) return;
            int count = queuedEvents.incrementAndGet();
            int length = queuedChars.addAndGet(text.length());
            if (text.length() > MAX_EVENT || length > MAX_EVENT * 4 || count > 128) {
                queuedEvents.decrementAndGet();
                queuedChars.addAndGet(-text.length());
                webSocket.cancel();
                if (incomingRejected.compareAndSet(false, true)) {
                    execute(() -> fail("语音服务响应超过安全限制。"));
                }
                return;
            }
            execute(() -> {
                queuedEvents.decrementAndGet();
                queuedChars.addAndGet(-text.length());
                if (active() && !incomingRejected.get()) receive(text);
            });
        }

        @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
            if (active() && incomingRejected.compareAndSet(false, true)) {
                webSocket.cancel();
                execute(() -> fail("语音服务返回了不支持的数据格式。"));
            }
        }

        @Override public void onFailure(WebSocket webSocket, Throwable failure, Response response) {
            int status = response == null ? 0 : response.code();
            execute(() -> fail(status == 429 ? "语音服务请求过于频繁，请稍后重试。"
                    : status == 401 || status == 403 ? "语音授权已失效，请重新获取临时凭据。"
                    : "语音服务连接失败，未自动重试。"));
        }

        @Override public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(1000, null);
            execute(() -> fail("语音连接提前关闭，未得到完整转写。"));
        }

        @Override public void onClosed(WebSocket webSocket, int code, String reason) {
            execute(() -> fail("语音连接已关闭，未得到完整转写。"));
        }
    };

    private void receive(String raw) {
        try {
            JSONObject event = new JSONObject(raw);
            String type = string(event, "type");
            switch (type) {
                case "session.created":
                    break;
                case "session.updated":
                    if (configured || !matchesSession(event.getJSONObject("session"), deployment)) {
                        throw new JSONException("configuration");
                    }
                    configured = true;
                    setupTimer.cancel(false);
                    finalTimer = worker.schedule(() -> fail("语音转写超时，未得到完整结果。"),
                            90, TimeUnit.SECONDS);
                    pump = worker.scheduleWithFixedDelay(this::pumpAudio, 0, 20, TimeUnit.MILLISECONDS);
                    break;
                case "input_audio_buffer.committed":
                    requireAudio();
                    transcript.committed(event);
                    break;
                case "conversation.item.input_audio_transcription.delta":
                case "conversation.item.input_audio_transcription.completed":
                    requireAudio();
                    transcript.text(event, type.endsWith(".completed"));
                    break;
                case "input_audio_buffer.cleared":
                    if (!clearSent) throw new JSONException("barrier");
                    transcript.barrier();
                    break;
                case "error":
                    JSONObject error = event.getJSONObject("error");
                    if (commitSent && !emptyCommit && transcript.committedCount() > 0
                            && "input_audio_buffer_commit_empty".equals(error.opt("code"))
                            && "invalid_request_error".equals(error.opt("type"))
                            && FINAL_COMMIT.equals(error.opt("event_id"))) {
                        emptyCommit = true;
                    } else {
                        fail("rate_limit_exceeded".equals(error.opt("code"))
                                ? "语音服务请求过于频繁，请稍后重试。"
                                : "语音服务拒绝转写，请重新录音或检查服务配置。");
                        return;
                    }
                    break;
                case "conversation.item.input_audio_transcription.failed":
                    fail("语音识别失败，请重新录音。");
                    return;
                case "input_audio_buffer.speech_started":
                case "input_audio_buffer.speech_stopped":
                case "conversation.item.created":
                case "conversation.item.added":
                case "conversation.item.done":
                case "rate_limits.updated":
                    requireAudio();
                    break;
                default:
                    throw new JSONException("event");
            }
            String result = transcript.result();
            if (result != null) {
                finish();
                main.post(() -> { if (!cancelled.get()) listener.onComplete(result); });
            }
        } catch (JSONException | RuntimeException failure) {
            fail("语音服务返回不完整或不兼容的数据；未保留转写。");
        }
    }

    private void requireAudio() throws JSONException {
        if (!configured || offset == 0) throw new JSONException("sequence");
    }

    private void pumpAudio() {
        if (!active()) return;
        try {
            if (socket.queueSize() > QUEUE_LIMIT) return;
            byte[] audio = pcm;
            if (audio == null) return;
            if (offset < audio.length) {
                int size = Math.min(24_000, audio.length - offset);
                String encoded = Base64.encodeToString(audio, offset, size, Base64.NO_WRAP);
                if (!send(new JSONObject().put("type", "input_audio_buffer.append")
                        .put("audio", encoded).toString())) return;
                Arrays.fill(audio, offset, offset + size, (byte) 0);
                offset += size;
            }
            if (offset == audio.length) {
                commitSent = true;
                if (!send(new JSONObject().put("type", "input_audio_buffer.commit")
                        .put("event_id", FINAL_COMMIT).toString())) return;
                clearSent = true;
                if (!send(new JSONObject().put("type", "input_audio_buffer.clear")
                        .put("event_id", "dashboard-clear-barrier").toString())) return;
                erase();
                pump.cancel(false);
            }
        } catch (JSONException | RuntimeException failure) {
            fail("语音上传失败，未自动重试。");
        }
    }

    private boolean send(String event) {
        if (!active() || incomingRejected.get()) return false;
        if (socket == null || !socket.send(event)) {
            fail("语音发送队列不可用，未自动重试。");
            return false;
        }
        return true;
    }

    private boolean active() { return !done && !cancelled.get(); }

    private void fail(String safeMessage) {
        if (!active()) return;
        finish();
        main.post(() -> { if (!cancelled.get()) listener.onError(safeMessage); });
    }

    private void finish() {
        if (done) return;
        done = true;
        if (setupTimer != null) setupTimer.cancel(false);
        if (finalTimer != null) finalTimer.cancel(false);
        if (pump != null) pump.cancel(false);
        WebSocket current = socket;
        socket = null;
        if (current != null) current.cancel();
        erase();
        transcript.clear();
        deployment = null;
        worker.shutdown();
        if (independentClient != null) {
            independentClient.connectionPool().evictAll();
            independentClient.dispatcher().executorService().shutdown();
        }
    }

    private void erase() {
        byte[] bytes = pcm;
        pcm = null;
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }

    private void execute(Runnable action) {
        try { worker.execute(action); }
        catch (RejectedExecutionException rejected) {
            // Late socket callbacks may arrive after finish has shut the worker down.
            if (!worker.isShutdown() || (!done && !cancelled.get())) throw rejected;
        }
    }

    static Credential validateCredential(JSONObject credential, long now) throws JSONException {
        if (credential == null) throw new JSONException("credential");
        String url = string(credential, "socketUrl");
        String deployment = string(credential, "deployment");
        String secret = string(credential, "clientSecret");
        Object expiry = credential.get("expiresAt");
        if (!SOCKET.matcher(url).matches() || !DEPLOYMENT.matcher(deployment).matches()
                || secret.isEmpty() || secret.length() > 8_192
                || !(expiry instanceof Number) || ((Number) expiry).doubleValue() <= now
                || Double.isInfinite(((Number) expiry).doubleValue())
                || Double.isNaN(((Number) expiry).doubleValue())
                || ((Number) expiry).doubleValue() != ((Number) expiry).longValue()) {
            throw new JSONException("credential");
        }
        for (int i = 0; i < secret.length(); i++) {
            if (secret.charAt(i) < 33 || secret.charAt(i) > 126) throw new JSONException("credential");
        }
        return new Credential(url, deployment, secret);
    }

    static JSONObject configuration(String deployment) throws JSONException {
        return new JSONObject().put("type", "session.update").put("session", new JSONObject()
                .put("type", "transcription").put("audio", new JSONObject().put("input",
                        new JSONObject().put("format", new JSONObject().put("type", "audio/pcm").put("rate", 24_000))
                                .put("transcription", new JSONObject().put("model", deployment).put("prompt", ""))
                                .put("turn_detection", new JSONObject().put("type", "server_vad")
                                        .put("silence_duration_ms", 1_000)))));
    }

    static boolean matchesSession(JSONObject session, String deployment) throws JSONException {
        JSONObject input = session.getJSONObject("audio").getJSONObject("input");
        JSONObject format = input.getJSONObject("format");
        JSONObject transcription = input.getJSONObject("transcription");
        JSONObject vad = input.getJSONObject("turn_detection");
        return "transcription".equals(session.opt("type")) && "audio/pcm".equals(format.opt("type"))
                && number(format.opt("rate"), 24_000) && deployment.equals(transcription.opt("model"))
                && "".equals(transcription.opt("prompt")) && "server_vad".equals(vad.opt("type"))
                && number(vad.opt("silence_duration_ms"), 1_000);
    }

    private static boolean number(Object value, int expected) {
        return value instanceof Number && ((Number) value).doubleValue() == expected;
    }

    private static String string(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new JSONException("type");
        return (String) value;
    }

    static final class Credential {
        final String url;
        final String deployment;
        final String secret;
        Credential(String url, String deployment, String secret) {
            this.url = url;
            this.deployment = deployment;
            this.secret = secret;
        }
    }
}
