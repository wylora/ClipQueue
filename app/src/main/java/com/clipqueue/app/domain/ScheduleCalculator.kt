package com.clipqueue.app.domain

import com.clipqueue.app.data.ScheduleConfig
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Находит ближайший разрешённый слот публикации с учётом дней недели и временного окна.
 * Окно может переходить через полночь (например, 20:00–02:00).
 */
object ScheduleCalculator {

    fun nextSlot(notBefore: Long, config: ScheduleConfig, zone: ZoneId = ZoneId.systemDefault()): Long {
        var t = Instant.ofEpochMilli(notBefore).atZone(zone).withSecond(0).withNano(0)
        if (t.toInstant().toEpochMilli() < notBefore) t = t.plusMinutes(1)
        repeat(400) {
            if (isAllowed(t, config)) return t.toInstant().toEpochMilli()
            t = advanceToNextWindowStart(t, config)
        }
        return notBefore // конфигурация без разрешённых слотов — не блокируем очередь
    }

    fun isAllowed(t: ZonedDateTime, c: ScheduleConfig): Boolean {
        val minute = t.hour * 60 + t.minute
        val start = c.windowStartMin
        val end = c.windowEndMin
        return if (start < end) {
            t.dayOfWeek in c.allowedDays && minute in start until end
        } else if (start > end) {
            // окно через полночь: часть после полуночи принадлежит дню начала окна
            (minute >= start && t.dayOfWeek in c.allowedDays) ||
                (minute < end && t.minusDays(1).dayOfWeek in c.allowedDays)
        } else {
            t.dayOfWeek in c.allowedDays // start == end — весь день
        }
    }

    private fun advanceToNextWindowStart(t: ZonedDateTime, c: ScheduleConfig): ZonedDateTime {
        val startTime = LocalTime.of(c.windowStartMin / 60, c.windowStartMin % 60)
        val todayStart = t.with(startTime)
        return if (t.isBefore(todayStart)) todayStart else todayStart.plusDays(1)
    }

    /** Раскладывает автоматические записи очереди по слотам. Возвращает время для каждой. */
    fun plan(count: Int, firstNotBefore: Long, config: ScheduleConfig, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        val result = ArrayList<Long>(count)
        var cursor = firstNotBefore
        repeat(count) {
            val slot = nextSlot(cursor, config, zone)
            result += slot
            cursor = slot + config.intervalMinutes * 60_000L
        }
        return result
    }
}
