package com.clipqueue.app.engine

import com.clipqueue.app.data.*

/*
 * Интерфейсы между ядром публикации и платформой. Ядро (PublishEngine) не зависит от Android,
 * поэтому его логика целиком покрывается обычными JVM-тестами с подменёнными реализациями.
 */

class AuthException(message: String) : Exception(message)

/** Ошибка TikTok API. [retryable] — временная, имеет смысл повторить позже. */
class TikTokApiException(val code: String, message: String, val retryable: Boolean) : Exception(message)

data class CreatorInfo(
    val nickname: String,
    val privacyOptions: List<String>,
    val maxDurationSec: Int,
    val commentDisabled: Boolean,
    val duetDisabled: Boolean,
    val stitchDisabled: Boolean
)

data class InitResult(val publishId: String, val uploadUrl: String)
data class PublishStatus(val status: String, val failReason: String?, val postIds: List<String>)

/** Официальный Content Posting API. */
interface PostingApi {
    suspend fun creatorInfo(token: String): CreatorInfo
    suspend fun initDirect(token: String, caption: String, privacy: String, size: Long, creator: CreatorInfo): InitResult
    suspend fun initInbox(token: String, size: Long): InitResult
    suspend fun upload(videoUri: String, size: Long, mime: String, uploadUrl: String, onProgress: suspend (Float) -> Unit)
    suspend fun fetchStatus(token: String, publishId: String): PublishStatus
}

interface TokenProvider {
    fun isConnected(): Boolean
    fun hasScope(scope: String): Boolean
    /** Действующий access token; при необходимости обновляет его. Бросает [AuthException]. */
    suspend fun accessToken(): String
}

sealed interface Availability {
    data object Ok : Availability
    data class Missing(val reason: String) : Availability
}

interface VideoAccess {
    fun checkAvailable(uri: String): Availability
}

/** Хранилище очереди для ядра. */
interface QueueStore {
    suspend fun get(id: Long): QueueItem?
    suspend fun update(item: QueueItem)
    /** Атомарная смена статуса; false — задание уже в другом состоянии. */
    suspend fun transition(id: Long, from: Set<PostStatus>, to: PostStatus): Boolean
    suspend fun log(itemId: Long?, message: String, isError: Boolean = false)
    suspend fun record(r: PublishRecord)
}

interface Notifier {
    fun upcoming(item: QueueItem)
    /** [quickShare] — можно опубликовать в одно касание: уведомление сразу открывает TikTok. */
    fun actionNeeded(item: QueueItem, reason: String, quickShare: Boolean)
    fun result(item: QueueItem, title: String, text: String)
}

/** Данные для сборки описания. */
interface CaptionInputs {
    suspend fun templates(): List<CaptionTemplate>
    suspend fun allSets(): List<HashtagSet>
    suspend fun options(): CaptionOptions
}
