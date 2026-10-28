package com.clipqueue.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clipqueue.app.ClipQueueApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Перезагрузка, обновление приложения, смена времени/часового пояса — пересобираем расписание. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as ClipQueueApp
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                app.container.scheduler.replan()
                app.container.queue.log(null, "Расписание восстановлено (${reason(intent.action)})")
            } finally {
                pending.finish()
            }
        }
    }

    private fun reason(action: String?) = when (action) {
        Intent.ACTION_BOOT_COMPLETED -> "перезагрузка"
        Intent.ACTION_MY_PACKAGE_REPLACED -> "обновление приложения"
        Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> "смена времени"
        else -> "системное событие"
    }
}

/** Точный будильник сработал — запускаем публикацию немедленно. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(PublishWorker.KEY_ID, -1)
        if (id > 0) (context.applicationContext as ClipQueueApp).container.scheduler.runNow(id)
    }
}
