package com.clipqueue.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Сохранение очереди, наборов и истории в настоящей SQLite (in-memory). Запуск: ./gradlew connectedAndroidTest */
@RunWith(AndroidJUnit4::class)
class QueueDatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var queue: QueueRepository
    private lateinit var hashtags: HashtagRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        queue = QueueRepository(db)
        hashtags = HashtagRepository(db.hashtags())
    }
    @After fun tearDown() = db.close()

    private fun video(name: String) = QueueItem(videoUri = "content://$name", displayName = name, durationMs = 5000, sizeBytes = 10, mimeType = "video/mp4")

    @Test fun addEditAndReorderPersist() = runTest {
        val a = queue.add(video("a")); val b = queue.add(video("b")); val c = queue.add(video("c"))
        queue.update(queue.get(b)!!.copy(customCaption = "новый текст", notes = "заметка"))
        queue.reorder(listOf(c, a, b))
        val all = queue.getAll()
        assertEquals(listOf(c, a, b), all.map { it.id })
        assertEquals("новый текст", queue.get(b)!!.customCaption)
        assertEquals(listOf(0, 1, 2), all.map { it.position })
    }

    @Test fun transitionIsAtomic() = runTest {
        val id = queue.add(video("a"))
        assertTrue(queue.transition(id, setOf(PostStatus.PENDING, PostStatus.READY), PostStatus.PUBLISHING))
        assertFalse(queue.transition(id, setOf(PostStatus.PENDING, PostStatus.READY), PostStatus.PUBLISHING))
        assertEquals(PostStatus.PUBLISHING, queue.get(id)!!.status)
    }

    @Test fun hashtagSetSaveNormalizesAndCopies() = runTest {
        val id = hashtags.save(HashtagSet(name = " Хоррор ", tags = "horror #gaming gaming scary!"))
        val saved = hashtags.many(listOf(id)).single()
        assertEquals("Хоррор", saved.name)
        assertEquals("#horror #gaming #scary", saved.tags)
        val copyId = hashtags.copy(saved)
        assertEquals("Хоррор (копия)", hashtags.many(listOf(copyId)).single().name)
    }

    @Test fun recordsSurviveItemDeletion() = runTest {
        val id = queue.add(video("a"))
        queue.record(PublishRecord(itemId = id, videoName = "a", scheduledAt = null, publishedAt = 1, caption = "x",
            hashtags = "", result = RecordResult.CONFIRMED_BY_USER, mode = PublishMode.SHARE))
        queue.delete(id)
        assertEquals(1, queue.publishRecords.first().size)
        assertNull(queue.get(id))
    }
}
