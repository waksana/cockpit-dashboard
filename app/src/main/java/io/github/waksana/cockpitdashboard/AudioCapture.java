package io.github.waksana.cockpitdashboard;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One instance per recording; call lifecycle methods on the main thread.
 * All listener calls run on the main thread.
 * Only a verified USB input is accepted. Enumeration is not a hardware compatibility guarantee.
 * The recipient owns the completed PCM array and must erase it when no longer needed.
 */
public final class AudioCapture {
    public interface Listener {
        void onStarted(String device);
        void onComplete(byte[] pcm);
        void onError(String message);
    }

    static final int RATE = 24_000;
    static final int MAX_BYTES = RATE * 2 * 120;
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean used = new AtomicBoolean();
    private volatile boolean cancelled;
    private volatile boolean stopping;
    private volatile boolean ready;
    private volatile boolean finished;
    private volatile String routeError;
    private volatile Thread worker;
    private volatile byte[] pending;

    public AudioCapture(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (!used.compareAndSet(false, true) || cancelled || stopping) return;
        worker = new Thread(this::record, "dashboard-usb-audio");
        worker.start();
    }

    /** A stop during setup cancels setup; it never starts a late recording. */
    public void stop() {
        if (finished || stopping) return;
        if (!ready) {
            cancel();
        } else {
            stopping = true;
            wake();
        }
    }

    public void cancel() {
        cancelled = true;
        stopping = true;
        byte[] bytes = pending;
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
        wake();
    }

    private void wake() {
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }

    static boolean isUsb(AudioDeviceInfo device) {
        return device != null && device.isSource()
                && (device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE
                || (Build.VERSION.SDK_INT >= 26
                && device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET));
    }

