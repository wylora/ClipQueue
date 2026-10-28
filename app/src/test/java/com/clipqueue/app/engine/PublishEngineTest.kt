package com.clipqueue.app.engine

import com.clipqueue.app.data.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PublishEngineTest {
    private fun item(status: PostStatus = PostStatus.PENDING, privacy: String? = "PUBLIC_TO_EVERYONE", confirmed: Boolean = false) = QueueItem(
        id = 1, videoUri = "content://v", displayName = "clip.mp4", durationMs = 10_000, sizeBytes = 1000,
        mimeType = "video/mp4", status = status, privacyLevel = privacy, captionConfirmed = confirmed, scheduledAt = 1000
    )

    private class Env(
        val item: QueueItem,
        mode: PublishMode = PublishMode.API_DIRECT,
        running: Boolean = true,
        review: Boolean = false
    ) {
        val store = FakeStore(item)
        val settings = FakeSettings(AppSettings(publishMode = mode, queueRunning = running, requireReview = review))
        val preparer = FakePreparer()
        val api = FakeApi()
        val tokens = FakeTokens()
        val notifier = FakeNotifier()
        val engine = PublishEngine(store, settings, preparer, api, tokens, notifier, now = { 5000L }, pollDelayMs = 0)
        val saved get() = store.items[1]!!
    }

    @Test fun directPostPublishesOnlyAfterTikTokConfirms() = runTest {
        val e = Env(item())
        assertEquals(Outcome.Done, e.engine.publish(1, force = false, attempt = 0))
        assertEquals(PostStatus.PUBLISHED, e.saved.status)
        assertEquals(RecordResult.PUBLISHED_API, e.store.records.single().result)
        assertEquals("7300000000000", e.store.records.single().publishId)
        assertEquals(1, e.settings.s.preparedTotal)
    }

    @Test fun stillProcessingIsNotMarkedPublished() = runTest {
        val e = Env(item()).apply { api.statuses = ArrayDeque() }
        val out = e.engine.publish(1, false, 0)
        assertTrue(out is Outcome.CheckLater)
        assertEquals(PostStatus.PUBLISHING, e.saved.status)
        assertTrue(e.store.records.isEmpty())
    }

    @Test fun inboxModeNeedsUserConfirmation() = runTest {
        val e = Env(item(), mode = PublishMode.API_INBOX).apply { api.statuses = ArrayDeque(listOf("SEND_TO_USER_INBOX")) }
        e.engine.publish(1, false, 0)
        assertEquals(PostStatus.NEEDS_CONFIRMATION, e.saved.status)
        assertEquals(RecordResult.SENT_TO_INBOX, e.store.records.single().result)
    }

    @Test fun shareModeAlwaysAsksUser() = runTest {
        val e = Env(item(), mode = PublishMode.SHARE)
        e.engine.publish(1, false, 0)
        assertEquals(PostStatus.NEEDS_CONFIRMATION, e.saved.status)
        assertEquals(0, e.api.inits)
        assertTrue(e.notifier.events.contains("action"))
    }

    /** Баг: в режиме подтверждения время отправки не сохранялось, и следующие видео шли подряд. */
    @Test fun shareModeRecordsDispatchTimeAndOffersOneTap() = runTest {
        val e = Env(item(), mode = PublishMode.SHARE)
        e.engine.publish(1, false, 0)
        assertEquals(5000L, e.settings.s.lastPostAt)
        assertEquals(true, e.notifier.lastQuick)
    }

    @Test fun reviewIsNotOneTap() = runTest {
        val e = Env(item(), review = true)
        e.engine.publish(1, false, 0)
        assertEquals(false, e.notifier.lastQuick)
    }

    @Test fun switchesToConfirmationWhenNotAuthorized() = runTest {
        val e = Env(item()).apply { tokens.connected = false }
        e.engine.publish(1, false, 0)
        assertEquals(PostStatus.NEEDS_CONFIRMATION, e.saved.status)
        assertTrue(e.saved.statusReason!!.contains("не подключён"))
    }

    @Test fun missingScopeOrPrivacyRequiresConfirmation() = runTest {
        val e1 = Env(item()).apply { tokens.scopes = setOf("video.upload") }
        e1.engine.publish(1, false, 0)
        assertEquals(PostStatus.NEEDS_CONFIRMATION, e1.saved.status)
        val e2 = Env(item(privacy = null))
        e2.engine.publish(1, false, 0)
        assertEquals(PostStatus.NEEDS_CONFIRMATION, e2.saved.status)
    }

    @Test fun requiredReviewBlocksUnconfirmedText() = runTest {
        val e = Env(item(), review = true)
        e.engine.publish(1, false, 0)
        assertEquals(PostStatus.NEEDS_CONFIRMATION, e.saved.status)
        assertEquals(0, e.api.inits)
        val ok = Env(item(confirmed = true), review = true)
        ok.engine.publish(1, false, 0)
        assertEquals(PostStatus.PUBLISHED, ok.saved.status)
    }

    @Test fun pausedQueueDoesNothingUnlessForced() = runTest {
        val e = Env(item(), running = false)
        e.engine.publish(1, false, 0)
        assertEquals(PostStatus.PENDING, e.saved.status)
        e.engine.publish(1, true, 0)
        assertEquals(PostStatus.PUBLISHED, e.saved.status)
    }

    @Test fun doesNotPublishTwice() = runTest {
        val e = Env(item())
        e.engine.publish(1, false, 0)
        e.engine.publish(1, false, 0)
        e.engine.publish(1, true, 0)
        assertEquals(1, e.api.inits)
        assertEquals(1, e.store.records.size)
    }

    @Test fun skipsWhenAnotherRunAlreadyClaimed() = runTest {
        val e = Env(item(status = PostStatus.PUBLISHING))
        assertEquals(Outcome.Done, e.engine.publish(1, true, 0))
        assertEquals(0, e.api.inits)
    }

    @Test fun networkErrorBeforeUploadRetriesThenFails() = runTest {
        val e = Env(item()).apply { api.initError = IOException("timeout") }
        assertEquals(Outcome.Retry, e.engine.publish(1, false, attempt = 0))
        assertEquals(PostStatus.PENDING, e.saved.status)
        assertEquals(Outcome.Done, e.engine.publish(1, false, attempt = 2))
        assertEquals(PostStatus.ERROR, e.saved.status)
        assertEquals(RecordResult.FAILED, e.store.records.single().result)
    }

    @Test fun nonRetryableApiErrorFailsImmediately() = runTest {
        val e = Env(item()).apply { api.initError = TikTokApiException("spam_risk_too_many_posts", "Дневной лимит", false) }
        assertEquals(Outcome.Done, e.engine.publish(1, false, 0))
        assertEquals(PostStatus.ERROR, e.saved.status)
        assertEquals("Дневной лимит", e.saved.lastError)
    }

    @Test fun errorAfterUploadNeverResends() = runTest {
        val e = Env(item()).apply { api.statusError = IOException("lost") }
        val out = e.engine.publish(1, false, 0)
        assertTrue(out is Outcome.CheckLater)
        assertEquals(1, e.api.uploads)
        assertEquals(PostStatus.PUBLISHING, e.saved.status)
        // Повторный запуск публикации не отправляет видео снова.
        e.engine.publish(1, true, 1)
        assertEquals(1, e.api.inits)
        // Проверка статуса позже завершает публикацию.
        e.api.statusError = null
        e.api.statuses = ArrayDeque(listOf("PUBLISH_COMPLETE"))
        e.engine.checkStatus(1)
        assertEquals(PostStatus.PUBLISHED, e.saved.status)
    }

    @Test fun expiredLoginFallsBackToConfirmation() = runTest {
        val e = Env(item()).apply { tokens.authError = true }
        e.engine.publish(1, false, 0)
        assertEquals(PostStatus.NEEDS_CONFIRMATION, e.saved.status)
        assertNull(e.saved.publishId)
    }

    @Test fun unavailablePrivacyAsksUser() = runTest {
        val e = Env(item(privacy = "FOLLOWER_OF_CREATOR"))
        e.engine.publish(1, false, 0)
        assertEquals(PostStatus.NEEDS_CONFIRMATION, e.saved.status)
        assertEquals(0, e.api.uploads)
    }

    @Test fun tiktokRejectionIsError() = runTest {
        val e = Env(item()).apply { api.statuses = ArrayDeque(listOf("FAILED")) }
        e.engine.publish(1, false, 0)
        assertEquals(PostStatus.ERROR, e.saved.status)
        assertTrue(e.saved.lastError!!.contains("bad_video"))
    }

    @Test fun preparationFailureIsErrorWithoutApiCalls() = runTest {
        val e = Env(item()).apply { preparer.failure = "Файл удалён" }
        e.engine.publish(1, false, 0)
        assertEquals(PostStatus.ERROR, e.saved.status)
        assertEquals(0, e.api.inits)
    }

    @Test fun prepareAheadMarksReadyAndNotifies() = runTest {
        val e = Env(item())
        e.engine.prepareAhead(1)
        assertEquals(PostStatus.READY, e.saved.status)
        assertEquals("Текст #tag", e.saved.finalCaption)
        assertTrue(e.notifier.events.contains("upcoming"))
        e.engine.publish(1, false, 0)
        assertEquals(PostStatus.PUBLISHED, e.saved.status)
    }

    @Test fun cancelledOrDraftIsIgnored() = runTest {
        for (s in listOf(PostStatus.CANCELLED, PostStatus.DRAFT, PostStatus.PUBLISHED)) {
            val e = Env(item(status = s))
            e.engine.publish(1, false, 0)
            assertEquals(s, e.saved.status)
        }
    }
}

