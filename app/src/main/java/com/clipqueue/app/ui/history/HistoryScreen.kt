package com.clipqueue.app.ui.history

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.clipqueue.app.AppContainer
import com.clipqueue.app.data.*
import com.clipqueue.app.ui.components.*
import com.clipqueue.app.ui.home.HistoryRow
import kotlinx.coroutines.flow.*

data class Stats(val prepared: Int = 0, val confirmed: Int = 0, val errors: Int = 0, val pending: Int = 0)

class HistoryViewModel(c: AppContainer) : ViewModel() {
    val records = c.queue.publishRecords.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val journal = c.queue.recentHistory(300).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** Только реальные данные приложения: просмотры и лайки через Content Posting API недоступны. */
    val stats = combine(c.queue.publishRecords, c.queue.items, c.settings.settings) { r, items, s ->
        Stats(
            prepared = s.preparedTotal,
            confirmed = r.count { it.result == RecordResult.PUBLISHED_API || it.result == RecordResult.CONFIRMED_BY_USER },
            errors = r.count { it.result == RecordResult.FAILED },
            pending = items.count { it.status.isScheduled || it.status == PostStatus.NEEDS_CONFIRMATION || it.status == PostStatus.PUBLISHING }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Stats())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenItem: (Long) -> Unit) {
    val vm = appViewModel { HistoryViewModel(it) }
    val records by vm.records.collectAsStateWithLifecycle()
    val journal by vm.journal.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val cs = MaterialTheme.colorScheme

    Scaffold(topBar = {
        Column {
            TopAppBar(title = { Text("История") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") } })
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Публикации") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Журнал событий") })
            }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp)) {
            if (tab == 0) {
                item {
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(cs.surfaceContainer).padding(vertical = 14.dp)) {
                        Stat("Подготовлено", stats.prepared, Modifier.weight(1f))
                        Stat("Подтверждено", stats.confirmed, Modifier.weight(1f))
                        Stat("Ошибок", stats.errors, Modifier.weight(1f))
                        Stat("Ожидают", stats.pending, Modifier.weight(1f))
                    }
                    Text("Просмотры и лайки не показываются: Content Posting API их не предоставляет.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                }
                if (records.isEmpty()) item { EmptyState("Публикаций пока нет", "Здесь появятся результаты: опубликовано, ошибки, отмены.") }
                items(records, key = { it.id }) { r -> RecordCard(r, onOpenItem) }
            } else {
                if (journal.isEmpty()) item { EmptyState("Журнал пуст", "Здесь записываются все действия приложения.") }
                items(journal, key = { it.id }) { HistoryRow(it) }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecordCard(r: PublishRecord, onOpenItem: (Long) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val good = r.result == RecordResult.PUBLISHED_API || r.result == RecordResult.CONFIRMED_BY_USER
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(MaterialTheme.shapes.medium).background(cs.surfaceContainer)
            .clickable { open = !open }.animateContentSize().padding(14.dp)
    ) {
        Text(r.videoName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(r.result.label, style = MaterialTheme.typography.bodySmall,
            color = when { good -> cs.primary; r.result == RecordResult.FAILED -> cs.error; else -> cs.onSurfaceVariant })
        Text("План: ${formatDateTime(r.scheduledAt)}" + (r.publishedAt?.let { ", факт: ${formatDateTime(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        if (open) {
            Spacer(Modifier.height(8.dp))
            if (r.caption.isNotBlank()) Text(r.caption, style = MaterialTheme.typography.bodyMedium)
            if (r.hashtags.isNotBlank()) Text("Хештеги: ${r.hashtags}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            r.error?.let { Text("Ошибка: $it", style = MaterialTheme.typography.bodySmall, color = cs.error) }
            r.publishId?.let { Text("ID в TikTok: $it", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            Text("Способ: ${r.mode.title}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            TextButton(onClick = { onOpenItem(r.itemId) }, contentPadding = PaddingValues(0.dp)) { Text("Открыть задание") }
        }
    }
}
