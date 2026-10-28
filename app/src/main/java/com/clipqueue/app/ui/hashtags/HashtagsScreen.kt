package com.clipqueue.app.ui.hashtags

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.*
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
import com.clipqueue.app.data.AppSettings
import com.clipqueue.app.data.HashtagSet
import com.clipqueue.app.domain.HashtagNormalizer
import com.clipqueue.app.ui.components.*
import com.clipqueue.app.ui.templates.TemplatesTab
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HashtagsViewModel(private val c: AppContainer) : ViewModel() {
    val sets = c.hashtags.sets.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())
    fun save(set: HashtagSet) = viewModelScope.launch { c.hashtags.save(set) }
    fun copy(set: HashtagSet) = viewModelScope.launch { c.hashtags.copy(set) }
    fun delete(set: HashtagSet) = viewModelScope.launch { c.hashtags.delete(set.id) }
    fun toggleDefault(set: HashtagSet) = viewModelScope.launch { c.hashtags.save(set.copy(isDefault = !set.isDefault)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HashtagsScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var create by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(if (tab == 0) "Хештеги" else "Шаблоны описаний") })
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Наборы хештегов") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Шаблоны") })
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { create = true }, icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(if (tab == 0) "Набор" else "Шаблон") })
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            if (tab == 0) SetsTab(create, onCreateHandled = { create = false })
            else TemplatesTab(create, onCreateHandled = { create = false })
        }
    }
}

@Composable
private fun SetsTab(createRequested: Boolean, onCreateHandled: () -> Unit) {
    val vm = appViewModel { HashtagsViewModel(it) }
    val sets by vm.sets.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<HashtagSet?>(null) }
    var deleting by remember { mutableStateOf<HashtagSet?>(null) }
    LaunchedEffect(createRequested) { if (createRequested) { editing = HashtagSet(name = "", tags = ""); onCreateHandled() } }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
        item {
            Text(
                "Ваши наборы хранятся только на телефоне. Наборы «по умолчанию» отмечаются в новых видео автоматически; " +
                    "в карточке видео можно выбрать несколько наборов, повторы убираются. " +
                    "Сейчас добавляется не больше ${if (settings.caption.maxHashtags > 0) settings.caption.maxHashtags else "∞"} хештегов (меняется в настройках).",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        }
        if (sets.none { it.isDefault && it.tagList.isNotEmpty() }) item {
            InfoBanner("Наборы по умолчанию пустые — к новым видео хештеги не добавятся. " +
                "Добавьте хештеги в набор «по умолчанию» или включите «Подставлять по умолчанию» у своего набора.",
                tone = Color3.Attention)
            Spacer(Modifier.height(8.dp))
        }
        items(sets, key = { it.id }) { set ->
            SetCard(set, onEdit = { editing = set }, onCopy = { vm.copy(set) }, onToggleDefault = { vm.toggleDefault(set) }, onDelete = { deleting = set })
        }
        if (sets.isEmpty()) item { EmptyState("Наборов пока нет", "Создайте набор, например «Хоррор»: #horror #gaming #indiegame #scary") }
    }

    editing?.let { set ->
        // Первый непустой набор пользователя сразу делаем «по умолчанию», чтобы хештеги начали подставляться.
        val initial = if (set.id == 0L && sets.none { it.isDefault && it.tagList.isNotEmpty() }) set.copy(isDefault = true) else set
        SetEditorDialog(initial, onDismiss = { editing = null }, onSave = { vm.save(it); editing = null })
    }
    deleting?.let { set ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить набор «${set.name}»?") },
            text = { Text("Набор исчезнет из всех видео, где был выбран. Уже опубликованные описания не изменятся.") },
            confirmButton = { TextButton(onClick = { vm.delete(set); deleting = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetCard(set: HashtagSet, onEdit: () -> Unit, onCopy: () -> Unit, onToggleDefault: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(MaterialTheme.shapes.medium)
            .background(if (set.isDefault) cs.primaryContainer else cs.surfaceContainer)
            .clickable(onClick = onEdit).padding(16.dp)
    ) {
        val fg = if (set.isDefault) cs.onPrimaryContainer else cs.onSurface
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(set.name, style = MaterialTheme.typography.titleMedium, color = fg)
                Text((if (set.isDefault) "По умолчанию, " else "") + "${set.tagList.size} хештегов",
                    style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.75f))
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Действия", tint = fg) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Редактировать") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Копировать набор") }, onClick = { menu = false; onCopy() })
                    DropdownMenuItem(text = { Text(if (set.isDefault) "Не подставлять по умолчанию" else "Подставлять по умолчанию") },
                        onClick = { menu = false; onToggleDefault() })
                    DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
                }
            }
        }
        if (set.tagList.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                set.tagList.forEach { tag ->
                    Text(tag, style = MaterialTheme.typography.labelLarge, color = if (set.isDefault) fg else cs.primary,
                        modifier = Modifier.clip(MaterialTheme.shapes.small)
                            .background((if (set.isDefault) fg else cs.primary).copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text("Пусто. Нажмите, чтобы добавить хештеги.", style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun SetEditorDialog(initial: HashtagSet, onDismiss: () -> Unit, onSave: (HashtagSet) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var tags by remember { mutableStateOf(initial.tagList) }
    var isDefault by remember { mutableStateOf(initial.isDefault) }
    var input by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }

    fun commit() {
        if (input.isBlank()) return
        notes = HashtagNormalizer.explain(input, tags)
        tags = HashtagNormalizer.dedupe(tags + HashtagNormalizer.parse(input))
        input = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Новый набор" else "Набор хештегов") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Название, например «Хоррор»") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(input, { input = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Хештеги или слова") }, placeholder = { Text("#horror gaming indiegame") },
                    trailingIcon = { IconButton(onClick = ::commit, enabled = input.isNotBlank()) { Icon(Icons.Outlined.AddCircle, "Добавить") } })
                notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                Spacer(Modifier.height(8.dp))
                if (tags.isEmpty()) Text("Добавьте хештеги. Слова без # станут хештегами автоматически.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                tags.forEachIndexed { i, tag ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", Modifier.width(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(tag, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { tags = HashtagNormalizer.move(tags, i, -1) }, enabled = i > 0, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.KeyboardArrowUp, "Выше") }
                        IconButton(onClick = { tags = HashtagNormalizer.move(tags, i, 1) }, enabled = i < tags.lastIndex, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.KeyboardArrowDown, "Ниже") }
                        IconButton(onClick = { tags = tags - tag }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Close, "Удалить") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Подставлять по умолчанию во все видео без своего выбора", Modifier.weight(1f))
                    Switch(checked = isDefault, onCheckedChange = { isDefault = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                commit()
                onSave(initial.copy(name = name, tags = HashtagNormalizer.join(tags), isDefault = isDefault))
            }, enabled = name.isNotBlank()) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
