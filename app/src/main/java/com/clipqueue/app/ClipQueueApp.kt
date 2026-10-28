package com.clipqueue.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.clipqueue.app.auth.TikTokAuth
import com.clipqueue.app.auth.TokenStore
import com.clipqueue.app.data.*
import com.clipqueue.app.engine.PostPreparer
import com.clipqueue.app.engine.PublishEngine
import com.clipqueue.app.media.VideoInspector
import com.clipqueue.app.publish.ShareLauncher
import com.clipqueue.app.publish.TikTokApiClient
import com.clipqueue.app.work.AndroidNotifier
import com.clipqueue.app.work.Notifications
import com.clipqueue.app.work.PublishScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Контейнер зависимостей без DI-фреймворков. */
class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = Room.databaseBuilder(context, AppDatabase::class.java, "clipqueue.db")
        .fallbackToDestructiveMigration()
        .build()
    val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    val settings = SettingsRepository(context)
    val queue = QueueRepository(db)
    val hashtags = HashtagRepository(db.hashtags())
    val templates = TemplateRepository(db.templates())
    val inspector = VideoInspector(context)
    val auth = TikTokAuth(context, settings, TokenStore(context), http)
    val api = TikTokApiClient(http, context.contentResolver)
    val preparer = PostPreparer(inspector, RoomCaptionInputs(hashtags, templates, settings))
    val share = ShareLauncher(context)
    val notifier = AndroidNotifier(context)
    val scheduler = PublishScheduler(context, queue, settings)
    val actions = QueueActions(queue, hashtags, settings, inspector, scheduler)
    val wiper = DataWiper(context, db, settings, queue, hashtags, templates, inspector, auth, scheduler)

    fun newEngine() = PublishEngine(queue, settings, preparer, api, auth, notifier)
}

class ClipQueueApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.appScope.launch {
            container.hashtags.ensureSeed()
            container.templates.ensureSeed()
            container.actions.repairHashtagSelection()
            container.scheduler.replan() // восстановление заданий после перезапуска приложения
        }
    }

    /** Миниатюры: Coil извлекает один кадр, не читая файл целиком. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
