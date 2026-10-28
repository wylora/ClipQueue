package com.clipqueue.app.engine

import com.clipqueue.app.data.*
import com.clipqueue.app.domain.CaptionBuilder
import com.clipqueue.app.domain.CaptionResult
import com.clipqueue.app.domain.TemplateResolver

sealed interface Preparation {
    data class Ready(val item: QueueItem, val caption: CaptionResult) : Preparation
    data class Failed(val reason: String) : Preparation
}

interface PostPreparing {
    suspend fun caption(item: QueueItem): CaptionResult
    suspend fun prepare(item: QueueItem): Preparation
}

/** Подготовка публикации для всех режимов: доступность файла, длительность, описание, лимиты. */
class PostPreparer(private val video: VideoAccess, private val inputs: CaptionInputs) : PostPreparing {

    override suspend fun caption(item: QueueItem): CaptionResult {
        val template = TemplateResolver.resolve(item, inputs.templates())
        return CaptionBuilder.build(item, template, inputs.allSets(), inputs.options())
    }

    override suspend fun prepare(item: QueueItem): Preparation {
        when (val a = video.checkAvailable(item.videoUri)) {
            is Availability.Missing -> return Preparation.Failed(a.reason)
            Availability.Ok -> Unit
        }
        if (item.durationMs in 1 until MIN_VIDEO_MS) return Preparation.Failed("TikTok принимает видео от 3 секунд")
        val caption = caption(item)
        if (caption.overLimit)
            return Preparation.Failed("Описание длиннее $TIKTOK_CAPTION_LIMIT символов (${caption.length}). Сократите текст или хештеги")
        return Preparation.Ready(item.copy(finalCaption = caption.text), caption)
    }
}
