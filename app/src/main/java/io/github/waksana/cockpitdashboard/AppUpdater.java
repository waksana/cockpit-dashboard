package io.github.waksana.cockpitdashboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import static io.github.waksana.cockpitdashboard.UpdateFailure.Stage.*;

/** Activity-owned updater. All public hooks and Listener calls run on the main thread. */
public final class AppUpdater {
    public interface Listener {
        /** True only when chat, login, recording, sending and other dialogs allow a prompt. */
        boolean isReady();
        /** Non-blocking, credential-free status; must not open a dialog. */
        void status(String message);
        default void failure(String message) { status(message); }
    }

    private final Activity activity;
    private final Listener listener;
    private final UpdateClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private UpdateClient.Cancellation cancellation;
    private boolean foreground, destroyed, busy, waitingPermission, deferred;
    private UpdateClient.Update update;
    private File verified;
    private AlertDialog dialog;
    private String pendingStatus;
    private boolean pendingFailure;

    public AppUpdater(Activity activity, Listener listener) {
        this(activity, listener, new UpdateClient());
    }

    AppUpdater(Activity activity, Listener listener, UpdateClient client) {
        this.activity = activity;
        this.listener = listener;
        this.client = client;
    }

    /** The caller schedules its one launch check; this class never polls or automatically retries. */
    public void check(boolean manual) {
        if (destroyed || !foreground || busy || dialog != null) return;
        if (update != null) {
            if (manual) deferred = false;
            presentIfReady();
            return;
        }
        UpdateClient.Cancellation operation = begin();
        status("正在检查应用更新…");
        worker.execute(() -> {
            try {
                long installed = versionCode(installedPackage());
                UpdateClient.Update result = client.check(installed, operation);
                finish(operation, () -> {
                    update = result;
                    deferred = false;
                    if (result != null) status("发现新版本 " + result.versionName + "，可确认下载。");
                    else if (manual) status("当前已是最新版本。");
                    else status("应用更新检查完成，当前已是最新版本。");
                    presentIfReady();
                });
            } catch (IOException | PackageManager.NameNotFoundException | IllegalArgumentException | SecurityException error) {
                finish(operation, () -> failure("应用更新检查失败。", RELEASE, error, null));
            }
        });
    }

    public void resume() {
        if (destroyed) return;
        foreground = true;
        if (waitingPermission) {
            waitingPermission = false;
            if (!canInstall()) {
                deferred = true;
                status("尚未允许此应用安装更新；可手动检查更新后重新授权。");
            } else {
                deferred = false;
                status("已允许安装来源，请再次确认安装更新。");
            }
        }
        presentIfReady();
    }

