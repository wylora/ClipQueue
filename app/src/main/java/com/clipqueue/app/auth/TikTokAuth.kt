package com.clipqueue.app.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.clipqueue.app.data.SettingsRepository
import com.clipqueue.app.engine.AuthException
import com.clipqueue.app.engine.TokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom

/**
 * Официальный OAuth 2.0 TikTok Login Kit.
 *
 * Схема (client_secret никогда не попадает в APK):
 *  1. Приложение открывает страницу авторизации TikTok в Custom Tab.
 *  2. TikTok перенаправляет на ваш HTTPS redirect_uri (бэкенд из папки backend/).
 *  3. Бэкенд перенаправляет на clipqueue://oauth?code=...&state=...
 *  4. Приложение отправляет code на бэкенд, тот обменивает его на токены с client_secret.
 */
class TikTokAuth(
    private val context: Context,
    private val settings: SettingsRepository,
    private val store: TokenStore,
    private val http: OkHttpClient
) : TokenProvider {
    companion object {
        const val AUTHORIZE_URL = "https://www.tiktok.com/v2/auth/authorize/"
        const val SCOPES = "user.info.basic,video.upload,video.publish"
    }

    private val refreshLock = Mutex()

    fun tokens(): TikTokTokens? = store.load()

    override fun isConnected(): Boolean = store.load()?.isRefreshValid == true
    override fun hasScope(scope: String): Boolean = store.load()?.hasScope(scope) == true
    override suspend fun accessToken(): String = validAccessToken()

    suspend fun startLogin(): Result<Unit> = runCatching {
        val s = settings.current()
        if (s.clientKey.isBlank() || s.backendUrl.isBlank())
            throw AuthException("Укажите Client Key и адрес бэкенда в настройках")
        val state = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        store.saveState(state)
        val url = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_key", s.clientKey)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", "${s.backendUrl}/callback")
            .appendQueryParameter("state", state)
            .build()
        CustomTabsIntent.Builder().setShowTitle(true).build().apply {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }.launchUrl(context, url)
    }

    /** Обрабатывает clipqueue://oauth?code=...&state=... */
    suspend fun handleRedirect(uri: Uri): Result<TikTokTokens> = runCatching {
        uri.getQueryParameter("error")?.let { throw AuthException("TikTok отклонил авторизацию: $it") }
        val code = uri.getQueryParameter("code") ?: throw AuthException("В ответе нет кода авторизации")
        val expected = store.consumeState()
        if (expected == null || expected != uri.getQueryParameter("state"))
            throw AuthException("Проверка state не пройдена. Начните вход заново")
        val s = settings.current()
        val json = postJson("${s.backendUrl}/token", JSONObject().put("code", code).put("redirect_uri", "${s.backendUrl}/callback"))
        val tokens = parseTokens(json)
        store.save(tokens)
        fetchDisplayName(tokens)?.let { store.save(tokens.copy(displayName = it)) }
        store.load() ?: tokens
    }

    /** Возвращает действующий access token, при необходимости обновляя его. */
    suspend fun validAccessToken(): String = refreshLock.withLock {
        val t = store.load() ?: throw AuthException("Аккаунт TikTok не подключён")
        if (t.isAccessValid) return t.accessToken
        if (!t.isRefreshValid) throw AuthException("Срок входа истёк. Подключите TikTok заново")
        val s = settings.current()
        val json = postJson("${s.backendUrl}/refresh", JSONObject().put("refresh_token", t.refreshToken))
        val fresh = parseTokens(json).copy(displayName = t.displayName)
        store.save(fresh)
        fresh.accessToken
    }

    /**
     * Отзывает доступ приложения к аккаунту TikTok (через бэкенд, т.к. нужен client_secret)
     * и удаляет токены с телефона. Токены удаляются даже если отзыв не удался.
     */
    suspend fun revokeAndLogout(): Result<Unit> {
        val t = store.load()
        val result = if (t == null) Result.success(Unit) else runCatching {
            val s = settings.current()
            if (s.backendUrl.isBlank()) throw AuthException("Адрес бэкенда не задан — доступ удалён только с телефона")
            postJson("${s.backendUrl}/revoke", JSONObject().put("token", t.accessToken))
            Unit
        }
        store.clear()
        return result
    }

    private fun parseTokens(j: JSONObject): TikTokTokens {
        if (j.has("error") && j.optString("error").isNotBlank())
            throw AuthException("Ошибка токена: ${j.optString("error_description", j.optString("error"))}")
        val now = System.currentTimeMillis()
        return TikTokTokens(
            accessToken = j.getString("access_token"),
            refreshToken = j.getString("refresh_token"),
            openId = j.optString("open_id"),
            scope = j.optString("scope"),
            accessExpiresAt = now + j.optLong("expires_in", 86400) * 1000,
            refreshExpiresAt = now + j.optLong("refresh_expires_in", 31536000) * 1000
        )
    }

    private suspend fun fetchDisplayName(t: TikTokTokens): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://open.tiktokapis.com/v2/user/info/?fields=display_name")
                .header("Authorization", "Bearer ${t.accessToken}")
                .get().build()
            http.newCall(req).execute().use { r ->
                JSONObject(r.body?.string().orEmpty()).optJSONObject("data")
                    ?.optJSONObject("user")?.optString("display_name")?.ifBlank { null }
            }
        }.getOrNull()
    }

    private suspend fun postJson(url: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Бэкенд ответил ${r.code}: ${text.take(200)}")
            JSONObject(text)
        }
    }
}
