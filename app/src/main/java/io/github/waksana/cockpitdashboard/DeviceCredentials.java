package io.github.waksana.cockpitdashboard;

import android.content.Context;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import okhttp3.HttpUrl;
import org.json.JSONException;
import org.json.JSONObject;

final class DeviceCredentials {
    interface Storage {
        JSONObject read() throws IOException, JSONException;
        void write(JSONObject value) throws IOException, JSONException;
        default Object identity() { return this; }
    }

    interface Refresh {
        DeviceOAuth.Tokens refresh(String token) throws IOException, JSONException;
    }

    interface Clock { long now(); }

    // Activity recreation must not start a second rotation while the old worker is finishing.
    private static final Object LOCK = new Object();
    private static final Object DEVICE_STORE = new Object();
    private static final Map<Object, Shared> STATES = new WeakHashMap<>();
    private static final class Shared {
        long generation;
        boolean uncertain;
        Flight flight;
    }
    private static final class Flight {
        final String origin, token;
        final long generation;
        final CountDownLatch done = new CountDownLatch(1);
        boolean succeeded;
        Flight(String origin, String token, long generation) {
            this.origin = origin;
            this.token = token;
            this.generation = generation;
        }
    }
    private final Storage storage;
    private final Clock clock;
    private final Shared shared;

    DeviceCredentials(Context context) {
        PrivateStore store = new PrivateStore(context, "device-credentials");
        storage = new Storage() {
            @Override public Object identity() { return DEVICE_STORE; }
            @Override public JSONObject read() throws IOException, JSONException {
                try { return store.read(); }
                catch (GeneralSecurityException error) { throw new IOException("Device storage unavailable", error); }
            }
            @Override public void write(JSONObject value) throws IOException, JSONException {
                try { store.write(value); }
                catch (GeneralSecurityException error) { throw new IOException("Device storage unavailable", error); }
            }
        };
        clock = System::currentTimeMillis;
        shared = shared(storage);
    }

    DeviceCredentials(Storage storage, Clock clock) {
        this.storage = storage;
        this.clock = clock;
        shared = shared(storage);
    }

    private static Shared shared(Storage storage) {
        synchronized (LOCK) {
            Shared state = STATES.get(storage.identity());
            if (state == null) {
                state = new Shared();
                STATES.put(storage.identity(), state);
            }
            return state;
        }
    }

    boolean present(String address) throws IOException, JSONException {
        synchronized (LOCK) {
            JSONObject saved = storage.read();
            return !saved.optString("origin").isEmpty()
                    && saved.getString("origin").equals(HostClient.normalizeAddress(address));
        }
    }

    void install(String address, DeviceOAuth.Tokens tokens) throws IOException, JSONException {
        synchronized (LOCK) {
            shared.generation++;
            shared.uncertain = true;
            save(HostClient.normalizeAddress(address), tokens);
            shared.uncertain = false;
        }
    }

    private void save(String origin, DeviceOAuth.Tokens tokens) throws IOException, JSONException {
        long now = clock.now();
        storage.write(new JSONObject().put("origin", origin).put("access", tokens.accessToken)
                .put("refresh", tokens.refreshToken).put("expires", now + tokens.expiresIn * 1000L)
                .put("refreshing", false).put("revoking", false));
    }

    String header(HttpUrl destination, Refresh refresh) throws HostClient.AuthenticationRequired {
        try {
            while (true) {
                Flight current;
                boolean owner = false;
                synchronized (LOCK) {
                    JSONObject saved = storage.read();
                    String origin = saved.getString("origin");
                    if (shared.uncertain || saved.optBoolean("revoking")
                            || !sameOrigin(HttpUrl.get(origin), destination)) {
                        throw new IOException("Credential origin mismatch or unavailable");
                    }
                    if (saved.getBoolean("refreshing")) {
                        if (shared.flight == null || shared.flight.generation != shared.generation
                                || !shared.flight.origin.equals(origin)
                                || !shared.flight.token.equals(saved.getString("refresh"))) {
                            throw new IOException("Uncertain rotation requires login");
                        }
                        current = shared.flight;
                    } else if (saved.getLong("expires") - clock.now() > 60_000L) {
                        return "Bearer " + saved.getString("access");
                    } else {
                        saved.put("refreshing", true);
                        try { storage.write(saved); }
                        catch (IOException | JSONException error) {
                            shared.uncertain = true;
                            throw error;
                        }
                        current = new Flight(origin, saved.getString("refresh"), shared.generation);
                        shared.flight = current;
                        owner = true;
                    }
                }
                if (!owner) {
                    current.done.await();
                    if (!current.succeeded) throw new IOException("Rotation did not finish reliably");
                    continue;
                }
                try {
                    DeviceOAuth.Tokens next = refresh.refresh(current.token);
                    synchronized (LOCK) {
                        JSONObject saved = storage.read();
                        if (!current.origin.equals(saved.optString("origin"))
                                || !current.token.equals(saved.optString("refresh"))
                                || !saved.optBoolean("refreshing") || saved.optBoolean("revoking")
                                || shared.uncertain || current.generation != shared.generation
                                || next.refreshToken.equals(current.token)) {
                            throw new IOException("Credentials changed during rotation");
                        }
                        try { save(current.origin, next); }
                        catch (IOException | JSONException error) {
                            shared.uncertain = true;
                            throw error;
                        }
                        current.succeeded = true;
                        return "Bearer " + next.accessToken;
                    }
                } finally {
                    synchronized (LOCK) {
                        if (shared.flight == current) shared.flight = null;
                        current.done.countDown();
                    }
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new HostClient.AuthenticationRequired("设备续期等待已中断；未发送请求，请重新连接");
        } catch (IOException | JSONException | IllegalArgumentException error) {
            throw new HostClient.AuthenticationRequired("设备续期未确认；请重新扫码，不会重放刷新或聊天请求");
        }
    }

    boolean revoke(String address, DeviceOAuth api) throws IOException, JSONException {
        String token;
        long generation;
        synchronized (LOCK) {
            generation = ++shared.generation;
            shared.uncertain = true;
            JSONObject saved = storage.read();
            if (!HostClient.normalizeAddress(address).equals(saved.getString("origin"))) {
                throw new IOException("Credential origin mismatch");
            }
            saved.put("refreshing", true).put("revoking", true);
            storage.write(saved);
            token = saved.getString("refresh");
        }
        api.revoke(token);
        synchronized (LOCK) {
            JSONObject saved = storage.read();
            if (generation == shared.generation && HostClient.normalizeAddress(address).equals(saved.optString("origin"))
                    && token.equals(saved.optString("refresh")) && saved.optBoolean("revoking")) {
                storage.write(new JSONObject());
                return true;
            }
            return false;
        }
    }

    void clear() throws IOException, JSONException {
        synchronized (LOCK) {
            shared.generation++;
            shared.uncertain = true;
            storage.write(new JSONObject());
        }
    }

    static boolean sameOrigin(HttpUrl first, HttpUrl second) {
        return first.isHttps() && second.isHttps() && first.host().equals(second.host())
                && first.port() == second.port();
    }
}
