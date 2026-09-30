package io.github.waksana.cockpitdashboard;

import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.BooleanSupplier;

import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SpeechTranscriberTest {
    private final List<SpeechTranscriber> running = new ArrayList<>();
    @After public void cancel() { for (SpeechTranscriber speech : running) speech.cancel(); }

    @Test public void credentialsStrictlyConstrainEndpointExpiryAndHeaders() throws Exception {
        JSONObject valid = credential();
        SpeechTranscriber.validateCredential(valid, 1);
        for (String url : new String[]{
                "wss://service.openai.azure.com.evil.test/openai/v1/realtime?intent=transcription",
                "ws://service.openai.azure.com/openai/v1/realtime?intent=transcription",
                "wss://service.openai.azure.com:443/openai/v1/realtime?intent=transcription",
                "wss://service.openai.azure.com/openai/v1/realtime?intent=transcription&secret=x",
                "wss://user@service.openai.azure.com/openai/v1/realtime?intent=transcription",
                "wss://service.openai.azure.com/openai/v1/realtime?intent=transcription#x"}) {
            assertThrows(JSONException.class, () -> SpeechTranscriber.validateCredential(
                    credential().put("socketUrl", url), 1));
        }
        assertThrows(JSONException.class, () -> SpeechTranscriber.validateCredential(
                credential().put("expiresAt", 1), 1));
        assertThrows(JSONException.class, () -> SpeechTranscriber.validateCredential(
                credential().put("expiresAt", "9999999999"), 1));
        assertThrows(JSONException.class, () -> SpeechTranscriber.validateCredential(
                credential().put("clientSecret", "token\r\nInjected: true"), 1));
        assertThrows(JSONException.class, () -> SpeechTranscriber.validateCredential(
                credential().put("deployment", "unsafe/token"), 1));
    }

    @Test public void unexpectedWorkerRejectionIsNotSilentlySwallowed() throws Exception {
        Harness h = new Harness();
        ScheduledThreadPoolExecutor executor = ReflectionHelpers.getField(h.speech, "worker");
        executor.shutdown();
        assertThrows(RejectedExecutionException.class, () -> h.speech.start(credential(), new byte[4_800]));
        h.speech.cancel();
    }

    @Test public void exactConfigurationMustBeAcknowledgedBeforeAnyAudio() throws Exception {
        Harness h = start(new byte[4_800]);
        assertEquals(1, h.socket.sent.size());
        assertEquals("Bearer ephemeral-test", h.socket.request.header("Authorization"));
        assertFalse(h.socket.request.url().toString().contains("ephemeral-test"));
        JSONObject session = SpeechTranscriber.configuration("deployment").getJSONObject("session");
        session.getJSONObject("audio").getJSONObject("input").getJSONObject("format").put("rate", 16_000);
        h.event(new JSONObject().put("type", "session.updated").put("session", session));
        await(() -> h.error != null);
        assertEquals(1, h.socket.sent.size());
        assertTrue(h.socket.cancelled);
    }

    @Test public void allRequiredSessionFieldsAreValidated() throws Exception {
        JSONObject session = SpeechTranscriber.configuration("deployment").getJSONObject("session");
        assertTrue(SpeechTranscriber.matchesSession(session, "deployment"));
        assertFalse(SpeechTranscriber.matchesSession(session, "other"));
        JSONObject input = session.getJSONObject("audio").getJSONObject("input");
        input.getJSONObject("transcription").put("prompt", "unexpected");
        assertFalse(SpeechTranscriber.matchesSession(session, "deployment"));
        input.getJSONObject("transcription").put("prompt", "");
        input.getJSONObject("turn_detection").put("silence_duration_ms", 500);
        assertFalse(SpeechTranscriber.matchesSession(session, "deployment"));
        input.getJSONObject("turn_detection").put("silence_duration_ms", 1000);
        input.getJSONObject("format").put("rate", "24000");
        assertFalse(SpeechTranscriber.matchesSession(session, "deployment"));
    }

    @Test public void finalCommitThenClearBarrierWaitsForOutOfOrderFinals() throws Exception {
        byte[] audio = new byte[48_000];
        Arrays.fill(audio, (byte) 7);
        Harness h = start(audio);
        configure(h);
        await(() -> h.socket.has("input_audio_buffer.clear"));
        assertEquals("input_audio_buffer.commit", h.socket.sent.get(h.socket.sent.size() - 2).getString("type"));
        assertEquals(SpeechTranscriber.FINAL_COMMIT,
                h.socket.sent.get(h.socket.sent.size() - 2).getString("event_id"));
        h.event(TranscriptTest.text("two", "second", true));
        h.event(TranscriptTest.commit("two", "one"));
        h.event(TranscriptTest.commit("one", null));
        h.event(new JSONObject().put("type", "input_audio_buffer.cleared"));
        Thread.sleep(30);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(h.result);
        h.event(TranscriptTest.text("one", "partial", false));
        h.event(TranscriptTest.text("one", "first", true));
        await(() -> h.result != null);
        assertEquals("first\nsecond", h.result);
        assertNull(h.error);
        assertArrayEquals(new byte[audio.length], audio);
        assertTrue(h.socket.cancelled);
        assertTrue(h.mainCallback);
    }

    @Test public void onlyPreciselyMatchedFinalEmptyCommitErrorIsTolerated() throws Exception {
        for (String eventId : new String[]{SpeechTranscriber.FINAL_COMMIT, "unrelated"}) {
            Harness h = start(new byte[4_800]);
            configure(h);
            await(() -> h.socket.has("input_audio_buffer.clear"));
            h.event(TranscriptTest.commit("one", null));
            h.event(TranscriptTest.text("one", "speech", true));
            h.event(new JSONObject().put("type", "error").put("error", new JSONObject()
                    .put("type", "invalid_request_error").put("code", "input_audio_buffer_commit_empty")
                    .put("event_id", eventId)));
            h.event(new JSONObject().put("type", "input_audio_buffer.cleared"));
            await(() -> h.result != null || h.error != null);
            if (SpeechTranscriber.FINAL_COMMIT.equals(eventId)) assertEquals("speech", h.result);
            else assertNotNull(h.error);
        }
    }

    @Test public void cancelSuppressesQueuedAndLateCallbacksAndErasesPcm() throws Exception {
        byte[] audio = new byte[48_000];
        Arrays.fill(audio, (byte) 3);
        Harness h = start(audio);
        h.speech.cancel();
        configure(h);
        h.socket.listener.onFailure(h.socket, new IllegalStateException("secret"), null);
        Thread.sleep(80);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(h.result);
        assertNull(h.error);
        assertArrayEquals(new byte[audio.length], audio);
        assertEquals(1, h.socket.sent.size());
        assertTrue(h.socket.cancelled);
    }

    @Test public void boundedQueueWaitsRatherThanEnqueuingWholeRecording() throws Exception {
        Harness h = start(new byte[AudioCapture.MAX_BYTES]);
        h.socket.queued = SpeechTranscriber.QUEUE_LIMIT + 1;
        configure(h);
        Thread.sleep(80);
        assertEquals(1, h.socket.sent.size());
        h.socket.queued = 0;
        await(() -> h.socket.has("input_audio_buffer.append"));
        assertTrue(h.socket.sent.size() < 10);
    }

    @Test public void invalidCredentialOrShortAudioNeverOpensSocket() throws Exception {
        for (boolean shortAudio : new boolean[]{true, false}) {
            Harness h = new Harness();
            byte[] audio = new byte[shortAudio ? 4_798 : 4_800];
            Arrays.fill(audio, (byte) 8);
            JSONObject auth = credential();
            if (!shortAudio) auth.put("expiresAt", 1);
            h.speech.start(auth, audio);
            await(() -> h.error != null);
            assertNull(h.socket.listener);
            assertArrayEquals(new byte[audio.length], audio);
        }
    }

    @Test public void oversizedAndFailedResponsesReturnOnlySafeErrors() throws Exception {
        Harness h = start(new byte[4_800]);
        h.socket.listener.onMessage(h.socket, "secret".repeat(SpeechTranscriber.MAX_EVENT / 6 + 1));
        await(() -> h.error != null);
        assertFalse(h.error.contains("secret"));
        assertTrue(h.socket.cancelled);
        Harness failed = start(new byte[4_800]);
        configure(failed);
        await(() -> failed.socket.has("input_audio_buffer.clear"));
        failed.event(new JSONObject().put("type", "conversation.item.input_audio_transcription.failed")
                .put("error", "private server details"));
        await(() -> failed.error != null);
        assertFalse(failed.error.contains("private"));
    }

    private Harness start(byte[] bytes) throws Exception {
        Harness h = new Harness();
        h.speech.start(credential(), bytes);
        await(() -> h.socket.has("session.update"));
        return h;
    }

    private static void configure(Harness h) throws Exception {
        h.event(new JSONObject().put("type", "session.updated").put("session",
                SpeechTranscriber.configuration("deployment").getJSONObject("session")));
    }

    private static JSONObject credential() throws Exception {
        return new JSONObject().put("clientSecret", "ephemeral-test")
                .put("expiresAt", System.currentTimeMillis() / 1000 + 60)
                .put("socketUrl", "wss://service.openai.azure.com/openai/v1/realtime?intent=transcription")
                .put("deployment", "deployment");
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
        assertTrue("condition timed out", condition.getAsBoolean());
    }

    private final class Harness implements SpeechTranscriber.Listener {
        final FakeSocket socket = new FakeSocket();
        final SpeechTranscriber speech = new SpeechTranscriber((request, listener) -> {
            socket.request = request;
            socket.listener = listener;
            listener.onOpen(socket, null);
            return socket;
        }, this);
        volatile String result;
        volatile String error;
        boolean mainCallback;
        Harness() { running.add(speech); }
        void event(JSONObject event) { socket.listener.onMessage(socket, event.toString()); }
        @Override public void onComplete(String text) {
            mainCallback = Looper.myLooper() == Looper.getMainLooper();
            result = text;
        }
        @Override public void onError(String message) { error = message; }
    }

    private static final class FakeSocket implements WebSocket {
        volatile Request request;
        volatile WebSocketListener listener;
        volatile boolean cancelled;
        volatile long queued;
        final List<JSONObject> sent = new CopyOnWriteArrayList<>();
        boolean has(String type) {
            for (JSONObject event : sent) if (type.equals(event.optString("type"))) return true;
            return false;
        }
        @Override public Request request() { return request; }
        @Override public long queueSize() { return queued; }
        @Override public boolean send(String text) {
            if (cancelled) return false;
            try { sent.add(new JSONObject(text)); }
            catch (JSONException invalid) { throw new AssertionError(invalid); }
            return true;
        }
        @Override public boolean send(ByteString bytes) { throw new AssertionError(); }
        @Override public boolean close(int code, String reason) { return true; }
        @Override public void cancel() { cancelled = true; }
    }
}
