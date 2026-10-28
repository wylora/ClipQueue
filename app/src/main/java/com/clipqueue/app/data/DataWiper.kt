package com.clipqueue.app.data

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.clipqueue.app.auth.TikTokAuth
import com.clipqueue.app.media.VideoInspector
import com.clipqueue.app.work.PublishScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Безопасное удаление всех локальных данных: задачи и будильники, доступ к видео,
 * отзыв доступа к TikTok, база, настройки, временные файлы, уведомления.
 * Оригиналы видео в галерее не затрагиваются.
 */
class DataWiper(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val queue: QueueRepository,
    private val hashtags: HashtagRepository,
    private val templates: TemplateRepository,
    private val inspector: VideoInspector,
    private val auth: TikTokAuth,
    private val scheduler: PublishScheduler
) {
    /** Возвращает предупреждение, если отозвать доступ в TikTok не удалось (токены всё равно удалены). */
    suspend fun wipe(): String? {
        queue.getAll().forEach { scheduler.cancel(it.id) }
        scheduler.cancelAll()
        val revoke = auth.revokeAndLogout()
        withContext(Dispatchers.IO) {
            inspector.persistedUris().forEach { inspector.releaseAccess(it) }
            db.clearAllTables()
            File(context.cacheDir, "share").deleteRecursively()
        }
        settings.clearAll()
        NotificationManagerCompat.from(context).cancelAll()
        hashtags.ensureSeed()
        templates.ensureSeed()
        return revoke.exceptionOrNull()?.message
    }
}
