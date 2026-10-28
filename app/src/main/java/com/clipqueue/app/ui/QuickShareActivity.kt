package com.clipqueue.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.clipqueue.app.ClipQueueApp
import com.clipqueue.app.data.PostStatus
import com.clipqueue.app.engine.Preparation
import com.clipqueue.app.work.Notifications
import kotlinx.coroutines.launch

/**
 * Невидимый экран для публикации в одно касание из уведомления:
 * проверяет видео, копирует описание с хештегами в буфер, открывает редактор TikTok
 * и оставляет уведомление «Опубликовали?» с кнопками — заходить в ClipQueue не нужно.
 * (Android разрешает открыть другое приложение только по действию пользователя — это касание и есть.)
 */
class QuickShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(Notifications.EXTRA_ITEM_ID, -1)
        val c = (application as ClipQueueApp).container
        if (id <= 0) { finish(); return }
        Notifications.cancel(this, id)
        lifecycleScope.launch {
            val item = c.queue.get(id)
            if (item == null || item.status == PostStatus.PUBLISHED) { finish(); return@launch }
            when (val p = c.preparer.prepare(item)) {
                is Preparation.Failed -> {
                    c.queue.update(item.copy(status = PostStatus.ERROR, lastError = p.reason))
                    c.queue.log(id, "Не удалось открыть в TikTok: ${p.reason}", isError = true)
                    Toast.makeText(this@QuickShareActivity, p.reason, Toast.LENGTH_LONG).show()
                }
                is Preparation.Ready -> {
                    c.share.copyCaption(p.caption.text)
                    val r = c.share.launch(this@QuickShareActivity, item.videoUri, item.mimeType)
                    if (r.isSuccess) {
                        Toast.makeText(this@QuickShareActivity, "Описание с хештегами скопировано — вставьте его в TikTok", Toast.LENGTH_LONG).show()
                        // Экран сейчас закроется — запись делаем в фоне приложения, не привязанном к экрану.
                        val ctx = applicationContext
                        c.appScope.launch {
                            c.queue.update(p.item.copy(status = PostStatus.NEEDS_CONFIRMATION,
                                statusReason = "Видео открыто в TikTok. Подтвердите, когда опубликуете"))
                            c.queue.log(id, "Открыто в TikTok: ${item.displayName}")
                            c.settings.setLastPostAt(System.currentTimeMillis())
                            c.scheduler.replan()
                            Notifications.showDidYouPost(ctx, id, item.displayName)
                        }
                    } else {
                        Toast.makeText(this@QuickShareActivity, r.exceptionOrNull()?.message ?: "Не удалось открыть TikTok", Toast.LENGTH_LONG).show()
                    }
                }
            }
            finish()
        }
    }
}
