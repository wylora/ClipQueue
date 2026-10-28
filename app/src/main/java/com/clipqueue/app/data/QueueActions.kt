package com.clipqueue.app.data

import android.net.Uri
import com.clipqueue.app.media.VideoInspector
import com.clipqueue.app.work.PublishScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Все пользовательские операции с очередью. После изменений расписание пересобирается. */
class QueueActions(
    private val queue: QueueRepository,
    private val hashtags: HashtagRepository,
    private val settings: SettingsRepository,
    private val inspector: VideoInspector,
    private val scheduler: PublishScheduler
) {
    private suspend fun <T> andReplan(block: suspend () -> T): T = block().also { scheduler.replan() }

    /**
     * Добавляет видео: постоянный доступ к URI, метаданные.
     * Наборы хештегов не «замораживаются»: видео берёт наборы по умолчанию на момент публикации,
     * поэтому хештеги, добавленные позже, тоже попадут в описание.
     */
    suspend fun addVideos(uris: List<Uri>): Int = andReplan {
        var added = 0
        withContext(Dispatchers.IO) {
            for (uri in uris) {
                inspector.persistAccess(uri)
                val info = inspector.inspect(uri) ?: continue
                queue.add(QueueItem(
                    videoUri = uri.toString(), displayName = info.displayName, durationMs = info.durationMs,
                    sizeBytes = info.sizeBytes, mimeType = info.mimeType, hashtagSetIds = ""
                ))
                added++
            }
        }
        added
    }

    /** Заменяет видео в задании, сохраняя описание, хештеги и расписание. */
    suspend fun replaceVideo(id: Long, uri: Uri): Boolean = andReplan {
        val item = queue.get(id) ?: return@andReplan false
        val info = withContext(Dispatchers.IO) { inspector.persistAccess(uri); inspector.inspect(uri) } ?: return@andReplan false
        val old = item.videoUri
        queue.update(item.copy(
            videoUri = uri.toString(), displayName = info.displayName, durationMs = info.durationMs,
            sizeBytes = info.sizeBytes, mimeType = info.mimeType, preparedAt = null, finalCaption = "",
            status = if (item.status == PostStatus.ERROR || item.status == PostStatus.READY) PostStatus.PENDING else item.status,
            lastError = null
        ))
        releaseIfUnused(old)
        queue.log(id, "Видео заменено: ${item.displayName} → ${info.displayName}")
        true
    }

    suspend fun copy(id: Long): Long? = andReplan {
        val item = queue.get(id) ?: return@andReplan null
        val newId = queue.add(item.copy(
            status = PostStatus.DRAFT, statusReason = null, attempts = 0, lastAttemptAt = null, lastError = null,
            publishId = null, resultInfo = null, preparedAt = null, publishedAt = null, finalCaption = "",
            captionConfirmed = false, createdAt = System.currentTimeMillis()
        ))
        queue.log(newId, "Создана копия задания «${item.displayName}» (черновик)")
        newId
    }

    suspend fun delete(ids: Collection<Long>) = andReplan {
        for (id in ids) {
            val item = queue.get(id) ?: continue
            scheduler.cancel(id)
            queue.delete(id)
            releaseIfUnused(item.videoUri)
        }
    }

    suspend fun cancel(ids: Collection<Long>) = andReplan {
        val mode = settings.current().publishMode
        for (item in queue.getMany(ids.toList())) {
            if (item.status == PostStatus.PUBLISHED || item.status == PostStatus.PUBLISHING) continue
            scheduler.cancel(item.id)
            queue.update(item.copy(status = PostStatus.CANCELLED, statusReason = null))
            queue.record(PublishRecord(itemId = item.id, videoName = item.displayName, scheduledAt = item.scheduledAt,
                publishedAt = null, caption = item.finalCaption, hashtags = "", result = RecordResult.CANCELLED, mode = mode))
            queue.log(item.id, "Отменено: ${item.displayName}")
        }
    }

    suspend fun cancelAllPending(): Int {
        val ids = queue.getAll().filter { it.status.isScheduled || it.status == PostStatus.NEEDS_CONFIRMATION }.map { it.id }
        cancel(ids)
        return ids.size
    }

    /** Вернуть в очередь: из черновика, отмены, ошибки или «требует подтверждения». */
    suspend fun enqueue(ids: Collection<Long>) = andReplan {
        for (item in queue.getMany(ids.toList())) {
            if (item.status == PostStatus.PUBLISHED || item.status == PostStatus.PUBLISHING) continue
            queue.update(item.copy(status = PostStatus.PENDING, lastError = null, statusReason = null, publishId = null))
            queue.log(item.id, "Поставлено в очередь: ${item.displayName}")
        }
    }

    suspend fun toDraft(ids: Collection<Long>) = andReplan {
        for (item in queue.getMany(ids.toList())) {
            if (item.status == PostStatus.PUBLISHED || item.status == PostStatus.PUBLISHING) continue
            scheduler.cancel(item.id)
            queue.update(item.copy(status = PostStatus.DRAFT, statusReason = null))
        }
    }

    suspend fun applyTemplate(ids: Collection<Long>, templateId: Long?) = andReplan {
        for (item in queue.getMany(ids.toList())) {
            if (item.status.isClosed) continue
            queue.update(item.copy(templateId = templateId, captionConfirmed = false))
        }
        queue.log(null, "Шаблон применён к ${ids.size} видео")
    }

    suspend fun applySets(ids: Collection<Long>, setIds: List<Long>, replace: Boolean) = andReplan {
        for (item in queue.getMany(ids.toList())) {
            if (item.status.isClosed) continue
            val ids = when {
                replace && setIds.isEmpty() -> "" // сброс к наборам по умолчанию
                replace -> setIds.toSetIdString()
                else -> (item.effectiveSetIds(hashtags.all()) + setIds).toSetIdString()
            }
            queue.update(item.copy(hashtagSetIds = ids, autoHashtags = true, captionConfirmed = false))
        }
        queue.log(null, "Наборы хештегов применены к ${ids.size} видео")
    }

    /** Видимость для Direct Post, явно выбранная пользователем для выбранных видео. */
    suspend fun applyPrivacy(ids: Collection<Long>, privacy: String) = andReplan {
        for (item in queue.getMany(ids.toList())) {
            if (item.status.isClosed || item.status == PostStatus.PUBLISHING) continue
            val back = item.status == PostStatus.NEEDS_CONFIRMATION && item.statusReason?.contains("кто увидит") == true
            queue.update(item.copy(privacyLevel = privacy,
                status = if (back) PostStatus.PENDING else item.status, statusReason = if (back) null else item.statusReason))
        }
        queue.log(null, "Видимость «${PrivacyLevels.label(privacy)}» выбрана для ${ids.size} видео")
    }

    /**
     * Исправление старых заданий: если у видео выбраны только пустые или удалённые наборы,
     * переключаем его на наборы по умолчанию, чтобы хештеги снова подставлялись.
     */
    suspend fun repairHashtagSelection(): Int {
        val sets = hashtags.all().associateBy { it.id }
        var fixed = 0
        for (item in queue.getAll()) {
            if (item.status.isClosed || item.usesDefaultSets || item.hashtagSetIds == NO_SETS) continue
            val selected = item.setIds.mapNotNull { sets[it] }
            if (selected.all { it.tagList.isEmpty() }) {
                queue.update(item.copy(hashtagSetIds = "")); fixed++
            }
        }
        if (fixed > 0) queue.log(null, "Хештеги: $fixed видео переключены на наборы по умолчанию")
        return fixed
    }

    suspend fun reschedule(id: Long, at: Long, zone: String) = andReplan {
        val item = queue.get(id) ?: return@andReplan
        val status = when (item.status) {
            PostStatus.ERROR, PostStatus.CANCELLED, PostStatus.READY, PostStatus.NEEDS_CONFIRMATION -> PostStatus.PENDING
            else -> item.status
        }
        queue.update(item.copy(scheduledAt = at, manualSchedule = true, timeZone = zone, status = status, statusReason = null, lastError = null))
        queue.log(id, "Перенесено: ${item.displayName}")
    }

    suspend fun reorder(ids: List<Long>) = andReplan { queue.reorder(ids) }

    suspend fun save(updated: QueueItem) = andReplan {
        val fresh = queue.get(updated.id) ?: return@andReplan
        // Статус и служебные поля берём из БД: в фоне они могли измениться.
        queue.update(updated.copy(
            status = fresh.status, attempts = fresh.attempts, lastAttemptAt = fresh.lastAttemptAt, publishId = fresh.publishId,
            resultInfo = fresh.resultInfo, publishedAt = fresh.publishedAt, preparedAt = fresh.preparedAt,
            videoUri = fresh.videoUri, displayName = fresh.displayName, durationMs = fresh.durationMs,
            sizeBytes = fresh.sizeBytes, mimeType = fresh.mimeType, position = fresh.position,
            lastError = fresh.lastError, statusReason = fresh.statusReason
        ))
        queue.log(updated.id, "Изменены настройки: ${updated.displayName}")
    }

    /** Пользователь подтверждает, что опубликовал видео сам. */
    suspend fun confirmPublished(id: Long) = andReplan {
        val item = queue.get(id) ?: return@andReplan
        val now = System.currentTimeMillis()
        queue.update(item.copy(status = PostStatus.PUBLISHED, publishedAt = now, statusReason = null, lastError = null,
            resultInfo = "Подтверждено вами"))
        queue.record(PublishRecord(itemId = id, videoName = item.displayName, scheduledAt = item.scheduledAt, publishedAt = now,
            caption = item.finalCaption, hashtags = com.clipqueue.app.domain.HashtagNormalizer.extractFromText(item.finalCaption).joinToString(" "),
            result = RecordResult.CONFIRMED_BY_USER, mode = settings.current().publishMode, publishId = item.publishId))
        queue.log(id, "Опубликовано (подтверждено вами): ${item.displayName}")
    }

    suspend fun markNotPublished(id: Long) = andReplan {
        val item = queue.get(id) ?: return@andReplan
        queue.update(item.copy(status = PostStatus.ERROR, lastError = "Публикация не завершена в TikTok", statusReason = null))
        queue.record(PublishRecord(itemId = id, videoName = item.displayName, scheduledAt = item.scheduledAt, publishedAt = null,
            caption = item.finalCaption, hashtags = "", result = RecordResult.FAILED, error = "Не завершено пользователем",
            mode = settings.current().publishMode))
        queue.log(id, "Публикация не завершена: ${item.displayName}", isError = true)
    }

    /** Подтвердить проверенный текст и отправить через API (обязательная проверка в режиме A). */
    suspend fun approveAndPublish(id: Long) {
        val item = queue.get(id) ?: return
        queue.update(item.copy(captionConfirmed = true, status = PostStatus.PENDING, statusReason = null))
        queue.log(id, "Описание подтверждено: ${item.displayName}")
        scheduler.runNow(id)
    }

    /** true — нужно открыть экран передачи в TikTok (режим B), иначе задача запущена в фоне. */
    suspend fun publishNow(id: Long): Boolean {
        val s = settings.current()
        val item = queue.get(id) ?: return false
        if (item.status == PostStatus.PUBLISHED || item.status == PostStatus.PUBLISHING) return false
        if (s.publishMode == PublishMode.SHARE) return true
        if (!item.status.isScheduled) queue.update(item.copy(status = PostStatus.PENDING, statusReason = null, lastError = null))
        scheduler.runNow(id)
        queue.log(id, "Запущена публикация вне расписания: ${item.displayName}")
        return false
    }

    suspend fun setRunning(running: Boolean) = andReplan {
        settings.setRunning(running)
        queue.log(null, if (running) "Очередь запущена" else "Очередь приостановлена")
    }

    private suspend fun releaseIfUnused(uri: String) {
        if (!queue.isUriUsed(uri)) inspector.releaseAccess(Uri.parse(uri))
    }
}
