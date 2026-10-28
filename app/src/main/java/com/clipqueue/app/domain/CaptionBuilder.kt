package com.clipqueue.app.domain

import com.clipqueue.app.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CaptionResult(
    val text: String,
    /** Все хештеги итогового текста. */
    val hashtags: List<String>,
    /** Хештеги, не добавленные из-за повторов (в разных наборах или в тексте). */
    val skippedDuplicates: Int = 0,
    /** Хештеги, не добавленные из-за лимита количества. */
    val droppedByLimit: Int = 0,
    val unknownVariables: List<String> = emptyList(),
    /** Текст отредактирован вручную и публикуется как есть. */
    val overridden: Boolean = false,
    val maxHashtags: Int = 0,
    /** Автоподстановка включена, но в выбранных наборах нет ни одного хештега. */
    val emptySets: Boolean = false
) {
    /** TikTok считает лимит в UTF-16 code units — это String.length. */
    val length: Int get() = text.length
    val overLimit: Boolean get() = length > TIKTOK_CAPTION_LIMIT
    val tooManyHashtags: Boolean get() = maxHashtags > 0 && hashtags.size > maxHashtags
}

/**
 * Собирает итоговое описание: текст (своё описание или шаблон) + хештеги из выбранных наборов
 * и индивидуальные хештеги видео. Никаких хештегов «от себя» не добавляет.
 *
 * Переменные: {title} {series} {category} {n} {date} {time} {weekday} {hashtags}
 */
object CaptionBuilder {
    val variables = listOf(
        "{title}" to "название файла без расширения",
        "{series}" to "название серии из карточки видео",
        "{category}" to "категория видео",
        "{n}" to "номер видео в очереди",
        "{date}" to "дата публикации",
        "{time}" to "время публикации",
        "{weekday}" to "день недели",
        "{hashtags}" to "место для хештегов"
    )
    private const val PLACEHOLDER = "{hashtags}"
    private val varRegex = Regex("\\{([a-zA-Z]+)\\}")
    private val ru = Locale("ru")

    fun build(
        item: QueueItem,
        template: CaptionTemplate?,
        /** Все наборы пользователя; нужные выбираются по заданию (или по умолчанию). */
        sets: List<HashtagSet>,
        options: CaptionOptions,
        whenMillis: Long = item.scheduledAt ?: System.currentTimeMillis(),
        zone: ZoneId = runCatching { ZoneId.of(item.timeZone) }.getOrDefault(ZoneId.systemDefault())
    ): CaptionResult {
        item.captionOverride?.let { manual ->
            return CaptionResult(manual, HashtagNormalizer.extractFromText(manual), overridden = true, maxHashtags = options.maxHashtags)
        }

        val zdt = Instant.ofEpochMilli(whenMillis).atZone(zone)
        val values = mapOf(
            "title" to item.displayName.substringBeforeLast('.').replace('_', ' ').trim(),
            "series" to item.seriesName.trim(),
            "category" to item.category.trim(),
            "n" to (item.position + 1).toString(),
            "date" to zdt.format(DateTimeFormatter.ofPattern("d MMMM", ru)),
            "time" to zdt.format(DateTimeFormatter.ofPattern("HH:mm", ru)),
            "weekday" to zdt.format(DateTimeFormatter.ofPattern("EEEE", ru))
        )
        val unknown = mutableListOf<String>()
        val source = item.customCaption.ifBlank { template?.body.orEmpty() }
        val body = varRegex.replace(source) { m ->
            val key = m.groupValues[1].lowercase()
            when {
                key == "hashtags" -> m.value
                values.containsKey(key) -> values.getValue(key)
                else -> m.value.also { unknown += it }
            }
        }.trim()

        // Наборы — в порядке, выбранном для видео; затем индивидуальные хештеги.
        val ordered = item.effectiveSetIds(sets).mapNotNull { id -> sets.firstOrNull { it.id == id } }
        val candidates = buildList {
            if (item.autoHashtags) ordered.forEach { addAll(it.tagList) }
            addAll(HashtagNormalizer.parse(item.extraHashtags))
        }
        val inBody = HashtagNormalizer.extractFromText(body.replace(PLACEHOLDER, ""))
        val inBodyLower = inBody.map { it.lowercase() }.toSet()
        val unique = HashtagNormalizer.dedupe(candidates).filter { it.lowercase() !in inBodyLower }
        val skipped = candidates.size - unique.size

        val room = if (options.maxHashtags > 0) (options.maxHashtags - inBody.size).coerceAtLeast(0) else Int.MAX_VALUE
        val added = unique.take(room)
        val dropped = unique.size - added.size
        val tagLine = added.joinToString(" ")

        val text = place(body, tagLine, options)
        return CaptionResult(
            text = text,
            hashtags = HashtagNormalizer.extractFromText(text),
            skippedDuplicates = skipped,
            droppedByLimit = dropped,
            unknownVariables = unknown.distinct(),
            maxHashtags = options.maxHashtags,
            emptySets = item.autoHashtags && ordered.all { it.tagList.isEmpty() }
        )
    }

    private fun place(body: String, tags: String, o: CaptionOptions): String {
        if (body.contains(PLACEHOLDER)) {
            return body.replace(PLACEHOLDER, tags).replace(Regex("[ \\t]+\\n"), "\n").trim()
        }
        if (tags.isEmpty()) return body
        if (body.isEmpty()) return tags
        if (o.placement == HashtagPlacement.AFTER_SEPARATOR && o.separator.isNotBlank()) {
            val i = body.indexOf(o.separator)
            if (i >= 0) {
                val end = i + o.separator.length
                val rest = body.substring(end)
                return (body.substring(0, end) + " " + tags + (if (rest.isNotBlank()) "\n" + rest.trimStart() else "")).trim()
            }
        }
        return body + "\n\n" + tags
    }
}

/** Выбор шаблона: явно выбранный → по умолчанию в категории видео → общий по умолчанию. */
object TemplateResolver {
    fun resolve(item: QueueItem, templates: List<CaptionTemplate>): CaptionTemplate? =
        item.templateId?.let { id -> templates.firstOrNull { it.id == id } }
            ?: templates.firstOrNull { it.isDefault && it.category.equals(item.category, ignoreCase = true) && item.category.isNotBlank() }
            ?: templates.firstOrNull { it.isDefault && it.category.isBlank() }
}
