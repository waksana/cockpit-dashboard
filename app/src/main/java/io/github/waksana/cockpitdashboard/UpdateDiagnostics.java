package io.github.waksana.cockpitdashboard;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Thread-confined observations, never a substitute for the updater's strict validation. */
final class UpdateDiagnostics {
    private static final int MAX_REPORT = 6000;
    private final int api = Build.VERSION.SDK_INT;
    private final String header;
    private String file = "file hashSize=NOT_CHECKED";
    private String primary = "primary state=NOT_READ";
    private String legacy = "legacy state=NOT_READ";
    private String outcome = "result validation=NOT_RUN";

    UpdateDiagnostics(String mode, int targetCode) {
        String knownMode;
        switch (mode == null ? "" : mode) {
            case "DOWNLOAD": case "INSTALL": case "DIAGNOSTIC_LATEST":
                knownMode = mode;
                break;
            default: knownMode = "UNKNOWN";
        }
        header = "Updater diagnostic only; legacy observations never bypass strict validation or authorize installation.\n"
                + ("DIAGNOSTIC_LATEST".equals(knownMode)
                        ? "Latest-release diagnostic: same-version NOT_NEWER is expected, not proof of signature validity.\n" : "")
                + "attempt id=" + UUID.randomUUID().toString().substring(0, 8)
                + " epochMs=" + System.currentTimeMillis() + " mode=" + knownMode
                + " source=DOWNLOADED_TARGET"
                + " API=" + api + " targetCode=" + targetCode + "\n"
                + "app versionName=" + token(BuildConfig.VERSION_NAME)
                + " versionCode=" + BuildConfig.VERSION_CODE + "\n"
                + "device manufacturer=" + token(Build.MANUFACTURER) + " model=" + token(Build.MODEL)
                + " incremental=" + token(Build.VERSION.INCREMENTAL);
    }

