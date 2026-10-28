package com.clipqueue.app.domain

import com.clipqueue.app.data.AppSettings
import com.clipqueue.app.data.PostStatus
import com.clipqueue.app.data.QueueItem
import java.time.ZoneId

/**
 * Чистая функция планирования: по одной и той же очереди и настройкам всегда даёт один результат.
 * Поэтому пересборка после перезагрузки, обновления или смены времени безопасна.
 *  - видео с конкретным временем сохраняют его;
 *  - остальные получают слоты по интервалу, дням недели и окну публикаций в порядке очереди;
 *  - первый слот — не раньше чем через интервал после последней публикации.
 */
object QueuePlanner {
    data class Slot(val itemId: Long, val at: Long)

    private val dispatched = setOf(PostStatus.NEEDS_CONFIRMATION, PostStatus.PUBLISHING, PostStatus.PUBLISHED)

    /**
     * Момент последней отправки. Учитывает и сохранённое время, и сами отправленные видео —
     * поэтому видео, ждущее подтверждения, тоже «занимает» свой интервал и следующие не стартуют сразу.
     */
    fun lastDispatch(items: List<QueueItem>, s: AppSettings, now: Long): Long {
        val fromItems = items.filter { it.status in dispatched }
            .mapNotNull { it.publishedAt ?: it.lastAttemptAt ?: it.scheduledAt?.takeIf { t -> t <= now } }
            .filter { it <= now }
            .maxOrNull() ?: 0L
        return maxOf(s.lastPostAt, fromItems)
    }

    /** Режим «ждать подтверждения»: пока есть видео, ждущее вас, следующее автоматически не запускается. */
    fun isOnHold(items: List<QueueItem>, s: AppSettings): Boolean =
        s.waitForConfirmation && items.any { it.status == PostStatus.NEEDS_CONFIRMATION }

    fun plan(items: List<QueueItem>, s: AppSettings, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Slot> {
        val active = items.filter { it.status.isScheduled }.sortedBy { it.position }
        val auto = active.filter { !it.manualSchedule || it.scheduledAt == null }
        val last = lastDispatch(items, s, now)
        val sinceLast = if (last > 0) last + s.schedule.intervalMinutes * 60_000L else 0L
        val first = maxOf(now + 30_000, sinceLast)
        val times = ScheduleCalculator.plan(auto.size, first, s.schedule, zone)
        val autoTimes = auto.zip(times).associate { (item, t) -> item.id to t }
        return active.map { Slot(it.id, autoTimes[it.id] ?: it.scheduledAt!!) }
    }
}
