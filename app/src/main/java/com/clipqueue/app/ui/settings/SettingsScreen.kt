package com.clipqueue.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.clipqueue.app.AppContainer
import com.clipqueue.app.data.*
import com.clipqueue.app.ui.components.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    var account by mutableStateOf(accountText())
        private set

    private fun accountText(): String? = c.auth.tokens()?.let { t ->
        val scopes = listOf("video.upload", "video.publish").filter { t.hasScope(it) }.joinToString().ifBlank { "нет прав на публикацию" }
        "${t.displayName ?: t.openId}. Разрешения: $scopes" + if (!t.isRefreshValid) ". Вход истёк" else ""
    }
    fun refreshAccount() { account = accountText() }
    private fun replanAfter(block: suspend () -> Unit) = viewModelScope.launch { block(); c.scheduler.replan() }

    fun setMode(m: PublishMode) = replanAfter { c.settings.setMode(m) }
    fun setTheme(t: ThemeMode) = viewModelScope.launch { c.settings.setTheme(t) }
    fun setSchedule(s: ScheduleConfig) = replanAfter { c.settings.setSchedule(s) }
    fun setExact(v: Boolean) = replanAfter { c.settings.setExactAlarms(v) }
    fun setAhead(m: Int) = replanAfter { c.settings.setPrepareAhead(m) }
    fun setReview(v: Boolean) = viewModelScope.launch { c.settings.setRequireReview(v) }
    fun setWait(v: Boolean) = replanAfter { c.settings.setWaitForConfirmation(v) }
    fun setCaption(o: CaptionOptions) = viewModelScope.launch { c.settings.setCaptionOptions(o) }
    fun login(key: String, url: String, onError: (String) -> Unit) = viewModelScope.launch {
        c.settings.setTikTokConfig(key, url)
        c.auth.startLogin().onFailure { onError(it.message ?: "Ошибка") }
    }
    fun revoke(onDone: (String) -> Unit) = viewModelScope.launch {
        val r = c.auth.revokeAndLogout()
        refreshAccount()
        c.queue.log(null, "Доступ к TikTok отозван")
        onDone(r.exceptionOrNull()?.message?.let { "Токены удалены с телефона. $it" } ?: "Доступ к аккаунту TikTok отозван")
    }
    fun wipe(onDone: (String) -> Unit) = viewModelScope.launch {
        val warn = c.wiper.wipe()
        refreshAccount()
        onDone(if (warn == null) "Все данные удалены" else "Данные удалены. $warn")
    }
    fun canExact() = c.scheduler.canUseExactAlarms()
}

