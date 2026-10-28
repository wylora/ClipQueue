package com.clipqueue.app.engine

import com.clipqueue.app.data.*
import com.clipqueue.app.domain.CaptionResult

class FakeStore(vararg items: QueueItem) : QueueStore {
    val items = items.associateBy { it.id }.toMutableMap()
    val records = mutableListOf<PublishRecord>()
    val logs = mutableListOf<String>()
    override suspend fun get(id: Long) = items[id]
    override suspend fun update(item: QueueItem) { items[item.id] = item }
    override suspend fun transition(id: Long, from: Set<PostStatus>, to: PostStatus): Boolean {
        val i = items[id] ?: return false
        if (i.status !in from) return false
        items[id] = i.copy(status = to); return true
    }
    override suspend fun log(itemId: Long?, message: String, isError: Boolean) { logs += message }
    override suspend fun record(r: PublishRecord) { records += r }
}

class FakeSettings(var s: AppSettings) : SettingsSource {
    override suspend fun current() = s
    override suspend fun setLastPostAt(v: Long) { s = s.copy(lastPostAt = v) }
    override suspend fun incrementPrepared() { s = s.copy(preparedTotal = s.preparedTotal + 1) }
}

class FakePreparer(var failure: String? = null) : PostPreparing {
    override suspend fun caption(item: QueueItem) = CaptionResult("Текст #tag", listOf("#tag"))
    override suspend fun prepare(item: QueueItem): Preparation =
        failure?.let { Preparation.Failed(it) } ?: Preparation.Ready(item.copy(finalCaption = "Текст #tag"), caption(item))
}

class FakeTokens(var connected: Boolean = true, var scopes: Set<String> = setOf("video.upload", "video.publish"), var authError: Boolean = false) : TokenProvider {
    override fun isConnected() = connected
    override fun hasScope(scope: String) = scope in scopes
    override suspend fun accessToken(): String { if (authError) throw AuthException("Вход истёк"); return "token" }
}

class FakeApi : PostingApi {
    var statuses = ArrayDeque(listOf("PROCESSING_UPLOAD", "PUBLISH_COMPLETE"))
    var initError: Exception? = null
    var uploadError: Exception? = null
    var statusError: Exception? = null
    var privacy = listOf("PUBLIC_TO_EVERYONE", "SELF_ONLY")
    var inits = 0
    var uploads = 0
    override suspend fun creatorInfo(token: String) = CreatorInfo("me", privacy, 600, false, false, false)
    override suspend fun initDirect(token: String, caption: String, privacy: String, size: Long, creator: CreatorInfo): InitResult {
        initError?.let { throw it }; inits++; return InitResult("pub-1", "https://upload")
    }
    override suspend fun initInbox(token: String, size: Long): InitResult { initError?.let { throw it }; inits++; return InitResult("pub-1", "https://upload") }
    override suspend fun upload(videoUri: String, size: Long, mime: String, uploadUrl: String, onProgress: suspend (Float) -> Unit) {
        uploadError?.let { throw it }; uploads++; onProgress(1f)
    }
    override suspend fun fetchStatus(token: String, publishId: String): PublishStatus {
        statusError?.let { throw it }
        return PublishStatus(statuses.removeFirstOrNull() ?: "PROCESSING_UPLOAD", "bad_video", listOf("7300000000000"))
    }
}

class FakeNotifier : Notifier {
    val events = mutableListOf<String>()
    override fun upcoming(item: QueueItem) { events += "upcoming" }
    var lastQuick: Boolean? = null
    override fun actionNeeded(item: QueueItem, reason: String, quickShare: Boolean) { events += "action"; lastQuick = quickShare }
    override fun result(item: QueueItem, title: String, text: String) { events += "result:$title" }
}

class FakeVideo(var result: Availability = Availability.Ok) : VideoAccess {
    override fun checkAvailable(uri: String) = result
}

class FakeInputs(
    val list: List<CaptionTemplate> = emptyList(),
    val setsList: List<HashtagSet> = emptyList(),
    val opts: CaptionOptions = CaptionOptions()
) : CaptionInputs {
    override suspend fun templates() = list
    override suspend fun allSets() = setsList
    override suspend fun options() = opts
}
