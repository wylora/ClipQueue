package com.clipqueue.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.clipqueue.app.ClipQueueApp
import com.clipqueue.app.data.AppSettings
import com.clipqueue.app.ui.theme.ClipQueueTheme
import com.clipqueue.app.work.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Запрос открыть экран передачи в TikTok (из уведомления). */
    private val pendingHandoff = MutableStateFlow<Long?>(null)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        val container = (application as ClipQueueApp).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())
            val handoff by pendingHandoff.collectAsStateWithLifecycle()
            ClipQueueTheme(settings.themeMode) {
                AppNavigation(handoffRequest = handoff, onHandoffConsumed = { pendingHandoff.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val id = intent.getLongExtra(Notifications.EXTRA_HANDOFF_ID, -1)
        if (id > 0) {
            pendingHandoff.value = id
            intent.removeExtra(Notifications.EXTRA_HANDOFF_ID)
        }
        val data = intent.data
        if (data != null && data.scheme == "clipqueue" && data.host == "oauth") {
            val c = (application as ClipQueueApp).container
            lifecycleScope.launch {
                c.auth.handleRedirect(data)
                    .onSuccess {
                        c.queue.log(null, "Аккаунт TikTok подключён")
                        Toast.makeText(this@MainActivity, "TikTok подключён: ${it.displayName ?: it.openId}", Toast.LENGTH_LONG).show()
                    }
                    .onFailure { Toast.makeText(this@MainActivity, it.message ?: "Не удалось подключить TikTok", Toast.LENGTH_LONG).show() }
            }
            intent.data = null
        }
    }
}
