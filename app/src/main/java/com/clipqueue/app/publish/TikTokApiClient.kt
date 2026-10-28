package com.clipqueue.app.publish

import android.content.ContentResolver
import android.net.Uri
import com.clipqueue.app.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream


/**
 * Клиент официального Content Posting API (open.tiktokapis.com/v2/post/publish/...).
 * Видео передаётся методом FILE_UPLOAD частями, поток читается напрямую из ContentResolver.
 */
class TikTokApiClient(private val http: OkHttpClient, private val resolver: ContentResolver) : PostingApi {
    private val base = "https://open.tiktokapis.com/v2/post/publish"
    private val jsonType = "application/json; charset=UTF-8".toMediaType()

    // Загрузка не должна переотправляться OkHttp: поток одноразовый.
    private val uploadClient = http.newBuilder()
        .retryOnConnectionFailure(false)
        .writeTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
        .readTimeout(2, java.util.concurrent.TimeUnit.MINUTES)
        .build()

    override suspend fun creatorInfo(token: String): CreatorInfo {
        val d = post("$base/creator_info/query/", token, JSONObject())
        val opts = d.optJSONArray("privacy_level_options")
        return CreatorInfo(
            nickname = d.optString("creator_nickname"),
            privacyOptions = (0 until (opts?.length() ?: 0)).map { opts!!.getString(it) },
            maxDurationSec = d.optInt("max_video_post_duration_sec", 0),
            commentDisabled = d.optBoolean("comment_disabled"),
            duetDisabled = d.optBoolean("duet_disabled"),
            stitchDisabled = d.optBoolean("stitch_disabled")
        )
    }

    /** Режим Direct Post: публикация сразу в профиль. */
    override suspend fun initDirect(token: String, caption: String, privacy: String, size: Long, creator: CreatorInfo): InitResult {
        val body = JSONObject()
            .put("post_info", JSONObject()
                .put("title", caption)
                .put("privacy_level", privacy)
                .put("disable_comment", creator.commentDisabled)
                .put("disable_duet", creator.duetDisabled)
                .put("disable_stitch", creator.stitchDisabled))
            .put("source_info", sourceInfo(size))
        return parseInit(post("$base/video/init/", token, body))
    }

    /** Режим Upload: видео попадает в черновики (inbox) TikTok, публикует пользователь. */
    override suspend fun initInbox(token: String, size: Long): InitResult {
        val body = JSONObject().put("source_info", sourceInfo(size))
        return parseInit(post("$base/inbox/video/init/", token, body))
    }

    override suspend fun fetchStatus(token: String, publishId: String): PublishStatus {
        val d = post("$base/status/fetch/", token, JSONObject().put("publish_id", publishId))
        val ids = d.optJSONArray("publicaly_available_post_id")
        return PublishStatus(
            status = d.optString("status"),
            failReason = d.optString("fail_reason").ifBlank { null },
            postIds = (0 until (ids?.length() ?: 0)).map { ids!!.get(it).toString() }
        )
    }

    /**
     * Загрузка частями по правилам TikTok: файл до 64 МБ — одним куском,
     * больше — кусками по 10 МБ, остаток добавляется к последнему куску.
     */
    override suspend fun upload(
        videoUri: String, size: Long, mime: String, uploadUrl: String,
        onProgress: suspend (Float) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val uri = Uri.parse(videoUri)
        val plan = ChunkPlan.of(size)
        val stream = resolver.openInputStream(uri) ?: throw IOException("Не удалось открыть видео")
        stream.use { input ->
            var offset = 0L
            for (i in 0 until plan.count) {
                coroutineContext.ensureActive()
                val len = if (i == plan.count - 1) size - offset else plan.chunkSize
                val req = Request.Builder().url(uploadUrl)
                    .put(StreamSliceBody(input, len, mime.toMediaType()))
                    .header("Content-Range", "bytes $offset-${offset + len - 1}/$size")
                    .build()
                uploadClient.newCall(req).execute().use { r ->
                    if (r.code !in listOf(200, 201, 206))
                        throw TikTokApiException("upload_${r.code}", "Сервер TikTok отклонил часть видео (${r.code})", r.code >= 500)
                }
                offset += len
                onProgress(offset.toFloat() / size)
            }
        }
    }

    private fun sourceInfo(size: Long): JSONObject {
        val plan = ChunkPlan.of(size)
        return JSONObject()
            .put("source", "FILE_UPLOAD")
            .put("video_size", size)
            .put("chunk_size", plan.chunkSize)
            .put("total_chunk_count", plan.count)
    }

    private fun parseInit(d: JSONObject) = InitResult(d.getString("publish_id"), d.getString("upload_url"))

    private suspend fun post(url: String, token: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw TikTokApiException("http_${r.code}", "Некорректный ответ TikTok (${r.code})", r.code >= 500)
            }
            val err = json.optJSONObject("error")
            val code = err?.optString("code") ?: "ok"
            if (code != "ok" || !r.isSuccessful) throw mapError(code, err?.optString("message").orEmpty(), r.code)
            json.optJSONObject("data") ?: JSONObject()
        }
    }

    private fun mapError(code: String, message: String, http: Int): TikTokApiException {
        val (text, retry) = when (code) {
            "spam_risk_too_many_posts" -> "Достигнут дневной лимит публикаций TikTok через API. Повторите завтра" to false
            "spam_risk_user_banned_from_posting" -> "TikTok запретил этому аккаунту публикации" to false
            "reached_active_user_cap" -> "Достигнут лимит активных пользователей приложения" to true
            "unaudited_client_can_only_post_to_private_accounts" ->
                "Приложение не прошло аудит TikTok: публиковать можно только в приватный аккаунт" to false
            "url_ownership_unverified" -> "Домен не подтверждён" to false
            "privacy_level_option_mismatch" -> "Выбранная видимость недоступна для аккаунта" to false
            "access_token_invalid", "scope_not_authorized" -> "Нет доступа. Подключите TikTok заново" to false
            "rate_limit_exceeded" -> "Слишком много запросов, повторим позже" to true
            "internal_error" -> "Внутренняя ошибка TikTok" to true
            else -> "TikTok: ${message.ifBlank { code }}" to (http >= 500)
        }
        return TikTokApiException(code, text, retry)
    }
}

data class ChunkPlan(val chunkSize: Long, val count: Int) {
    companion object {
        private const val MB = 1024L * 1024
        fun of(size: Long): ChunkPlan =
            if (size <= 64 * MB) ChunkPlan(size, 1)
            else ChunkPlan(10 * MB, (size / (10 * MB)).toInt())
    }
}

/** Отдаёт ровно [length] байт из общего потока, не загружая их в память целиком. */
private class StreamSliceBody(
    private val input: InputStream,
    private val length: Long,
    private val type: MediaType
) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = length
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(256 * 1024)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) throw IOException("Файл оказался короче ожидаемого")
            sink.write(buffer, 0, read)
            remaining -= read
        }
    }
}
