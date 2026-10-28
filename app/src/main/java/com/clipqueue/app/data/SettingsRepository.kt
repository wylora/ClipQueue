package com.clipqueue.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek

private val Context.dataStore by preferencesDataStore(name = "settings")

data class ScheduleConfig(
    val intervalMinutes: Int = 180,
    val allowedDays: Set<DayOfWeek> = DayOfWeek.values().toSet(),
    /** Минуты от полуночи. */
    val windowStartMin: Int = 10 * 60,
    val windowEndMin: Int = 22 * 60
)

data class CaptionOptions(
    val placement: HashtagPlacement = HashtagPlacement.END,
    val separator: String = "",
    /** 0 — не ограничивать. */
    val maxHashtags: Int = DEFAULT_MAX_HASHTAGS
)

data class AppSettings(
    val publishMode: PublishMode = PublishMode.SHARE,
    val queueRunning: Boolean = false,
    val schedule: ScheduleConfig = ScheduleConfig(),
    val caption: CaptionOptions = CaptionOptions(),
    /** Не публиковать текст, который пользователь не подтвердил. */
    val requireReview: Boolean = false,
    /** Пока предыдущее видео ждёт вашего подтверждения, следующее по расписанию не запускается. */
    val waitForConfirmation: Boolean = true,
    /** За сколько минут заранее готовить задание и присылать напоминание. */
    val prepareAheadMinutes: Int = 15,
    val exactAlarms: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val clientKey: String = "",
    val backendUrl: String = "",
    val lastPostAt: Long = 0L,
    val preparedTotal: Int = 0
)

/** Минимальный интерфейс настроек для ядра публикации (подменяется в тестах). */
interface SettingsSource {
    suspend fun current(): AppSettings
    suspend fun setLastPostAt(v: Long)
    suspend fun incrementPrepared()
}

class SettingsRepository(private val context: Context) : SettingsSource {
    private object K {
        val mode = stringPreferencesKey("publish_mode")
        val running = booleanPreferencesKey("queue_running")
        val interval = intPreferencesKey("interval_min")
        val days = stringSetPreferencesKey("allowed_days")
        val winStart = intPreferencesKey("window_start")
        val winEnd = intPreferencesKey("window_end")
        val placement = stringPreferencesKey("hashtag_placement")
        val separator = stringPreferencesKey("hashtag_separator")
        val maxTags = intPreferencesKey("max_hashtags")
        val review = booleanPreferencesKey("require_review")
        val wait = booleanPreferencesKey("wait_confirmation")
        val ahead = intPreferencesKey("prepare_ahead")
        val exact = booleanPreferencesKey("exact_alarms")
        val theme = stringPreferencesKey("theme")
        val clientKey = stringPreferencesKey("client_key")
        val backend = stringPreferencesKey("backend_url")
        val lastPost = longPreferencesKey("last_post_at")
        val prepared = intPreferencesKey("prepared_total")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            publishMode = p[K.mode]?.let { runCatching { PublishMode.valueOf(it) }.getOrNull() } ?: PublishMode.SHARE,
            queueRunning = p[K.running] ?: false,
            schedule = ScheduleConfig(
                intervalMinutes = p[K.interval] ?: 180,
                allowedDays = p[K.days]?.mapNotNull { it.toIntOrNull()?.let(DayOfWeek::of) }?.toSet()
                    ?.takeIf { it.isNotEmpty() } ?: DayOfWeek.values().toSet(),
                windowStartMin = p[K.winStart] ?: 600,
                windowEndMin = p[K.winEnd] ?: 1320
            ),
            caption = CaptionOptions(
                placement = p[K.placement]?.let { runCatching { HashtagPlacement.valueOf(it) }.getOrNull() } ?: HashtagPlacement.END,
                separator = p[K.separator] ?: "",
                maxHashtags = p[K.maxTags] ?: DEFAULT_MAX_HASHTAGS
            ),
            requireReview = p[K.review] ?: false,
            waitForConfirmation = p[K.wait] ?: true,
            prepareAheadMinutes = p[K.ahead] ?: 15,
            exactAlarms = p[K.exact] ?: false,
            themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            clientKey = p[K.clientKey] ?: "",
            backendUrl = p[K.backend] ?: "",
            lastPostAt = p[K.lastPost] ?: 0L,
            preparedTotal = p[K.prepared] ?: 0
        )
    }

    override suspend fun current(): AppSettings = settings.first()
    override suspend fun setLastPostAt(v: Long) { context.dataStore.edit { it[K.lastPost] = v } }
    override suspend fun incrementPrepared() { context.dataStore.edit { it[K.prepared] = (it[K.prepared] ?: 0) + 1 } }

    suspend fun setMode(m: PublishMode) = context.dataStore.edit { it[K.mode] = m.name }
    suspend fun setRunning(v: Boolean) = context.dataStore.edit { it[K.running] = v }
    suspend fun setExactAlarms(v: Boolean) = context.dataStore.edit { it[K.exact] = v }
    suspend fun setTheme(t: ThemeMode) = context.dataStore.edit { it[K.theme] = t.name }
    suspend fun setRequireReview(v: Boolean) = context.dataStore.edit { it[K.review] = v }
    suspend fun setWaitForConfirmation(v: Boolean) = context.dataStore.edit { it[K.wait] = v }
    suspend fun setPrepareAhead(min: Int) = context.dataStore.edit { it[K.ahead] = min.coerceIn(0, 240) }
    suspend fun setCaptionOptions(o: CaptionOptions) = context.dataStore.edit {
        it[K.placement] = o.placement.name
        it[K.separator] = o.separator
        it[K.maxTags] = o.maxHashtags.coerceIn(0, 100)
    }
    suspend fun setTikTokConfig(clientKey: String, backendUrl: String) = context.dataStore.edit {
        it[K.clientKey] = clientKey.trim()
        it[K.backend] = backendUrl.trim().trimEnd('/')
    }
    suspend fun setSchedule(c: ScheduleConfig) = context.dataStore.edit {
        it[K.interval] = c.intervalMinutes.coerceIn(5, 7 * 24 * 60)
        it[K.days] = c.allowedDays.map { d -> d.value.toString() }.toSet()
        it[K.winStart] = c.windowStartMin.coerceIn(0, 1439)
        it[K.winEnd] = c.windowEndMin.coerceIn(1, 1440)
    }
    suspend fun clearAll() = context.dataStore.edit { it.clear() }
}
