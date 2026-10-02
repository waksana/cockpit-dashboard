package io.github.waksana.cockpitdashboard;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import static io.github.waksana.cockpitdashboard.UpdateFailure.Stage.*;
import static io.github.waksana.cockpitdashboard.UpdateFailure.Reason.*;

/** Public release traffic has its own credential-free transport, unrelated to the host client. */
final class UpdateClient {
    static final String LATEST = "https://api.github.com/repos/waksana/cockpit-dashboard/releases/latest";
    static final String DOWNLOAD = "https://github.com/waksana/cockpit-dashboard/releases/download/";
    static final String APK = "cockpit-dashboard.apk";
    static final int MAX_API = 1024 * 1024;
    static final int MAX_MANIFEST = 8 * 1024;
    static final long MAX_APK = 100L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private final OkHttpClient http;

    static final class Update {
        final int versionCode;
        final String versionName, sha256, tag;
        final long size;

        Update(int versionCode, String versionName, String sha256, long size, String tag) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.sha256 = sha256;
            this.size = size;
            this.tag = tag;
        }
    }

    static final class Cancellation {
        private boolean cancelled;
        private Call call;

        synchronized void cancel() {
            cancelled = true;
            if (call != null) call.cancel();
        }

        synchronized void check() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
        }

        synchronized void attach(Call next) throws InterruptedIOException {
            check();
            call = next;
        }
    }

    UpdateClient() {
        this(new OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.MINUTES).build());
    }

    // Package-private transport injection is for synthetic tests only, never host configuration.
    UpdateClient(OkHttpClient transport) {
        http = transport.newBuilder().cookieJar(CookieJar.NO_COOKIES)
                .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .addNetworkInterceptor(chain -> {
                    Response response = chain.proceed(chain.request());
                    return response.code() == 503
                            ? response.newBuilder().header("Retry-After", "1").build() : response;
                }).build();
    }

    Update check(long installedCode, Cancellation cancellation) throws IOException {
        try {
            JSONObject release = json(read(HttpUrl.get(LATEST), MAX_API, false, cancellation, RELEASE));
            if (!Boolean.FALSE.equals(release.get("draft")) || !Boolean.FALSE.equals(release.get("prerelease"))) {
                throw new IOException("Not a stable release");
            }
            String tag = requiredString(release, "tag_name");
            validateTag(tag);
            JSONArray assets = release.getJSONArray("assets");
            boolean manifestFound = false, apkFound = false;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = requiredString(asset, "name");
                if (!name.equals("update.json") && !name.equals(APK)) continue;
                validateAssetUrl(requiredString(asset, "browser_download_url"), tag, name);
                if (name.equals("update.json")) {
                    if (manifestFound) throw new IOException("Duplicate manifest asset");
                    manifestFound = true;
                } else {
                    if (apkFound) throw new IOException("Duplicate APK asset");
                    apkFound = true;
                }
            }
            if (!manifestFound || !apkFound) throw new IOException("Release assets missing");
            Update update = parseManifest(read(assetUrl(tag, "update.json"), MAX_MANIFEST, true, cancellation, MANIFEST), tag);
            return update.versionCode > installedCode ? update : null;
        } catch (UpdateFailure error) {
            throw error;
        } catch (JSONException | IllegalArgumentException | IOException error) {
            throw new UpdateFailure(RELEASE, METADATA);
        }
    }

    static Update parseManifest(byte[] bytes, String tag) throws IOException {
        if (bytes.length > MAX_MANIFEST) throw new UpdateFailure(MANIFEST, TOO_LARGE);
        try {
            validateTag(tag);
            JSONObject manifest = json(bytes);
            long code = positiveInteger(manifest.get("versionCode"));
            long size = positiveInteger(manifest.get("size"));
            String version = requiredString(manifest, "versionName");
            String digest = requiredString(manifest, "sha256");
            if (code > Integer.MAX_VALUE || size > MAX_APK || !version.equals(tag.substring(1))
                    || !digest.matches("[0-9a-f]{64}") || !requiredString(manifest, "apk").equals(APK)) {
                throw new IOException("Invalid update manifest");
            }
            return new Update((int) code, version, digest, size, tag);
        } catch (JSONException | IOException error) {
            throw new UpdateFailure(MANIFEST, METADATA);
        }
    }

    private static JSONObject json(byte[] bytes) throws JSONException {
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private static String requiredString(JSONObject object, String name) throws JSONException, IOException {
        Object value = object.get(name);
        if (!(value instanceof String)) throw new IOException("Invalid string field");
        return (String) value;
    }

    private static long positiveInteger(Object value) throws IOException {
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IOException("Invalid integer field");
        long result = ((Number) value).longValue();
        if (result <= 0) throw new IOException("Invalid integer field");
        return result;
    }

    private static void validateTag(String tag) throws IOException {
        if (tag == null || tag.length() > 80 || !tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new IOException("Invalid stable tag");
        }
    }

    static void validateAssetUrl(String url, String tag, String name) throws IOException {
        if (!url.equals(assetUrl(tag, name).toString())) throw new IOException("Unexpected release asset URL");
    }

    private static HttpUrl assetUrl(String tag, String name) throws IOException {
        validateTag(tag);
        if (!name.equals(APK) && !name.equals("update.json")) throw new IOException("Unexpected asset");
        return HttpUrl.get(DOWNLOAD + tag + "/" + name);
    }

    static boolean allowedRedirect(HttpUrl next, HttpUrl original) {
        if (!next.isHttps() || next.port() != 443 || !next.username().isEmpty()
                || !next.password().isEmpty() || next.fragment() != null) return false;
        if (next.host().equals("github.com")) return next.equals(original);
        return next.host().equals("release-assets.githubusercontent.com")
                || next.host().equals("objects.githubusercontent.com")
                || next.host().equals("github-releases.githubusercontent.com");
    }

    private Response open(HttpUrl original, boolean redirects, Cancellation cancellation, UpdateFailure.Stage stage)
            throws IOException {
        HttpUrl current = original;
        for (int count = 0; count <= MAX_REDIRECTS; count++) {
            cancellation.check();
            Request request = new Request.Builder().url(current)
                    .header("Accept", "application/octet-stream, application/json")
                    .header("User-Agent", "Cockpit-Dashboard-Updater")
                    .header("Accept-Encoding", "identity").build();
            Call call = http.newCall(request);
            cancellation.attach(call);
            Response response = call.execute();
            if (response.code() == 200 && response.body() != null) return response;
            if (response.isRedirect() && redirects && count < MAX_REDIRECTS) {
                String location = response.header("Location");
                HttpUrl next = location == null ? null : current.resolve(location);
                response.close();
                if (next == null || !allowedRedirect(next, original)) throw new UpdateFailure(stage, REDIRECT);
                current = next;
            } else {
                int status = response.code();
                boolean redirect = response.isRedirect();
                response.close();
                if (redirect) throw new UpdateFailure(stage, redirects ? REDIRECT_LIMIT : REDIRECT);
                throw UpdateFailure.http(stage, status);
            }
        }
        throw new UpdateFailure(stage, REDIRECT_LIMIT);
    }

    private byte[] read(HttpUrl url, int limit, boolean redirects, Cancellation cancellation, UpdateFailure.Stage stage)
            throws IOException {
        try (Response response = open(url, redirects, cancellation, stage)) {
            if (response.body().contentLength() > limit) throw new UpdateFailure(stage, TOO_LARGE);
            return boundedRead(response.body().byteStream(), limit, cancellation, stage);
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            throw UpdateFailure.at(stage, error);
        }
    }

    static byte[] boundedRead(InputStream input, int limit, Cancellation cancellation) throws IOException {
        return boundedRead(input, limit, cancellation, UpdateFailure.Stage.MIN_SDK);
    }

    private static byte[] boundedRead(InputStream input, int limit, Cancellation cancellation, UpdateFailure.Stage stage)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            cancellation.check();
            if ((long) output.size() + count > limit) throw new UpdateFailure(stage, TOO_LARGE);
            output.write(buffer, 0, count);
        }
        cancellation.check();
        return output.toByteArray();
    }

    File download(Update update, File filesDirectory, Cancellation cancellation) throws IOException {
        if (update.size <= 0 || update.size > MAX_APK) throw new UpdateFailure(MANIFEST, METADATA);
        File directory = new File(filesDirectory, "updates");
        File partial = new File(directory, APK + ".part");
        File target = new File(directory, APK);
        boolean complete = false;
        UpdateFailure.Stage stage = APK_STORAGE;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new UpdateFailure(APK_STORAGE, STORAGE);
            stage = APK_DOWNLOAD;
            try (Response response = open(assetUrl(update.tag, APK), true, cancellation, APK_DOWNLOAD)) {
                long length = response.body().contentLength();
                if (length != -1 && length != update.size) throw UpdateFailure.size(APK_DOWNLOAD, update.size, length);
                stage = APK_STORAGE;
                try (FileOutputStream output = new FileOutputStream(partial)) {
                    MessageDigest digest = sha256();
                    byte[] buffer = new byte[32 * 1024];
                    long total = 0;
                    int count;
                    InputStream input = response.body().byteStream();
                    while (true) {
                        stage = APK_DOWNLOAD;
                        count = input.read(buffer);
                        if (count == -1) break;
                        cancellation.check();
                        total += count;
                        if (total > update.size || total > MAX_APK) throw UpdateFailure.size(APK_DOWNLOAD, update.size, total);
                        digest.update(buffer, 0, count);
                        stage = APK_STORAGE;
                        output.write(buffer, 0, count);
                    }
                    requireDigest(update, total, digest.digest());
                    stage = APK_STORAGE;
                    output.getFD().sync();
                }
                stage = APK_DOWNLOAD;
            }
            cancellation.check();
            stage = APK_STORAGE;
            if (!partial.renameTo(target)) throw new UpdateFailure(APK_STORAGE, STORAGE);
            complete = true;
            return target;
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            throw UpdateFailure.at(stage, error);
        } finally {
            if (!complete && partial.exists() && !partial.delete()) partial.deleteOnExit();
        }
    }

    static void verifyFile(File file, Update update, Cancellation cancellation) throws IOException {
        if (update.size <= 0 || update.size > MAX_APK || file.length() != update.size) {
            throw UpdateFailure.size(FILE_VERIFY, update.size, file.length());
        }
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[32 * 1024];
        try (InputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                cancellation.check();
                total += count;
                if (total > update.size) throw UpdateFailure.size(FILE_VERIFY, update.size, total);
                digest.update(buffer, 0, count);
            }
        }
        cancellation.check();
        requireDigest(update, total, digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void requireDigest(Update update, long size, byte[] bytes) throws IOException {
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        if (size != update.size) throw UpdateFailure.size(FILE_VERIFY, update.size, size);
        if (!hex.toString().equals(update.sha256)) throw new UpdateFailure(FILE_VERIFY, HASH);
    }
}
