package com.clipqueue.app.ui.home

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.clipqueue.app.AppContainer
import com.clipqueue.app.data.*
import com.clipqueue.app.ui.components.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class Integration(val ok: Boolean, val title: String, val detail: String)

data class HomeState(
    val inQueue: Int = 0,
    val published: Int = 0,
    val errors: Int = 0,
    val next: QueueItem? = null,
    val needsConfirmation: List<QueueItem> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val history: List<HistoryEntry> = emptyList(),
    val integration: Integration = Integration(false, "", ""),
    val onHold: Boolean = false
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val tick = MutableStateFlow(0)

    val state: StateFlow<HomeState> = combine(c.queue.items, c.settings.settings, c.queue.recentHistory(12), tick) { items, s, h, _ ->
        HomeState(
            inQueue = items.count { it.status.isScheduled },
            published = items.count { it.status == PostStatus.PUBLISHED },
            errors = items.count { it.status == PostStatus.ERROR },
            next = items.filter { it.status.isScheduled && it.scheduledAt != null }.minByOrNull { it.scheduledAt!! },
            needsConfirmation = items.filter { it.status == PostStatus.NEEDS_CONFIRMATION },
            settings = s,
            history = h,
            integration = integration(s),
            onHold = s.queueRunning && com.clipqueue.app.domain.QueuePlanner.isOnHold(items, s)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeState())

    private fun integration(s: AppSettings): Integration {
        val tiktok = c.share.installedTikTok() != null
        val t = c.auth.tokens()
        return when (s.publishMode) {
            PublishMode.SHARE -> if (tiktok) Integration(true, "Публикация в одно касание",
                    "В назначенное время придёт уведомление: нажмите его — видео откроется в TikTok с описанием в буфере. " +
                    "Полностью без вашего участия публикует только TikTok API — подключается в настройках")
                else Integration(false, "TikTok не найден", "Установите приложение TikTok или подключите API в настройках")
            else -> when {
                t == null || !t.isRefreshValid -> Integration(false, "Аккаунт TikTok не подключён",
                    "Задания будут переходить в «Требует подтверждения». Подключите аккаунт в настройках")
                s.publishMode == PublishMode.API_DIRECT && !t.hasScope("video.publish") ->
                    Integration(false, "Нет разрешения video.publish", "Войдите заново и разрешите публикацию")
                else -> Integration(true, "TikTok API подключён", "Аккаунт: ${t.displayName ?: t.openId}. ${s.publishMode.title}")
            }
        }
    }

    fun refresh() { tick.value++ }
    fun toggleQueue() = viewModelScope.launch { c.actions.setRunning(!c.settings.current().queueRunning) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenItem: (Long) -> Unit, onHandoff: (Long) -> Unit,
    onOpenVideos: () -> Unit, onOpenHistory: () -> Unit, onOpenSettings: () -> Unit
) {
    val vm = appViewModel { HomeViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val pickers = rememberVideoPickers { n -> Toast.makeText(ctx, if (n > 0) "Добавлено видео: $n" else "Не удалось прочитать видео", Toast.LENGTH_SHORT).show() }
    LaunchedEffect(Unit) { while (true) { delay(30_000); vm.refresh() } }

    Scaffold(topBar = { TopAppBar(title = { Text("ClipQueue", style = MaterialTheme.typography.headlineSmall) }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item { NextPostHero(s, onOpenItem) }
            item { Spacer(Modifier.height(14.dp)); CountsStrip(s, onOpenVideos) }
            item { Spacer(Modifier.height(14.dp)); QueueControl(s, vm::toggleQueue) }
            item { Spacer(Modifier.height(10.dp)); IntegrationCard(s.integration, onOpenSettings) }

            if (s.onHold) item {
                Spacer(Modifier.height(10.dp))
                InfoBanner("Следующее видео ждёт, пока вы подтвердите предыдущее. Так неподтверждённые не копятся; отключается в настройках.",
                    tone = Color3.Attention)
            }
            if (s.needsConfirmation.isNotEmpty()) {
                item { SectionTitle("Требуют подтверждения") }
                items(s.needsConfirmation, key = { it.id }) { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable { onHandoff(item.id) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VideoThumb(item.videoUri, size = 40.dp, corner = 8.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(item.statusReason ?: "Откройте и завершите публикацию", style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f))
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = pickers.fromGallery, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Outlined.AddToPhotos, null); Spacer(Modifier.width(8.dp)); Text("Добавить видео")
                    }
                    OutlinedButton(onClick = pickers.fromFiles, modifier = Modifier.height(52.dp)) {
                        Icon(Icons.Outlined.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Файлы")
                    }
                }
            }

            item {
                SectionTitle("Последние события") {
                    TextButton(onClick = onOpenHistory) { Text("История и статистика") }
                }
            }
            if (s.history.isEmpty()) {
                item { Text("Здесь появятся добавления, публикации и ошибки.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(s.history, key = { "h${it.id}" }) { HistoryRow(it) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun NextPostHero(s: HomeState, onOpenItem: (Long) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val next = s.next
    Box(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(cs.primaryContainer, cs.surfaceContainerHigh)))
            .then(if (next != null) Modifier.clickable { onOpenItem(next.id) } else Modifier)
            .padding(20.dp)
    ) {
        Column {
            Text("Ближайшая публикация", style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer.copy(alpha = 0.75f))
            Spacer(Modifier.height(6.dp))
            if (next == null) {
                Text(if (!s.settings.queueRunning && s.inQueue > 0) "Очередь на паузе" else "Ничего не запланировано",
                    style = MaterialTheme.typography.headlineMedium, color = cs.onPrimaryContainer)
                Text(if (s.inQueue > 0) "Запустите очередь, и видео разойдутся по расписанию" else "Добавьте видео из галереи, чтобы собрать очередь",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer.copy(alpha = 0.8f))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (s.settings.queueRunning) formatRelative(next.scheduledAt!!) else "на паузе",
                            style = MaterialTheme.typography.displaySmall, color = cs.onPrimaryContainer)
                        Text(formatInZone(next.scheduledAt, next.timeZone), style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Text(next.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, color = cs.onPrimaryContainer.copy(alpha = 0.85f))
                    }
                    Spacer(Modifier.width(14.dp))
                    VideoThumb(next.videoUri, size = 72.dp, corner = 14.dp)
                }
            }
        }
    }
}

@Composable
private fun CountsStrip(s: HomeState, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(cs.surfaceContainer).clickable(onClick = onClick).padding(vertical = 14.dp)) {
        Count("В очереди", s.inQueue, cs.onSurface, Modifier.weight(1f))
        VerticalDivider(Modifier.height(40.dp).align(Alignment.CenterVertically), color = cs.outlineVariant)
        Count("Опубликовано", s.published, cs.onSurface, Modifier.weight(1f))
        VerticalDivider(Modifier.height(40.dp).align(Alignment.CenterVertically), color = cs.outlineVariant)
        Count("Ошибки", s.errors, if (s.errors > 0) cs.error else cs.onSurface, Modifier.weight(1f))
    }
}

@Composable
private fun Count(label: String, value: Int, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QueueControl(s: HomeState, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val running = s.settings.queueRunning
    val dot by animateColorAsState(if (running) cs.primary else cs.outline, label = "dot")
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(cs.surfaceContainer).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (running) "Очередь работает" else "Очередь на паузе", style = MaterialTheme.typography.titleMedium)
            Text(if (running) "Публикации идут по расписанию" else "Задания сохранены, публикации не запускаются",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        FilledTonalButton(onClick = onToggle) {
            Icon(if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text(if (running) "Пауза" else "Запустить")
        }
    }
}

@Composable
private fun IntegrationCard(i: Integration, onOpenSettings: () -> Unit) {
    InfoBanner(
        "${i.title}\n${i.detail}",
        tone = if (i.ok) Color3.Good else Color3.Attention
    ) { if (!i.ok) TextButton(onClick = onOpenSettings) { Text("Настроить") } }
}

@Composable
fun HistoryRow(h: HistoryEntry) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(if (h.isError) cs.error else cs.primary))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.message, style = MaterialTheme.typography.bodyMedium, color = if (h.isError) cs.error else cs.onSurface)
            Text(formatDateTime(h.timestamp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}
