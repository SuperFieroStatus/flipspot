package dev.mike.flipspot.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Tokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
    val scope: String?,
) {
    fun isExpired(skewMs: Long = 60_000): Boolean = System.currentTimeMillis() + skewMs >= expiresAtMs
}

/**
 * Access + refresh token, AES-GCM encrypted with a non-exportable Android Keystore key and
 * kept in a private SharedPreferences file.
 *
 * Does the same job as EncryptedSharedPreferences without androidx.security-crypto (deprecated
 * upstream) and its Tink dependency, which would add ~1 MB to the APK.
 */
object TokenStore {
    private const val TAG = "FlipSpot"
    private const val PREFS = "tokens"
    private const val KEY_BLOB = "blob"
    private const val KEY_ALIAS = "flipspot_tokens"
    private const val GCM_IV_BYTES = 12

    @Synchronized
    fun load(context: Context): Tokens? {
        val blob = prefs(context).getString(KEY_BLOB, null) ?: return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, GCM_IV_BYTES))
            val json = JSONObject(String(cipher.doFinal(raw, GCM_IV_BYTES, raw.size - GCM_IV_BYTES)))
            Tokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").ifEmpty { null },
                expiresAtMs = json.getLong("expires_at_ms"),
                scope = json.optString("scope").ifEmpty { null },
            )
        } catch (e: Exception) {
            Log.w(TAG, "TokenStore: stored tokens unreadable, clearing", e)
            clear(context)
            null
        }
    }

    @Synchronized
    fun save(context: Context, tokens: Tokens) {
        val json = JSONObject()
            .put("access_token", tokens.accessToken)
            .put("refresh_token", tokens.refreshToken ?: "")
            .put("expires_at_ms", tokens.expiresAtMs)
            .put("scope", tokens.scope ?: "")
            .toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == GCM_IV_BYTES)
        val out = iv + cipher.doFinal(json.toByteArray())
        prefs(context).edit().putString(KEY_BLOB, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_BLOB).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
