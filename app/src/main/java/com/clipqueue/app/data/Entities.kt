package com.clipqueue.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.ZoneId

@Entity(tableName = "queue_items", indices = [Index("status"), Index("scheduledAt"), Index("position")])
data class QueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoUri: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String?,
    val position: Int = 0,
    /** Категория контента: выбирает шаблон по умолчанию этой категории. */
    val category: String = "",
    /** Индивидуальное описание. Шаблон его никогда не перезаписывает. */
    val customCaption: String = "",
    /** Итоговый текст, отредактированный вручную. Если задан — публикуется как есть. */
    val captionOverride: String? = null,
    /** Пользователь проверил итоговый текст (нужно при включённой обязательной проверке). */
    val captionConfirmed: Boolean = false,
    val templateId: Long? = null,
    val seriesName: String = "",
    /**
     * Наборы хештегов в порядке подстановки: "3,1,7".
     * Пусто — наборы «по умолчанию» на момент публикации (меняются в разделе «Хештеги»).
     * "none" — явно без наборов.
     */
    val hashtagSetIds: String = "",
    /** false — наборы не подставляются автоматически (свои хештеги видео остаются). */
    val autoHashtags: Boolean = true,
    /** Индивидуальные хештеги видео, нормализованные, через пробел. */
    val extraHashtags: String = "",
    /** Снимок итогового описания на момент подготовки. */
    val finalCaption: String = "",
    val scheduledAt: Long? = null,
    val manualSchedule: Boolean = false,
    val timeZone: String = ZoneId.systemDefault().id,
    val notes: String = "",
    val status: PostStatus = PostStatus.PENDING,
    /** Почему задание требует подтверждения — показывается пользователю. */
    val statusReason: String? = null,
    val attempts: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    val publishId: String? = null,
    val resultInfo: String? = null,
    /** Видимость для Direct Post. Выбирается явно, без значения по умолчанию (требование TikTok). */
    val privacyLevel: String? = null,
    val preparedAt: Long? = null,
    val publishedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val setIds: List<Long> get() = hashtagSetIds.split(',').mapNotNull { it.trim().toLongOrNull() }
    val usesDefaultSets: Boolean get() = hashtagSetIds.isBlank()

    /** Наборы, которые реально попадут в описание. */
    fun effectiveSetIds(all: List<HashtagSet>): List<Long> =
        if (usesDefaultSets) all.filter { it.isDefault }.map { it.id } else setIds
}

const val NO_SETS = "none"
fun List<Long>.toSetIdString() = if (isEmpty()) NO_SETS else distinct().joinToString(",")

@Entity(tableName = "hashtag_sets")
data class HashtagSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Нормализованные хештеги через пробел в заданном пользователем порядке. */
    val tags: String,
    /** Набор подставляется в новые видео автоматически. Таких наборов может быть несколько. */
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    val tagList: List<String> get() = tags.split(' ').filter { it.isNotBlank() }
}

@Entity(tableName = "caption_templates")
data class CaptionTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val body: String,
    /** Пусто — общий шаблон. */
    val category: String = "",
    /** Шаблон по умолчанию в своей категории. */
    val isDefault: Boolean = false
)

/** Журнал событий. */
@Entity(tableName = "history", indices = [Index("timestamp")])
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long?,
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val isError: Boolean = false
)

/** История публикаций. Сохраняется и после удаления задания из очереди. */
@Entity(tableName = "publish_records", indices = [Index("createdAt")])
data class PublishRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val videoName: String,
    val scheduledAt: Long?,
    val publishedAt: Long?,
    val caption: String,
    val hashtags: String,
    val result: RecordResult,
    val error: String? = null,
    val publishId: String? = null,
    val mode: PublishMode,
    val createdAt: Long = System.currentTimeMillis()
)
