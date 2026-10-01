package io.github.waksana.cockpitdashboard;

import android.app.Activity;
import android.graphics.Bitmap;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DeviceLoginTest {
    private MockWebServer server;
    private OkHttpClient http;
    private final AtomicInteger successes = new AtomicInteger(), failures = new AtomicInteger();

    @Before public void setup() throws Exception {
        HeldCertificate cert = new HeldCertificate.Builder().commonName("localhost")
                .addSubjectAlternativeName("localhost").build();
        HandshakeCertificates tls = new HandshakeCertificates.Builder().heldCertificate(cert).build();
        HandshakeCertificates trust = new HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate()).build();
        server = new MockWebServer();
        server.useHttps(tls.sslSocketFactory(), false);
        server.start();
        http = new OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager())
                .readTimeout(1, TimeUnit.SECONDS).build();
    }

    @After public void close() throws Exception { server.shutdown(); }

    private MockResponse grant() {
        return new MockResponse().setBody("{\"device_code\":\"synthetic-device-secret\","
                + "\"user_code\":\"ABCD-EFGH\",\"verification_uri\":\"https://auth.example/device\","
                + "\"verification_uri_complete\":\"https://auth.example/device?user_code=ABCD-EFGH\","
                + "\"expires_in\":300,\"interval\":1}");
    }

    private DeviceLogin login(Activity activity) {
        return new DeviceLogin(activity, new DeviceOAuth(server.url("/"), http), new DeviceLogin.Listener() {
            @Override public void complete(DeviceOAuth.Tokens tokens) { successes.incrementAndGet(); }
            @Override public void failed(String message) { failures.incrementAndGet(); }
        });
    }

    private void waitForField(DeviceLogin login, String name) throws Exception {
        Field field = DeviceLogin.class.getDeclaredField(name);
        field.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (field.get(login) == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
            ShadowLooper.idleMainLooper();
        }
        assertNotNull(field.get(login));
    }

    @Test public void localQrRoundTripsExactlyWithoutDeviceCode() throws Exception {
        String uri = "https://auth.example/device?user_code=ABCD-EFGH";
        Bitmap bitmap = DeviceLogin.qr(uri);
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        BinaryBitmap image = new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(), pixels)));
        assertEquals(uri, new MultiFormatReader().decode(image).getText());
        assertEquals(0, server.getRequestCount());
    }

    @Test public void backgroundAbandonsPendingAuthorizationAndIgnoresLateResponse() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity activity = controller.get();
            DeviceLogin login = login(activity);
            Field field = MainActivity.class.getDeclaredField("deviceLogin");
            field.setAccessible(true);
            field.set(activity, login);
            server.enqueue(grant().setBodyDelay(200, TimeUnit.MILLISECONDS));
            login.start();
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
            activity.onPause();
            Thread.sleep(300);
            ShadowLooper.idleMainLooper();
            assertEquals(1, failures.get());
            assertEquals(0, successes.get());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void cancelDuringTokenRequestNeverAcceptsLateTokensOrRestarts() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).create().start().resume()) {
            DeviceLogin login = login(controller.get());
            server.enqueue(grant());
            login.start();
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
            waitForField(login, "polling");
            server.enqueue(new MockResponse().setBody("{\"access_token\":\"synthetic-access\","
                    + "\"refresh_token\":\"synthetic-refresh\",\"token_type\":\"Bearer\","
                    + "\"expires_in\":900,\"scope\":\"host-access\"}").setBodyDelay(200, TimeUnit.MILLISECONDS));
            ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
            login.cancel("cancel");
            login.cancel("cancel again");
            Thread.sleep(300);
            ShadowLooper.idleMainLooper(20, TimeUnit.SECONDS);
            assertEquals(1, failures.get());
            assertEquals(0, successes.get());
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void slowAuthorizationResponseStillWaitsFullIntervalBeforeFirstPoll() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).create().start().resume()) {
            DeviceLogin login = login(controller.get());
            server.enqueue(grant().setBodyDelay(200, TimeUnit.MILLISECONDS));
            login.start();
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
            ShadowLooper.idleMainLooper(10, TimeUnit.SECONDS);
            waitForField(login, "polling");
            assertEquals(1, server.getRequestCount());
            login.cancel("cancel");
            ShadowLooper.idleMainLooper(20, TimeUnit.SECONDS);
            assertEquals(1, server.getRequestCount());
            assertEquals(0, successes.get());
        }
    }
}