    public void pause() {
        foreground = false;
        if (cancellation != null) {
            cancellation.cancel();
            cancellation = null;
            if (busy) {
                pendingStatus = "更新操作已中断；请手动检查更新后继续。";
                pendingFailure = false;
            }
        }
        busy = false;
        // A cancelled download must not immediately nag again when the activity resumes.
        if (update != null && verified == null) deferred = true;
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    public void destroy() {
        if (destroyed) return;
        pause();
        destroyed = true;
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    /** Includes the updater's own dialog; do not use this from Listener.isReady(). */
    public boolean isBusy() {
        return busy || dialog != null;
    }

    public boolean isPresenting() { return dialog != null; }

    /** Call after render/readiness changes, as well as resume, to deliver a deferred notice or prompt. */
    public void presentIfReady() {
        if (!ready()) return;
        if (pendingStatus != null) {
            String message = pendingStatus;
            pendingStatus = null;
            if (pendingFailure) listener.failure(message);
            else listener.status(message);
        }
        if (!ready() || busy || dialog != null || update == null || deferred || waitingPermission) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        if (verified == null) {
            builder.setTitle("发现新版本 " + update.versionName)
                    .setMessage("从 GitHub 下载 " + ((update.size + 1023) / 1024)
                            + " KiB 更新？校验成功后仍需您确认安装，不会静默安装。")
                    .setPositiveButton("下载", (which, button) -> {
                        dialog = null;
                        if (ready()) download();
                    });
        } else {
            boolean permitted = canInstall();
            builder.setTitle("安装更新 " + update.versionName)
                    .setMessage(permitted
                            ? "文件完整性和签名已校验。是否打开 Android 安装器？"
                            : "请先在 Android 设置中允许此应用安装未知应用。返回后仍需再次确认安装。")
                    .setPositiveButton(permitted ? "安装" : "打开设置", (which, button) -> {
                        dialog = null;
                        if (!ready()) return;
                        if (canInstall()) confirmInstall();
                        else authorizeInstall();
                    });
        }
        builder.setNegativeButton("稍后", (which, button) -> deferred = true)
                .setOnCancelListener(which -> deferred = true);
        AlertDialog shown = builder.create();
        shown.setOnDismissListener(which -> {
            if (dialog == shown) dialog = null;
        });
        dialog = shown;
        shown.show();
    }

    private boolean ready() {
        return foreground && !destroyed && !activity.isFinishing() && !activity.isDestroyed() && listener.isReady();
    }

    private void status(String message) {
        status(message, false);
    }

    private void status(String message, boolean failure) {
        pendingStatus = message;
        pendingFailure = failure;
        if (ready()) {
            pendingStatus = null;
            if (failure) listener.failure(message);
            else listener.status(message);
        }
    }

    private void failure(String summary, UpdateFailure.Stage stage, Throwable error, UpdateClient.Update selected) {
        String diagnostic = UpdateFailure.at(stage, error).describe(Build.VERSION.SDK_INT,
                selected == null ? null : selected.versionCode);
        // Do not pass a Throwable: its message, causes and stack may contain signed URLs or device paths.
        Log.w("DashboardUpdater", diagnostic);
        status(summary + "\n" + diagnostic + "\n请记录此诊断信息，可稍后手动重试；未自动安装。", true);
    }

    private UpdateClient.Cancellation begin() {
        busy = true;
        cancellation = new UpdateClient.Cancellation();
        return cancellation;
    }

    private void finish(UpdateClient.Cancellation operation, Runnable result) {
        main.post(() -> {
            if (destroyed || !foreground || cancellation != operation) return;
            cancellation = null;
            busy = false;
            result.run();
        });
    }

    private void download() {
        if (busy || update == null) return;
        UpdateClient.Update selected = update;
        UpdateClient.Cancellation operation = begin();
        status("正在下载并校验更新；离开应用会中断下载。");
        worker.execute(() -> {
            File downloaded = null;
            try {
                downloaded = client.download(selected, activity.getFilesDir(), operation);
                validateArchive(downloaded, selected, operation);
                File result = downloaded;
                finish(operation, () -> {
                    verified = result;
                    status("更新已校验，请确认安装。");
                    presentIfReady();
                });
            } catch (IOException | PackageManager.NameNotFoundException | IllegalArgumentException | SecurityException error) {
                if (downloaded != null) downloaded.delete();
                finish(operation, () -> {
                    verified = null;
                    deferred = true;
                    failure("更新下载或安全校验失败，未安装。", APK_DOWNLOAD, error, selected);
                });
            }
        });
    }

    private boolean canInstall() {
        try {
            return Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls();
        } catch (SecurityException error) {
            return false;
        }
    }

    private void authorizeInstall() {
        if (Build.VERSION.SDK_INT < 26) {
            status("此系统无需单独授权安装来源，请重新确认安装。");
            return;
        }
        try {
            waitingPermission = true;
            deferred = true;
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (ActivityNotFoundException | SecurityException error) {
            waitingPermission = false;
            failure("无法打开安装来源设置。请在系统设置中允许此应用安装未知应用。", SOURCE_SETTINGS, error, update);
        }
    }

    private void confirmInstall() {
        if (busy || verified == null || update == null) return;
        UpdateClient.Update selected = update;
        File apk = verified;
        UpdateClient.Cancellation operation = begin();
        status("正在再次校验安装文件…");
        worker.execute(() -> {
            try {
                validateArchive(apk, selected, operation);
                finish(operation, () -> {
                    if (!ready() || !canInstall()) {
                        status("尚未安装；请在应用空闲且允许安装来源后再次确认。");
                        return;
                    }
                    try {
                        activity.startActivity(installerIntent(activity, apk));
                        deferred = true;
                        // Keep the file in place: the external installer may still be reading it.
                        verified = null;
                        update = null;
                        status("已打开 Android 安装器，请按系统提示完成；尚未确认安装成功。");
                    } catch (ActivityNotFoundException | SecurityException | IllegalArgumentException error) {
                        deferred = true;
                        failure("无法打开 Android 安装器，请检查系统是否支持安装及来源权限。", INSTALLER, error, selected);
                    }
                });
            } catch (IOException | PackageManager.NameNotFoundException | IllegalArgumentException | SecurityException error) {
                apk.delete();
                finish(operation, () -> {
                    verified = null;
                    update = null;
                    failure("安装前安全校验失败，未安装。请重新检查更新。", FILE_VERIFY, error, selected);
                });
            }
        });
    }

    static Intent installerIntent(Activity activity, File apk) {
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
        return new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    @SuppressWarnings("deprecation")
    private PackageInfo installedPackage() throws PackageManager.NameNotFoundException, IOException {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(),
                    Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException | SecurityException error) {
            throw UpdateFailure.at(INSTALLED_PACKAGE, error);
        }
    }

    @SuppressWarnings("deprecation")
    private void validateArchive(File apk, UpdateClient.Update selected, UpdateClient.Cancellation operation)
            throws IOException, PackageManager.NameNotFoundException {
        UpdateFailure.Stage stage = FILE_VERIFY;
        try {
            UpdateClient.verifyFile(apk, selected, operation);
            stage = APK_PARSE;
            PackageInfo archive = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),
                    Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
            validatePackages(activity.getPackageName(), installedPackage(), archive, selected, Build.VERSION.SDK_INT);
            if (Build.VERSION.SDK_INT < 24) {
                stage = MIN_SDK;
                int minimum = minSdkFromApk(apk, operation);
                if (minimum > Build.VERSION.SDK_INT) throw UpdateFailure.minSdk(minimum);
            }
            operation.check();
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            throw UpdateFailure.at(stage, error);
        }
    }

    @SuppressWarnings("deprecation")
    static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    static void validatePackages(String ownPackage, PackageInfo installed, PackageInfo archive,
            UpdateClient.Update selected, int sdk) throws IOException {
        if (installed == null) throw new UpdateFailure(INSTALLED_PACKAGE, UpdateFailure.Reason.NOT_FOUND);
        if (archive == null || archive.applicationInfo == null) {
            throw new UpdateFailure(APK_PARSE, UpdateFailure.Reason.ARCHIVE);
        }
        if (!ownPackage.equals(installed.packageName) || !ownPackage.equals(archive.packageName)) {
            throw new UpdateFailure(PACKAGE, UpdateFailure.Reason.PACKAGE);
        }
        if (versionCode(archive) != selected.versionCode) {
            throw new UpdateFailure(VERSION, UpdateFailure.Reason.VERSION_CODE);
        }
        if (versionCode(archive) <= versionCode(installed)) {
            throw new UpdateFailure(VERSION, UpdateFailure.Reason.NOT_NEWER);
        }
        if (!selected.versionName.equals(archive.versionName)) {
            throw new UpdateFailure(VERSION, UpdateFailure.Reason.VERSION_NAME);
        }
        if (Build.VERSION.SDK_INT >= 24 && archive.applicationInfo.minSdkVersion > sdk) {
            throw UpdateFailure.minSdk(archive.applicationInfo.minSdkVersion);
        }
        Signature current = singleSigner(installed, INSTALLED_SIGNER);
        Signature candidate = singleSigner(archive, APK_SIGNER);
        if (!current.equals(candidate)) throw new UpdateFailure(SIGNATURE, UpdateFailure.Reason.CERTIFICATE);
    }

    @SuppressWarnings("deprecation")
    private static Signature singleSigner(PackageInfo info, UpdateFailure.Stage stage) throws IOException {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null) {
                throw new UpdateFailure(stage, UpdateFailure.Reason.SIGNER_MISSING);
            }
            if (info.signingInfo.hasMultipleSigners()) {
                throw new UpdateFailure(stage, UpdateFailure.Reason.SIGNER_MULTIPLE);
            }
            signatures = info.signingInfo.getApkContentsSigners();
        } else signatures = info.signatures;
        if (signatures == null || signatures.length == 0 || (signatures.length == 1 && signatures[0] == null)) {
            throw new UpdateFailure(stage, UpdateFailure.Reason.SIGNER_MISSING);
        }
        if (signatures.length != 1) {
            throw new UpdateFailure(stage, UpdateFailure.Reason.SIGNER_MULTIPLE);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            Signature[] history = info.signingInfo.getSigningCertificateHistory();
            if (history == null || history.length != 1 || history[0] == null || !history[0].equals(signatures[0])) {
                throw new UpdateFailure(stage, UpdateFailure.Reason.SIGNER_HISTORY);
            }
        }
        return signatures[0];
    }

