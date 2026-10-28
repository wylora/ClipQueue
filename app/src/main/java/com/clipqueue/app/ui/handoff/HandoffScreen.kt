package com.clipqueue.app.ui.handoff

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.clipqueue.app.AppContainer
import com.clipqueue.app.data.*
import com.clipqueue.app.domain.CaptionResult
import com.clipqueue.app.engine.Preparation
import com.clipqueue.app.ui.components.*
import com.clipqueue.app.ui.item.CaptionPreview
import com.clipqueue.app.work.Notifications
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Экран подтверждения (режим B и обязательная проверка текста).
 * «Опубликовано» отмечает только пользователь: приложение не видит результат внутри TikTok.
 */
class HandoffViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val item = c.queue.item(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val caption = MutableStateFlow<CaptionResult?>(null)
    val error = MutableStateFlow<String?>(null)
    val launched = MutableStateFlow(false)
    val apiAvailable get() = c.auth.isConnected()

    init { prepare() }

    fun prepare() = viewModelScope.launch {
        val current = c.queue.get(id) ?: return@launch
        if (current.status == PostStatus.PUBLISHED || current.status == PostStatus.PUBLISHING) {
            caption.value = c.preparer.caption(current); return@launch
        }
        when (val p = c.preparer.prepare(current)) {
            is Preparation.Failed -> { error.value = p.reason; caption.value = c.preparer.caption(current) }
            is Preparation.Ready -> {
                error.value = null
                caption.value = p.caption
                val reason = current.statusReason.takeIf { current.status == PostStatus.NEEDS_CONFIRMATION }
                    ?: "Публикация через приложение TikTok: осталось нажать «Опубликовать»"
                if (current.preparedAt == null) c.settings.incrementPrepared()
                c.queue.update(p.item.copy(status = PostStatus.NEEDS_CONFIRMATION, statusReason = reason,
                    preparedAt = current.preparedAt ?: System.currentTimeMillis()))
                c.scheduler.replan() // снимает запланированный запуск этого задания
                c.share.copyCaption(p.caption.text)
            }
        }
    }

    suspend fun openTikTok(activity: android.content.Context): Result<Unit> {
        val current = c.queue.get(id) ?: return Result.failure(IllegalStateException("Задание удалено"))
        caption.value?.let { c.share.copyCaption(it.text) }
        val r = c.share.launch(activity, current.videoUri, current.mimeType)
        if (r.isSuccess) {
            launched.value = true
            c.queue.log(id, "Видео открыто в TikTok: ${current.displayName}")
            c.settings.setLastPostAt(System.currentTimeMillis())
        } else c.queue.log(id, "Не удалось открыть TikTok: ${r.exceptionOrNull()?.message}", isError = true)
        return r
    }

    fun approveViaApi(onDone: () -> Unit) = viewModelScope.launch { c.actions.approveAndPublish(id); onDone() }
    fun markPublished(onDone: () -> Unit) = viewModelScope.launch { c.actions.confirmPublished(id); onDone() }
    fun markFailed(onDone: () -> Unit) = viewModelScope.launch { c.actions.markNotPublished(id); onDone() }
    fun postpone(hours: Int, onDone: () -> Unit) = viewModelScope.launch {
        val current = c.queue.get(id) ?: return@launch
        c.actions.reschedule(id, System.currentTimeMillis() + hours * 3_600_000L, current.timeZone)
        onDone()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandoffScreen(id: Long, onBack: () -> Unit) {
    val vm = appViewModel(key = "handoff$id") { HandoffViewModel(it, id) }
    val item by vm.item.collectAsStateWithLifecycle()
    val caption by vm.caption.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val launched by vm.launched.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = container()
    LaunchedEffect(id) { Notifications.cancel(ctx, id) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Подтверждение публикации") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") } })
        }
    ) { pad ->
        val rec = item
        if (rec == null) { Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Задание не найдено") }; return@Scaffold }
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VideoThumb(rec.videoUri, size = 64.dp, corner = 14.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(rec.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Spacer(Modifier.height(4.dp)); StatusPill(rec.status)
                }
            }
            Spacer(Modifier.height(16.dp))

            if (rec.status == PostStatus.PUBLISHED) {
                InfoBanner("Видео отмечено как опубликованное. ${rec.resultInfo ?: ""}", tone = Color3.Good)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
                return@Column
            }
            if (rec.status == PostStatus.PUBLISHING) {
                InfoBanner("Видео загружается в TikTok через API. Результат придёт уведомлением.")
                return@Column
            }
            rec.statusReason?.let { InfoBanner(it, tone = Color3.Attention); Spacer(Modifier.height(12.dp)) }
            error?.let { e ->
                InfoBanner("Публикация невозможна: $e", tone = Color3.Attention) { TextButton(onClick = { vm.prepare() }) { Text("Проверить снова") } }
                Spacer(Modifier.height(12.dp))
            }

            val inbox = rec.resultInfo == "Видео в черновиках TikTok"
            val canApi = settings.publishMode != PublishMode.SHARE && vm.apiAvailable && !inbox
            val steps = when {
                inbox -> listOf(
                    "Видео уже загружено. Откройте TikTok и уведомление о новом видео во «Входящих»",
                    "Если описание не подставилось — долгое нажатие в поле описания → «Вставить» (текст уже в буфере)",
                    "Нажмите «Опубликовать» в TikTok и вернитесь сюда, чтобы отметить результат")
                canApi -> listOf(
                    "Проверьте итоговое описание ниже",
                    "Нажмите «Подтвердить и опубликовать» — видео уйдёт через TikTok API, результат придёт уведомлением")
                else -> listOf(
                    "Описание с хештегами уже скопировано в буфер обмена",
                    "Нажмите «Открыть в TikTok» — видео откроется в редакторе TikTok",
                    "В поле описания: долгое нажатие → «Вставить», затем «Опубликовать»",
                    "Вернитесь сюда и отметьте результат")
            }
            steps.forEachIndexed { i, s -> Step(i + 1, s) }

            SectionTitle("Описание")
            caption?.let { p -> CaptionPreview(p) { c.share.copyCaption(p.text); Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show() } }

            Spacer(Modifier.height(20.dp))
            if (canApi) {
                Button(onClick = { vm.approveViaApi(onBack) }, enabled = error == null, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Outlined.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text("Подтвердить и опубликовать")
                }
                Spacer(Modifier.height(8.dp))
            }
            if (inbox) {
                Button(onClick = {
                    c.share.installedTikTok()?.let { pkg -> ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { li -> ctx.startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                        ?: Toast.makeText(ctx, "Приложение TikTok не найдено", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Открыть TikTok") }
            } else {
                val share: @Composable RowScope.() -> Unit = { Icon(Icons.Outlined.Send, null); Spacer(Modifier.width(8.dp)); Text("Открыть в TikTok") }
                val onShare = { scope.launch { vm.openTikTok(ctx).onFailure { e -> Toast.makeText(ctx, e.message ?: "Ошибка", Toast.LENGTH_LONG).show() } }; Unit }
                if (canApi) OutlinedButton(onClick = onShare, enabled = error == null, modifier = Modifier.fillMaxWidth(), content = share)
                else Button(onClick = onShare, enabled = error == null, modifier = Modifier.fillMaxWidth().height(56.dp), content = share)
            }

            if (!canApi || launched || inbox) {
                Spacer(Modifier.height(20.dp))
                Text(if (launched || inbox) "Как прошла публикация?" else "Уже опубликовали сами?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { vm.markPublished(onBack) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.CheckCircle, null); Spacer(Modifier.width(8.dp)); Text("Опубликовано")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.postpone(1, onBack) }, modifier = Modifier.weight(1f)) { Text("Отложить на 1 ч") }
                OutlinedButton(onClick = { vm.markFailed(onBack) }, modifier = Modifier.weight(1f)) { Text("Не получилось") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Text(n.toString(), style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
    }
}
