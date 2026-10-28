package com.clipqueue.app.ui.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.clipqueue.app.AppContainer
import com.clipqueue.app.data.CaptionTemplate
import com.clipqueue.app.data.TIKTOK_CAPTION_LIMIT
import com.clipqueue.app.domain.CaptionBuilder
import com.clipqueue.app.ui.components.EmptyState
import com.clipqueue.app.ui.components.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TemplatesViewModel(private val c: AppContainer) : ViewModel() {
    val templates = c.templates.templates.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun save(t: CaptionTemplate) = viewModelScope.launch { c.templates.save(t) }
    fun delete(t: CaptionTemplate) = viewModelScope.launch { c.templates.delete(t.id) }
    fun makeDefault(t: CaptionTemplate) = viewModelScope.launch { c.templates.setDefault(t) }
}

@Composable
fun TemplatesTab(createRequested: Boolean, onCreateHandled: () -> Unit) {
    val vm = appViewModel { TemplatesViewModel(it) }
    val list by vm.templates.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CaptionTemplate?>(null) }
    LaunchedEffect(createRequested) { if (createRequested) { editing = CaptionTemplate(name = "", body = ""); onCreateHandled() } }
    val cs = MaterialTheme.colorScheme

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
        item {
            Text("Шаблон используется, если у видео нет своего описания. В каждой категории может быть свой шаблон по умолчанию. " +
                "Изменение шаблона не трогает индивидуальные описания.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
        }
        val groups = list.groupBy { it.category }
        groups.forEach { (category, templates) ->
            item(key = "cat:$category") {
                Text(if (category.isBlank()) "Общие" else category, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            items(templates, key = { it.id }) { t ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(MaterialTheme.shapes.medium)
                        .background(cs.surfaceContainer).clickable { editing = t }.padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (t.isDefault) Text("По умолчанию", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                        else TextButton(onClick = { vm.makeDefault(t) }) { Text("Сделать основным") }
                        IconButton(onClick = { vm.delete(t) }) { Icon(Icons.Outlined.Delete, "Удалить") }
                    }
                    Text(t.body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 4)
                }
            }
        }
        if (list.isEmpty()) item { EmptyState("Шаблонов нет", "Создайте шаблон, например «Новый ролик уже здесь! Смотри до конца 👀»") }
    }

    editing?.let { t ->
        var name by remember(t.id) { mutableStateOf(t.name) }
        var body by remember(t.id) { mutableStateOf(t.body) }
        var category by remember(t.id) { mutableStateOf(t.category) }
        var isDefault by remember(t.id) { mutableStateOf(t.isDefault) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (t.id == 0L) "Новый шаблон" else "Шаблон") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(category, { category = it }, label = { Text("Категория (пусто — общий)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(body, { body = it }, label = { Text("Текст") }, minLines = 4, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("${body.length} / $TIKTOK_CAPTION_LIMIT") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("По умолчанию в категории", Modifier.weight(1f)); Switch(checked = isDefault, onCheckedChange = { isDefault = it })
                    }
                    Text("Нажмите на переменную, чтобы вставить:", style = MaterialTheme.typography.bodySmall)
                    CaptionBuilder.variables.forEach { (v, desc) ->
                        Text("$v — $desc", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { body += v }.padding(vertical = 3.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.save(t.copy(name = name, body = body, category = category, isDefault = isDefault)); editing = null },
                    enabled = name.isNotBlank()) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } }
        )
    }
}