    // ApplicationInfo.minSdkVersion only exists from API 24; read the signed binary manifest on API 23.
    static int minSdkFromApk(File apk, UpdateClient.Cancellation operation) throws IOException {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("AndroidManifest.xml");
            if (entry == null || entry.getSize() > UpdateClient.MAX_API) throw new IOException("Invalid APK manifest");
            try (InputStream input = zip.getInputStream(entry)) {
                return binaryMinSdk(UpdateClient.boundedRead(input, UpdateClient.MAX_API, operation));
            }
        } catch (java.io.InterruptedIOException error) {
            throw UpdateFailure.at(MIN_SDK, error);
        } catch (IOException error) {
            throw new UpdateFailure(MIN_SDK, UpdateFailure.Reason.BINARY_MANIFEST);
        }
    }

    static int binaryMinSdk(byte[] bytes) throws IOException {
        try {
            ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (bytes.length < 8 || u16(data, 0) != 3 || u16(data, 2) != 8 || data.getInt(4) != bytes.length) {
                throw new IOException("Invalid binary manifest");
            }
            String[] strings = null;
            boolean root = false, usesSdk = false;
            int result = 1;
            for (int at = 8; at < bytes.length;) {
                int type = u16(data, at), header = u16(data, at + 2), size = data.getInt(at + 4);
                if (header < 8 || size < header || size > bytes.length - at) throw new IOException("Invalid manifest chunk");
                if (type == 1) {
                    if (strings != null || header < 28) throw new IOException("Invalid string pool");
                    int count = data.getInt(at + 8), flags = data.getInt(at + 16), start = data.getInt(at + 20);
                    if (count < 0 || count > 16384 || (long) header + count * 4L > size
                            || start < header + count * 4 || start >= size) throw new IOException("Invalid string pool");
                    strings = new String[count];
                    for (int i = 0; i < count; i++) {
                        long offset = (long) at + start + data.getInt(at + header + i * 4);
                        if (offset < at + start || offset >= at + size) throw new IOException("Invalid string offset");
                        ByteBuffer text = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                        text.limit(at + size);
                        text.position((int) offset);
                        boolean utf8 = (flags & 0x100) != 0;
                        int length = stringLength(text, utf8);
                        if (utf8) length = stringLength(text, true);
                        long byteLength = utf8 ? length : 2L * length;
                        if (byteLength < 0 || byteLength + (utf8 ? 1 : 2) > text.remaining()) {
                            throw new IOException("Invalid manifest string");
                        }
                        byte[] value = new byte[(int) byteLength];
                        text.get(value);
                        if (text.get() != 0 || (!utf8 && text.get() != 0)) throw new IOException("Invalid string terminator");
                        strings[i] = new String(value, utf8 ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
                    }
                } else if (type == 0x102) {
                    if (strings == null || header != 16 || size < 36) throw new IOException("Invalid manifest element");
                    int extension = at + header;
                    String name = string(strings, data.getInt(extension + 4));
                    if ("manifest".equals(name)) root = true;
                    if ("uses-sdk".equals(name)) {
                        if (usesSdk || !root) throw new IOException("Invalid uses-sdk");
                        usesSdk = true;
                        int start = u16(data, extension + 8), stride = u16(data, extension + 10);
                        int count = u16(data, extension + 12);
                        if (start < 20 || stride < 20 || header + (long) start + (long) stride * count > size) {
                            throw new IOException("Invalid manifest attributes");
                        }
                        boolean found = false;
                        for (int i = 0; i < count; i++) {
                            int attribute = extension + start + i * stride;
                            if (!"minSdkVersion".equals(string(strings, data.getInt(attribute + 4)))) continue;
                            if (!"http://schemas.android.com/apk/res/android".equals(string(strings, data.getInt(attribute)))) {
                                continue;
                            }
                            if (found) throw new IOException("Duplicate minimum SDK");
                            found = true;
                            int valueType = data.get(attribute + 15) & 0xff;
                            int value = data.getInt(attribute + 16);
                            if (valueType == 0x10 || valueType == 0x11) result = value;
                            else if (valueType == 3) result = Integer.parseInt(string(strings, value));
                            else throw new IOException("Unsupported minimum SDK");
                            if (result < 1) throw new IOException("Invalid minimum SDK");
                        }
                    }
                }
                at += size;
            }
            if (!root) throw new IOException("Missing manifest root");
            return result;
        } catch (IndexOutOfBoundsException | java.nio.BufferUnderflowException | IllegalArgumentException error) {
            throw new IOException("Invalid binary manifest");
        }
    }

    private static String string(String[] strings, int index) throws IOException {
        if (index == -1) return "";
        if (index < 0 || index >= strings.length) throw new IOException("Invalid string index");
        return strings[index];
    }

    private static int u16(ByteBuffer data, int at) {
        return data.getShort(at) & 0xffff;
    }

    private static int stringLength(ByteBuffer data, boolean utf8) {
        int value = utf8 ? data.get() & 0xff : data.getShort() & 0xffff;
        int flag = utf8 ? 0x80 : 0x8000;
        return (value & flag) == 0 ? value
                : ((value & ~flag) << (utf8 ? 8 : 16)) | (utf8 ? data.get() & 0xff : data.getShort() & 0xffff);
    }
}
