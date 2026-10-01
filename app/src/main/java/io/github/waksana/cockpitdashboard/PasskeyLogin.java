package io.github.waksana.cockpitdashboard;

import android.app.Activity;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.GetPublicKeyCredentialOption;
import androidx.credentials.PublicKeyCredential;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException;
import androidx.credentials.exceptions.GetCredentialUnsupportedException;
import androidx.credentials.exceptions.NoCredentialException;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

final class PasskeyLogin {
    interface Listener {
        void complete(GateSession session);
        void failed(String message);
    }

    private final Activity activity;
    private final GateApi gate;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final CancellationSignal signal = new CancellationSignal();
    private boolean finished;
    private boolean resumed = true;
    private JSONObject pendingOptions;

    PasskeyLogin(Activity activity, GateApi gate, Listener listener) {
        this.activity = activity;
        this.gate = gate;
        this.listener = listener;
    }

    void start() {
        if (Build.VERSION.SDK_INT < 28) {
            fail("原生 Passkey 需要 Android 9 / API 28 以上；本机 API " + Build.VERSION.SDK_INT);
            return;
        }
        network.execute(() -> {
            try {
                JSONObject options = gate.options();
                main.post(() -> requestCredential(options));
            } catch (IOException | JSONException | IllegalArgumentException error) {
                main.post(() -> fail(networkError(error)));
            }
        });
    }

    private void requestCredential(JSONObject options) {
        if (finished || activity.isDestroyed() || activity.isFinishing()) return;
        if (!resumed) {
            pendingOptions = options;
            return;
        }
        pendingOptions = null;
        try {
            GetCredentialRequest request = new GetCredentialRequest.Builder()
                    .addCredentialOption(new GetPublicKeyCredentialOption(options.toString())).build();
            CredentialManager.create(activity).getCredentialAsync(activity, request, signal, main::post,
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override public void onResult(GetCredentialResponse response) {
                            if (finished) return;
                            if (!(response.getCredential() instanceof PublicKeyCredential)) {
                                fail("凭据服务未返回 Passkey，未登录");
                                return;
                            }
                            String json = ((PublicKeyCredential) response.getCredential()).getAuthenticationResponseJson();
                            network.execute(() -> {
                                try {
                                    GateSession session = gate.finish(json);
                                    main.post(() -> {
                                        if (finished) return;
                                        finished = true;
                                        network.shutdown();
                                        listener.complete(session);
                                    });
                                } catch (IOException | JSONException | IllegalArgumentException error) {
                                    main.post(() -> fail(networkError(error)));
                                }
                            });
                        }
                        @Override public void onError(GetCredentialException error) { fail(credentialError(error)); }
                    });
        } catch (IllegalArgumentException | IllegalStateException error) {
            fail("本机凭据服务无法启动；请检查系统、凭据提供器及域名关联");
        }
    }

    void cancel() {
        finished = true;
        pendingOptions = null;
        signal.cancel();
        gate.cancel();
        network.shutdown();
    }

    void pause() { resumed = false; }

    void resume() {
        resumed = true;
        if (!finished && pendingOptions != null) requestCredential(pendingOptions);
    }

    private void fail(String message) {
        if (finished) return;
        finished = true;
        pendingOptions = null;
        gate.cancel();
        network.shutdown();
        listener.failed(message);
    }

    static String credentialError(GetCredentialException error) {
        if (error instanceof GetCredentialCancellationException) return "已取消 Passkey 登录";
        if (error instanceof GetCredentialProviderConfigurationException
                || error instanceof GetCredentialUnsupportedException) {
            return "本机未提供可用的原生 Passkey 服务；需要兼容的凭据提供器，GMUI 版本不能证明支持";
        }
        if (error instanceof NoCredentialException) {
            return "没有可用 Passkey 或跨设备入口；请检查凭据提供器、已有 Passkey 和域名关联";
        }
        return "原生 Passkey 未完成（" + error.getClass().getSimpleName()
                + "）；检查凭据提供器、域名关联及 App 签名。不会自动重试";
    }

    private static String networkError(Exception error) {
        if (error instanceof HostClient.Rejected) return error.getMessage();
        return "Passkey 登录未确认；网络或协议失败。请检查网关配置后主动重试";
    }
}
