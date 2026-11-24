package nl.weegdagboek.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Bewaart de Nextcloud-gegevens. Het app-wachtwoord wordt versleuteld met een AES-sleutel
 * die in de Android Keystore leeft (hardware-beveiligd waar de telefoon dat ondersteunt)
 * en die het toestel nooit kan verlaten. Op schijf staat alleen de versleutelde versie.
 */
final class SecureStore {

    private static final String ALIAS = "weegdagboek_nextcloud";
    private static final String PREFS = "nextcloud";
    private final SharedPreferences prefs;

    static final class Account {
        final String server, userId, loginName, appPassword;
        Account(String server, String userId, String loginName, String appPassword) {
            this.server = server; this.userId = userId; this.loginName = loginName; this.appPassword = appPassword;
        }
    }

    SecureStore(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void save(Account a) throws Exception {
        prefs.edit()
            .putString("server", a.server)
            .putString("userId", a.userId)
            .putString("loginName", a.loginName)
            .putString("pass", encrypt(a.appPassword))
            .apply();
    }

    /** null als er niets is opgeslagen of de sleutel niet meer past (bijv. na terugzetten op een ander toestel). */
    synchronized Account load() {
        String server = prefs.getString("server", null);
        String enc = prefs.getString("pass", null);
        if (server == null || enc == null) return null;
        try {
            return new Account(server, prefs.getString("userId", ""), prefs.getString("loginName", ""), decrypt(enc));
        } catch (Exception e) {
            clear();
            return null;
        }
    }

    synchronized void clear() {
        prefs.edit().clear().apply();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
        } catch (Exception ignored) { }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try { return generate(true); } catch (Exception ignored) { /* geen StrongBox-chip: gewone TEE */ }
        }
        return generate(false);
    }

    private static SecretKey generate(boolean strongBox) throws Exception {
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256);
        if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(b.build());
        return kg.generateKey();
    }

    private static String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    private static String decrypt(String stored) throws Exception {
        String[] parts = stored.split(":", 2);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
