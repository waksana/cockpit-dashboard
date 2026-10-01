package io.github.waksana.cockpitdashboard;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UpdateClientTest {
    static final String TAG = "v1.2.3";
    static final byte[] APK_BYTES = "synthetic signed APK bytes".getBytes(StandardCharsets.UTF_8);
    private final List<Request> requests = new ArrayList<>();
    private final Queue<Response.Builder> responses = new ArrayDeque<>();
    private final UpdateClient client = new UpdateClient(new OkHttpClient.Builder().addInterceptor(chain -> {
        requests.add(chain.request());
        Response.Builder response = responses.poll();
        if (response == null) throw new IOException("No synthetic response");
        return response.request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture").build();
    }).build());

    @After public void cleanup() {
        File directory = new File(RuntimeEnvironment.getApplication().getFilesDir(), "updates");
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        directory.delete();
    }

    static String digest(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        }
        return text.toString();
    }

    static JSONObject manifest() throws Exception {
        return new JSONObject().put("versionCode", 10203).put("versionName", "1.2.3")
                .put("sha256", digest(APK_BYTES)).put("size", APK_BYTES.length).put("apk", UpdateClient.APK);
    }

    static byte[] bytes(JSONObject json) {
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    static JSONObject release() throws Exception {
        JSONArray assets = new JSONArray();
        for (String name : new String[]{"update.json", UpdateClient.APK}) {
            assets.put(new JSONObject().put("name", name)
                    .put("browser_download_url", UpdateClient.DOWNLOAD + TAG + "/" + name));
        }
        return new JSONObject().put("tag_name", TAG).put("draft", false).put("prerelease", false).put("assets", assets);
    }

    private void respond(byte[] body) {
        responses.add(new Response.Builder().code(200).body(ResponseBody.create(body, MediaType.get("application/json"))));
    }

    private void redirect(String target) {
        responses.add(new Response.Builder().code(302).header("Location", target)
                .body(ResponseBody.create(new byte[0], null)));
    }

    @Test public void checksOnlyStableCanonicalAssetsWithoutCredentialsAndComparesIntegers() throws Exception {
        for (long installed : new long[]{10202, 10203, 10204, 1L << 33}) {
            respond(bytes(release()));
            respond(bytes(manifest()));
            UpdateClient.Update result = client.check(installed, new UpdateClient.Cancellation());
            assertEquals(installed < 10203, result != null);
        }
        assertEquals(8, requests.size());
        for (int i = 0; i < requests.size(); i++) {
            Request request = requests.get(i);
            assertEquals(i % 2 == 0 ? UpdateClient.LATEST : UpdateClient.DOWNLOAD + TAG + "/update.json",
                    request.url().toString());
            assertEquals("GET", request.method());
            assertNull(request.header("Cookie"));
            assertNull(request.header("Authorization"));
        }
    }

    @Test public void rejectsInvalidManifestFieldsAndOversizedBodies() throws Exception {
        Object[][] invalid = {
                {"versionCode", 0}, {"versionCode", -1}, {"versionCode", 1.5},
                {"versionCode", "10203"}, {"versionCode", 2147483648L}, {"versionCode", JSONObject.NULL},
                {"versionName", "9.9.9"}, {"versionName", true}, {"apk", "../cockpit-dashboard.apk"},
                {"apk", "other.apk"}, {"sha256", "a".repeat(63)}, {"sha256", "A".repeat(64)},
                {"sha256", "z".repeat(64)}, {"size", 0}, {"size", -1}, {"size", "25"},
                {"size", UpdateClient.MAX_APK + 1}, {"size", 25.5}
        };
        for (Object[] change : invalid) {
            byte[] body = bytes(manifest().put((String) change[0], change[1]));
            assertThrows(change[0].toString(), IOException.class, () -> UpdateClient.parseManifest(body, TAG));
        }
        for (String key : new String[]{"versionCode", "versionName", "sha256", "size", "apk"}) {
            JSONObject json = manifest();
            json.remove(key);
            assertThrows(IOException.class, () -> UpdateClient.parseManifest(bytes(json), TAG));
        }
        assertThrows(IOException.class, () -> UpdateClient.parseManifest(new byte[UpdateClient.MAX_MANIFEST + 1], TAG));
        assertThrows(IOException.class, () -> UpdateClient.parseManifest(bytes(manifest()), "v1.2.3-beta"));
    }

    @Test public void rejectsDraftPrereleaseMissingDuplicateAndForeignAssetsBeforeFetchingThem() throws Exception {
        List<JSONObject> invalid = new ArrayList<>();
        invalid.add(release().put("draft", true));
        invalid.add(release().put("prerelease", true));
        invalid.add(release().put("draft", "false"));
        invalid.add(release().put("tag_name", "v1.2.3/../../another"));
        invalid.add(release().put("assets", new JSONArray()));
        JSONObject duplicate = release();
        duplicate.getJSONArray("assets").put(duplicate.getJSONArray("assets").getJSONObject(0));
        invalid.add(duplicate);
        JSONObject foreign = release();
        foreign.getJSONArray("assets").getJSONObject(1).put("browser_download_url",
                "https://github.com/other/repo/releases/download/v1.2.3/cockpit-dashboard.apk");
        invalid.add(foreign);
        for (JSONObject body : invalid) {
            respond(bytes(body));
            assertThrows(IOException.class, () -> client.check(1, new UpdateClient.Cancellation()));
        }
        assertEquals(invalid.size(), requests.size());
    }

    @Test public void exactAssetUrlRejectsQueryUserInfoFragmentEncodingAndPorts() throws Exception {
        String canonical = UpdateClient.DOWNLOAD + TAG + "/" + UpdateClient.APK;
        UpdateClient.validateAssetUrl(canonical, TAG, UpdateClient.APK);
        for (String url : new String[]{
                canonical + "?token=unsafe", canonical + "#fragment", canonical.replace("https:", "http:"),
                canonical.replace("github.com", "github.com.evil.example"),
                canonical.replace("github.com", "user@github.com"),
                canonical.replace("github.com", "github.com:444"),
                canonical.replace("/v1.2.3/", "/%761.2.3/"),
                canonical.replace("cockpit-dashboard.apk", "../cockpit-dashboard.apk")
        }) assertThrows(url, IOException.class, () -> UpdateClient.validateAssetUrl(url, TAG, UpdateClient.APK));
    }

    @Test public void onlyExactHttpsGitHubCdnHostsMayReceiveRedirects() {
        HttpUrl original = HttpUrl.get(UpdateClient.DOWNLOAD + TAG + "/update.json");
        for (String host : new String[]{"release-assets.githubusercontent.com", "objects.githubusercontent.com",
                "github-releases.githubusercontent.com"}) {
            assertTrue(UpdateClient.allowedRedirect(HttpUrl.get("https://" + host + "/asset?signature=synthetic"), original));
        }
        for (String url : new String[]{
                "http://release-assets.githubusercontent.com/file",
                "https://release-assets.githubusercontent.com.evil.example/file",
                "https://user:password@release-assets.githubusercontent.com/file",
                "https://release-assets.githubusercontent.com:444/file",
                "https://release-assets.githubusercontent.com/file#fragment",
                "https://raw.githubusercontent.com/file", "https://api.github.com/file",
                "https://github.com/other/repo/releases/download/v1.2.3/update.json",
                original + "?unexpected=true"
        }) assertFalse(url, UpdateClient.allowedRedirect(HttpUrl.get(url), original));
    }

    @Test public void redirectsAreBoundedAndApiNeverRedirects() throws Exception {
        redirect("https://release-assets.githubusercontent.com/file");
        assertThrows(IOException.class, () -> client.check(1, new UpdateClient.Cancellation()));
        assertEquals(1, requests.size());
        respond(bytes(release()));
        for (int i = 0; i < 6; i++) redirect("https://release-assets.githubusercontent.com/file-" + i);
        assertThrows(IOException.class, () -> client.check(1, new UpdateClient.Cancellation()));
        assertEquals(8, requests.size());
    }

    @Test public void permitsManifestCdnRedirectAndRejectsForeignRedirect() throws Exception {
        respond(bytes(release()));
        redirect("https://release-assets.githubusercontent.com/manifest?signature=synthetic");
        respond(bytes(manifest()));
        assertNotNull(client.check(1, new UpdateClient.Cancellation()));
        respond(bytes(release()));
        redirect("https://evil.example/manifest");
        assertThrows(IOException.class, () -> client.check(1, new UpdateClient.Cancellation()));
        assertEquals(5, requests.size());
    }

    @Test public void boundedReadsAndCancellationRejectBeforeUse() throws Exception {
        assertArrayEquals(new byte[10], UpdateClient.boundedRead(new ByteArrayInputStream(new byte[10]), 10,
                new UpdateClient.Cancellation()));
        assertThrows(IOException.class, () -> UpdateClient.boundedRead(new ByteArrayInputStream(new byte[11]), 10,
                new UpdateClient.Cancellation()));
        UpdateClient.Cancellation cancellation = new UpdateClient.Cancellation();
        cancellation.cancel();
        assertThrows(IOException.class, () -> client.check(1, cancellation));
        assertTrue(requests.isEmpty());
        respond(new byte[UpdateClient.MAX_API + 1]);
        assertThrows(IOException.class, () -> client.check(1, new UpdateClient.Cancellation()));
    }

    @Test public void downloadVerifiesSizeDigestAndPublishesOnlyFixedPrivateFilename() throws Exception {
        UpdateClient.Update update = UpdateClient.parseManifest(bytes(manifest()), TAG);
        respond(APK_BYTES);
        File files = RuntimeEnvironment.getApplication().getFilesDir();
        File apk = client.download(update, files, new UpdateClient.Cancellation());
        assertEquals(new File(files, "updates/" + UpdateClient.APK), apk);
        assertFalse(new File(files, "updates/" + UpdateClient.APK + ".part").exists());
        UpdateClient.verifyFile(apk, update, new UpdateClient.Cancellation());
        byte[] tampered = APK_BYTES.clone();
        tampered[0] ^= 1;
        Files.write(apk.toPath(), tampered);
        assertThrows(IOException.class, () -> UpdateClient.verifyFile(apk, update, new UpdateClient.Cancellation()));
        for (byte[] body : new byte[][]{tampered, new byte[1], new byte[APK_BYTES.length + 1]}) {
            respond(body);
            assertThrows(IOException.class, () -> client.download(update, files, new UpdateClient.Cancellation()));
            assertFalse(new File(files, "updates/" + UpdateClient.APK + ".part").exists());
        }
    }
}
