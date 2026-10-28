package com.clipqueue.app.domain

import com.clipqueue.app.data.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class SchedulingTest {
    private val zone = ZoneId.of("Europe/Tallinn")
    private fun ms(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val cfg = ScheduleConfig(intervalMinutes = 120, windowStartMin = 10 * 60, windowEndMin = 22 * 60)

    @Test fun lateNightMovesToNextMorning() = assertEquals(ms(23, 10, 0), ScheduleCalculator.nextSlot(ms(22, 23, 15), cfg, zone))

    @Test fun planRespectsIntervalAndWindow() =
        assertEquals(listOf(ms(22, 19, 0), ms(22, 21, 0), ms(23, 10, 0)), ScheduleCalculator.plan(3, ms(22, 19, 0), cfg, zone))

    @Test fun skipsDisallowedDays() {
        val weekend = cfg.copy(allowedDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) // 22.10.2026 — четверг
        assertEquals(ms(24, 10, 0), ScheduleCalculator.nextSlot(ms(22, 12, 0), weekend, zone))
    }

    @Test fun overnightWindow() {
        val night = cfg.copy(windowStartMin = 22 * 60, windowEndMin = 2 * 60)
        assertEquals(ms(23, 1, 0), ScheduleCalculator.nextSlot(ms(23, 1, 0), night, zone))
        assertEquals(ms(23, 22, 0), ScheduleCalculator.nextSlot(ms(23, 3, 0), night, zone))
    }

    private fun item(id: Long, pos: Int, status: PostStatus = PostStatus.PENDING, manualAt: Long? = null) = QueueItem(
        id = id, videoUri = "u$id", displayName = "v$id", durationMs = 5000, sizeBytes = 1, mimeType = null, position = pos,
        status = status, scheduledAt = manualAt, manualSchedule = manualAt != null
    )

    @Test fun plannerKeepsManualTimesAndSkipsInactive() {
        val s = AppSettings(schedule = cfg, queueRunning = true)
        val items = listOf(
            item(1, 0), item(2, 1, manualAt = ms(25, 15, 0)), item(3, 2),
            item(4, 3, PostStatus.DRAFT), item(5, 4, PostStatus.PUBLISHED), item(6, 5, PostStatus.CANCELLED)
        )
        val plan = QueuePlanner.plan(items, s, ms(22, 12, 0) - 30_000, zone)
        assertEquals(listOf(1L, 2L, 3L), plan.map { it.itemId })
        assertEquals(ms(22, 12, 0), plan[0].at)
        assertEquals(ms(25, 15, 0), plan[1].at)
        assertEquals(ms(22, 14, 0), plan[2].at)
    }

    /** Баг: видео, ждущее подтверждения, не занимало интервал — следующие стартовали через 30 секунд. */
    @Test fun waitingVideoKeepsInterval() {
        val s = AppSettings(schedule = cfg, lastPostAt = 0, waitForConfirmation = false)
        val waiting = item(1, 0, PostStatus.NEEDS_CONFIRMATION).copy(scheduledAt = ms(22, 11, 0))
        val plan = QueuePlanner.plan(listOf(waiting, item(2, 1), item(3, 2)), s, ms(22, 11, 5), zone)
        assertEquals(listOf(ms(22, 13, 0), ms(22, 15, 0)), plan.map { it.at })
    }

    @Test fun holdWhileWaitingForConfirmation() {
        val waiting = item(1, 0, PostStatus.NEEDS_CONFIRMATION)
        assertEquals(true, QueuePlanner.isOnHold(listOf(waiting, item(2, 1)), AppSettings()))
        assertEquals(false, QueuePlanner.isOnHold(listOf(waiting), AppSettings(waitForConfirmation = false)))
        assertEquals(false, QueuePlanner.isOnHold(listOf(item(2, 1)), AppSettings()))
    }

    @Test fun plannerHonoursLastPostInterval() {
        val s = AppSettings(schedule = cfg, lastPostAt = ms(22, 13, 0))
        val plan = QueuePlanner.plan(listOf(item(1, 0)), s, ms(22, 12, 0), zone)
        assertEquals(ms(22, 15, 0), plan.single().at)
    }

    /** Пересборка после перезапуска даёт тот же результат — задания не «уплывают» и не дублируются. */
    @Test fun replanIsDeterministicAfterRestart() {
        val s = AppSettings(schedule = cfg)
        val items = listOf(item(1, 0), item(2, 1), item(3, 2, manualAt = ms(24, 11, 0)))
        val now = ms(22, 12, 0)
        assertEquals(QueuePlanner.plan(items, s, now, zone), QueuePlanner.plan(items.reversed(), s, now, zone))
    }
}
