package com.clipqueue.app.domain

/**
 * Приводит пользовательский ввод к валидным хештегам TikTok.
 *  "anime, #Edit  ##gaming fyp!" -> [#anime, #Edit, #gaming, #fyp]
 *  "моя серия"                   -> [#моя, #серия]  (пробел внутри хештега недопустим)
 * Разрешены буквы любого алфавита, цифры и «_». Регистр сохраняется,
 * дубликаты отсекаются без учёта регистра. Ничего не добавляет от себя.
 */
object HashtagNormalizer {
    const val MAX_TAG_LENGTH = 100
    private val separators = Regex("[\\s,;]+")
    private val inText = Regex("#[\\p{L}\\p{N}_]+")

    fun normalizeOne(raw: String): String? {
        val body = raw.trim().trimStart('#')
            .filter { it.isLetterOrDigit() || it == '_' }
            .take(MAX_TAG_LENGTH)
        if (body.isEmpty() || body.all { it == '_' }) return null
        return "#$body"
    }

    fun parse(input: String): List<String> =
        input.split(separators)
            .flatMap { token -> token.split('#').filter { it.isNotBlank() } }
            .mapNotNull(::normalizeOne)
            .let(::dedupe)

    fun dedupe(tags: List<String>): List<String> {
        val seen = HashSet<String>()
        return tags.filter { seen.add(it.lowercase()) }
    }

    fun extractFromText(text: String): List<String> = inText.findAll(text).map { it.value }.toList()

    fun join(tags: List<String>): String = tags.joinToString(" ")

    /** Сдвигает хештег в списке (порядок внутри набора задаёт пользователь). */
    fun move(tags: List<String>, index: Int, delta: Int): List<String> {
        val to = (index + delta).coerceIn(0, tags.lastIndex)
        if (index !in tags.indices || to == index) return tags
        return tags.toMutableList().apply { add(to, removeAt(index)) }
    }

    /** Пояснения к нормализации для подсказки в интерфейсе. */
    fun explain(input: String, existing: List<String> = emptyList()): List<String> {
        val notes = mutableListOf<String>()
        val raw = input.split(separators).flatMap { it.split('#') }.filter { it.isNotBlank() }
        if (raw.any { t -> !t.trim().all { it.isLetterOrDigit() || it == '_' } })
            notes += "Знаки препинания и эмодзи внутри хештегов удалены"
        val normalized = raw.mapNotNull(::normalizeOne)
        val unique = dedupe(normalized)
        if (normalized.size > unique.size) notes += "Повторы во вводе убраны: ${normalized.size - unique.size}"
        val existingLower = existing.map { it.lowercase() }.toSet()
        val already = unique.count { it.lowercase() in existingLower }
        if (already > 0) notes += "Уже есть в наборе: $already"
        return notes
    }
}
