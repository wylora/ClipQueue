package com.clipqueue.app.data

import com.clipqueue.app.domain.HashtagNormalizer
import com.clipqueue.app.engine.CaptionInputs
import com.clipqueue.app.engine.QueueStore
import kotlinx.coroutines.flow.Flow

class QueueRepository(private val db: AppDatabase) : QueueStore {
    private val dao = db.queue()
    private val history = db.history()
    private val records = db.records()

    val items: Flow<List<QueueItem>> = dao.observeAll()
    val publishRecords: Flow<List<PublishRecord>> = records.observeAll()
    fun item(id: Long): Flow<QueueItem?> = dao.observe(id)
    fun recentHistory(limit: Int = 30): Flow<List<HistoryEntry>> = history.observeRecent(limit)
    fun historyFor(id: Long): Flow<List<HistoryEntry>> = history.observeForItem(id)

    override suspend fun get(id: Long) = dao.get(id)
    override suspend fun update(item: QueueItem) = dao.update(item)
    override suspend fun transition(id: Long, from: Set<PostStatus>, to: PostStatus): Boolean =
        dao.transition(id, from.map { it.name }, to.name) > 0
    override suspend fun record(r: PublishRecord) { records.insert(r) }
    override suspend fun log(itemId: Long?, message: String, isError: Boolean) {
        history.insert(HistoryEntry(itemId = itemId, message = message, isError = isError))
        history.prune(System.currentTimeMillis() - 180L * 24 * 3600 * 1000)
    }

    suspend fun getAll() = dao.getAll()
    suspend fun getMany(ids: List<Long>) = dao.getMany(ids)
    suspend fun isUriUsed(uri: String) = dao.countByUri(uri) > 0

    suspend fun add(item: QueueItem): Long {
        val id = dao.insert(item.copy(id = 0, position = dao.maxPosition() + 1))
        log(id, "Добавлено: ${item.displayName}")
        return id
    }

    suspend fun delete(id: Long) {
        val name = dao.get(id)?.displayName
        dao.delete(id)
        log(null, "Удалено из очереди: ${name ?: "#$id"}")
    }

    /** Новый порядок публикации. Записи вне списка сохраняют относительный порядок в конце. */
    suspend fun reorder(orderedIds: List<Long>) {
        val all = dao.getAll()
        val byId = all.associateBy { it.id }
        val head = orderedIds.mapNotNull { byId[it] }
        val tail = all.filter { it.id !in orderedIds.toSet() }
        dao.updateAll((head + tail).mapIndexed { i, it -> it.copy(position = i) })
    }

    suspend fun move(id: Long, delta: Int) {
        val ids = dao.getAll().map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        ids.add(to, ids.removeAt(from))
        reorder(ids)
    }
}

class HashtagRepository(private val dao: HashtagDao) {
    val sets: Flow<List<HashtagSet>> = dao.observeAll()
    suspend fun all() = dao.getAll()
    suspend fun many(ids: List<Long>) = if (ids.isEmpty()) emptyList() else dao.getMany(ids)
    suspend fun defaultIds(): List<Long> = dao.getDefaults().map { it.id }

    suspend fun ensureSeed() {
        if (dao.getAll().isEmpty()) dao.insert(HashtagSet(name = "Основные", tags = "", isDefault = true))
    }

    /** Есть ли хоть один хештег в наборах по умолчанию. */
    suspend fun defaultsHaveTags(): Boolean = dao.getDefaults().any { it.tagList.isNotEmpty() }

    suspend fun save(set: HashtagSet): Long {
        val normalized = set.copy(
            name = set.name.trim().ifBlank { "Без названия" },
            tags = HashtagNormalizer.join(HashtagNormalizer.parse(set.tags))
        )
        return if (normalized.id == 0L) dao.insert(normalized) else { dao.update(normalized); normalized.id }
    }

    suspend fun copy(set: HashtagSet): Long =
        dao.insert(set.copy(id = 0, name = "${set.name} (копия)", isDefault = false, createdAt = System.currentTimeMillis()))

    suspend fun delete(id: Long) = dao.delete(id)
}

class TemplateRepository(private val dao: TemplateDao) {
    val templates: Flow<List<CaptionTemplate>> = dao.observeAll()
    suspend fun all() = dao.getAll()
    suspend fun get(id: Long) = dao.get(id)

    suspend fun ensureSeed() {
        if (dao.getAll().isEmpty()) {
            dao.insert(CaptionTemplate(name = "Название видео", body = "{title}", isDefault = true))
            dao.insert(CaptionTemplate(name = "Серия", body = "{series} — часть {n}\n{hashtags}"))
        }
    }

    suspend fun save(t: CaptionTemplate): Long {
        val clean = t.copy(name = t.name.trim(), category = t.category.trim())
        val id = if (clean.id == 0L) dao.insert(clean.copy(isDefault = false)) else { dao.update(clean); clean.id }
        if (clean.isDefault) dao.setDefault(id, clean.category)
        return id
    }

    suspend fun setDefault(t: CaptionTemplate) = dao.setDefault(t.id, t.category)
    suspend fun delete(id: Long) = dao.delete(id)
}

/** Источник данных для сборки описания из Room и DataStore. */
class RoomCaptionInputs(
    private val hashtags: HashtagRepository,
    private val templates: TemplateRepository,
    private val settings: SettingsRepository
) : CaptionInputs {
    override suspend fun templates() = templates.all()
    override suspend fun allSets() = hashtags.all()
    override suspend fun options() = settings.current().caption
}
