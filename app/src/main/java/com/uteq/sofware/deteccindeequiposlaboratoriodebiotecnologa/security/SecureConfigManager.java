package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.security;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Guarda de forma cifrada, en el dispositivo, la OpenAI API Key que el usuario introduce
 * manualmente desde {@code ConfiguracionActivity}.
 * <p>
 * Usa {@link EncryptedSharedPreferences} respaldado por una clave maestra del Android
 * Keystore (AES-256-GCM). La clave nunca se guarda en texto plano, nunca se escribe a
 * {@code strings.xml}/{@code BuildConfig}/código fuente y nunca se registra en Logcat.
 * <p>
 * Toda la lógica de almacenamiento vive aquí, no en las Activities.
 */
public class SecureConfigManager {

    private static final String PREFS_FILE_NAME = "secure_config";
    private static final String KEY_OPENAI_API_KEY = "openai_api_key";

    private final SharedPreferences securePrefs;

    public SecureConfigManager(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            securePrefs = EncryptedSharedPreferences.create(
                    context.getApplicationContext(),
                    PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            // No se incluye la causa completa en logs de producción; solo se traduce a una
            // excepción no verificada para que la Activity pueda informar un error genérico.
            throw new IllegalStateException("No se pudo inicializar el almacenamiento seguro de configuración.", e);
        }
    }

    public void saveApiKey(String apiKey) {
        securePrefs.edit().putString(KEY_OPENAI_API_KEY, apiKey).apply();
    }

    public String getApiKey() {
        return securePrefs.getString(KEY_OPENAI_API_KEY, null);
    }

    public boolean hasApiKey() {
        String apiKey = getApiKey();
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    public void deleteApiKey() {
        securePrefs.edit().remove(KEY_OPENAI_API_KEY).apply();
    }
}
