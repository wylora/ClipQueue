package com.clipqueue.app.data

/** Статусы задания. Хранятся в БД по имени. */
enum class PostStatus(val label: String) {
    DRAFT("Черновик"),
    PENDING("Ожидает публикации"),
    READY("Готово к публикации"),
    NEEDS_CONFIRMATION("Требует подтверждения"),
    PUBLISHING("Публикуется"),
    PUBLISHED("Опубликовано"),
    ERROR("Ошибка"),
    CANCELLED("Отменено");

    /** Задание участвует в автоматическом расписании. */
    val isScheduled: Boolean get() = this == PENDING || this == READY
    val isClosed: Boolean get() = this == PUBLISHED || this == CANCELLED
}

/**
 * Способ доставки видео в TikTok.
 *  SHARE      — режим B: официальный Intent в приложение TikTok, финальное нажатие делает пользователь.
 *  API_INBOX  — режим A (Upload): Content Posting API кладёт видео во «Входящие» TikTok, scope video.upload.
 *  API_DIRECT — режим A (Direct Post): полная публикация через API, scope video.publish.
 */
enum class PublishMode(val title: String) {
    SHARE("С подтверждением в TikTok"),
    API_INBOX("API: загрузка во «Входящие» TikTok"),
    API_DIRECT("API: прямая публикация")
}

enum class ThemeMode(val title: String) { DARK("Тёмная"), LIGHT("Светлая"), SYSTEM("Как в системе") }

enum class HashtagPlacement(val title: String, val hint: String) {
    END("В конце описания", "Хештеги добавляются отдельным абзацем после текста"),
    AFTER_SEPARATOR("После разделителя", "Хештеги вставляются сразу после заданного текста-разделителя; если его нет — в конец"),
    TEMPLATE("По шаблону", "Хештеги встают на место переменной {hashtags} в шаблоне; если её нет — в конец")
}

/** Итог публикации в истории. */
enum class RecordResult(val label: String) {
    PUBLISHED_API("Опубликовано, подтверждено TikTok API"),
    CONFIRMED_BY_USER("Опубликовано, подтверждено вами"),
    SENT_TO_INBOX("Отправлено во «Входящие» TikTok"),
    FAILED("Ошибка"),
    CANCELLED("Отменено")
}

object PrivacyLevels {
    val labels = linkedMapOf(
        "PUBLIC_TO_EVERYONE" to "Все",
        "MUTUAL_FOLLOW_FRIENDS" to "Друзья",
        "FOLLOWER_OF_CREATOR" to "Подписчики",
        "SELF_ONLY" to "Только я"
    )
    fun label(code: String?) = labels[code] ?: (code ?: "Не выбрано")
}

/** Лимит описания Content Posting API (UTF-16). */
const val TIKTOK_CAPTION_LIMIT = 2200
/** TikTok в приложении предупреждает «Maximum 5 hashtags». В API-документации отдельного лимита нет. */
const val DEFAULT_MAX_HASHTAGS = 5
const val MIN_VIDEO_MS = 3000L