    static AudioDeviceInfo findUsb(AudioManager manager) {
        if (manager == null) return null;
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (isUsb(device)) return device;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void record() {
        AudioRecord recorder = null;
        AudioManager manager = null;
        AudioDeviceCallback deviceCallback = null;
        AudioRecord.OnRoutingChangedListener routingCallback = null;
        byte[] pcm = null;
        byte[] chunk = new byte[4_800];
        byte[] result = null;
        String error = null;
        int size = 0;
        try {
            if (stopping) return;
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new CaptureFailure("请先允许麦克风权限；仅支持 USB 音频输入。");
            }
            manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo usb = findUsb(manager);
            if (usb == null) {
                throw new CaptureFailure("未发现 USB 音频输入。请连接接收器；不会使用内置麦克风。");
            }
            if (stopping) return;
            int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new CaptureFailure("设备不支持 24kHz 单声道 PCM16 录音。");
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum, chunk.length * 2));
            if (stopping) return;
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED
                    || recorder.getSampleRate() != RATE || recorder.getChannelCount() != 1
                    || recorder.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT
                    || !recorder.setPreferredDevice(usb)) {
                throw new CaptureFailure("无法以 24kHz 单声道 PCM16 打开 USB 输入；设备兼容性尚未验证。");
            }
            final int usbId = usb.getId();
            final AudioRecord active = recorder;
            routingCallback = changed -> {
                if (ready && !finished && !stopping) {
                    try {
                        if (!routedTo(active, usbId)) {
                            routeError = "USB 音频路由已改变，录音已丢弃；不会切换内置麦克风。";
                        }
                    } catch (SecurityException denied) {
                        routeError = "USB 音频路由权限不可用，录音已丢弃。";
                        Log.e("DashboardAudio", "USB route permission check failed.");
                    } catch (RuntimeException failure) {
                        routeError = "系统无法确认 USB 音频路由，录音已丢弃。";
                        Log.e("DashboardAudio", "USB route verification failed.");
                    }
                    if (routeError != null) wake();
                }
            };
            deviceCallback = new AudioDeviceCallback() {
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    for (AudioDeviceInfo device : removed) {
                        if (device.getId() == usbId && !finished) {
                            routeError = "USB 音频设备已断开，录音已丢弃。";
                            wake();
                        }
                    }
                }
            };
            recorder.addOnRoutingChangedListener(routingCallback, main);
            manager.registerAudioDeviceCallback(deviceCallback, main);
            if (stopping) return;
            recorder.startRecording();
            if (stopping) return;
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new CaptureFailure("USB 录音启动失败。");
            }
            long routeDeadline = SystemClock.elapsedRealtime() + 1_500;
            while (!stopping && !routedTo(recorder, usbId)
                    && SystemClock.elapsedRealtime() < routeDeadline) {
                if (routeError != null) throw new CaptureFailure(routeError);
                if (recorder.getRoutedDevice() != null) {
                    throw new CaptureFailure("系统选择了非指定 USB 输入；录音已停止，不会使用内置麦克风。");
                }
                pause();
            }
            if (stopping) return;
            if (!routedTo(recorder, usbId)) {
                throw new CaptureFailure("系统未将录音路由到 USB 输入。请检查 USB/OTG 设置及设备支持。");
            }
            pcm = new byte[MAX_BYTES];
            ready = true;
            String deviceName = usb.getProductName() == null ? "USB" : usb.getProductName().toString();
            main.post(() -> {
                if (!cancelled && !stopping && !finished) listener.onStarted(deviceName);
            });
            long deadline = SystemClock.elapsedRealtime() + 120_000;
            while (!stopping && size < MAX_BYTES && SystemClock.elapsedRealtime() < deadline) {
                if (routeError != null) throw new CaptureFailure(routeError);
                if (!routedTo(recorder, usbId)) throw new CaptureFailure("USB 音频路由已丢失，录音已丢弃。");
                int count = recorder.read(chunk, 0, Math.min(chunk.length, MAX_BYTES - size),
                        AudioRecord.READ_NON_BLOCKING);
                if (stopping) break;
                if (count < 0) throw new CaptureFailure("USB 音频读取失败，请重新连接设备。");
                if (count % 2 != 0) throw new CaptureFailure("USB 音频格式异常。");
                if (routeError != null || !routedTo(recorder, usbId)) {
                    throw new CaptureFailure("USB 音频路由已改变，录音已丢弃。");
                }
                System.arraycopy(chunk, 0, pcm, size, count);
                size += count;
                Arrays.fill(chunk, (byte) 0);
                if (count == 0) pause();
            }
            if (!cancelled) {
                if (routeError != null || !routedTo(recorder, usbId)) {
                    throw new CaptureFailure("USB 音频已断开或路由改变，录音已丢弃。");
                }
                stopping = true;
                stopRecordingIfActive(recorder);
                if (size < 4_800) throw new CaptureFailure("录音太短，请至少录制 0.1 秒。");
                result = Arrays.copyOf(pcm, size);
            }
        } catch (SecurityException denied) {
            error = "麦克风权限不可用或已撤回，录音已停止。";
        } catch (CaptureFailure failure) {
            error = failure.getMessage();
        } catch (RuntimeException failure) {
            error = "无法使用 USB 录音；本机系统与接收器兼容性尚未验证。";
        } finally {
            ready = false;
            final AudioRecord closingRecorder = recorder;
            final AudioManager closingManager = manager;
            final AudioRecord.OnRoutingChangedListener closingRouting = routingCallback;
            final AudioDeviceCallback closingDevices = deviceCallback;
            boolean cleaned = cleanup(
                    () -> { if (closingRecorder != null) stopRecordingIfActive(closingRecorder); },
                    () -> {
                        if (closingRecorder != null && closingRouting != null) {
                            closingRecorder.removeOnRoutingChangedListener(closingRouting);
                        }
                    },
                    () -> { if (closingRecorder != null) closingRecorder.release(); },
                    () -> {
                        if (closingManager != null && closingDevices != null) {
                            closingManager.unregisterAudioDeviceCallback(closingDevices);
                        }
                    });
            if (!cleaned) {
                error = "USB 录音资源清理失败，录音已丢弃。请重新连接设备或重启应用。";
                if (result != null) Arrays.fill(result, (byte) 0);
                result = null;
            }
            if (pcm != null) Arrays.fill(pcm, (byte) 0);
            Arrays.fill(chunk, (byte) 0);
            finished = true;
            worker = null;
        }
        if (cancelled) {
            if (result != null) Arrays.fill(result, (byte) 0);
            return;
        }
        final byte[] completed = result;
        final String failure = error;
        pending = completed;
        main.post(() -> {
            pending = null;
            if (cancelled) {
                if (completed != null) Arrays.fill(completed, (byte) 0);
            } else if (failure != null) {
                listener.onError(failure);
            } else if (completed != null) {
                listener.onComplete(completed);
            }
        });
    }

    static void stopRecordingIfActive(AudioRecord recorder) {
        if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
    }

    static boolean cleanup(Runnable... operations) {
        boolean succeeded = true;
        for (Runnable operation : operations) {
            try {
                operation.run();
            } catch (RuntimeException failure) {
                // Vendor failures must not prevent the remaining independent release operations.
                succeeded = false;
                Log.e("DashboardAudio", "USB audio cleanup failed; recording discarded.");
            }
        }
        return succeeded;
    }

    private static boolean routedTo(AudioRecord recorder, int usbId) {
        AudioDeviceInfo route = recorder.getRoutedDevice();
        return isUsb(route) && route.getId() == usbId;
    }

    private static void pause() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException stopped) {
            // stop/cancel and routing failures interrupt this sleep so the loop rechecks its flags.
        }
    }

    private static final class CaptureFailure extends Exception {
        CaptureFailure(String message) { super(message); }
    }
}
