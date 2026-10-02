package io.github.waksana.cockpitdashboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Looper;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLog;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class AppUpdaterTest {
    private ActivityController<TestActivity> controller;
    private TestActivity activity;
    private AppUpdater updater;
    private boolean ready = true;
    private final List<String> statuses = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();
    private UpdateClient.Update update;

    public static class TestActivity extends Activity {
        boolean reject;
        @Override public void startActivity(Intent intent) {
            if (reject) throw new ActivityNotFoundException("synthetic private error that must not be shown");
            super.startActivity(intent);
        }
    }

    @Before public void setup() throws Exception {
        ShadowLog.clear();
        controller = Robolectric.buildActivity(TestActivity.class).setup();
        activity = controller.get();
        update = UpdateClient.parseManifest(UpdateClientTest.bytes(UpdateClientTest.manifest()), UpdateClientTest.TAG);
        PackageInfo installed = packageInfo(activity.getPackageName(), 10000, 23, "01");
        PackageInfo original = activity.getPackageManager().getPackageInfo(activity.getPackageName(),
                PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA);
        installed.providers = original.providers;
        installed.applicationInfo = original.applicationInfo;
        shadowOf(activity.getPackageManager()).installPackage(installed);
        // FileProvider caches canonical roots; Robolectric gives each test a different data directory.
        Field cache = androidx.core.content.FileProvider.class.getDeclaredField("sCache");
        cache.setAccessible(true);
        ((java.util.Map<?, ?>) cache.get(null)).clear();
        shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(true);
    }

    @After public void cleanup() {
        if (updater != null) updater.destroy();
        controller.pause().stop().destroy();
        File directory = new File(activity.getFilesDir(), "updates");
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        directory.delete();
    }

    private void create(UpdateClient client) {
        updater = new AppUpdater(activity, new AppUpdater.Listener() {
            @Override public boolean isReady() { return ready; }
            @Override public void status(String message) { statuses.add(message); }
            @Override public void failure(String message) { failures.add(message); statuses.add(message); }
            @Override public void diagnostic(String report) { diagnostics.add(report); }
        }, client);
        updater.resume();
    }

    private void field(String name, Object value) throws Exception {
        Field field = AppUpdater.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(updater, value);
    }

    @Test public void deferredFailureAndLaterInterruptedOperationKeepDistinctNoticeKinds() throws Exception {
        create(synthetic(chain -> response(chain, new byte[0])));
        ready = false;
        Method status = AppUpdater.class.getDeclaredMethod("status", String.class, boolean.class);
        status.setAccessible(true);
        status.invoke(updater, "safe failure", true);
        updater.presentIfReady();
        assertTrue(failures.isEmpty());
        ready = true;
        updater.presentIfReady();
        assertEquals(1, failures.size());
        field("busy", true);
        field("cancellation", new UpdateClient.Cancellation());
        updater.pause();
        updater.resume();
        assertEquals(1, failures.size());
        assertTrue(statuses.get(statuses.size() - 1).contains("已中断"));
    }

    private static PackageInfo packageInfo(String name, int version, int minSdk, String certificate) {
        PackageInfo info = new PackageInfo();
        info.packageName = name;
        info.versionCode = version;
        info.versionName = "1.2.3";
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = name;
        if (android.os.Build.VERSION.SDK_INT >= 24) info.applicationInfo.minSdkVersion = minSdk;
        info.signatures = new Signature[]{new Signature(certificate)};
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.signingInfo = new SigningInfo();
            shadowOf(info.signingInfo).setSignatures(info.signatures);
            shadowOf(info.signingInfo).setPastSigningCertificates(info.signatures);
        }
        return info;
    }

    private static UpdateClient synthetic(okhttp3.Interceptor interceptor) {
        return new UpdateClient(new OkHttpClient.Builder().addInterceptor(interceptor).build());
    }

    private static Response response(okhttp3.Interceptor.Chain chain, byte[] bytes) {
        return new Response.Builder().request(chain.request()).code(200).message("fixture").protocol(Protocol.HTTP_1_1)
                .body(ResponseBody.create(bytes, MediaType.get("application/json"))).build();
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Updater did not settle", condition.getAsBoolean());
    }

    @Test public void validatesOwnPackageVersionMinimumSdkAndExactSingleCertificate() throws Exception {
        PackageInfo installed = packageInfo(activity.getPackageName(), 10000, 23, "01");
        PackageInfo archive = packageInfo(activity.getPackageName(), 10203, 23, "01");
        AppUpdater.validatePackages(activity.getPackageName(), installed, archive, update, 28);
        for (PackageInfo invalid : new PackageInfo[]{
                null, packageInfo("another.package", 10203, 23, "01"),
                packageInfo(activity.getPackageName(), 10204, 23, "01"),
                packageInfo(activity.getPackageName(), 10203, 29, "01"),
                packageInfo(activity.getPackageName(), 10203, 23, "02")}) {
            assertThrows(IOException.class, () -> AppUpdater.validatePackages(activity.getPackageName(),
                    installed, invalid, update, 28));
        }
        installed.setLongVersionCode(1L << 33);
        assertThrows(IOException.class, () -> AppUpdater.validatePackages(activity.getPackageName(), installed, archive, update, 28));
    }

    @Test public void rejectsMultipleSignersMissingSignaturesAndRotation() throws Exception {
        PackageInfo installed = packageInfo(activity.getPackageName(), 10000, 23, "01");
        PackageInfo archive = packageInfo(activity.getPackageName(), 10203, 23, "01");
        shadowOf(archive.signingInfo).setPastSigningCertificates(new Signature[]{new Signature("00"), new Signature("01")});
        assertThrows(IOException.class, () -> AppUpdater.validatePackages(activity.getPackageName(), installed, archive, update, 28));
        shadowOf(archive.signingInfo).setSignatures(new Signature[]{new Signature("01"), new Signature("02")});
        assertThrows(IOException.class, () -> AppUpdater.validatePackages(activity.getPackageName(), installed, archive, update, 28));
        archive.signingInfo = null;
        assertThrows(IOException.class, () -> AppUpdater.validatePackages(activity.getPackageName(), installed, archive, update, 28));
    }

    @Test @Config(sdk = 23) public void api23UsesExactLegacySignatureAndStillRejectsDowngrades() throws Exception {
        PackageInfo installed = packageInfo(activity.getPackageName(), 10000, 23, "01");
        PackageInfo archive = packageInfo(activity.getPackageName(), 10203, 23, "01");
        AppUpdater.validatePackages(activity.getPackageName(), installed, archive, update, 23);
        archive.signatures = new Signature[]{new Signature("02")};
        assertThrows(IOException.class, () -> AppUpdater.validatePackages(activity.getPackageName(), installed, archive, update, 23));
        archive.signatures = new Signature[]{new Signature("01"), new Signature("02")};
        assertThrows(IOException.class, () -> AppUpdater.validatePackages(activity.getPackageName(), installed, archive, update, 23));
        archive.signatures = null;
        assertThrows(IOException.class, () -> AppUpdater.validatePackages(activity.getPackageName(), installed, archive, update, 23));
    }

    @Test public void initialCheckFailuresRemainVisibleOnceAndNeverRevealExceptionText() throws Exception {
        ready = false;
        create(synthetic(chain -> { throw new IOException("synthetic secret must not be shown"); }));
        updater.check(false);
        await(() -> !updater.isBusy());
        assertTrue(statuses.isEmpty());
        ready = true;
        updater.presentIfReady();
        assertEquals(1, statuses.size());
        assertTrue(statuses.get(0).contains("检查失败"));
        assertFalse(statuses.get(0).contains("synthetic"));
        assertTrue(statuses.get(0).contains("RELEASE/IO"));
        assertTrue(statuses.get(0).contains("API=28"));
        assertEquals(1, ShadowLog.getLogsForTag("DashboardUpdater").size());
        assertNull(ShadowLog.getLogsForTag("DashboardUpdater").get(0).throwable);
        assertFalse(ShadowLog.getLogsForTag("DashboardUpdater").get(0).msg.contains("synthetic"));
        updater.presentIfReady();
        assertEquals(1, statuses.size());
    }

    @Test public void discoveryDefersUntilReadyAndLaterDoesNotNagAgain() throws Exception {
        byte[] release = UpdateClientTest.bytes(UpdateClientTest.release());
        byte[] manifest = UpdateClientTest.bytes(UpdateClientTest.manifest());
        ready = false;
        create(synthetic(chain -> response(chain, chain.request().url().toString().equals(UpdateClient.LATEST) ? release : manifest)));
        updater.check(false);
        await(() -> !updater.isBusy());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        ready = true;
        updater.presentIfReady();
        AlertDialog prompt = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(prompt.isShowing());
        prompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        updater.presentIfReady();
        assertFalse(prompt.isShowing());
        assertFalse(updater.isBusy());
        updater.check(true);
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        updater.pause();
        assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing());
    }

    @Test public void pauseCancelsChecksAndSuppressesLateCallbacksWithoutOverlappingRequests() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        create(synthetic(chain -> {
            calls.incrementAndGet();
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            throw new IOException("late failure");
        }));
        updater.check(false);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        updater.check(true);
        assertEquals(1, calls.get());
        updater.pause();
        int notices = statuses.size();
        release.countDown();
        Thread.sleep(50);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(notices, statuses.size());
        assertFalse(updater.isBusy());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        assertTrue(ShadowLog.getLogsForTag("DashboardUpdater").isEmpty());
    }

    private File stageVerified() throws Exception {
        create(synthetic(chain -> { throw new IOException("Unexpected network request"); }));
        File directory = new File(activity.getFilesDir(), "updates");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File apk = new File(directory, UpdateClient.APK);
        Files.write(apk.toPath(), UpdateClientTest.APK_BYTES);
        shadowOf(activity.getPackageManager()).setPackageArchiveInfo(apk.getAbsolutePath(),
                packageInfo(activity.getPackageName(), update.versionCode, 23, "01"));
        field("update", update);
        field("verified", apk);
        return apk;
    }

    @Test public void installerUsesOnlyPrivateContentUriAndReadGrantAfterExplicitConfirmation() throws Exception {
        File apk = stageVerified();
        assertEquals("content", AppUpdater.installerIntent(activity, apk).getData().getScheme());
        updater.presentIfReady();
        assertNull(shadowOf(activity).getNextStartedActivity());
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        await(() -> !updater.isBusy());
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(statuses.toString(), intent);
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals("content", intent.getData().getScheme());
        assertEquals(activity.getPackageName() + ".updates", intent.getData().getAuthority());
        assertEquals("application/vnd.android.package-archive", intent.getType());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.getFlags());
        assertTrue(apk.exists());
        File outside = new File(activity.getFilesDir(), "not-shared.apk");
        assertThrows(IllegalArgumentException.class, () -> AppUpdater.installerIntent(activity, outside));
    }

    @Test public void unknownSourcesRoundTripNeverInstallsWithoutAnotherConfirmation() throws Exception {
        stageVerified();
        shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(false);
        updater.presentIfReady();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        Intent settings = shadowOf(activity).getNextStartedActivity();
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.getAction());
        assertEquals("package:" + activity.getPackageName(), settings.getData().toString());
        updater.pause();
        shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(true);
        updater.resume();
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        await(() -> !updater.isBusy());
        assertEquals(Intent.ACTION_VIEW, shadowOf(activity).getNextStartedActivity().getAction());
    }

    @Test public void deniedUnknownSourcesDoesNotLoopOrPretendPermissionWasGranted() throws Exception {
        stageVerified();
        shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(false);
        updater.presentIfReady();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        shadowOf(activity).getNextStartedActivity();
        updater.pause();
        updater.resume();
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertFalse(updater.isBusy());
        assertTrue(statuses.get(statuses.size() - 1).contains("尚未允许"));
        updater.presentIfReady();
        assertFalse(updater.isBusy());
    }

    @Test public void missingInstallerAndTamperedFileProduceActionableFailureNotSuccess() throws Exception {
        File apk = stageVerified();
        activity.reject = true;
        updater.presentIfReady();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        await(() -> !updater.isBusy());
        assertTrue(statuses.get(statuses.size() - 1).contains("无法打开 Android 安装器"));
        assertFalse(statuses.get(statuses.size() - 1).contains("synthetic"));
        assertTrue(statuses.get(statuses.size() - 1).contains("INSTALLER/NOT_FOUND"));
        activity.reject = false;
        Files.write(apk.toPath(), new byte[UpdateClientTest.APK_BYTES.length]);
        updater.check(true);
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        await(() -> !updater.isBusy());
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertTrue(statuses.get(statuses.size() - 1).contains("安全校验失败"));
        assertTrue(statuses.get(statuses.size() - 1).contains("FILE_VERIFY/HASH"));
    }

    @Test public void packageChecksReportDistinctParseIdentityVersionCompatibilityAndCertificateFailures() throws Exception {
        PackageInfo installed = packageInfo(activity.getPackageName(), 10000, 23, "01");
        PackageInfo[] invalid = {null, packageInfo("other.package", 10203, 23, "01"),
                packageInfo(activity.getPackageName(), 10204, 23, "01"),
                packageInfo(activity.getPackageName(), 10203, 29, "01"),
                packageInfo(activity.getPackageName(), 10203, 23, "02")};
        UpdateFailure.Stage[] stages = {UpdateFailure.Stage.APK_PARSE, UpdateFailure.Stage.PACKAGE,
                UpdateFailure.Stage.VERSION, UpdateFailure.Stage.MIN_SDK, UpdateFailure.Stage.SIGNATURE};
        UpdateFailure.Reason[] reasons = {UpdateFailure.Reason.ARCHIVE, UpdateFailure.Reason.PACKAGE,
                UpdateFailure.Reason.VERSION_CODE, UpdateFailure.Reason.MIN_SDK, UpdateFailure.Reason.CERTIFICATE};
        for (int i = 0; i < invalid.length; i++) {
            PackageInfo archive = invalid[i];
            UpdateFailure failure = assertThrows(UpdateFailure.class, () -> AppUpdater.validatePackages(
                    activity.getPackageName(), installed, archive, update, 28));
            assertEquals(stages[i], failure.stage);
            assertEquals(reasons[i], failure.reason);
            if (i == 3) assertTrue(failure.describe(28, update.versionCode).contains("minSdk=29"));
        }
    }

    @Test public void nullSigningHistoryEntryFailsClosedInsteadOfCrashingWorker() throws Exception {
        PackageInfo installed = packageInfo(activity.getPackageName(), 10000, 23, "01");
        PackageInfo archive = packageInfo(activity.getPackageName(), 10203, 23, "01");
        shadowOf(archive.signingInfo).setPastSigningCertificates(new Signature[]{null});
        UpdateFailure failure = assertThrows(UpdateFailure.class, () -> AppUpdater.validatePackages(
                activity.getPackageName(), installed, archive, update, 28));
        assertEquals(UpdateFailure.Reason.SIGNER_HISTORY, failure.reason);
        assertEquals(UpdateFailure.Stage.APK_SIGNER, failure.stage);
        shadowOf(installed.signingInfo).setPastSigningCertificates(new Signature[]{null});
        failure = assertThrows(UpdateFailure.class, () -> AppUpdater.validatePackages(
                activity.getPackageName(), installed, archive, update, 28));
        assertEquals(UpdateFailure.Stage.INSTALLED_SIGNER, failure.stage);
    }

    @Test public void downloadFailureIsReadableWithoutAdbAndLogsNoServerBodyOrSignedUrl() throws Exception {
        create(synthetic(chain -> new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(403).message("secret HTTP message")
                .body(ResponseBody.create("https://private.example?signature=secret Cookie: private", null)).build()));
        field("update", update);
        updater.presentIfReady();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        await(() -> !updater.isBusy());
        String notice = statuses.get(statuses.size() - 1);
        assertTrue(notice.contains("APK_DOWNLOAD/HTTP"));
        assertTrue(notice.contains("HTTP=403"));
        assertTrue(notice.contains("API=28"));
        assertTrue(notice.contains("targetCode=10203"));
        assertFalse(notice.contains("secret"));
        assertFalse(notice.contains("https://"));
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertFalse(ShadowLog.getLogsForTag("DashboardUpdater").isEmpty());
        for (ShadowLog.LogItem log : ShadowLog.getLogsForTag("DashboardUpdater")) {
            assertNull(log.throwable);
            assertFalse(log.msg.contains("secret"));
            assertFalse(log.msg.contains("https://"));
        }
        assertEquals(1, diagnostics.size());
        updater.presentIfReady();
        assertFalse(updater.isBusy());
    }

    @Test public void missingModernSignerStillFailsClosedAndPublishesSeparateReport() throws Exception {
        File apk = stageVerified();
        PackageInfo archive = packageInfo(activity.getPackageName(), update.versionCode, 23, "01");
        archive.signingInfo = null;
        shadowOf(activity.getPackageManager()).setPackageArchiveInfo(apk.getAbsolutePath(), archive);
        updater.presentIfReady();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        await(() -> !updater.isBusy());
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).contains("SIGNER_MISSING"));
        assertTrue(statuses.get(statuses.size() - 1).contains("APK_SIGNER/SIGNER_MISSING"));
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertFalse(apk.exists());
    }

    @Test public void explicitProbeDownloadsEvenCurrentVersionButNeverInstallsOrTouchesVerifiedApk() throws Exception {
        byte[] release = UpdateClientTest.bytes(UpdateClientTest.release());
        byte[] manifest = UpdateClientTest.bytes(UpdateClientTest.manifest());
        AtomicInteger requests = new AtomicInteger();
        create(synthetic(chain -> {
            int request = requests.getAndIncrement();
            return response(chain, request == 0 ? release : request == 1 ? manifest : UpdateClientTest.APK_BYTES);
        }));
        PackageInfo installed = packageInfo(activity.getPackageName(), update.versionCode, 23, "01");
        shadowOf(activity.getPackageManager()).installPackage(installed);
        File probe = new File(activity.getCacheDir(), "update-diagnostic/updates/" + UpdateClient.APK);
        shadowOf(activity.getPackageManager()).setPackageArchiveInfo(probe.getAbsolutePath(), installed);
        File installer = new File(activity.getFilesDir(), "keep-installer.apk");
        Files.write(installer.toPath(), new byte[]{1, 2, 3});
        field("verified", installer);
        updater.diagnose();
        await(() -> !updater.isBusy());
        assertEquals(3, requests.get());
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).contains("NOT_NEWER"));
        assertTrue(diagnostics.get(0).contains("DIAGNOSTIC_LATEST"));
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(installer.toPath()));
        assertFalse(probe.exists());
        assertTrue(installer.delete());
    }

    @Test public void cancelledExplicitProbeCannotPublishLateReport() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        create(synthetic(chain -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            throw new IOException("private signed URL");
        }));
        updater.diagnose();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        updater.pause();
        release.countDown();
        Thread.sleep(80);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(diagnostics.isEmpty());
        assertTrue(ShadowLog.getLogsForTag("DashboardUpdater").isEmpty());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test public void systemArchiveParseFailureIsNotBlamedOnSignatureAndDeletesFile() throws Exception {
        File apk = stageVerified();
        shadowOf(activity.getPackageManager()).setPackageArchiveInfo(apk.getAbsolutePath(), null);
        updater.presentIfReady();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        await(() -> !updater.isBusy());
        assertTrue(statuses.get(statuses.size() - 1).contains("APK_PARSE/ARCHIVE"));
        assertFalse(apk.exists());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test @Config(sdk = 23) public void unreadableApi23ManifestHasCompatibilityDiagnostic() throws Exception {
        File apk = stageVerified();
        updater.presentIfReady();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        await(() -> !updater.isBusy());
        String notice = statuses.get(statuses.size() - 1);
        assertTrue(notice, notice.contains("MIN_SDK/BINARY_MANIFEST"));
        assertTrue(notice.contains("API=23"));
        assertFalse(apk.exists());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test @Config(sdk = 23) public void api23TooNewManifestShowsRequiredAndActualApiWithoutInstalling() throws Exception {
        File apk = stageVerified();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(binaryManifest(24));
            zip.closeEntry();
        }
        byte[] content = bytes.toByteArray();
        Files.write(apk.toPath(), content);
        field("update", new UpdateClient.Update(update.versionCode, update.versionName,
                UpdateClientTest.digest(content), content.length, update.tag));
        updater.presentIfReady();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        await(() -> !updater.isBusy());
        String notice = statuses.get(statuses.size() - 1);
        assertTrue(notice, notice.contains("MIN_SDK/MIN_SDK"));
        assertTrue(notice.contains("minSdk=24"));
        assertTrue(notice.contains("API=23"));
        assertFalse(apk.exists());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test public void binaryManifestSupportsApi23MinSdkAndRejectsUnsupportedValues() throws Exception {
        assertEquals(23, AppUpdater.binaryMinSdk(binaryManifest(23)));
        assertEquals(35, AppUpdater.binaryMinSdk(binaryManifest(35)));
        assertThrows(IOException.class, () -> AppUpdater.binaryMinSdk(binaryManifest(-1)));
        assertThrows(IOException.class, () -> AppUpdater.binaryMinSdk(new byte[]{3, 0, 8, 0}));
        byte[] corrupt = binaryManifest(23);
        ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putInt(12, Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> AppUpdater.binaryMinSdk(corrupt));
    }

    private static byte[] binaryManifest(int minSdk) throws Exception {
        String[] names = {"manifest", "uses-sdk", "minSdkVersion", "http://schemas.android.com/apk/res/android"};
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        int[] offsets = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            offsets[i] = strings.size();
            byte[] value = names[i].getBytes(StandardCharsets.UTF_8);
            strings.write(value.length);
            strings.write(value.length);
            strings.write(value);
            strings.write(0);
        }
        while (strings.size() % 4 != 0) strings.write(0);
        int poolSize = 28 + offsets.length * 4 + strings.size();
        ByteBuffer data = ByteBuffer.allocate(8 + poolSize + 36 + 56).order(ByteOrder.LITTLE_ENDIAN);
        data.putShort((short) 3).putShort((short) 8).putInt(data.capacity());
        data.putShort((short) 1).putShort((short) 28).putInt(poolSize)
                .putInt(names.length).putInt(0).putInt(0x100).putInt(28 + names.length * 4).putInt(0);
        for (int offset : offsets) data.putInt(offset);
        data.put(strings.toByteArray());
        element(data, 0, 0);
        element(data, 1, 1);
        data.putInt(3).putInt(2).putInt(-1).putShort((short) 8).put((byte) 0).put((byte) 0x10).putInt(minSdk);
        return data.array();
    }

    private static void element(ByteBuffer data, int name, int attributes) {
        data.putShort((short) 0x102).putShort((short) 16).putInt(36 + attributes * 20).putInt(1).putInt(-1)
                .putInt(-1).putInt(name).putShort((short) 20).putShort((short) 20).putShort((short) attributes)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0);
    }
}