class PostPreparerTest {
    private val base = QueueItem(id = 1, videoUri = "content://v", displayName = "a.mp4", durationMs = 10_000, sizeBytes = 1,
        mimeType = "video/mp4", customCaption = "Привет", hashtagSetIds = "1")
    private val inputs = FakeInputs(setsList = listOf(HashtagSet(id = 1, name = "s", tags = "#a #b")), opts = CaptionOptions(maxHashtags = 0))

    @Test fun readyWithCaptionSnapshot() = runTest {
        val p = PostPreparer(FakeVideo(), inputs).prepare(base)
        assertTrue(p is Preparation.Ready)
        assertEquals("Привет\n\n#a #b", (p as Preparation.Ready).item.finalCaption)
    }
    @Test fun missingFileFails() = runTest {
        val p = PostPreparer(FakeVideo(Availability.Missing("Файл удалён")), inputs).prepare(base)
        assertEquals(Preparation.Failed("Файл удалён"), p)
    }
    @Test fun tooShortVideoFails() = runTest {
        assertTrue(PostPreparer(FakeVideo(), inputs).prepare(base.copy(durationMs = 1500)) is Preparation.Failed)
    }
    @Test fun tooLongCaptionFails() = runTest {
        assertTrue(PostPreparer(FakeVideo(), inputs).prepare(base.copy(customCaption = "x".repeat(2300))) is Preparation.Failed)
    }
}
