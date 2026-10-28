package com.clipqueue.app.publish

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Передача видео в приложение TikTok официальным способом — через Intent ACTION_SEND
 * (описан в документации TikTok Share Kit как поддерживаемая альтернатива SDK).
 * TikTok сам открывает редактор; подпись туда не передаётся, поэтому она копируется в буфер.
 */
class ShareLauncher(private val context: Context) {
    companion object {
        val TIKTOK_PACKAGES = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
    }

    fun installedTikTok(): String? = TIKTOK_PACKAGES.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0); true
        } catch (_: PackageManager.NameNotFoundException) { false }
    }

    fun copyCaption(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Подпись TikTok", text))
    }

    /** Должен вызываться из Activity (Android запрещает запуск чужих экранов из фона). */
    suspend fun launch(activityContext: Context, videoUri: String, mime: String?): Result<Unit> = runCatching {
        val pkg = installedTikTok() ?: throw IllegalStateException("Приложение TikTok не установлено")
        val uri = Uri.parse(videoUri)
        try {
            activityContext.startActivity(buildIntent(uri, mime, pkg))
        } catch (e: SecurityException) {
            // Некоторые провайдеры не разрешают передавать доступ дальше — делаем временную копию.
            val shared = copyToShareCache(uri)
            activityContext.startActivity(buildIntent(shared, "video/mp4", pkg))
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("TikTok не принимает видео. Обновите приложение TikTok")
        }
    }

    private fun buildIntent(uri: Uri, mime: String?, pkg: String) = Intent(Intent.ACTION_SEND).apply {
        type = mime?.takeIf { it.startsWith("video/") } ?: "video/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("video", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setPackage(pkg)
    }

    private suspend fun copyToShareCache(uri: Uri): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // держим только одну временную копию
        val out = File(dir, "clip_${System.currentTimeMillis()}.mp4")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it, 256 * 1024) }
        } ?: throw IllegalStateException("Не удалось прочитать видео")
        FileProvider.getUriForFile(context, "${context.packageName}.files", out)
    }
}
