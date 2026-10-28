package com.clipqueue.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_items ORDER BY position ASC")
    fun observeAll(): Flow<List<QueueItem>>

    @Query("SELECT * FROM queue_items WHERE id = :id")
    fun observe(id: Long): Flow<QueueItem?>

    @Query("SELECT * FROM queue_items WHERE id = :id")
    suspend fun get(id: Long): QueueItem?

    @Query("SELECT * FROM queue_items ORDER BY position ASC")
    suspend fun getAll(): List<QueueItem>

    @Query("SELECT * FROM queue_items WHERE id IN (:ids)")
    suspend fun getMany(ids: List<Long>): List<QueueItem>

    @Query("SELECT COUNT(*) FROM queue_items WHERE videoUri = :uri")
    suspend fun countByUri(uri: String): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM queue_items")
    suspend fun maxPosition(): Int

    /**
     * Атомарный переход статуса. Возвращает 0, если задание уже в другом состоянии —
     * так один и тот же ролик не отправится дважды при повторном запуске задачи.
     */
    @Query("UPDATE queue_items SET status = :to WHERE id = :id AND status IN (:from)")
    suspend fun transition(id: Long, from: List<String>, to: String): Int

    @Insert suspend fun insert(item: QueueItem): Long
    @Update suspend fun update(item: QueueItem)
    @Update suspend fun updateAll(items: List<QueueItem>)
    @Query("DELETE FROM queue_items WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface HashtagDao {
    @Query("SELECT * FROM hashtag_sets ORDER BY isDefault DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<HashtagSet>>
    @Query("SELECT * FROM hashtag_sets") suspend fun getAll(): List<HashtagSet>
    @Query("SELECT * FROM hashtag_sets WHERE id IN (:ids)") suspend fun getMany(ids: List<Long>): List<HashtagSet>
    @Query("SELECT * FROM hashtag_sets WHERE id = :id") suspend fun get(id: Long): HashtagSet?
    @Query("SELECT * FROM hashtag_sets WHERE isDefault = 1") suspend fun getDefaults(): List<HashtagSet>
    @Insert suspend fun insert(set: HashtagSet): Long
    @Update suspend fun update(set: HashtagSet)
    @Query("DELETE FROM hashtag_sets WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM caption_templates ORDER BY category COLLATE NOCASE ASC, isDefault DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CaptionTemplate>>
    @Query("SELECT * FROM caption_templates") suspend fun getAll(): List<CaptionTemplate>
    @Query("SELECT * FROM caption_templates WHERE id = :id") suspend fun get(id: Long): CaptionTemplate?
    @Insert suspend fun insert(t: CaptionTemplate): Long
    @Update suspend fun update(t: CaptionTemplate)
    @Query("UPDATE caption_templates SET isDefault = (id = :id) WHERE category = :category")
    suspend fun setDefault(id: Long, category: String)
    @Query("DELETE FROM caption_templates WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntry>>
    @Query("SELECT * FROM history WHERE itemId = :itemId ORDER BY timestamp DESC")
    fun observeForItem(itemId: Long): Flow<List<HistoryEntry>>
    @Insert suspend fun insert(e: HistoryEntry)
    @Query("DELETE FROM history WHERE timestamp < :before") suspend fun prune(before: Long)
}

@Dao
interface RecordDao {
    @Query("SELECT * FROM publish_records ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PublishRecord>>
    @Insert suspend fun insert(r: PublishRecord): Long
}

@Database(
    entities = [QueueItem::class, HashtagSet::class, CaptionTemplate::class, HistoryEntry::class, PublishRecord::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun queue(): QueueDao
    abstract fun hashtags(): HashtagDao
    abstract fun templates(): TemplateDao
    abstract fun history(): HistoryDao
    abstract fun records(): RecordDao
}
