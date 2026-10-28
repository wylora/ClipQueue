package com.clipqueue.app.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.clipqueue.app.AppContainer
import com.clipqueue.app.ClipQueueApp
import com.clipqueue.app.data.PostStatus
import com.clipqueue.app.ui.theme.statusColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun container(): AppContainer = (LocalContext.current.applicationContext as ClipQueueApp).container

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val c = container()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(c) } })
}

@Composable
fun VideoThumb(uri: String, modifier: Modifier = Modifier, size: Dp = 56.dp, corner: Dp = 12.dp) {
    val ctx = LocalContext.current
    val request = remember(uri) {
        ImageRequest.Builder(ctx).data(Uri.parse(uri)).videoFrameMillis(1000).size(256).build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(width = size, height = size * 1.33f).clip(RoundedCornerShape(corner)),
        loading = { ThumbPlaceholder() },
        error = { ThumbPlaceholder() }
    )
}

@Composable
private fun ThumbPlaceholder() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatusPill(status: PostStatus, modifier: Modifier = Modifier) {
    val c = statusColors(status)
    Text(
        status.label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = c.fg,
        modifier = modifier.clip(RoundedCornerShape(50)).background(c.bg).padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, tone: Color3 = Color3.Neutral, action: (@Composable () -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        Color3.Neutral -> cs.surfaceContainerHigh to cs.onSurface
        Color3.Attention -> cs.secondaryContainer to cs.onSecondaryContainer
        Color3.Good -> cs.primaryContainer to cs.onPrimaryContainer
    }
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(bg).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

enum class Color3 { Neutral, Attention, Good }

private val ru = Locale("ru")
fun formatDateTime(ms: Long?): String {
    if (ms == null) return "Время не задано"
    val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val day = when (z.toLocalDate()) {
        today -> "Сегодня"
        today.plusDays(1) -> "Завтра"
        today.minusDays(1) -> "Вчера"
        else -> z.format(DateTimeFormatter.ofPattern("d MMM", ru))
    }
    return "$day, ${z.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

fun formatRelative(ms: Long): String {
    val diff = ms - System.currentTimeMillis()
    val mins = Math.abs(diff) / 60_000
    val text = when {
        mins < 1 -> return "сейчас"
        mins < 60 -> "$mins мин"
        mins < 24 * 60 -> "${mins / 60} ч ${mins % 60} мин"
        else -> "${mins / 1440} дн ${(mins % 1440) / 60} ч"
    }
    return if (diff > 0) "через $text" else "$text назад"
}

/**
 * Выбор видео: системный Photo Picker (без разрешений на всё хранилище) или «Файлы» (SAF).
 * Доступ к URI сразу делается постоянным, метаданные читаются в фоне.
 */
class VideoPickers(val fromGallery: () -> Unit, val fromFiles: () -> Unit)

@Composable
fun rememberVideoPickers(onAdded: (Int) -> Unit): VideoPickers {
    val c = container()
    val scope = rememberCoroutineScope()
    val handle: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) scope.launch { onAdded(c.actions.addVideos(uris)) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(), handle)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), handle)
    return remember {
        VideoPickers(
            fromGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            fromFiles = { files.launch(arrayOf("video/*")) }
        )
    }
}

/** Выбор одного видео (для замены видео в задании). */
@Composable
fun rememberSingleVideoPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onPicked) }
    return remember { { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) } }
}

/** Часовые пояса для выбора в задании: системный + распространённые. */
fun commonZones(): List<String> = (listOf(ZoneId.systemDefault().id) + listOf(
    "UTC", "Europe/Kaliningrad", "Europe/Moscow", "Europe/Samara", "Asia/Yekaterinburg", "Asia/Novosibirsk",
    "Asia/Vladivostok", "Europe/Tallinn", "Europe/Riga", "Europe/Vilnius", "Europe/Kyiv", "Europe/Minsk",
    "Asia/Almaty", "Asia/Tashkent", "Asia/Tbilisi", "Asia/Yerevan", "Asia/Baku", "Europe/Berlin", "Europe/London",
    "Europe/Istanbul", "Asia/Dubai", "Asia/Tokyo", "America/New_York", "America/Los_Angeles"
)).distinct()

fun formatInZone(ms: Long?, zoneId: String): String {
    if (ms == null) return "Время не задано"
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    if (zone.rules.getOffset(Instant.ofEpochMilli(ms)) == ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(ms)))
        return formatDateTime(ms)
    val z = Instant.ofEpochMilli(ms).atZone(zone)
    return z.format(DateTimeFormatter.ofPattern("d MMM, HH:mm", ru)) + " (" + zone.id + "), у вас " + formatDateTime(ms)
}

/** Двухшаговый выбор даты и времени в заданном часовом поясе. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeDialog(initialMs: Long, zoneId: String, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    val initial = Instant.ofEpochMilli(initialMs).atZone(zone)
    var date by remember { mutableStateOf<LocalDate?>(null) }
    if (date == null) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    date = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        ?: initial.toLocalDate()
                }) { Text("Далее") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
        ) { DatePicker(state) }
    } else {
        val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(date!!.format(DateTimeFormatter.ofPattern("d MMMM yyyy", ru))) },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    onConfirm(date!!.atTime(state.hour, state.minute).atZone(zone).toInstant().toEpochMilli())
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { date = null }) { Text("Назад") } }
        )
    }
}