    /** Call only after UpdateClient.verifyFile succeeds; this method does not verify bytes. */
    void fileVerified(File apk, String expectedSha256) throws IOException {
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid diagnostic SHA-256");
        }
        long size = apk.length();
        file = "file bytes=" + size + " expectedSha256=" + expectedSha256 + " hashSize=PASS";
    }

    @SuppressWarnings("deprecation")
    void packages(PackageManager pm, String ownPackage, File apk, PackageInfo installed,
            PackageInfo archive, UpdateClient.Cancellation operation) throws IOException {
        operation.check();
        Snapshot current = snapshot(installed, ownPackage);
        Snapshot candidate = snapshot(archive, ownPackage);
        primary = "primary API=" + api + " flags="
                + (api >= 28 ? "GET_SIGNING_CERTIFICATES(" + PackageManager.GET_SIGNING_CERTIFICATES + ")"
                        : "GET_SIGNATURES(" + PackageManager.GET_SIGNATURES + ")")
                + "\ninstalled " + current.facts + "\narchive " + candidate.facts;
        operation.check();
        if (api < 28) {
            legacy = "legacy extraReads=SKIPPED_API_LT_28";
            return;
        }
        // Separate queries, with no assignment to or fallback for either primary PackageInfo.
        String installedProbe;
        operation.check();
        try {
            installedProbe = legacySnapshot(pm.getPackageInfo(ownPackage, PackageManager.GET_SIGNATURES),
                    ownPackage, current.sha256);
        } catch (PackageManager.NameNotFoundException error) {
            installedProbe = "error=NOT_FOUND equalsPrimaryInstalled=UNKNOWN";
        } catch (IllegalArgumentException error) {
            installedProbe = "error=INVALID equalsPrimaryInstalled=UNKNOWN";
        } catch (SecurityException error) {
            installedProbe = "error=PERMISSION equalsPrimaryInstalled=UNKNOWN";
        }
        operation.check();
        String archiveProbe;
        operation.check();
        try {
            archiveProbe = legacySnapshot(pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES),
                    ownPackage, current.sha256);
        } catch (IllegalArgumentException error) {
            archiveProbe = "error=INVALID equalsPrimaryInstalled=UNKNOWN";
        } catch (SecurityException error) {
            archiveProbe = "error=PERMISSION equalsPrimaryInstalled=UNKNOWN";
        }
        operation.check();
        legacy = "legacy diagnosticOnly=true flags=GET_SIGNATURES(" + PackageManager.GET_SIGNATURES + ")"
                + "\nlegacyInstalled " + installedProbe + "\nlegacyArchive " + archiveProbe;
    }

    void result(UpdateFailure failure) {
        outcome = failure == null ? "result validation=PASS"
                : failure.describe(api, null) + "\nresult validation=FAIL stage=" + failure.stage.name()
                        + " reason=" + failure.reason.name();
    }

    String describe() {
        String body = header + "\n" + file + "\n" + primary + "\n" + legacy;
        int available = MAX_REPORT - outcome.length() - 1;
        if (body.length() > available) body = body.substring(0, available - 16) + "\nTRUNCATED=true";
        return body + "\n" + outcome;
    }

    static String observe(PackageInfo info, String ownPackage) {
        return snapshot(info, ownPackage).facts;
    }

    static String token(String value) {
        if (value == null || value.isEmpty()) return "UNKNOWN";
        StringBuilder safe = new StringBuilder(48);
        for (int i = 0; i < value.length() && i < 48; i++) {
            char c = value.charAt(i);
            safe.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        return safe.toString();
    }

    @SuppressWarnings("deprecation")
    private static Snapshot snapshot(PackageInfo info, String ownPackage) {
        if (info == null) return new Snapshot("nullInfo=true", null);
        StringBuilder facts = new StringBuilder("nullInfo=false appInfo=")
                .append(info.applicationInfo == null ? "NULL" : "PRESENT")
                .append(" versionCode=").append(Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode)
                .append(" minSdk=").append(Build.VERSION.SDK_INT < 24 ? "UNAVAILABLE_API" : info.applicationInfo == null
                        ? "UNKNOWN" : Integer.toString(info.applicationInfo.minSdkVersion))
                .append(" expectedPackage=").append(ownPackage != null && ownPackage.equals(info.packageName));
        Snapshot old = signatures(info.signatures);
        String sha256 = null;
        if (Build.VERSION.SDK_INT >= 28) {
            facts.append(" signingInfo=").append(info.signingInfo == null ? "NULL" : "PRESENT");
            if (info.signingInfo != null) {
                try {
                    boolean multiple = info.signingInfo.hasMultipleSigners();
                    facts.append(" hasMultipleSigners=").append(multiple);
                } catch (IllegalArgumentException | IllegalStateException | NullPointerException | SecurityException error) {
                    facts.append(" hasMultipleSigners=ERROR_").append(getterError(error));
                }
                try {
                    Snapshot contents = signatures(info.signingInfo.getApkContentsSigners());
                    facts.append(" contents[").append(contents.facts).append(']');
                    sha256 = contents.sha256;
                } catch (IllegalArgumentException | IllegalStateException | NullPointerException | SecurityException error) {
                    facts.append(" contents[error=").append(getterError(error)).append(']');
                }
                try {
                    Snapshot history = signatures(info.signingInfo.getSigningCertificateHistory());
                    facts.append(" history[").append(history.facts).append(']');
                } catch (IllegalArgumentException | IllegalStateException | NullPointerException | SecurityException error) {
                    facts.append(" history[error=").append(getterError(error)).append(']');
                }
            } else {
                facts.append(" hasMultipleSigners=UNKNOWN contents[state=UNAVAILABLE] history[state=UNAVAILABLE]");
            }
        } else {
            facts.append(" signingInfo=UNAVAILABLE_API");
            sha256 = old.sha256;
        }
        facts.append(" signatures[").append(old.facts).append(']');
        return new Snapshot(facts.toString(), sha256);
    }

    @SuppressWarnings("deprecation")
    private static String legacySnapshot(PackageInfo info, String ownPackage, String primarySha256) {
        if (info == null) return "nullInfo=true equalsPrimaryInstalled=UNKNOWN";
        Snapshot signatures = signatures(info.signatures);
        return "nullInfo=false expectedPackage=" + (ownPackage != null && ownPackage.equals(info.packageName))
                + " signatures[" + signatures.facts + "] equalsPrimaryInstalled="
                + (primarySha256 == null || signatures.sha256 == null ? "UNKNOWN"
                        : primarySha256.equals(signatures.sha256) ? "true" : "false");
    }

    private static Snapshot signatures(Signature[] values) {
        if (values == null) return new Snapshot("state=NULL", null);
        int nulls = 0;
        int scanned = Math.min(values.length, 64);
        for (int i = 0; i < scanned; i++) if (values[i] == null) nulls++;
        String facts = "state=PRESENT size=" + values.length + " nullEntries=" + nulls
                + (scanned < values.length ? " scanned=" + scanned : "");
        String fingerprint = null;
        if (values.length == 1 && values[0] != null) {
            try {
                byte[] certificate = values[0].toByteArray();
                if (certificate.length == 0 || certificate.length > 65536) {
                    facts += " sha256=UNAVAILABLE_SIZE";
                } else {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate);
                    StringBuilder hex = new StringBuilder(64);
                    for (byte b : digest) {
                        hex.append("0123456789abcdef".charAt((b & 0xff) >>> 4))
                                .append("0123456789abcdef".charAt(b & 0x0f));
                    }
                    fingerprint = hex.toString();
                    facts += " sha256=" + fingerprint;
                }
            } catch (NoSuchAlgorithmException error) {
                facts += " sha256=UNAVAILABLE_ALGORITHM";
            } catch (IllegalArgumentException | IllegalStateException | NullPointerException | SecurityException error) {
                facts += " sha256=ERROR_" + getterError(error);
            }
        }
        return new Snapshot(facts, fingerprint);
    }

    private static String getterError(RuntimeException error) {
        return error instanceof SecurityException ? "PERMISSION" : "INVALID";
    }

    private static final class Snapshot {
        final String facts;
        final String sha256;

        Snapshot(String facts, String sha256) {
            this.facts = facts;
            this.sha256 = sha256;
        }
    }
}
