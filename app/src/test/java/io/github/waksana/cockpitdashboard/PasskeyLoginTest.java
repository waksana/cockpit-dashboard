package io.github.waksana.cockpitdashboard;

import android.app.Activity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException;
import androidx.credentials.exceptions.NoCredentialException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PasskeyLoginTest {
    @Test public void providerFailuresAreDistinctFromCancellation() {
        String cancelled = PasskeyLogin.credentialError(new GetCredentialCancellationException());
        String unavailable = PasskeyLogin.credentialError(new GetCredentialProviderConfigurationException());
        String missing = PasskeyLogin.credentialError(new NoCredentialException());
        assertNotEquals(cancelled, unavailable);
        assertNotEquals(missing, unavailable);
        assertTrue(cancelled.contains("取消"));
        assertTrue(unavailable.contains("凭据提供器"));
        assertTrue(missing.contains("域名关联"));
    }

    @Test @Config(sdk = 27)
    public void unsupportedAndroidFailsBeforeNetwork() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).create()) {
            AtomicReference<String> failure = new AtomicReference<>();
            PasskeyLogin login = new PasskeyLogin(controller.get(), new GateApi("https://example.invalid/"),
                    new PasskeyLogin.Listener() {
                        @Override public void complete(GateSession session) { fail("Unexpected login"); }
                        @Override public void failed(String message) { failure.set(message); }
                    });
            login.start();
            assertNotNull(failure.get());
            assertTrue(failure.get().contains("API 28"));
            login.cancel();
        }

    }

    @Test public void optionsArrivingInBackgroundDoNotLaunchProviderAndCanBeCancelled() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).create()) {
            PasskeyLogin login = new PasskeyLogin(controller.get(), new GateApi("https://example.invalid/"),
                    new PasskeyLogin.Listener() {
                        @Override public void complete(GateSession session) { fail("Unexpected login"); }
                        @Override public void failed(String message) { fail("Provider must not be invoked in background"); }
                    });
            login.pause();
            Method request = PasskeyLogin.class.getDeclaredMethod("requestCredential", JSONObject.class);
            request.setAccessible(true);
            JSONObject options = new JSONObject();
            request.invoke(login, options);
            Field pending = PasskeyLogin.class.getDeclaredField("pendingOptions");
            pending.setAccessible(true);
            assertSame(options, pending.get(login));
            login.cancel();
            login.resume();
            assertNull(pending.get(login));
            request.invoke(login, options);
            assertNull(pending.get(login));
        }
    }
}
