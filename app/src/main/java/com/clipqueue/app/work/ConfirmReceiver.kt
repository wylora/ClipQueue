package com.clipqueue.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clipqueue.app.ClipQueueApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Кнопки «Опубликовано» / «Не получилось» прямо в уведомлении. */
class ConfirmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PUBLISHED = "com.clipqueue.app.CONFIRM_PUBLISHED"
        const val ACTION_FAILED = "com.clipqueue.app.CONFIRM_FAILED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Notifications.EXTRA_ITEM_ID, -1)
        if (id <= 0) return
        val pending = goAsync()
        val c = (context.applicationContext as ClipQueueApp).container
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_PUBLISHED -> c.actions.confirmPublished(id)
                    ACTION_FAILED -> c.actions.markNotPublished(id)
                }
                Notifications.cancel(context, id)
            } finally {
                pending.finish()
            }
        }
    }
}
