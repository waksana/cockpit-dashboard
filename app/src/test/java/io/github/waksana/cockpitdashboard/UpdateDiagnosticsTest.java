package io.github.waksana.cockpitdashboard;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowSigningInfo;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = UpdateDiagnosticsTest.SigningSnapshotShadow.class)
public class UpdateDiagnosticsTest {
    private static final String OWN = "io.github.waksana.cockpitdashboard";
    private static final String SHA = "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a";
    private static final File APK = new File("private-path-must-not-appear/credential-secret.apk");

    @Implements(value = SigningInfo.class, minSdk = 28)
    public static class SigningSnapshotShadow extends ShadowSigningInfo {
        Signature[] contents;
        Signature[] history;
        boolean multiple;
        RuntimeException failure;

        @Implementation
        public boolean hasMultipleSigners() {
            if (failure != null) throw failure;
            return multiple;
        }

        @Implementation
        public Signature[] getApkContentsSigners() {
            if (failure != null) throw failure;
            return contents;
        }

        @Implementation
        public Signature[] getSigningCertificateHistory() {
            if (failure != null) throw failure;
            return history;
        }
    }

    private static PackageInfo info() {
        PackageInfo info = new PackageInfo();
        info.packageName = OWN;
        info.versionCode = 10203;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = OWN;
        if (Build.VERSION.SDK_INT >= 24) info.applicationInfo.minSdkVersion = 23;
        info.signatures = new Signature[]{new Signature("01")};
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo = new SigningInfo();
            SigningSnapshotShadow signing = Shadow.extract(info.signingInfo);
            signing.contents = info.signatures;
            signing.history = info.signatures;
        }
        return info;
    }

    private static PackageManager manager(PackageInfo legacyInstalled, PackageInfo legacyArchive) {
        PackageManager pm = RuntimeEnvironment.getApplication().getPackageManager();
        shadowOf(pm).installPackage(legacyInstalled);
        shadowOf(pm).setPackageArchiveInfo(APK.getAbsolutePath(), legacyArchive);
        return pm;
    }

    @Test
    public void headerHasBoundedSafeDeviceAndAttemptFacts() {
        long before = System.currentTimeMillis();
        UpdateDiagnostics report = new UpdateDiagnostics("DIAGNOSTIC_LATEST", 10203);
        String text = report.describe();
        assertTrue(text.contains("diagnostic only"));
        assertTrue(text.contains("never bypass"));
        assertTrue(text.matches("(?s).*attempt id=[0-9a-f]{8} epochMs=[0-9]+ mode=DIAGNOSTIC_LATEST.*"));
        long time = Long.parseLong(text.split("epochMs=")[1].split(" ")[0]);
        assertTrue(time >= before && time <= System.currentTimeMillis());
        assertTrue(text.contains("source=DOWNLOADED_TARGET"));
        assertTrue(text.contains("same-version NOT_NEWER is expected, not proof of signature validity"));
        assertTrue(text.contains("API=28 targetCode=10203"));
        assertTrue(text.contains("versionName=" + UpdateDiagnostics.token(BuildConfig.VERSION_NAME)));
        assertTrue(text.contains("versionCode=" + BuildConfig.VERSION_CODE));
        assertTrue(text.contains("manufacturer=" + UpdateDiagnostics.token(Build.MANUFACTURER)));
        assertTrue(text.contains("model=" + UpdateDiagnostics.token(Build.MODEL)));
        assertTrue(text.contains("incremental=" + UpdateDiagnostics.token(Build.VERSION.INCREMENTAL)));
        assertTrue(text.contains("hashSize=NOT_CHECKED"));
        assertTrue(text.endsWith("result validation=NOT_RUN"));
        assertEquals(text, report.describe());
        assertFalse(text.contains("fingerprint="));
        assertFalse(text.contains("serial="));
    }

    @Test
    public void sanitizationCannotInjectLinesOrArbitraryModes() {
        assertEquals("UNKNOWN", UpdateDiagnostics.token(null));
        assertEquals("UNKNOWN", UpdateDiagnostics.token(""));
        assertEquals("XGIMI_Model-1.2_", UpdateDiagnostics.token("XGIMI\nModel-1.2中"));
        String safe = UpdateDiagnostics.token("a".repeat(10000) + "\nsecret=true");
        assertEquals(48, safe.length());
        assertTrue(safe.matches("[A-Za-z0-9._-]+"));
        assertFalse(new UpdateDiagnostics("https://secret/path\nPASS", 0).describe().contains("secret"));
        assertTrue(new UpdateDiagnostics("DIAGNOSTIC_LATEST", 0).describe().contains("mode=DIAGNOSTIC_LATEST"));
    }

    @Test
    public void manualDiscoveryAndSameVersionRejectionNeverClaimSignatureApproval() {
        UpdateDiagnostics discovery = new UpdateDiagnostics("DIAGNOSTIC_LATEST", 0);
        discovery.result(UpdateFailure.at(UpdateFailure.Stage.RELEASE, new IOException("https://secret")));
        assertTrue(discovery.describe().contains("targetCode=0"));
        assertTrue(discovery.describe().endsWith("result validation=FAIL stage=RELEASE reason=IO"));
        assertFalse(discovery.describe().contains("secret"));
        UpdateDiagnostics selected = new UpdateDiagnostics("DIAGNOSTIC_LATEST", 10203);
        selected.result(new UpdateFailure(UpdateFailure.Stage.VERSION, UpdateFailure.Reason.NOT_NEWER));
        assertTrue(selected.describe().contains("targetCode=10203"));
        assertTrue(selected.describe().contains("same-version NOT_NEWER is expected, not proof of signature validity"));
        assertTrue(selected.describe().endsWith("result validation=FAIL stage=VERSION reason=NOT_NEWER"));
        assertFalse(selected.describe().contains("validation=PASS"));
        for (String mode : new String[]{"DOWNLOAD", "INSTALL"}) {
            String text = new UpdateDiagnostics(mode, 10203).describe();
            assertTrue(text.contains("mode=" + mode));
            assertFalse(text.contains("same-version NOT_NEWER"));
        }
    }

    @Test
    public void fileFactsRequireLowercaseShaAndNeverContainPaths() throws Exception {
        UpdateDiagnostics report = new UpdateDiagnostics("DOWNLOAD", 10203);
        File synthetic = new File("private-path/credential-secret.apk") {
            @Override public long length() { return 12345; }
        };
        assertThrows(IOException.class, () -> report.fileVerified(synthetic, SHA.toUpperCase(java.util.Locale.ROOT)));
        assertThrows(IOException.class, () -> report.fileVerified(synthetic, SHA + "\nsecret"));
        assertThrows(IOException.class, () -> report.fileVerified(synthetic, null));
        assertTrue(report.describe().contains("hashSize=NOT_CHECKED"));
        report.fileVerified(synthetic, SHA);
        assertTrue(report.describe().contains("file bytes=12345 expectedSha256=" + SHA + " hashSize=PASS"));
        assertFalse(report.describe().contains("private-path"));
        assertFalse(report.describe().contains("credential-secret"));
    }

    @Test
    public void nullPackagesAndWrongNamesRemainNumericOrBooleanFacts() {
        assertEquals("nullInfo=true", UpdateDiagnostics.observe(null, OWN));
        PackageInfo info = info();
        info.packageName = "secret.package.name";
        info.versionName = "secret-version";
        info.applicationInfo = null;
        String text = UpdateDiagnostics.observe(info, OWN);
        assertTrue(text.contains("nullInfo=false appInfo=NULL versionCode=10203 minSdk=UNKNOWN expectedPackage=false"));
        assertFalse(text.contains("secret"));
    }

    @Test
    public void modernNullIsNotReplacedByLegacyField() {
        PackageInfo info = info();
        info.signingInfo = null;
        String text = UpdateDiagnostics.observe(info, OWN);
        assertTrue(text.contains("signingInfo=NULL hasMultipleSigners=UNKNOWN"));
        assertTrue(text.contains("contents[state=UNAVAILABLE] history[state=UNAVAILABLE]"));
        assertTrue(text.contains("signatures[state=PRESENT size=1 nullEntries=0 sha256=" + SHA + "]"));
        assertNull(info.signingInfo);
    }

    @Test
    public void contentsAndHistoryDistinguishNullEmptyAndNullEntry() {
        PackageInfo info = info();
        SigningSnapshotShadow signing = Shadow.extract(info.signingInfo);
        signing.contents = null;
        signing.history = null;
        String text = UpdateDiagnostics.observe(info, OWN);
        assertTrue(text.contains("contents[state=NULL]"));
        assertTrue(text.contains("history[state=NULL]"));
        signing.contents = new Signature[0];
        signing.history = new Signature[0];
        text = UpdateDiagnostics.observe(info, OWN);
        assertTrue(text.contains("contents[state=PRESENT size=0 nullEntries=0]"));
        assertTrue(text.contains("history[state=PRESENT size=0 nullEntries=0]"));
        signing.contents = new Signature[]{null};
        signing.history = new Signature[]{null};
        text = UpdateDiagnostics.observe(info, OWN);
        assertTrue(text.contains("contents[state=PRESENT size=1 nullEntries=1]"));
        assertTrue(text.contains("history[state=PRESENT size=1 nullEntries=1]"));
    }

    @Test
    public void fingerprintsAreSha256AndMultipleArraysDoNotEmitCertificates() {
        PackageInfo info = info();
        String text = UpdateDiagnostics.observe(info, OWN);
        assertTrue(text.contains("contents[state=PRESENT size=1 nullEntries=0 sha256=" + SHA + "]"));
        assertTrue(text.contains("history[state=PRESENT size=1 nullEntries=0 sha256=" + SHA + "]"));
        SigningSnapshotShadow signing = Shadow.extract(info.signingInfo);
        signing.multiple = true;
        signing.contents = new Signature[]{new Signature("01"), new Signature("02")};
        signing.history = new Signature[]{new Signature("01"), null};
        info.signatures = null;
        text = UpdateDiagnostics.observe(info, OWN);
        assertTrue(text.contains("hasMultipleSigners=true"));
        assertTrue(text.contains("contents[state=PRESENT size=2 nullEntries=0]"));
        assertTrue(text.contains("history[state=PRESENT size=2 nullEntries=1]"));
        assertFalse(text.contains("sha256="));
    }

    @Test
    public void brokenModernGettersExposeOnlyFixedErrors() {
        PackageInfo info = info();
        SigningSnapshotShadow signing = Shadow.extract(info.signingInfo);
        for (RuntimeException failure : new RuntimeException[]{
                new IllegalArgumentException("https://secret/path"),
                new IllegalStateException("credential-secret"),
                new NullPointerException("private-path"),
                new SecurityException("token=secret")}) {
            signing.failure = failure;
            String label = failure instanceof SecurityException ? "PERMISSION" : "INVALID";
            String text = UpdateDiagnostics.observe(info, OWN);
            assertTrue(text.contains("hasMultipleSigners=ERROR_" + label));
            assertTrue(text.contains("contents[error=" + label + "]"));
            assertTrue(text.contains("history[error=" + label + "]"));
            assertTrue(text.contains("signatures[state=PRESENT"));
            assertFalse(text.contains("secret"));
            assertFalse(text.contains("private-path"));
        }
    }

    @Test
    public void legacyPresentModernMissingStaysInformationalAndCannotPassValidation() throws Exception {
        PackageInfo installed = info();
        PackageInfo archive = info();
        archive.signingInfo = null;
        UpdateDiagnostics report = new UpdateDiagnostics("DOWNLOAD", 10203);
        report.packages(manager(info(), info()), OWN, APK, installed, archive, new UpdateClient.Cancellation());
        report.result(new UpdateFailure(UpdateFailure.Stage.APK_SIGNER, UpdateFailure.Reason.SIGNER_MISSING));
        String text = report.describe();
        assertTrue(text.contains("flags=GET_SIGNING_CERTIFICATES(" + PackageManager.GET_SIGNING_CERTIFICATES + ")"));
        assertTrue(text.contains("legacy diagnosticOnly=true flags=GET_SIGNATURES(" + PackageManager.GET_SIGNATURES + ")"));
        assertTrue(text.contains("legacyArchive nullInfo=false expectedPackage=true signatures[state=PRESENT"));
        assertTrue(text.contains("equalsPrimaryInstalled=true"));
        assertTrue(text.endsWith("result validation=FAIL stage=APK_SIGNER reason=SIGNER_MISSING"));
        assertFalse(text.contains("validation=PASS"));
        assertNull(archive.signingInfo);
        assertFalse(text.contains(APK.getName()));
        installed.signingInfo = null;
        report.packages(manager(info(), info()), OWN, APK, installed, archive, new UpdateClient.Cancellation());
        assertTrue(report.describe().contains("equalsPrimaryInstalled=UNKNOWN"));
        assertFalse(report.describe().contains("equalsPrimaryInstalled=true"));
    }

    @Test
    public void legacyDifferenceAndMissingHaveDistinctComparisonStates() throws Exception {
        PackageInfo legacyArchive = info();
        legacyArchive.signatures = new Signature[]{new Signature("02")};
        UpdateDiagnostics report = new UpdateDiagnostics("DIAGNOSTIC_LATEST", 10203);
        report.packages(manager(info(), legacyArchive), OWN, APK, info(), info(), new UpdateClient.Cancellation());
        assertTrue(report.describe().contains("equalsPrimaryInstalled=false"));
        legacyArchive.signatures = null;
        report.packages(manager(info(), legacyArchive), OWN, APK, info(), info(), new UpdateClient.Cancellation());
        assertTrue(report.describe().contains("signatures[state=NULL] equalsPrimaryInstalled=UNKNOWN"));
        assertTrue(report.describe().contains("hashSize=NOT_CHECKED"));
    }

    @Test
    public void failedInstalledProbeDoesNotSkipArchiveAndErrorsHaveNoMessages() throws Exception {
        PackageManager pm = manager(info(), info());
        UpdateDiagnostics report = new UpdateDiagnostics("DIAGNOSTIC_LATEST", 0);
        File denied = new File("ignored") {
            @Override public String getAbsolutePath() { throw new SecurityException("https://credential-secret/path"); }
        };
        report.packages(pm, "not.installed.secret", denied, info(), info(), new UpdateClient.Cancellation());
        String text = report.describe();
        assertTrue(text.contains("legacyInstalled error=NOT_FOUND equalsPrimaryInstalled=UNKNOWN"));
        assertTrue(text.contains("legacyArchive error=PERMISSION equalsPrimaryInstalled=UNKNOWN"));
        assertFalse(text.contains("secret"));
        assertFalse(text.contains("https://"));
        report.result(UpdateFailure.at(UpdateFailure.Stage.APK_PARSE,
                new IllegalArgumentException("private-path and token=secret")));
        assertTrue(report.describe().endsWith("result validation=FAIL stage=APK_PARSE reason=INVALID"));
        assertFalse(report.describe().contains("private-path"));
    }

    @Test
    @Config(sdk = 23)
    public void api23UsesNormalSignaturesWithoutAdditionalReads() throws Exception {
        PackageInfo installed = info();
        UpdateDiagnostics report = new UpdateDiagnostics("DIAGNOSTIC_LATEST", 10203);
        // Null manager and file deliberately prove no legacy query or path lookup occurs.
        report.packages(null, OWN, null, installed, info(), new UpdateClient.Cancellation());
        String text = report.describe();
        assertTrue(text.contains("API=23"));
        assertTrue(text.contains("flags=GET_SIGNATURES(" + PackageManager.GET_SIGNATURES + ")"));
        assertTrue(text.contains("minSdk=UNAVAILABLE_API"));
        assertTrue(text.contains("signingInfo=UNAVAILABLE_API"));
        assertTrue(text.contains("signatures[state=PRESENT size=1 nullEntries=0 sha256=" + SHA + "]"));
        assertTrue(text.contains("legacy extraReads=SKIPPED_API_LT_28"));
    }

    @Test
    public void cancellationBeforeOrAfterProbeIsNotSwallowed() throws Exception {
        UpdateClient.Cancellation cancelled = new UpdateClient.Cancellation();
        cancelled.cancel();
        UpdateDiagnostics report = new UpdateDiagnostics("DIAGNOSTIC_LATEST", 10203);
        assertThrows(InterruptedIOException.class, () -> report.packages(null, OWN, null, info(), info(), cancelled));
        UpdateClient.Cancellation during = new UpdateClient.Cancellation();
        File cancelling = new File("ignored") {
            @Override public String getAbsolutePath() {
                during.cancel();
                return APK.getAbsolutePath();
            }
        };
        PackageManager pm = manager(info(), info());
        assertThrows(InterruptedIOException.class,
                () -> report.packages(pm, OWN, cancelling, info(), info(), during));
        assertTrue(report.describe().endsWith("result validation=NOT_RUN"));
    }

    @Test
    public void repeatedSnapshotsAndLargeArraysRemainBoundedAndResultIsExplicit() throws Exception {
        PackageInfo installed = info();
        PackageInfo archive = info();
        PackageManager pm = manager(info(), info());
        UpdateDiagnostics report = new UpdateDiagnostics("DOWNLOAD", Integer.MAX_VALUE);
        for (int i = 0; i < 100; i++) {
            report.packages(pm, OWN, APK, installed, archive, new UpdateClient.Cancellation());
            report.fileVerified(APK, SHA);
        }
        assertTrue(report.describe().length() <= 6000);
        assertEquals(report.describe(), report.describe());
        SigningSnapshotShadow signing = Shadow.extract(archive.signingInfo);
        signing.contents = new Signature[10000];
        signing.history = new Signature[10000];
        archive.signatures = new Signature[10000];
        report.packages(pm, OWN, APK, installed, archive, new UpdateClient.Cancellation());
        assertTrue(report.describe().contains("size=10000 nullEntries=64 scanned=64"));
        assertTrue(report.describe().length() <= 6000);
        report.result(null);
        assertTrue(report.describe().endsWith("result validation=PASS"));
    }
}
