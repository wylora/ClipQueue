package com.clipqueue.app.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TikTokTokens(
    val accessToken: String,
    val refreshToken: String,
    val openId: String,
    val scope: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
    val displayName: String? = null
) {
    val isAccessValid get() = System.currentTimeMillis() < accessExpiresAt - 5 * 60_000
    val isRefreshValid get() = System.currentTimeMillis() < refreshExpiresAt - 60_000
    fun hasScope(s: String) = scope.split(',', ' ').any { it.trim() == s }
}

/**
 * Хранит OAuth-токены TikTok, зашифрованные ключом AES-GCM из Android Keystore.
 * Пароли от аккаунта TikTok приложение не получает и не хранит никогда.
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("tiktok_tokens", Context.MODE_PRIVATE)
    private val alias = "clipqueue_tokens_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun save(t: TikTokTokens) {
        val json = JSONObject()
            .put("a", t.accessToken).put("r", t.refreshToken).put("o", t.openId).put("s", t.scope)
            .put("ae", t.accessExpiresAt).put("re", t.refreshExpiresAt).put("n", t.displayName ?: "")
            .toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = cipher.doFinal(json.toByteArray())
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(enc, Base64.NO_WRAP))
            .apply()
    }

    fun load(): TikTokTokens? = try {
        val iv = Base64.decode(prefs.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val data = Base64.decode(prefs.getString("data", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        val j = JSONObject(String(cipher.doFinal(data)))
        TikTokTokens(
            j.getString("a"), j.getString("r"), j.getString("o"), j.getString("s"),
            j.getLong("ae"), j.getLong("re"), j.optString("n").ifBlank { null }
        )
    } catch (_: Exception) {
        null
    }

    fun clear() = prefs.edit().clear().apply()

    fun saveState(state: String) = prefs.edit().putString("oauth_state", state).apply()
    fun consumeState(): String? = prefs.getString("oauth_state", null).also { prefs.edit().remove("oauth_state").apply() }
}
