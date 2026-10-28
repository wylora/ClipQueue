package com.clipqueue.app.media

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.clipqueue.app.engine.Availability
import com.clipqueue.app.engine.VideoAccess

data class VideoInfo(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String?
)


/**
 * Работает с видео только через ContentResolver и URI: файлы не копируются
 * и не читаются целиком в память. Метаданные берутся из провайдера и заголовков контейнера.
 */
class VideoInspector(private val context: Context) : VideoAccess {
    private val resolver get() = context.contentResolver

    /**
     * Делает доступ к URI постоянным (переживает перезапуск телефона).
     * Поддерживается для Photo Picker и Storage Access Framework.
     */
    fun persistAccess(uri: Uri): Boolean = try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    }

    /** Список URI, к которым у приложения есть постоянный доступ (для полной очистки данных). */
    fun persistedUris(): List<Uri> = resolver.persistedUriPermissions.map { it.uri }

    fun releaseAccess(uri: Uri) {
        try {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) { }
    }

    fun inspect(uri: Uri): VideoInfo? {
        var name = "video.mp4"
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it)?.let { n -> name = n } }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        } catch (_: Exception) { return null }
        if (size < 0) size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        val duration = readDuration(uri)
        return VideoInfo(uri, name, duration, size, resolver.getType(uri))
    }

    private fun readDuration(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }

    override fun checkAvailable(uri: String): Availability {
        val uriString = uri
        return try {
            resolver.openAssetFileDescriptor(Uri.parse(uriString), "r")?.use { afd ->
                if (afd.length == 0L) Availability.Missing("Файл пустой") else Availability.Ok
            } ?: Availability.Missing("Провайдер не вернул файл")
        } catch (e: SecurityException) {
            Availability.Missing("Нет доступа к файлу. Добавьте видео заново")
        } catch (e: java.io.FileNotFoundException) {
            Availability.Missing("Файл удалён или перемещён")
        } catch (e: Exception) {
            Availability.Missing(e.message ?: "Файл недоступен")
        }
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "—"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024 * 1024 -> "%.0f КБ".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f МБ".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f ГБ".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
