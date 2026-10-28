package com.clipqueue.app.ui.videos

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.clipqueue.app.AppContainer
import com.clipqueue.app.data.*
import com.clipqueue.app.media.formatDuration
import com.clipqueue.app.media.formatSize
import com.clipqueue.app.ui.components.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class VideoFilter(val label: String, val match: (PostStatus) -> Boolean) {
    ALL("Все", { true }),
    WAITING("Ожидают", { it.isScheduled }),
    CONFIRM("Требуют подтверждения", { it == PostStatus.NEEDS_CONFIRMATION }),
    DRAFT("Черновики", { it == PostStatus.DRAFT }),
    PUBLISHING("Публикуются", { it == PostStatus.PUBLISHING }),
    DONE("Опубликованные", { it == PostStatus.PUBLISHED }),
    ERRORS("Ошибки", { it == PostStatus.ERROR }),
    CANCELLED("Отменённые", { it == PostStatus.CANCELLED })
}

class VideosViewModel(private val c: AppContainer) : ViewModel() {
    val filter = MutableStateFlow(VideoFilter.ALL)
    val query = MutableStateFlow("")
    val items = combine(c.queue.items, filter, query) { list, f, q ->
        list.filter { f.match(it.status) && (q.isBlank() || it.displayName.contains(q.trim(), ignoreCase = true)
            || it.notes.contains(q.trim(), ignoreCase = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    val templates = c.templates.templates.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sets = c.hashtags.sets.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun run(block: suspend () -> Unit) = viewModelScope.launch { block() }

    fun reorder(ids: List<Long>) = run { c.actions.reorder(ids) }
    fun copy(id: Long, done: () -> Unit) = run { c.actions.copy(id); done() }
    fun delete(ids: Set<Long>) = run { c.actions.delete(ids) }
    fun cancel(ids: Set<Long>) = run { c.actions.cancel(ids) }
    fun enqueue(ids: Set<Long>) = run { c.actions.enqueue(ids) }
    fun toDraft(ids: Set<Long>) = run { c.actions.toDraft(ids) }
    fun applyTemplate(ids: Set<Long>, templateId: Long?) = run { c.actions.applyTemplate(ids, templateId) }
    fun applySets(ids: Set<Long>, setIds: List<Long>, replace: Boolean) = run { c.actions.applySets(ids, setIds, replace) }
    fun applyPrivacy(ids: Set<Long>, code: String) = run { c.actions.applyPrivacy(ids, code) }
    fun cancelAllPending(done: (Int) -> Unit) = run { done(c.actions.cancelAllPending()) }
    fun toggleQueue() = run { c.actions.setRunning(!c.settings.current().queueRunning) }
    fun publishNow(id: Long, openHandoff: (Long) -> Unit) = run { if (c.actions.publishNow(id)) openHandoff(id) }
}

private class DragState {
    var key by mutableStateOf<Long?>(null)
    var offset by mutableFloatStateOf(0f)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VideosScreen(onOpenItem: (Long) -> Unit, onHandoff: (Long) -> Unit) {
    val vm = appViewModel { VideosViewModel(it) }
    val items by vm.items.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val sets by vm.sets.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val pickers = rememberVideoPickers { n -> Toast.makeText(ctx, if (n > 0) "Добавлено видео: $n" else "Не удалось прочитать видео", Toast.LENGTH_SHORT).show() }

    var selected by remember { mutableStateOf(setOf<Long>()) }
    val selecting = selected.isNotEmpty()
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // Перетаскивание: локальная копия порядка, в БД пишем по окончании жеста.
    val listState = rememberLazyListState()
    val drag = remember { DragState() }
    var local by remember { mutableStateOf(items) }
    LaunchedEffect(items) { if (drag.key == null) local = items }
    val canDrag = filter == VideoFilter.ALL && query.isBlank() && !selecting

    fun onDrag(dy: Float) {
        val key = drag.key ?: return
        drag.offset += dy
        val visible = listState.layoutInfo.visibleItemsInfo
        val cur = visible.firstOrNull { it.key == key } ?: return
        val mid = (cur.offset + drag.offset + cur.size / 2f).toInt()
        val target = visible.firstOrNull { it.key != key && mid in it.offset..(it.offset + it.size) } ?: return
        val from = local.indexOfFirst { it.id == key }
        val to = local.indexOfFirst { it.id == target.key }
        if (from < 0 || to < 0) return
        local = local.toMutableList().apply { add(to, removeAt(from)) }
        drag.offset += (cur.offset - target.offset).toFloat()
    }
    fun endDrag() {
        if (drag.key != null) vm.reorder(local.map { it.id })
        drag.key = null; drag.offset = 0f
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("Выбрано: ${selected.size}") },
                    navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Outlined.Close, "Снять выбор") } },
                    actions = {
                        IconButton(onClick = { selected = local.map { it.id }.toSet() }) { Icon(Icons.Outlined.SelectAll, "Выбрать все") }
                        IconButton(onClick = { dialog = "template" }) { Icon(Icons.Outlined.Notes, "Применить шаблон") }
                        IconButton(onClick = { dialog = "sets" }) { Icon(Icons.Outlined.Tag, "Наборы хештегов") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Ещё") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            if (settings.publishMode == PublishMode.API_DIRECT)
                                DropdownMenuItem(text = { Text("Кто увидит (видимость)") }, onClick = { menu = false; dialog = "privacy" })
                            DropdownMenuItem(text = { Text("Поставить в очередь") }, onClick = { menu = false; vm.enqueue(selected); selected = emptySet() })
                            DropdownMenuItem(text = { Text("В черновики") }, onClick = { menu = false; vm.toDraft(selected); selected = emptySet() })
                            DropdownMenuItem(text = { Text("Отменить") }, onClick = { menu = false; vm.cancel(selected); selected = emptySet() })
                            DropdownMenuItem(text = { Text("Удалить из приложения") }, onClick = { menu = false; toDelete = selected })
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Видео") },
                    actions = {
                        TextButton(onClick = vm::toggleQueue) { Text(if (settings.queueRunning) "Пауза" else "Запустить") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Ещё") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Добавить из файлов") }, onClick = { menu = false; pickers.fromFiles() })
                            DropdownMenuItem(text = { Text("Выбрать несколько") }, onClick = { menu = false; selected = local.take(1).map { it.id }.toSet() })
                            DropdownMenuItem(text = { Text("Отменить все ожидающие") }, onClick = { menu = false; dialog = "cancelAll" })
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!selecting) ExtendedFloatingActionButton(onClick = pickers.fromGallery, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Видео") })
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it }, singleLine = true,
                placeholder = { Text("Поиск по названию и заметкам") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Outlined.Close, "Очистить") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(VideoFilter.entries) { f -> FilterChip(selected = f == filter, onClick = { vm.filter.value = f }, label = { Text(f.label) }) }
            }
            if (!settings.queueRunning) {
                InfoBanner("Очередь на паузе: публикации не запускаются. Порядок и настройки сохраняются.",
                    Modifier.padding(16.dp, 8.dp), tone = Color3.Attention) { TextButton(onClick = vm::toggleQueue) { Text("Запустить") } }
            }
            if (local.isEmpty()) {
                EmptyState(
                    if (filter == VideoFilter.ALL && query.isBlank()) "Очередь пуста" else "Ничего не найдено",
                    if (filter == VideoFilter.ALL && query.isBlank()) "Выберите одно или несколько видео. Оригиналы останутся в галерее." else "Измените поиск или фильтр."
                ) { if (filter == VideoFilter.ALL && query.isBlank()) Button(onClick = pickers.fromGallery) { Text("Выбрать видео") } }
            } else if (canDrag) {
                Text("Удерживайте ⠿ и перетаскивайте, чтобы изменить порядок публикации",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp)) {
                items(local, key = { it.id }) { item ->
                    val dragging = drag.key == item.id
                    VideoRow(
                        item = item,
                        number = item.position + 1,
                        selecting = selecting,
                        isSelected = item.id in selected,
                        dragHandle = if (canDrag) Modifier.pointerInput(item.id) {
                            detectDragGestures(
                                onDragStart = { drag.key = item.id; drag.offset = 0f },
                                onDragEnd = { endDrag() },
                                onDragCancel = { endDrag() },
                                onDrag = { change, amount -> change.consume(); onDrag(amount.y) }
                            )
                        } else null,
                        onClick = {
                            when {
                                selecting -> selected = if (item.id in selected) selected - item.id else selected + item.id
                                item.status == PostStatus.NEEDS_CONFIRMATION -> onHandoff(item.id)
                                else -> onOpenItem(item.id)
                            }
                        },
                        onLongClick = { selected = selected + item.id },
                        onEdit = { onOpenItem(item.id) },
                        onCopy = { vm.copy(item.id) { } ; Toast.makeText(ctx, "Копия создана как черновик", Toast.LENGTH_SHORT).show() },
                        onPublishNow = { vm.publishNow(item.id, onHandoff) },
                        onEnqueue = { vm.enqueue(setOf(item.id)) },
                        onDraft = { vm.toDraft(setOf(item.id)) },
                        onCancel = { vm.cancel(setOf(item.id)) },
                        onDelete = { toDelete = setOf(item.id) },
                        modifier = (if (dragging) Modifier else Modifier.animateItem())
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) drag.offset else 0f }
                            .then(if (dragging) Modifier.shadow(8.dp, MaterialTheme.shapes.medium) else Modifier)
                    )
                }
            }
        }
    }

    if (toDelete.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { toDelete = emptySet() },
            title = { Text(if (toDelete.size == 1) "Удалить задание?" else "Удалить заданий: ${toDelete.size}?") },
            text = { Text("Задания исчезнут из приложения. Видео в галерее останутся, история публикаций сохранится.") },
            confirmButton = { TextButton(onClick = { vm.delete(toDelete); selected = selected - toDelete; toDelete = emptySet() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { toDelete = emptySet() }) { Text("Отмена") } }
        )
    }
    when (dialog) {
        "cancelAll" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Отменить все ожидающие?") },
            text = { Text("Все ожидающие и требующие подтверждения задания получат статус «Отменено». Их можно вернуть в очередь.") },
            confirmButton = { TextButton(onClick = {
                dialog = null
                vm.cancelAllPending { n -> Toast.makeText(ctx, "Отменено заданий: $n", Toast.LENGTH_SHORT).show() }
            }) { Text("Отменить все") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Назад") } }
        )
        "template" -> TemplatePickDialog(templates, onDismiss = { dialog = null }) { tid ->
            vm.applyTemplate(selected, tid); dialog = null; selected = emptySet()
        }
        "privacy" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Кто увидит выбранные видео") },
            text = {
                Column {
                    PrivacyLevels.labels.forEach { (code, label) ->
                        TextButton(onClick = { vm.applyPrivacy(selected, code); dialog = null; selected = emptySet() }) { Text(label) }
                    }
                    Text("Выбор делаете вы — так требует TikTok для прямой публикации.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } }
        )
        "sets" -> SetsPickDialog(sets, onDismiss = { dialog = null }) { ids, replace ->
            vm.applySets(selected, ids, replace); dialog = null; selected = emptySet()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    item: QueueItem, number: Int, selecting: Boolean, isSelected: Boolean, dragHandle: Modifier?,
    onClick: () -> Unit, onLongClick: () -> Unit, onEdit: () -> Unit, onCopy: () -> Unit, onPublishNow: () -> Unit,
    onEnqueue: () -> Unit, onDraft: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit, modifier: Modifier
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().padding(vertical = 5.dp).clip(MaterialTheme.shapes.medium)
            .background(if (isSelected) cs.primaryContainer else cs.surfaceContainer)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selecting) {
            Icon(if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
                tint = if (isSelected) cs.primary else cs.outline, modifier = Modifier.padding(end = 8.dp))
        } else if (dragHandle != null) {
            Icon(Icons.Outlined.DragIndicator, "Перетащить", tint = cs.onSurfaceVariant, modifier = dragHandle.padding(end = 4.dp).size(28.dp))
        } else {
            Text(number.toString(), style = MaterialTheme.typography.titleMedium, color = cs.onSurfaceVariant, modifier = Modifier.width(28.dp))
        }
        VideoThumb(item.videoUri, size = 48.dp, corner = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${formatDuration(item.durationMs)}, ${formatSize(item.sizeBytes)}, добавлено ${formatDateTime(item.createdAt)}",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(item.status)
                if (item.scheduledAt != null && !item.status.isClosed) {
                    Spacer(Modifier.width(8.dp))
                    Text(formatDateTime(item.scheduledAt), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            val note = when (item.status) {
                PostStatus.ERROR -> item.lastError
                PostStatus.NEEDS_CONFIRMATION -> item.statusReason
                else -> null
            }
            if (note != null) {
                Spacer(Modifier.height(4.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    color = if (item.status == PostStatus.ERROR) cs.error else cs.secondary)
            }
        }
        if (!selecting) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Действия") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Редактировать") }, onClick = { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                DropdownMenuItem(text = { Text("Копировать задание") }, onClick = { menu = false; onCopy() }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                if (!item.status.isClosed && item.status != PostStatus.PUBLISHING)
                    DropdownMenuItem(text = { Text("Опубликовать сейчас") }, onClick = { menu = false; onPublishNow() }, leadingIcon = { Icon(Icons.Outlined.Send, null) })
                if (item.status in setOf(PostStatus.DRAFT, PostStatus.ERROR, PostStatus.CANCELLED, PostStatus.NEEDS_CONFIRMATION))
                    DropdownMenuItem(text = { Text(if (item.status == PostStatus.ERROR) "Подготовить заново" else "Поставить в очередь") },
                        onClick = { menu = false; onEnqueue() }, leadingIcon = { Icon(Icons.Outlined.Replay, null) })
                if (item.status.isScheduled)
                    DropdownMenuItem(text = { Text("В черновики") }, onClick = { menu = false; onDraft() }, leadingIcon = { Icon(Icons.Outlined.EditNote, null) })
                if (!item.status.isClosed && item.status != PostStatus.PUBLISHING)
                    DropdownMenuItem(text = { Text("Отменить") }, onClick = { menu = false; onCancel() }, leadingIcon = { Icon(Icons.Outlined.Block, null) })
                DropdownMenuItem(text = { Text("Удалить из приложения") }, onClick = { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Outlined.Delete, null) })
            }
        }
    }
}

@Composable
private fun TemplatePickDialog(templates: List<CaptionTemplate>, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    var choice by remember { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Шаблон для выбранных") },
        text = {
            Column {
                val options = listOf<Pair<Long?, String>>(null to "По умолчанию для категории") +
                    templates.map { it.id to (if (it.category.isBlank()) it.name else "${it.name} (${it.category})") }
                options.forEach { (id, name) ->
                    Row(Modifier.fillMaxWidth().selectable(choice == id) { choice = id }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = choice == id, onClick = { choice = id }); Text(name)
                    }
                }
                Text("Индивидуальные описания видео не изменятся: шаблон используется, только если своего текста нет.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(choice) }) { Text("Применить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun SetsPickDialog(sets: List<HashtagSet>, onDismiss: () -> Unit, onPick: (List<Long>, Boolean) -> Unit) {
    var chosen by remember { mutableStateOf(listOf<Long>()) }
    var replace by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Наборы хештегов") },
        text = {
            Column {
                sets.forEach { s ->
                    val on = s.id in chosen
                    Row(Modifier.fillMaxWidth().selectable(on) { chosen = if (on) chosen - s.id else chosen + s.id }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on, onCheckedChange = { chosen = if (on) chosen - s.id else chosen + s.id })
                        Column {
                            Text(s.name)
                            Text(s.tags.ifBlank { "пусто" }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Заменить текущие наборы", Modifier.weight(1f))
                    Switch(checked = replace, onCheckedChange = { replace = it })
                }
                Text(when {
                    replace && chosen.isEmpty() -> "Ничего не отмечено — видео вернутся к наборам по умолчанию"
                    replace -> "У видео останутся только отмеченные наборы"
                    else -> "Отмеченные наборы добавятся к уже выбранным"
                },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(chosen, replace) }, enabled = chosen.isNotEmpty() || replace) { Text("Применить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
