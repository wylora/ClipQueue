package com.clipqueue.app.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import com.clipqueue.app.data.PostStatus
import com.clipqueue.app.data.QueueRepository
import com.clipqueue.app.data.SettingsRepository
import com.clipqueue.app.domain.QueuePlanner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Раскладывает очередь по времени и ставит задачи:
 *  - «prepare-ID» за N минут: проверка файла, снимок описания, напоминание;
 *  - «publish-ID» в назначенное время.
 * По умолчанию — WorkManager (переживает перезагрузку, но в Doze возможна задержка на минуты).
 * С точными будильниками публикацию запускает AlarmManager.setExactAndAllowWhileIdle.
 * Никаких бесконечных циклов: только разовые задачи под конкретные задания.
 */
class PublishScheduler(
    private val context: Context,
    private val queue: QueueRepository,
    private val settings: SettingsRepository
) {
    private val wm get() = WorkManager.getInstance(context)
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val mutex = Mutex()

    companion object {
        const val TAG = "clipqueue"
        fun publishName(id: Long) = "publish-$id"
        fun prepareName(id: Long) = "prepare-$id"
        fun statusName(id: Long) = "status-$id"
    }

    fun canUseExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    suspend fun replan() = mutex.withLock {
        val s = settings.current()
        val all = queue.getAll()
        val now = System.currentTimeMillis()

        // Всё, что не в расписании, — без задач.
        all.filter { !it.status.isScheduled }.forEach { cancel(it.id) }
        all.filter { it.status == PostStatus.PUBLISHING && it.publishId != null }.forEach { enqueueStatusCheck(it.id, 0) }

        if (!s.queueRunning) {
            all.filter { it.status.isScheduled }.forEach { cancel(it.id) }
            return@withLock
        }
        val exact = s.exactAlarms && canUseExactAlarms()
        val hold = QueuePlanner.isOnHold(all, s)
        for (slot in QueuePlanner.plan(all, s, now)) {
            val item = all.first { it.id == slot.itemId }
            if (item.scheduledAt != slot.at) queue.update(item.copy(scheduledAt = slot.at))
            if (hold && !item.manualSchedule) {
                // Ждём подтверждения предыдущего видео; после него расписание пересоберётся.
                cancel(item.id)
                continue
            }
            val prepareAt = slot.at - s.prepareAheadMinutes * 60_000L
            if (item.status == PostStatus.PENDING && s.prepareAheadMinutes > 0 && prepareAt > now + 60_000) {
                enqueue(prepareName(item.id), PublishWorker.PHASE_PREPARE, item.id, prepareAt - now)
            } else {
                wm.cancelUniqueWork(prepareName(item.id))
            }
            schedulePublish(item.id, slot.at, exact)
        }
    }

    private fun schedulePublish(itemId: Long, at: Long, exact: Boolean) {
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
        if (exact && delay > 60_000) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(itemId))
            wm.cancelUniqueWork(publishName(itemId))
        } else {
            alarms.cancel(alarmIntent(itemId))
            enqueue(publishName(itemId), PublishWorker.PHASE_PUBLISH, itemId, delay)
        }
    }

    private fun enqueue(name: String, phase: String, itemId: Long, delayMs: Long, force: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<PublishWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(PublishWorker.KEY_ID to itemId, PublishWorker.KEY_PHASE to phase, PublishWorker.KEY_FORCE to force))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
    }

    /** «Опубликовать сейчас» и срабатывание точного будильника. */
    fun runNow(itemId: Long) {
        val req = OneTimeWorkRequestBuilder<PublishWorker>()
            .setInputData(workDataOf(PublishWorker.KEY_ID to itemId, PublishWorker.KEY_PHASE to PublishWorker.PHASE_PUBLISH, PublishWorker.KEY_FORCE to true))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork(publishName(itemId), ExistingWorkPolicy.REPLACE, req)
    }

    fun enqueueStatusCheck(itemId: Long, delayMinutes: Long) {
        val req = OneTimeWorkRequestBuilder<PublishWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(PublishWorker.KEY_ID to itemId, PublishWorker.KEY_PHASE to PublishWorker.PHASE_STATUS))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork(statusName(itemId), ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(itemId: Long) {
        wm.cancelUniqueWork(publishName(itemId))
        wm.cancelUniqueWork(prepareName(itemId))
        alarms.cancel(alarmIntent(itemId))
    }

    fun cancelAll() {
        wm.cancelAllWorkByTag(TAG)
    }

    private fun alarmIntent(itemId: Long): PendingIntent {
        val i = Intent(context, AlarmReceiver::class.java).putExtra(PublishWorker.KEY_ID, itemId)
        return PendingIntent.getBroadcast(context, itemId.toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
