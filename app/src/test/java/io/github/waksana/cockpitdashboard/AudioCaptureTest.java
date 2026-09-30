package io.github.waksana.cockpitdashboard;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.ReflectionHelpers;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AudioCaptureTest {
    @Test public void noUsbFailsExplicitlyWithoutBuiltinFallback() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        Recording listener = new Recording();
        AudioCapture capture = new AudioCapture(app, listener);
        capture.start();
        awaitError(listener);
        assertTrue(listener.error.contains("USB"));
        assertFalse(listener.started);
        assertNull(listener.pcm);
        assertTrue(listener.onMain);
    }

    @Test public void permissionDenialIsReportedSafely() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO);
        Recording listener = new Recording();
        new AudioCapture(app, listener).start();
        awaitError(listener);
        assertTrue(listener.error.contains("权限"));
        assertFalse(listener.started);
    }

    @Test @Config(sdk = 23) public void minimumAndroidVersionRejectsMissingUsb() throws Exception {
        noUsbFailsExplicitlyWithoutBuiltinFallback();
    }

    @Test public void enumerationAcceptsOnlyUsbSourcesNotBuiltinOrUsbOutputs() {
        AudioManager manager = (AudioManager) RuntimeEnvironment.getApplication()
                .getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo builtIn = device(AudioDeviceInfo.TYPE_BUILTIN_MIC, true);
        AudioDeviceInfo usbOutput = device(AudioDeviceInfo.TYPE_USB_DEVICE, false);
        AudioDeviceInfo usbInput = device(AudioDeviceInfo.TYPE_USB_DEVICE, true);
        AudioDeviceInfo headset = device(AudioDeviceInfo.TYPE_USB_HEADSET, true);
        assertFalse(AudioCapture.isUsb(builtIn));
        assertFalse(AudioCapture.isUsb(usbOutput));
        assertTrue(AudioCapture.isUsb(usbInput));
        assertTrue(AudioCapture.isUsb(headset));
        Shadows.shadowOf(manager).setInputDevices(Arrays.asList(builtIn, usbOutput, usbInput));
        assertSame(usbInput, AudioCapture.findUsb(manager));
    }

    @Test public void stopOrCancelBeforeStartPreventsLateAcquisitionAndCallbacks() throws Exception {
        for (boolean stop : new boolean[]{true, false}) {
            Recording listener = new Recording();
            AudioCapture capture = new AudioCapture(RuntimeEnvironment.getApplication(), listener);
            if (stop) capture.stop(); else capture.cancel();
            capture.start();
            Thread.sleep(40);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(listener.started);
            assertNull(listener.error);
            assertNull(listener.pcm);
        }
    }

    @Test public void cancelSuppressesAlreadyQueuedError() throws Exception {
        Recording listener = new Recording();
        AudioCapture capture = new AudioCapture(RuntimeEnvironment.getApplication(), listener);
        capture.start();
        Thread.sleep(40);
        capture.cancel();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(listener.error);
        assertFalse(listener.started);
    }

    @Test public void cleanupAttemptsEveryOperationAndReportsFailureWithoutSensitiveLogging() {
        AtomicInteger operations = new AtomicInteger();
        assertFalse(AudioCapture.cleanup(
                () -> {
                    operations.incrementAndGet();
                    throw new IllegalStateException("private device details");
                },
                operations::incrementAndGet,
                () -> {
                    operations.incrementAndGet();
                    throw new SecurityException("private platform details");
                },
                operations::incrementAndGet));
        assertEquals(4, operations.get());
        assertFalse(ShadowLog.getLogsForTag("DashboardAudio").isEmpty());
        for (ShadowLog.LogItem log : ShadowLog.getLogsForTag("DashboardAudio")) {
            assertFalse(log.msg.contains("private"));
            assertNull(log.throwable);
        }
        assertTrue(AudioCapture.cleanup(operations::incrementAndGet));
    }

    @Test public void cleanupDoesNotStopAnAlreadyStoppedRecorderTwice() {
        CountingRecord recorder = new CountingRecord();
        try {
            recorder.startRecording();
            AudioCapture.stopRecordingIfActive(recorder);
            assertTrue(AudioCapture.cleanup(() -> AudioCapture.stopRecordingIfActive(recorder)));
            assertEquals(1, recorder.stops);
        } finally {
            recorder.release();
        }
    }

    private static void awaitError(Recording listener) throws Exception {
        long end = System.nanoTime() + 3_000_000_000L;
        while (listener.error == null && System.nanoTime() < end) {
            Thread.sleep(5);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        assertNotNull(listener.error);
    }

    private static AudioDeviceInfo device(int type, boolean source) {
        AudioDeviceInfo device = AudioDeviceInfoBuilder.newBuilder().setType(type).build();
        Object port = ReflectionHelpers.getField(device, "mPort");
        ReflectionHelpers.setField(port, "mRole", source ? 1 : 2);
        return device;
    }

    private static final class Recording implements AudioCapture.Listener {
        boolean started;
        String error;
        byte[] pcm;
        boolean onMain;
        @Override public void onStarted(String device) { started = true; }
        @Override public void onComplete(byte[] bytes) { pcm = bytes; }
        @Override public void onError(String message) {
            error = message;
            onMain = Looper.myLooper() == Looper.getMainLooper();
        }

    }

    private static final class CountingRecord extends AudioRecord {
        int stops;
        CountingRecord() {
            super(MediaRecorder.AudioSource.MIC, AudioCapture.RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, 9_600);
        }
        @Override public void stop() {
            stops++;
            super.stop();
        }
    }
}