private val ru = Locale("ru")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onOpenHistory: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val toast: (String) -> Unit = { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
    var canExact by remember { mutableStateOf(vm.canExact()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAccount(); canExact = vm.canExact() }

    var clientKey by remember(s.clientKey) { mutableStateOf(s.clientKey) }
    var backend by remember(s.backendUrl) { mutableStateOf(s.backendUrl) }
    var intervalText by remember(s.schedule.intervalMinutes) {
        mutableStateOf(if (s.schedule.intervalMinutes % 60 == 0) (s.schedule.intervalMinutes / 60).toString() else s.schedule.intervalMinutes.toString())
    }
    var inHours by remember(s.schedule.intervalMinutes) { mutableStateOf(s.schedule.intervalMinutes % 60 == 0) }
    var separator by remember(s.caption.separator) { mutableStateOf(s.caption.separator) }
    var timePickerFor by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Настройки") }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            SectionTitle("Публикация в TikTok")
            PublishMode.entries.forEach { m ->
                Row(Modifier.fillMaxWidth().selectable(s.publishMode == m) { vm.setMode(m) }.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    RadioButton(selected = s.publishMode == m, onClick = { vm.setMode(m) })
                    Column(Modifier.padding(start = 8.dp, top = 10.dp)) {
                        Text(m.title, style = MaterialTheme.typography.titleSmall)
                        Text(modeDescription(m), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Обязательная проверка описания", style = MaterialTheme.typography.bodyLarge)
                    Text("Текст, который вы не подтвердили, не публикуется автоматически: задание попросит проверки.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.requireReview, onCheckedChange = vm::setReview)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Ждать подтверждения предыдущего видео", style = MaterialTheme.typography.bodyLarge)
                    Text("Если видео ждёт вашего нажатия, следующее не запустится, и неподтверждённые не накопятся. " +
                        "После подтверждения отсчёт интервала продолжится. Видео с конкретным временем выходят по своему времени.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.waitForConfirmation, onCheckedChange = vm::setWait)
            }

            if (s.publishMode != PublishMode.SHARE) {
                SectionTitle("Аккаунт TikTok")
                InfoBanner(vm.account?.let { "Подключён: $it" } ?: "Не подключён. Нужен Client Key вашего приложения на developers.tiktok.com и бэкенд из папки backend/ проекта.",
                    tone = if (vm.account != null) Color3.Good else Color3.Neutral)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(clientKey, { clientKey = it }, label = { Text("Client Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(backend, { backend = it }, label = { Text("Адрес бэкенда (https://...)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Text("Client Secret в приложение не вводится — он хранится только на бэкенде.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.login(clientKey, backend, toast) }, enabled = clientKey.isNotBlank() && backend.startsWith("https://")) {
                        Text(if (vm.account != null) "Войти заново" else "Подключить TikTok")
                    }
                    if (vm.account != null) OutlinedButton(onClick = { confirm = "revoke" }) { Text("Отозвать доступ") }
                }
                if (s.publishMode == PublishMode.API_DIRECT) {
                    Spacer(Modifier.height(8.dp))
                    Text("Пока ваше приложение не прошло аудит TikTok, прямые публикации видны только вам, аккаунт должен быть приватным, " +
                        "а API-публиковать могут не более 5 пользователей в сутки. Видимость выбирается в карточке каждого видео.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }

            SectionTitle("Хештеги")
            HashtagPlacement.entries.forEach { p ->
                Row(Modifier.fillMaxWidth().selectable(s.caption.placement == p) { vm.setCaption(s.caption.copy(placement = p)) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top) {
                    RadioButton(selected = s.caption.placement == p, onClick = { vm.setCaption(s.caption.copy(placement = p)) })
                    Column(Modifier.padding(start = 8.dp, top = 10.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleSmall)
                        Text(p.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (s.caption.placement == HashtagPlacement.AFTER_SEPARATOR) {
                OutlinedTextField(separator, { separator = it }, label = { Text("Текст-разделитель, например «👇»") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { TextButton(onClick = { vm.setCaption(s.caption.copy(separator = separator)); toast("Разделитель сохранён") }) { Text("OK") } })
            }
            Spacer(Modifier.height(8.dp))
            Text("Максимум хештегов в описании", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(3, 5, 10, 20, 0).forEach { n ->
                    FilterChip(selected = s.caption.maxHashtags == n, onClick = { vm.setCaption(s.caption.copy(maxHashtags = n)) },
                        label = { Text(if (n == 0) "Без лимита" else n.toString()) })
                }
            }
            Text("В официальном API TikTok отдельного лимита на хештеги нет — ограничена длина описания (2200 символов). " +
                "Но приложение TikTok сейчас предупреждает «Maximum 5 hashtags», поэтому по умолчанию 5. Лишние хештеги не добавляются, приложение об этом сообщает.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionTitle("Расписание")
            Text("Интервал между публикациями", style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(intervalText, { intervalText = it.filter(Char::isDigit).take(4) }, singleLine = true,
                    modifier = Modifier.width(100.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = !inHours, onClick = { inHours = false }, label = { Text("мин") })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = inHours, onClick = { inHours = true }, label = { Text("ч") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val v = intervalText.toIntOrNull() ?: return@TextButton
                    vm.setSchedule(s.schedule.copy(intervalMinutes = if (inHours) v * 60 else v)); toast("Расписание обновлено")
                }) { Text("Применить") }
            }
            Text("Минимум 5 минут. Интервал не обходит лимиты TikTok: если TikTok сообщит о дневном лимите, задание остановится с понятной ошибкой.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text("Дни публикаций", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.values().forEach { d ->
                    val on = d in s.schedule.allowedDays
                    FilterChip(selected = on, label = { Text(d.getDisplayName(TextStyle.SHORT_STANDALONE, ru)) }, onClick = {
                        val next = if (on) s.schedule.allowedDays - d else s.schedule.allowedDays + d
                        if (next.isNotEmpty()) vm.setSchedule(s.schedule.copy(allowedDays = next))
                    })
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Окно публикаций", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { timePickerFor = "start" }) { Text("с ${hhmm(s.schedule.windowStartMin)}") }
                OutlinedButton(onClick = { timePickerFor = "end" }) { Text("до ${hhmm(s.schedule.windowEndMin)}") }
            }
            Spacer(Modifier.height(10.dp))
            Text("Готовить заранее и напоминать", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 5, 15, 30, 60).forEach { m ->
                    FilterChip(selected = s.prepareAheadMinutes == m, onClick = { vm.setAhead(m) },
                        label = { Text(if (m == 0) "Нет" else "за $m мин") })
                }
            }
            Text("Заранее проверяется файл и собирается описание; если что-то не так, вы узнаете до публикации.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionTitle("Точность и энергосбережение")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Точные будильники", style = MaterialTheme.typography.bodyLarge)
                    Text("Запуск в заданную минуту даже в режиме Doze. Без них Android может сдвинуть запуск на несколько минут. Точность до секунды не гарантируется.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.exactAlarms && canExact, onCheckedChange = { v ->
                    if (v && !canExact && Build.VERSION.SDK_INT >= 31)
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) }
                    vm.setExact(v)
                })
            }
            OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) {
                Text("Настройки энергосбережения")
            }
            Text("На Xiaomi, Huawei, Samsung исключите ClipQueue из оптимизации батареи, иначе прошивка может задерживать задачи.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionTitle("Оформление")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeMode.entries.forEach { t -> FilterChip(selected = s.themeMode == t, onClick = { vm.setTheme(t) }, label = { Text(t.title) }) }
            }

            SectionTitle("Данные")
            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) { Text("История публикаций и статистика") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirm = "wipe" }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Удалить все данные приложения") }
            Text("ClipQueue использует только официальные способы TikTok, не хранит пароль от аккаунта и не нажимает кнопки в TikTok за вас. " +
                "«Опубликовано» ставится только после подтверждения TikTok API или вашего.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(32.dp))
        }
    }

    timePickerFor?.let { which ->
        val initial = if (which == "start") s.schedule.windowStartMin else s.schedule.windowEndMin
        val state = rememberTimePickerState(initial / 60 % 24, initial % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { timePickerFor = null },
            confirmButton = { TextButton(onClick = {
                val m = state.hour * 60 + state.minute
                vm.setSchedule(if (which == "start") s.schedule.copy(windowStartMin = m) else s.schedule.copy(windowEndMin = if (m == 0) 1440 else m))
                timePickerFor = null
            }) { Text("Готово") } },
            dismissButton = { TextButton(onClick = { timePickerFor = null }) { Text("Отмена") } },
            text = { TimePicker(state) }
        )
    }
    when (confirm) {
        "revoke" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Отозвать доступ к TikTok?") },
            text = { Text("ClipQueue потеряет доступ к аккаунту, токены будут удалены. Задания в режиме API перейдут в «Требует подтверждения».") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.revoke(toast) }) { Text("Отозвать") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } }
        )
        "wipe" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Удалить все данные?") },
            text = { Text("Будут удалены очередь, наборы хештегов, шаблоны, история, настройки и вход в TikTok; доступ к видео будет освобождён. " +
                "Видео в галерее не пострадают. Действие необратимо.") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.wipe(toast) }) { Text("Удалить всё") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } }
        )
    }
}

private fun hhmm(min: Int) = "%02d:%02d".format((min / 60) % 24, min % 60)

private fun modeDescription(m: PublishMode) = when (m) {
    PublishMode.SHARE -> "Работает сразу, без аккаунта разработчика. В назначенное время приходит уведомление, видео открывается в TikTok, описание уже в буфере. «Опубликовать» нажимаете вы."
    PublishMode.API_INBOX -> "Видео загружается через API в фоне и ждёт во «Входящих» TikTok. Вы завершаете публикацию одним касанием. Нужно разрешение video.upload."
    PublishMode.API_DIRECT -> "Полностью автоматическая публикация через API с подтверждением статуса. Нужно разрешение video.publish; до аудита TikTok посты только приватные."
}
