package io.github.waksana.cockpitdashboard;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.HttpUrl;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DeviceCredentialsTest {
    private static final String ORIGIN = "https://cockpit.example/";
    private static final HttpUrl URL = HttpUrl.get(ORIGIN + "intent/prompt");

    private static final class Memory implements DeviceCredentials.Storage {
        String json = "{}";
        boolean failWrite;
        boolean publishBeforeFailure;
        @Override public JSONObject read() throws JSONException { return new JSONObject(json); }
        @Override public void write(JSONObject value) throws IOException {
            if (failWrite) {
                if (publishBeforeFailure) json = value.toString();
                throw new IOException("synthetic storage failure");
            }
            json = value.toString();
        }
    }

    private static DeviceOAuth.Tokens tokens(String suffix, long expires) {
        return new DeviceOAuth.Tokens("access-" + suffix, "refresh-" + suffix, expires);
    }

    @Test public void restartRetainsCredentialsAndExactOriginBinding() throws Exception {
        Memory storage = new Memory();
        DeviceCredentials credentials = new DeviceCredentials(storage, () -> 1000);
        credentials.install(ORIGIN, tokens("one", 900));
        DeviceCredentials restored = new DeviceCredentials(storage, () -> 2000);
        assertTrue(restored.present(ORIGIN));
        AtomicInteger calls = new AtomicInteger();
        DeviceCredentials.Refresh forbidden = t -> { calls.incrementAndGet(); throw new IOException(); };
        assertEquals("Bearer access-one", restored.header(URL, forbidden));
        for (String other : new String[]{"http://cockpit.example/", "https://cockpit.example:444/",
                "https://other.example/", "https://api.github.com/", "https://azure.example/"}) {
            assertThrows(HostClient.AuthenticationRequired.class,
                    () -> restored.header(HttpUrl.get(other), forbidden));
        }
        assertEquals(0, calls.get());
    }

    @Test public void refreshMarkerPrecedesNetworkAndNewTokenPersists() throws Exception {
        Memory storage = new Memory();
        AtomicLong clock = new AtomicLong(1000);
        DeviceCredentials credentials = new DeviceCredentials(storage, clock::get);
        credentials.install(ORIGIN, tokens("one", 900));
        clock.set(841000);
        assertEquals("Bearer access-two", credentials.header(URL, old -> {
            assertEquals("refresh-one", old);
            assertTrue(storage.read().getBoolean("refreshing"));
            return tokens("two", 900);
        }));
        assertFalse(storage.read().getBoolean("refreshing"));
        DeviceCredentials restored = new DeviceCredentials(storage, clock::get);
        assertEquals("Bearer access-two", restored.header(URL, t -> { throw new IOException(); }));
    }

    @Test public void crashAndNetworkUncertaintyNeverReplayRefresh() throws Exception {
        Memory storage = new Memory();
        DeviceCredentials credentials = new DeviceCredentials(storage, () -> 1000);
        credentials.install(ORIGIN, tokens("one", 60));
        AtomicInteger calls = new AtomicInteger();
        DeviceCredentials.Refresh uncertain = t -> { calls.incrementAndGet(); throw new IOException("lost response"); };
        assertThrows(HostClient.AuthenticationRequired.class, () -> credentials.header(URL, uncertain));
        DeviceCredentials restarted = new DeviceCredentials(storage, () -> 1000);
        assertThrows(HostClient.AuthenticationRequired.class, () -> restarted.header(URL, uncertain));
        assertEquals(1, calls.get());
        restarted.install(ORIGIN, tokens("new-scan", 900));
        assertEquals("Bearer access-new-scan", restarted.header(URL, uncertain));
    }

    @Test public void failedMarkerWriteNeverSendsAndFailedFinalWriteNeverReplays() throws Exception {
        Memory storage = new Memory();
        DeviceCredentials credentials = new DeviceCredentials(storage, () -> 1000);
        credentials.install(ORIGIN, tokens("one", 60));
        storage.failWrite = true;
        AtomicInteger calls = new AtomicInteger();
        assertThrows(HostClient.AuthenticationRequired.class,
                () -> credentials.header(URL, t -> { calls.incrementAndGet(); return tokens("two", 900); }));
        assertEquals(0, calls.get());
        storage.failWrite = false;
        credentials.install(ORIGIN, tokens("new-attempt", 60));
        assertThrows(HostClient.AuthenticationRequired.class, () -> credentials.header(URL, t -> {
            calls.incrementAndGet();
            storage.failWrite = true;
            return tokens("two", 900);
        }));
        storage.failWrite = false;
        DeviceCredentials restarted = new DeviceCredentials(storage, () -> 1000);
        assertThrows(HostClient.AuthenticationRequired.class,
                () -> restarted.header(URL, t -> { calls.incrementAndGet(); return tokens("three", 900); }));
        assertEquals(1, calls.get());
    }

    @Test public void sharedPreferencesMemoryPublicationBeforeFailedCommitCannotEscapeAcrossRecreation() throws Exception {
        Memory storage = new Memory();
        DeviceCredentials credentials = new DeviceCredentials(storage, () -> 1000);
        credentials.install(ORIGIN, tokens("one", 60));
        assertThrows(HostClient.AuthenticationRequired.class, () -> credentials.header(URL, t -> {
            storage.publishBeforeFailure = true;
            storage.failWrite = true;
            return tokens("two", 900);
        }));
        assertFalse(storage.read().getBoolean("refreshing"));
        assertEquals("access-two", storage.read().getString("access"));
        storage.failWrite = false;
        DeviceCredentials recreated = new DeviceCredentials(storage, () -> 1000);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(HostClient.AuthenticationRequired.class,
                () -> recreated.header(URL, t -> { calls.incrementAndGet(); return tokens("three", 900); }));
        assertEquals(0, calls.get());
        recreated.install(ORIGIN, tokens("explicit-scan", 900));
        assertEquals("Bearer access-explicit-scan", credentials.header(URL, t -> { throw new IOException(); }));
    }

    @Test public void concurrentReadersAndRecreatedActivityShareOneRotation() throws Exception {
        Memory storage = new Memory();
        DeviceCredentials first = new DeviceCredentials(storage, () -> 1000);
        DeviceCredentials second = new DeviceCredentials(storage, () -> 1000);
        first.install(ORIGIN, tokens("one", 60));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        DeviceCredentials.Refresh refresh = t -> {
            calls.incrementAndGet();
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            return tokens("two", 900);
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = workers.submit(() -> first.header(URL, refresh));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<String> b = workers.submit(() -> second.header(URL, refresh));
            release.countDown();
            assertEquals("Bearer access-two", a.get(5, TimeUnit.SECONDS));
            assertEquals("Bearer access-two", b.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test public void logoutDuringRefreshCannotResurrectCredentials() throws Exception {
        Memory storage = new Memory();
        DeviceCredentials credentials = new DeviceCredentials(storage, () -> 1000);
        credentials.install(ORIGIN, tokens("one", 60));
        assertThrows(HostClient.AuthenticationRequired.class, () -> credentials.header(URL, t -> {
            credentials.clear();
            return tokens("two", 900);
        }));
        assertFalse(credentials.present(ORIGIN));
        assertEquals("{}", storage.json);
    }

    @Test public void newlyScannedCredentialCannotBeOverwrittenByLateRefresh() throws Exception {
        Memory storage = new Memory();
        DeviceCredentials credentials = new DeviceCredentials(storage, () -> 1000);
        credentials.install(ORIGIN, tokens("one", 60));
        assertThrows(HostClient.AuthenticationRequired.class, () -> credentials.header(URL, t -> {
            credentials.install(ORIGIN, tokens("new-scan", 900));
            return tokens("old-rotation", 900);
        }));
        assertEquals("Bearer access-new-scan", credentials.header(URL, t -> { throw new IOException(); }));
    }
}
