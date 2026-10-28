package com.clipqueue.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.clipqueue.app.ClipQueueApp
import com.clipqueue.app.engine.Outcome

/** Тонкая обёртка WorkManager над PublishEngine: фаза, foreground-уведомление, перевод результата. */
class PublishWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val KEY_ID = "item_id"
        const val KEY_PHASE = "phase"
        const val KEY_FORCE = "force"
        const val PHASE_PREPARE = "prepare"
        const val PHASE_PUBLISH = "publish"
        const val PHASE_STATUS = "status"
    }

    private val c get() = (applicationContext as ClipQueueApp).container

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        if (id <= 0) return Result.success()
        val engine = c.newEngine().apply {
            onProgress = { item, f -> runCatching { setForeground(foreground(id, item.displayName, f)) } }
        }
        val outcome = when (inputData.getString(KEY_PHASE)) {
            PHASE_PREPARE -> engine.prepareAhead(id)
            PHASE_STATUS -> engine.checkStatus(id)
            else -> engine.publish(id, inputData.getBoolean(KEY_FORCE, false), runAttemptCount)
        }
        return when (outcome) {
            Outcome.Done -> Result.success()
            Outcome.Retry -> Result.retry()
            is Outcome.CheckLater -> { c.scheduler.enqueueStatusCheck(id, outcome.minutes); Result.success() }
        }
    }

    private fun foreground(id: Long, name: String, fraction: Float?): ForegroundInfo {
        val n = Notifications.progress(applicationContext, name, fraction)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(Notifications.progressId(id), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(Notifications.progressId(id), n)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = inputData.getLong(KEY_ID, -1)
        return foreground(id, c.queue.get(id)?.displayName ?: "Видео", null)
    }
}
