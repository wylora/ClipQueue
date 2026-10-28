package com.clipqueue.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.clipqueue.app.R
import com.clipqueue.app.ui.MainActivity

object Notifications {
    const val CH_ACTION = "publish_action"
    const val CH_PROGRESS = "upload_progress"
    const val CH_RESULT = "publish_result"
    const val CH_UPCOMING = "publish_upcoming"
    const val EXTRA_HANDOFF_ID = "handoff_item_id"
    const val EXTRA_ITEM_ID = "item_id"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_ACTION, "Требует подтверждения", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Видео подготовлено, нужно ваше действие"
            },
            NotificationChannel(CH_UPCOMING, "Скоро публикация", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Напоминание перед запланированной публикацией"
            },
            NotificationChannel(CH_PROGRESS, "Загрузка видео", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_RESULT, "Результаты публикаций", NotificationManager.IMPORTANCE_DEFAULT)
        ))
    }

    private fun canPost(context: Context) =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openHandoff(context: Context, itemId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_HANDOFF_ID, itemId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, itemId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Открывает видео в TikTok одним касанием, минуя экраны приложения. */
    fun quickShare(context: Context, itemId: Long): PendingIntent {
        val intent = Intent(context, com.clipqueue.app.ui.QuickShareActivity::class.java).apply {
            putExtra(EXTRA_ITEM_ID, itemId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        return PendingIntent.getActivity(context, 200_000 + itemId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun confirmIntent(context: Context, itemId: Long, action: String, code: Int): PendingIntent {
        val i = Intent(context, ConfirmReceiver::class.java).setAction(action).putExtra(EXTRA_ITEM_ID, itemId)
        return PendingIntent.getBroadcast(context, code + itemId.toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showActionNeeded(context: Context, itemId: Long, title: String, text: String, quickShare: Boolean = false) {
        if (!canPost(context)) return
        val b = NotificationCompat.Builder(context, CH_ACTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(if (quickShare) quickShare(context, itemId) else openHandoff(context, itemId))
            .setAutoCancel(true)
        if (quickShare) {
            b.addAction(0, "Открыть в TikTok", quickShare(context, itemId))
            b.addAction(0, "Уже опубликовал", confirmIntent(context, itemId, ConfirmReceiver.ACTION_PUBLISHED, 300_000))
        }
        NotificationManagerCompat.from(context).notify(actionId(itemId), b.build())
    }

    /** После открытия TikTok: подтверждение результата прямо из шторки. */
    fun showDidYouPost(context: Context, itemId: Long, name: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_ACTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Опубликовали «$name»?")
            .setContentText("Описание в буфере: долгое нажатие в поле описания TikTok → «Вставить»")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setContentIntent(openHandoff(context, itemId))
            .addAction(0, "Опубликовано", confirmIntent(context, itemId, ConfirmReceiver.ACTION_PUBLISHED, 300_000))
            .addAction(0, "Не получилось", confirmIntent(context, itemId, ConfirmReceiver.ACTION_FAILED, 400_000))
            .addAction(0, "Открыть снова", quickShare(context, itemId))
            .build()
        NotificationManagerCompat.from(context).notify(actionId(itemId), n)
    }

    fun showResult(context: Context, itemId: Long, title: String, text: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openHandoff(context, itemId))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(actionId(itemId), n)
    }

    fun showUpcoming(context: Context, item: com.clipqueue.app.data.QueueItem) {
        if (!canPost(context)) return
        val time = item.scheduledAt?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        } ?: "скоро"
        val n = NotificationCompat.Builder(context, CH_UPCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Публикация в $time")
            .setContentText("${item.displayName}: файл проверен, описание готово")
            .setContentIntent(openHandoff(context, item.id))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(actionId(item.id), n)
    }

    fun progress(context: Context, name: String, fraction: Float?) =
        NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Загрузка в TikTok")
            .setContentText(name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .build()

    fun cancel(context: Context, itemId: Long) = NotificationManagerCompat.from(context).cancel(actionId(itemId))
    fun actionId(itemId: Long) = 10_000 + itemId.toInt()
    fun progressId(itemId: Long) = 50_000 + itemId.toInt()
}

/** Реализация уведомлений для ядра публикации. */
class AndroidNotifier(private val context: Context) : com.clipqueue.app.engine.Notifier {
    override fun upcoming(item: com.clipqueue.app.data.QueueItem) = Notifications.showUpcoming(context, item)
    override fun actionNeeded(item: com.clipqueue.app.data.QueueItem, reason: String, quickShare: Boolean) =
        Notifications.showActionNeeded(context, item.id,
            if (quickShare) "Пора публиковать: ${item.displayName}" else "Требует подтверждения: ${item.displayName}",
            reason, quickShare)
    override fun result(item: com.clipqueue.app.data.QueueItem, title: String, text: String) =
        Notifications.showResult(context, item.id, title, text)
}
