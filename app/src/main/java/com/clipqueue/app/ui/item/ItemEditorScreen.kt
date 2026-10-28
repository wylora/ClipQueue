package com.clipqueue.app.ui.item

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.clipqueue.app.AppContainer
import com.clipqueue.app.data.*
import com.clipqueue.app.domain.CaptionBuilder
import com.clipqueue.app.domain.CaptionResult
import com.clipqueue.app.domain.HashtagNormalizer
import com.clipqueue.app.domain.TemplateResolver
import com.clipqueue.app.engine.Availability
import com.clipqueue.app.media.formatDuration
import com.clipqueue.app.media.formatSize
import com.clipqueue.app.ui.components.*
import com.clipqueue.app.ui.home.HistoryRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ItemEditorViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val draft = MutableStateFlow<QueueItem?>(null)
    val templates = c.templates.templates.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sets = c.hashtags.sets.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val history = c.queue.historyFor(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val availability = MutableStateFlow<Availability?>(null)
    val dirty = MutableStateFlow(false)
    val missing = MutableStateFlow(false)

    /** Предпросмотр по тем же правилам, что и при реальной подготовке публикации. */
    val preview: StateFlow<CaptionResult?> = combine(draft, templates, sets, settings) { d, t, s, st ->
        d ?: return@combine null
        CaptionBuilder.build(d, TemplateResolver.resolve(d, t), s, st.caption)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { draft.value = c.queue.get(id); missing.value = draft.value == null }
        checkFile()
    }

    fun edit(f: (QueueItem) -> QueueItem) { draft.value = draft.value?.let(f); dirty.value = true }
    /** Изменение, влияющее на текст: сбрасывает отметку «текст проверен». */
    fun editText(f: (QueueItem) -> QueueItem) = edit { f(it).copy(captionConfirmed = false) }

    fun addTags(input: String) = editText { d ->
        d.copy(extraHashtags = HashtagNormalizer.join(HashtagNormalizer.parse(d.extraHashtags + " " + input)))
    }
    fun removeTag(tag: String) = editText { d ->
        d.copy(extraHashtags = HashtagNormalizer.join(HashtagNormalizer.parse(d.extraHashtags).filterNot { it.equals(tag, true) }))
    }
    fun toggleSet(setId: Long) = editText { d ->
        val ids = d.effectiveSetIds(sets.value)
        d.copy(hashtagSetIds = (if (setId in ids) ids - setId else ids + setId).toSetIdString())
    }
    fun useDefaultSets() = editText { it.copy(hashtagSetIds = "") }
    fun startManualEdit() { val text = preview.value?.text ?: return; editText { it.copy(captionOverride = text) } }
    fun resetManualEdit() = editText { it.copy(captionOverride = null) }

    fun checkFile() = viewModelScope.launch {
        val uri = c.queue.get(id)?.videoUri ?: return@launch
        availability.value = withContext(Dispatchers.IO) { c.inspector.checkAvailable(uri) }
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        draft.value?.let { c.actions.save(it) }
        dirty.value = false
        onDone()
    }

    fun replaceVideo(uri: Uri, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        draft.value?.let { c.actions.save(it) }
        val ok = c.actions.replaceVideo(id, uri)
        draft.value = c.queue.get(id); dirty.value = false
        checkFile()
        onResult(ok)
    }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        draft.value?.let { c.actions.save(it) }
        block()
        draft.value = c.queue.get(id); dirty.value = false
    }
    fun confirmPublished() = act { c.actions.confirmPublished(id) }
    fun cancel() = act { c.actions.cancel(setOf(id)) }
    fun enqueue() = act { c.actions.enqueue(setOf(id)) }
    fun toDraft() = act { c.actions.toDraft(setOf(id)) }
    fun copy(done: () -> Unit) = act { c.actions.copy(id); done() }
    fun publishNow(openHandoff: (Long) -> Unit) = act { if (c.actions.publishNow(id)) openHandoff(id) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItemEditorScreen(id: Long, onBack: () -> Unit, onHandoff: (Long) -> Unit) {
    val vm = appViewModel(key = "item$id") { ItemEditorViewModel(it, id) }
    val draft by vm.draft.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val sets by vm.sets.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val availability by vm.availability.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val missing by vm.missing.collectAsStateWithLifecycle()
    val c = container()
    val ctx = LocalContext.current
    var showPlayer by remember { mutableStateOf(false) }
    var showDateTime by remember { mutableStateOf(false) }
    var tagInput by remember { mutableStateOf("") }
    val pickReplacement = rememberSingleVideoPicker { uri ->
        vm.replaceVideo(uri) { ok -> Toast.makeText(ctx, if (ok) "Видео заменено, настройки сохранены" else "Не удалось прочитать видео", Toast.LENGTH_SHORT).show() }
    }

    val d = draft
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Задание") },
                navigationIcon = { IconButton(onClick = { if (dirty) vm.save(onBack) else onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") } },
                actions = { TextButton(onClick = { vm.save(onBack) }, enabled = d != null) { Text("Сохранить") } }
            )
        }
    ) { pad ->
        if (d == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (missing) Text("Задание удалено. Запись о нём осталась в истории.") else CircularProgressIndicator()
            }
            return@Scaffold
        }
        val cs = MaterialTheme.colorScheme
        val locked = d.status == PostStatus.PUBLISHED || d.status == PostStatus.PUBLISHING
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            // Видео
            Row(verticalAlignment = Alignment.CenterVertically) {
                VideoThumb(d.videoUri, size = 84.dp, corner = 16.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text("${formatDuration(d.durationMs)}, ${formatSize(d.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Text("Добавлено ${formatDateTime(d.createdAt)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp)); StatusPill(d.status)
                }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPlayer = true }) { Icon(Icons.Outlined.PlayCircle, null); Spacer(Modifier.width(6.dp)); Text("Просмотр") }
                OutlinedButton(onClick = { vm.checkFile() }) { Icon(Icons.Outlined.FactCheck, null); Spacer(Modifier.width(6.dp)); Text("Проверить файл") }
                if (!locked) OutlinedButton(onClick = pickReplacement) { Icon(Icons.Outlined.SwapHoriz, null); Spacer(Modifier.width(6.dp)); Text("Заменить видео") }
            }
            availability?.let {
                Spacer(Modifier.height(8.dp))
                when (it) {
                    Availability.Ok -> InfoBanner("Файл доступен", tone = Color3.Good)
                    is Availability.Missing -> InfoBanner("Файл недоступен: ${it.reason}. Замените видео в задании", tone = Color3.Attention)
                }
            }
            when (d.status) {
                PostStatus.ERROR -> d.lastError?.let { Spacer(Modifier.height(8.dp)); InfoBanner("Ошибка: $it", tone = Color3.Attention) { TextButton(onClick = vm::enqueue) { Text("Повторить") } } }
                PostStatus.NEEDS_CONFIRMATION -> { Spacer(Modifier.height(8.dp)); InfoBanner(d.statusReason ?: "Нужно ваше действие", tone = Color3.Attention) { TextButton(onClick = { onHandoff(d.id) }) { Text("Открыть") } } }
                PostStatus.PUBLISHED -> { Spacer(Modifier.height(8.dp)); InfoBanner("Опубликовано ${formatDateTime(d.publishedAt)}. ${d.resultInfo ?: ""}", tone = Color3.Good) }
                else -> Unit
            }

            // Описание
            SectionTitle("Описание")
            OutlinedTextField(
                value = d.category, onValueChange = { v -> vm.editText { it.copy(category = v) } }, enabled = !locked,
                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Категория (выбирает шаблон по умолчанию)") }
            )
            val categories = templates.map { it.category }.filter { it.isNotBlank() }.distinct()
            if (categories.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { cat ->
                        FilterChip(selected = d.category.equals(cat, true), enabled = !locked,
                            onClick = { vm.editText { it.copy(category = if (it.category.equals(cat, true)) "" else cat) } }, label = { Text(cat) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val resolved = TemplateResolver.resolve(d, templates)
            Dropdown(
                label = "Шаблон описания",
                value = templates.firstOrNull { it.id == d.templateId }?.name ?: "Автоматически (${resolved?.name ?: "нет"})",
                options = listOf<Pair<Long?, String>>(null to "Автоматически: по категории или общий") +
                    templates.map { it.id to (if (it.category.isBlank()) it.name else "${it.name} (${it.category})") },
                onSelect = { tid -> vm.editText { it.copy(templateId = tid) } }
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = d.customCaption, onValueChange = { v -> vm.editText { it.copy(customCaption = v) } }, enabled = !locked,
                modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("Своё описание для этого видео") },
                supportingText = { Text(if (d.customCaption.isBlank()) "Пусто — используется шаблон" else "Шаблон не применяется к этому видео") }
            )
            OutlinedTextField(
                value = d.seriesName, onValueChange = { v -> vm.editText { it.copy(seriesName = v) } }, enabled = !locked,
                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Название серии — переменная {series}") }
            )

            // Хештеги
            SectionTitle("Хештеги")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Подставлять наборы автоматически", style = MaterialTheme.typography.bodyLarge)
                    Text(if (d.autoHashtags) "Выбранные наборы добавятся к описанию" else "Наборы не добавляются, остаются только свои хештеги видео",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Switch(checked = d.autoHashtags, enabled = !locked, onCheckedChange = { v -> vm.editText { it.copy(autoHashtags = v) } })
            }
            if (d.autoHashtags) {
                val effective = d.effectiveSetIds(sets)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (d.usesDefaultSets) "Сейчас: наборы по умолчанию (при их изменении видео получит новые хештеги)"
                        else "Свой выбор наборов: добавляются в порядке выбора, повторы убираются",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (!d.usesDefaultSets && !locked) TextButton(onClick = vm::useDefaultSets) { Text("По умолчанию") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sets.forEach { s ->
                        val order = effective.indexOf(s.id)
                        FilterChip(selected = order >= 0, enabled = !locked, onClick = { vm.toggleSet(s.id) },
                            label = { Text(if (order >= 0) "${order + 1}. ${s.name}" else s.name) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tagInput, onValueChange = { tagInput = it }, enabled = !locked,
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Свои хештеги этого видео") }, placeholder = { Text("anime edit #amv") },
                trailingIcon = { IconButton(onClick = { vm.addTags(tagInput); tagInput = "" }, enabled = tagInput.isNotBlank()) { Icon(Icons.Outlined.AddCircle, "Добавить") } }
            )
            val extras = HashtagNormalizer.parse(d.extraHashtags)
            if (extras.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                extras.forEach { tag ->
                    InputChip(selected = false, enabled = !locked, onClick = { vm.removeTag(tag) }, label = { Text(tag) },
                        trailingIcon = { Icon(Icons.Outlined.Close, "Удалить", Modifier.size(16.dp)) })
                }
            }

            // Итоговый текст
            SectionTitle("Итоговое описание") {
                if (!locked) {
                    if (d.captionOverride == null) TextButton(onClick = vm::startManualEdit) { Text("Править вручную") }
                    else TextButton(onClick = vm::resetManualEdit) { Text("Вернуть автоматическое") }
                }
            }
            if (d.captionOverride != null) {
                OutlinedTextField(
                    value = d.captionOverride, onValueChange = { v -> vm.editText { it.copy(captionOverride = v) } }, enabled = !locked,
                    modifier = Modifier.fillMaxWidth(), minLines = 4,
                    supportingText = { Text("Текст опубликуется ровно так. Наборы и шаблон больше не применяются") }
                )
                Spacer(Modifier.height(6.dp))
            }
            preview?.let { p -> CaptionPreview(p) { c.share.copyCaption(p.text); Toast.makeText(ctx, "Описание скопировано", Toast.LENGTH_SHORT).show() } }
            if (settings.requireReview && !locked) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Checkbox(checked = d.captionConfirmed, onCheckedChange = { v -> vm.edit { it.copy(captionConfirmed = v) } })
                    Text("Я проверил итоговый текст — можно публиковать", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Видимость
            if (settings.publishMode == PublishMode.API_DIRECT && !locked) {
                SectionTitle("Кто увидит публикацию")
                Text("TikTok требует, чтобы видимость выбирали вы. Если вариант недоступен для аккаунта, задание попросит подтверждения.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrivacyLevels.labels.forEach { (code, label) ->
                        FilterChip(selected = d.privacyLevel == code, onClick = { vm.edit { it.copy(privacyLevel = code) } }, label = { Text(label) })
                    }
                }
            }

            // Расписание
            SectionTitle("Дата и время")
            if (!locked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Конкретное время", style = MaterialTheme.typography.bodyLarge)
                        Text(if (d.manualSchedule) formatInZone(d.scheduledAt, d.timeZone) else "Выключено — время назначит расписание очереди",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Switch(checked = d.manualSchedule, onCheckedChange = { v ->
                        vm.edit { it.copy(manualSchedule = v, scheduledAt = if (v) (it.scheduledAt ?: (System.currentTimeMillis() + 3_600_000)) else it.scheduledAt) }
                        if (v) showDateTime = true
                    })
                }
                if (d.manualSchedule) {
                    Spacer(Modifier.height(6.dp))
                    Dropdown(label = "Часовой пояс", value = d.timeZone, options = commonZones().map { it to it },
                        onSelect = { z -> vm.edit { it.copy(timeZone = z) } })
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { showDateTime = true }) { Icon(Icons.Outlined.Event, null); Spacer(Modifier.width(6.dp)); Text("Изменить дату и время") }
                }
            } else {
                Text(formatInZone(d.scheduledAt, d.timeZone), style = MaterialTheme.typography.bodyMedium)
            }

            SectionTitle("Заметки")
            OutlinedTextField(value = d.notes, onValueChange = { v -> vm.edit { it.copy(notes = v) } },
                modifier = Modifier.fillMaxWidth(), minLines = 2, placeholder = { Text("Только для вас, в TikTok не публикуется") })

            // Действия
            SectionTitle("Действия")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!locked) FilledTonalButton(onClick = { vm.publishNow(onHandoff) }) { Text("Опубликовать сейчас") }
                OutlinedButton(onClick = { vm.copy { Toast.makeText(ctx, "Копия создана как черновик", Toast.LENGTH_SHORT).show() } }) { Text("Копировать задание") }
                when (d.status) {
                    PostStatus.DRAFT, PostStatus.CANCELLED, PostStatus.ERROR -> OutlinedButton(onClick = vm::enqueue) { Text("Поставить в очередь") }
                    PostStatus.PENDING, PostStatus.READY -> OutlinedButton(onClick = vm::toDraft) { Text("В черновики") }
                    else -> Unit
                }
                if (!locked && d.status != PostStatus.CANCELLED) OutlinedButton(onClick = vm::cancel) { Text("Отменить") }
                if (d.status == PostStatus.NEEDS_CONFIRMATION || d.status == PostStatus.ERROR)
                    OutlinedButton(onClick = vm::confirmPublished) { Text("Я уже опубликовал") }
            }

            if (history.isNotEmpty()) {
                SectionTitle("Журнал задания")
                history.take(20).forEach { HistoryRow(it) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showPlayer && d != null) VideoPlayerDialog(d.videoUri) { showPlayer = false }
    if (showDateTime && d != null) {
        DateTimeDialog(d.scheduledAt ?: (System.currentTimeMillis() + 3_600_000), d.timeZone, onDismiss = { showDateTime = false }) { ms ->
            vm.edit { it.copy(scheduledAt = ms, manualSchedule = true) }
            showDateTime = false
        }
    }
}

@Composable
fun CaptionPreview(p: CaptionResult, onCopy: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(cs.surfaceContainerHigh).padding(14.dp)) {
        Text(p.text.ifBlank { "Описание пустое" }, style = MaterialTheme.typography.bodyMedium,
            color = if (p.text.isBlank()) cs.onSurfaceVariant else cs.onSurface)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${p.length} / $TIKTOK_CAPTION_LIMIT символов", style = MaterialTheme.typography.labelMedium,
                    color = if (p.overLimit) cs.error else cs.onSurfaceVariant)
                Text("Хештегов: ${p.hashtags.size}" + if (p.maxHashtags > 0) " из ${p.maxHashtags}" else "",
                    style = MaterialTheme.typography.labelMedium, color = if (p.tooManyHashtags) cs.secondary else cs.onSurfaceVariant)
            }
            TextButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Копировать") }
        }
        val notes = buildList {
            if (p.overLimit) add("Описание длиннее лимита TikTok — публикация будет остановлена. Сократите текст или хештеги.")
            if (p.droppedByLimit > 0) add("Не добавлено из-за лимита количества: ${p.droppedByLimit}. Лимит меняется в настройках.")
            if (p.tooManyHashtags) add("Хештегов больше лимита из настроек. TikTok в приложении предупреждает о максимуме в 5.")
            if (p.skippedDuplicates > 0) add("Повторы убраны: ${p.skippedDuplicates}")
            if (p.unknownVariables.isNotEmpty()) add("Неизвестные переменные оставлены как есть: ${p.unknownVariables.joinToString()}")
            if (p.overridden) add("Текст отредактирован вручную")
            if (p.emptySets && !p.overridden) add("В выбранных наборах нет хештегов. Заполните набор в разделе «Хештеги» или выберите другой.")
        }
        notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = if (p.overLimit) cs.error else cs.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(label: String, value: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(v); open = false }) }
        }
    }
}

@Composable
fun VideoPlayerDialog(uri: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(uri))); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(MaterialTheme.shapes.large).background(androidx.compose.ui.graphics.Color.Black)) {
            AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = Modifier.fillMaxSize())
        }
    }
}
