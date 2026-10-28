package com.clipqueue.app.engine

import com.clipqueue.app.data.*
import kotlinx.coroutines.delay
import java.io.IOException

sealed interface Outcome {
    data object Done : Outcome
    /** Временная ошибка — повторить с задержкой (WorkManager backoff). */
    data object Retry : Outcome
    /** TikTok ещё обрабатывает видео — проверить статус позже. */
    data class CheckLater(val minutes: Long) : Outcome
}

/**
 * Ядро публикации. Правила:
 *  - «Опубликовано» ставится только по PUBLISH_COMPLETE от TikTok API или по подтверждению пользователя;
 *  - если автопубликация невозможна (режим B, нет входа, нет разрешения, обязательная проверка текста,
 *    не выбрана видимость) — задание переходит в «Требует подтверждения» с понятной причиной;
 *  - отправка защищена атомарным переходом статуса, повторный запуск не публикует ролик дважды;
 *  - после успешной загрузки видео повторная отправка не выполняется ни при каких ошибках.
 */
class PublishEngine(
    private val store: QueueStore,
    private val settings: SettingsSource,
    private val preparer: PostPreparing,
    private val api: PostingApi,
    private val tokens: TokenProvider,
    private val notifier: Notifier,
    private val now: () -> Long = System::currentTimeMillis,
    private val pollDelayMs: Long = 15_000,
    private val maxAttempts: Int = 3
) {
    /** Заблаговременная подготовка: проверка файла, снимок описания, напоминание. */
    suspend fun prepareAhead(id: Long): Outcome {
        val item = store.get(id) ?: return Outcome.Done
        if (item.status != PostStatus.PENDING || !settings.current().queueRunning) return Outcome.Done
        return when (val p = preparer.prepare(item)) {
            is Preparation.Failed -> {
                store.update(item.copy(status = PostStatus.ERROR, lastError = p.reason))
                store.log(id, "Не удалось подготовить «${item.displayName}»: ${p.reason}", isError = true)
                notifier.result(item, "Видео не готово к публикации", "${item.displayName}: ${p.reason}")
                Outcome.Done
            }
            is Preparation.Ready -> {
                if (!store.transition(id, setOf(PostStatus.PENDING), PostStatus.READY)) return Outcome.Done
                val ready = markPrepared(p.item).copy(status = PostStatus.READY, lastError = null)
                store.update(ready)
                store.log(id, "Подготовлено заранее: ${item.displayName}")
                notifier.upcoming(ready)
                Outcome.Done
            }
        }
    }

    suspend fun publish(id: Long, force: Boolean, attempt: Int): Outcome {
        val item = store.get(id) ?: return Outcome.Done
        val s = settings.current()
        if (!force && !s.queueRunning) return Outcome.Done
        if (!item.status.isScheduled) return Outcome.Done

        val ready = when (val p = preparer.prepare(item)) {
            is Preparation.Failed -> return fail(item, p.reason, retryable = false, attempt = attempt, mode = s.publishMode)
            is Preparation.Ready -> markPrepared(p.item)
        }
        store.update(ready)
        // Время отправки фиксируется в любом режиме — от него отсчитывается интервал до следующего видео.
        settings.setLastPostAt(now())

        confirmationReason(ready, s)?.let { (reason, quick) -> return needsConfirmation(ready, reason, quick) }

        if (!store.transition(id, setOf(PostStatus.PENDING, PostStatus.READY), PostStatus.PUBLISHING)) return Outcome.Done
        val claimed = ready.copy(
            status = PostStatus.PUBLISHING, attempts = ready.attempts + 1, lastAttemptAt = now(),
            lastError = null, statusReason = null, publishId = null
        )
        store.update(claimed)

        var current = claimed
        var uploaded = false
        return try {
            val token = tokens.accessToken()
            val init = if (s.publishMode == PublishMode.API_DIRECT) {
                val creator = api.creatorInfo(token)
                val privacy = claimed.privacyLevel!!
                if (privacy !in creator.privacyOptions)
                    return needsConfirmation(claimed, "Видимость «${PrivacyLevels.label(privacy)}» недоступна для аккаунта. Выберите другую")
                if (creator.maxDurationSec > 0 && claimed.durationMs > creator.maxDurationSec * 1000L)
                    return fail(claimed, "Видео длиннее разрешённого для аккаунта (${creator.maxDurationSec} с)", false, attempt, s.publishMode)
                api.initDirect(token, claimed.finalCaption, privacy, claimed.sizeBytes, creator)
            } else {
                api.initInbox(token, claimed.sizeBytes)
            }
            current = claimed.copy(publishId = init.publishId)
            store.update(current)
            store.log(id, "Загрузка в TikTok начата: ${item.displayName}")
            val mime = claimed.mimeType?.takeIf { it.startsWith("video/") } ?: "video/mp4"
            api.upload(claimed.videoUri, claimed.sizeBytes, mime, init.uploadUrl) { onProgress?.invoke(current, it) }
            uploaded = true
            poll(current, token, maxChecks = 20, mode = s.publishMode)
        } catch (e: AuthException) {
            if (uploaded) Outcome.CheckLater(15)
            else needsConfirmation(current.copy(publishId = null), "${e.message}. Публикация через приложение TikTok или подключите аккаунт заново", quickShare = true)
        } catch (e: TikTokApiException) {
            if (uploaded) Outcome.CheckLater(15)
            else fail(current.copy(publishId = null), e.message ?: e.code, e.retryable, attempt, s.publishMode)
        } catch (e: IOException) {
            if (uploaded) Outcome.CheckLater(10)
            else fail(current.copy(publishId = null), "Сеть: ${e.message ?: "нет соединения"}", true, attempt, s.publishMode)
        }
    }

    suspend fun checkStatus(id: Long): Outcome {
        val item = store.get(id) ?: return Outcome.Done
        if (item.status != PostStatus.PUBLISHING || item.publishId == null) return Outcome.Done
        val mode = settings.current().publishMode
        return try {
            poll(item, tokens.accessToken(), maxChecks = 6, mode = mode)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Outcome.CheckLater(15)
        }
    }

    /** Обратный вызов прогресса загрузки (для уведомления foreground-задачи). */
    var onProgress: (suspend (QueueItem, Float) -> Unit)? = null

    /** Причина подтверждения и можно ли завершить в одно касание через приложение TikTok. */
    private fun confirmationReason(item: QueueItem, s: AppSettings): Pair<String, Boolean>? = when {
        s.requireReview && !item.captionConfirmed ->
            "Включена обязательная проверка описания. Проверьте текст и подтвердите публикацию" to false
        s.publishMode == PublishMode.SHARE ->
            "Нажмите уведомление — видео откроется в TikTok, описание с хештегами уже в буфере" to true
        !tokens.isConnected() ->
            "Аккаунт TikTok не подключён — публикация через приложение TikTok. Для автопубликации подключите аккаунт в настройках" to true
        s.publishMode == PublishMode.API_DIRECT && !tokens.hasScope("video.publish") ->
            "У приложения нет разрешения video.publish — публикация через приложение TikTok" to true
        s.publishMode == PublishMode.API_INBOX && !tokens.hasScope("video.upload") ->
            "У приложения нет разрешения video.upload — публикация через приложение TikTok" to true
        s.publishMode == PublishMode.API_DIRECT && item.privacyLevel == null ->
            "Выберите, кто увидит видео — TikTok требует, чтобы это сделали вы" to false
        else -> null
    }

    private suspend fun markPrepared(item: QueueItem): QueueItem {
        if (item.preparedAt != null) return item
        settings.incrementPrepared()
        return item.copy(preparedAt = now())
    }

    private suspend fun needsConfirmation(item: QueueItem, reason: String, quickShare: Boolean = false): Outcome {
        val updated = item.copy(status = PostStatus.NEEDS_CONFIRMATION, statusReason = reason)
        store.update(updated)
        store.log(item.id, "Требует подтверждения: ${item.displayName}. $reason")
        notifier.actionNeeded(updated, reason, quickShare)
        return Outcome.Done
    }

    private suspend fun poll(item: QueueItem, token: String, maxChecks: Int, mode: PublishMode): Outcome {
        val publishId = item.publishId ?: return Outcome.Done
        repeat(maxChecks) { i ->
            delay(if (i == 0) pollDelayMs / 3 else pollDelayMs)
            val st = api.fetchStatus(token, publishId)
            when (st.status) {
                "PUBLISH_COMPLETE" -> {
                    val at = now()
                    val info = if (st.postIds.isNotEmpty()) "ID поста: ${st.postIds.joinToString()}" else "Подтверждено TikTok"
                    store.update(item.copy(status = PostStatus.PUBLISHED, resultInfo = info, publishedAt = at))
                    record(item, RecordResult.PUBLISHED_API, mode, publishedAt = at,
                        publishId = st.postIds.firstOrNull() ?: publishId)
                    store.log(item.id, "Опубликовано: ${item.displayName}")
                    notifier.result(item, "Опубликовано в TikTok", item.displayName)
                    return Outcome.Done
                }
                "SEND_TO_USER_INBOX" -> {
                    val reason = "Видео во «Входящих» TikTok. Откройте уведомление TikTok и нажмите «Опубликовать»"
                    store.update(item.copy(status = PostStatus.NEEDS_CONFIRMATION, statusReason = reason,
                        resultInfo = "Видео в черновиках TikTok"))
                    record(item, RecordResult.SENT_TO_INBOX, mode, publishId = publishId)
                    store.log(item.id, "Загружено во «Входящие» TikTok: ${item.displayName}")
                    notifier.actionNeeded(item, reason, quickShare = false)
                    return Outcome.Done
                }
                "FAILED" -> {
                    val reason = "TikTok отклонил видео: ${st.failReason ?: "причина не указана"}"
                    store.update(item.copy(status = PostStatus.ERROR, lastError = reason))
                    record(item, RecordResult.FAILED, mode, error = reason, publishId = publishId)
                    store.log(item.id, reason, isError = true)
                    notifier.result(item, "Не удалось опубликовать", "${item.displayName}: $reason")
                    return Outcome.Done
                }
                else -> Unit // PROCESSING_UPLOAD / PROCESSING_DOWNLOAD
            }
        }
        return Outcome.CheckLater(10)
    }

    private suspend fun fail(item: QueueItem, reason: String, retryable: Boolean, attempt: Int, mode: PublishMode): Outcome {
        val canRetry = retryable && attempt + 1 < maxAttempts
        if (canRetry) {
            store.update(item.copy(status = PostStatus.PENDING, lastError = reason))
            store.log(item.id, "Временная ошибка, повтор позже: $reason")
            return Outcome.Retry
        }
        store.update(item.copy(status = PostStatus.ERROR, lastError = reason))
        record(item, RecordResult.FAILED, mode, error = reason, publishId = item.publishId)
        store.log(item.id, "Ошибка: ${item.displayName}. $reason", isError = true)
        notifier.result(item, "Не удалось опубликовать", "${item.displayName}: $reason")
        return Outcome.Done
    }

    private suspend fun record(
        item: QueueItem, result: RecordResult, mode: PublishMode,
        publishedAt: Long? = null, error: String? = null, publishId: String? = null
    ) = store.record(PublishRecord(
        itemId = item.id, videoName = item.displayName, scheduledAt = item.scheduledAt, publishedAt = publishedAt,
        caption = item.finalCaption,
        hashtags = com.clipqueue.app.domain.HashtagNormalizer.extractFromText(item.finalCaption).joinToString(" "),
        result = result, error = error, publishId = publishId, mode = mode, createdAt = now()
    ))
}
