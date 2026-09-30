package io.github.waksana.cockpitdashboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONException;
import org.json.JSONObject;

final class PrivateStore {
    private static final String ALIAS = "cockpit-dashboard-local";
    private final SharedPreferences preferences;
    PrivateStore(Context context) {
        preferences = context.getSharedPreferences("private-state", Context.MODE_PRIVATE);
    }

    private SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }

    JSONObject read() throws GeneralSecurityException, IOException, JSONException {
        String encoded = preferences.getString("payload", null);
        if (encoded == null) return new JSONObject();
        JSONObject envelope = new JSONObject(encoded);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(envelope.getString("data"),
                Base64.NO_WRAP)), StandardCharsets.UTF_8));
    }

    void write(JSONObject value) throws GeneralSecurityException, IOException, JSONException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        JSONObject envelope = new JSONObject()
                .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .put("data", Base64.encodeToString(cipher.doFinal(value.toString()
                        .getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
        if (!preferences.edit().putString("payload", envelope.toString()).commit()) {
            throw new IOException("Unable to persist local state");
        }
    }
}
