package tools.fmc.musiccompanion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets at rest. The watch pairing key is encrypted with AES-GCM under a key that never leaves Android
 * Keystore; only ciphertext is written to the app's private preferences.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val key: SecretKey by lazy {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null) as SecretKey? ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
            }
            .generateKey()
    }

    operator fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(CIPHER_MODE)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, IV_LENGTH))
            String(cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH), Charsets.UTF_8)
        }.getOrNull()
    }

    operator fun set(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(CIPHER_MODE).apply { init(Cipher.ENCRYPT_MODE, key) }
            val raw = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(name, Base64.encodeToString(raw, Base64.NO_WRAP))
                .apply()
        }
    }

    private companion object {
        const val PREFERENCES = "secure"
        const val KEY_ALIAS = "tools.fmc.musiccompanion.keystore.k"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val CIPHER_MODE = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
