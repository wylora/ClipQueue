package com.clipqueue.app.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.clipqueue.app.AppContainer
import com.clipqueue.app.data.AppSettings
import com.clipqueue.app.data.PostStatus
import com.clipqueue.app.data.QueueItem
import com.clipqueue.app.ui.components.*
import com.clipqueue.app.ui.theme.statusColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class ScheduleViewModel(private val c: AppContainer) : ViewModel() {
    val items = c.queue.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    fun reschedule(item: QueueItem, at: Long) = viewModelScope.launch { c.actions.reschedule(item.id, at, item.timeZone) }
    fun toggleQueue() = viewModelScope.launch { c.actions.setRunning(!c.settings.current().queueRunning) }
}

private val ru = Locale("ru")

/** День записи в календаре: опубликованные — по факту, остальные — по плану. */
private fun QueueItem.day(zone: ZoneId): LocalDate? =
    (publishedAt ?: scheduledAt)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(onOpenItem: (Long) -> Unit) {
    val vm = appViewModel { ScheduleViewModel(it) }
    val items by vm.items.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var rescheduling by remember { mutableStateOf<QueueItem?>(null) }
    val visible = remember(items) { items.filter { it.status != PostStatus.CANCELLED && it.status != PostStatus.DRAFT } }
    val byDay = remember(visible) { visible.filter { it.day(zone) != null }.groupBy { it.day(zone)!! } }
    val upcoming = remember(items) {
        items.filter { (it.status.isScheduled || it.status == PostStatus.NEEDS_CONFIRMATION) && it.scheduledAt != null }
            .sortedBy { it.scheduledAt }.take(10)
    }
    val cs = MaterialTheme.colorScheme

    Scaffold(topBar = {
        TopAppBar(title = { Text("Расписание") }, actions = {
            TextButton(onClick = vm::toggleQueue) { Text(if (settings.queueRunning) "Пауза" else "Запустить") }
        })
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(horizontal = 16.dp)) {
            item {
                if (!settings.queueRunning) {
                    InfoBanner("Очередь на паузе — время сохранено, но публикации не запускаются.", tone = Color3.Attention)
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "Интервал ${settings.schedule.intervalMinutes} мин, окно ${hhmm(settings.schedule.windowStartMin)}–${hhmm(settings.schedule.windowEndMin)}. " +
                        "Android может сдвинуть запуск на несколько минут в режиме энергосбережения.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                )
                SectionTitle("Ближайшие публикации")
                if (upcoming.isEmpty()) Text("Нет запланированных публикаций.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            items(upcoming, key = { "u${it.id}" }) { item ->
                ScheduleRow(item, zone, onClick = { onOpenItem(item.id) }, onReschedule = { rescheduling = item })
            }
            item {
                SectionTitle("Календарь")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Предыдущий месяц") }
                    Text(month.month.getDisplayName(TextStyle.FULL_STANDALONE, ru).replaceFirstChar { it.uppercase() } + " " + month.year,
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Следующий месяц") }
                }
                Row {
                    DayOfWeek.values().forEach { d ->
                        Text(d.getDisplayName(TextStyle.SHORT_STANDALONE, ru), Modifier.weight(1f), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                }
                val offset = month.atDay(1).dayOfWeek.value - 1
                val rows = (offset + month.lengthOfMonth() + 6) / 7
                for (r in 0 until rows) {
                    Row {
                        for (col in 0 until 7) {
                            val idx = r * 7 + col - offset + 1
                            if (idx < 1 || idx > month.lengthOfMonth()) {
                                Spacer(Modifier.weight(1f).aspectRatio(1f))
                            } else {
                                val date = month.atDay(idx)
                                val dayItems = byDay[date].orEmpty()
                                val isSel = date == selected
                                val isToday = date == LocalDate.now()
                                Column(
                                    Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(MaterialTheme.shapes.small)
                                        .background(if (isSel) cs.primaryContainer else cs.surface)
                                        .then(if (isToday && !isSel) Modifier.border(1.dp, cs.primary, MaterialTheme.shapes.small) else Modifier)
                                        .clickable { selected = date },
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                                ) {
                                    Text(idx.toString(), style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) cs.onPrimaryContainer else cs.onSurface)
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        dayItems.take(3).forEach { Box(Modifier.size(5.dp).clip(CircleShape).background(statusColors(it.status).bg)) }
                                    }
                                }
                            }
                        }
                    }
                }
                SectionTitle(selected.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", ru)))
            }
            val dayItems = byDay[selected].orEmpty().sortedBy { it.publishedAt ?: it.scheduledAt }
            if (dayItems.isEmpty()) item { Text("В этот день публикаций нет.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
            items(dayItems, key = { "d${it.id}" }) { item ->
                ScheduleRow(item, zone, onClick = { onOpenItem(item.id) },
                    onReschedule = if (item.status.isClosed || item.status == PostStatus.PUBLISHING) null else ({ rescheduling = item }))
            }
            item {
                val unscheduled = items.count { it.status.isScheduled && it.scheduledAt == null }
                if (unscheduled > 0) { Spacer(Modifier.height(12.dp)); InfoBanner("Ещё $unscheduled видео получат время после запуска очереди.") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    rescheduling?.let { item ->
        DateTimeDialog(item.scheduledAt ?: (System.currentTimeMillis() + 3_600_000), item.timeZone,
            onDismiss = { rescheduling = null }) { ms -> vm.reschedule(item, ms); rescheduling = null }
    }
}

@Composable
private fun ScheduleRow(item: QueueItem, zone: ZoneId, onClick: () -> Unit, onReschedule: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(MaterialTheme.shapes.medium)
            .background(cs.surfaceContainer).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val ms = item.publishedAt ?: item.scheduledAt
        Column(Modifier.width(64.dp)) {
            Text(ms?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "—",
                style = MaterialTheme.typography.titleMedium)
            Text(ms?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM", ru)) } ?: "",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        VideoThumb(item.videoUri, size = 36.dp, corner = 8.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            StatusPill(item.status)
        }
        if (onReschedule != null) IconButton(onClick = onReschedule) { Icon(Icons.Outlined.EditCalendar, "Перенести") }
    }
}

private fun hhmm(min: Int) = "%02d:%02d".format((min / 60) % 24, min % 60)
