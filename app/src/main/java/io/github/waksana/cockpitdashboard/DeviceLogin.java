package io.github.waksana.cockpitdashboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;

final class DeviceLogin {
    interface Listener {
        void complete(DeviceOAuth.Tokens tokens);
        void failed(String message);
    }

    private final Activity activity;
    private final DeviceOAuth api;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private AlertDialog box;
    private TextView details;
    private DeviceOAuth.Grant grant;
    private DevicePoll polling;
    private boolean finished, inFlight;

    DeviceLogin(Activity activity, DeviceOAuth api, Listener listener) {
        this.activity = activity;
        this.api = api;
        this.listener = listener;
    }

    void start() {
        long started = SystemClock.elapsedRealtime();
        network.execute(() -> {
            try {
                DeviceOAuth.Grant result = api.authorize();
                Bitmap image = qr(result.verificationUriComplete);
                main.post(() -> {
                    if (finished) return;
                    grant = result;
                    polling = new DevicePoll(started, result.expiresIn, result.interval);
                    polling.pending(SystemClock.elapsedRealtime());
                    show(image);
                    tick.run();
                });
            } catch (DeviceOAuth.Unavailable error) {
                main.post(() -> fail(unavailable(error)));
            } catch (IOException | JSONException | WriterException | IllegalArgumentException error) {
                main.post(() -> fail("扫码申请未确认；请检查网络和网关后主动重试，不会自动重新申请"));
            }
        });
    }

    static Bitmap qr(String text) throws WriterException {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 420, 420);
        Bitmap bitmap = Bitmap.createBitmap(matrix.getWidth(), matrix.getHeight(), Bitmap.Config.ARGB_8888);
        int[] pixels = new int[matrix.getWidth() * matrix.getHeight()];
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                pixels[y * matrix.getWidth() + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        bitmap.setPixels(pixels, 0, matrix.getWidth(), 0, 0, matrix.getWidth(), matrix.getHeight());
        return bitmap;
    }

    private void show(Bitmap image) {
        LinearLayout content = new LinearLayout(activity);
        content.setPadding(24, 12, 24, 12);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView qr = new ImageView(activity);
        qr.setImageBitmap(image);
        qr.setContentDescription("手机扫描登录二维码，或在下方验证地址输入验证码");
        content.addView(qr, new LinearLayout.LayoutParams(420, 420));
        details = new TextView(activity);
        details.setTextSize(20);
        content.addView(details);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        box = new AlertDialog.Builder(activity).setTitle("用手机 Passkey 批准此设备")
                .setView(scroll).setNegativeButton("取消", (d, w) -> cancel("已取消扫码登录")).create();
        box.setOnCancelListener(d -> cancel("已取消扫码登录"));
        box.show();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (finished) return;
            long now = SystemClock.elapsedRealtime();
            if (polling.expired(now)) {
                fail("二维码已过期；不会自动重新申请，请主动重新扫码");
                return;
            }
            details.setText("站点：" + api.resourceOrigin() + "\n验证码：" + grant.userCode + "\n验证地址：" + grant.verificationUri
                    + "\n请在手机核对验证码和站点后批准。剩余 " + polling.remainingSeconds(now) + " 秒"
                    + "\n取消或离开前台会停止此轮登录。");
            if (!inFlight && polling.delay(now) == 0) poll();
            main.postDelayed(this, 1000);
        }
    };

    private void poll() {
        inFlight = true;
        network.execute(() -> {
            try {
                DeviceOAuth.Tokens tokens = api.poll(grant.deviceCode);
                main.post(() -> {
                    if (finished) return;
                    if (polling.expired(SystemClock.elapsedRealtime())) {
                        fail("响应到达时登录已过期，未保存凭据；如已批准，请在 Auth 页撤销设备");
                        return;
                    }
                    finish();
                    listener.complete(tokens);
                });
            } catch (DeviceOAuth.Error error) {
                main.post(() -> {
                    if (finished) return;
                    inFlight = false;
                    long now = SystemClock.elapsedRealtime();
                    if ("authorization_pending".equals(error.code)) polling.pending(now);
                    else if ("slow_down".equals(error.code)) polling.slowDown(now);
                    else fail("设备登录已停止（" + terminal(error.code) + "）；请主动重新扫码");
                });
            } catch (DeviceOAuth.Unavailable error) {
                main.post(() -> fail(unavailable(error)));
            } catch (InterruptedIOException error) {
                main.post(() -> {
                    if (finished) return;
                    inFlight = false;
                    polling.timeout(SystemClock.elapsedRealtime());
                });
            } catch (IOException | JSONException error) {
                main.post(() -> fail("登录响应未确认，已停止；如手机已批准，请在 Auth 页检查或撤销设备"));
            }
        });
    }

    private static String terminal(String code) {
        switch (code) {
            case "access_denied": return "手机拒绝";
            case "expired_token": return "验证码过期";
            case "invalid_grant": return "授权失效或已使用";
            default: return "网关拒绝或协议不兼容";
        }
    }

    private static String unavailable(DeviceOAuth.Unavailable error) {
        return (error.status == 429 ? "网关限流" : "网关暂时不可用")
                + "，已停止本轮登录，不会自动重试"
                + (error.retryAfterSeconds > 0 ? "；至少等待 " + error.retryAfterSeconds + " 秒后再主动申请" : "；请稍后主动申请");
    }

    void cancel(String message) {
        fail(message + "；不会接受迟到回执或自动重试。若已批准，可在 Auth 页撤销设备");
    }

    private void fail(String message) {
        if (finished) return;
        finish();
        listener.failed(message);
    }

    private void finish() {
        finished = true;
        main.removeCallbacks(tick);
        if (polling != null) polling.stop();
        api.cancel();
        network.shutdown();
        if (box != null) box.dismiss();
    }
}
