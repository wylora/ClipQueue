package com.clipqueue.app.domain

import com.clipqueue.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HashtagNormalizerTest {
    @Test fun createsSetFromMixedInput() {
        assertEquals(listOf("#horror", "#gaming", "#indiegame", "#scary"), HashtagNormalizer.parse("horror, #gaming  ##indiegame scary!"))
    }
    @Test fun splitsGluedTagsAndKeepsCyrillic() {
        assertEquals(listOf("#аниме", "#монтаж"), HashtagNormalizer.parse("#аниме#монтаж"))
    }
    @Test fun removesDuplicatesCaseInsensitive() {
        assertEquals(listOf("#FYP"), HashtagNormalizer.parse("#FYP #fyp fyp"))
    }
    @Test fun rejectsInvalidTokens() {
        assertTrue(HashtagNormalizer.parse("# ## !!! ___").isEmpty())
        assertNull(HashtagNormalizer.normalizeOne("#"))
    }
    @Test fun movesTagsWithinSet() {
        assertEquals(listOf("#b", "#a", "#c"), HashtagNormalizer.move(listOf("#a", "#b", "#c"), 1, -1))
        assertEquals(listOf("#a", "#b", "#c"), HashtagNormalizer.move(listOf("#a", "#b", "#c"), 0, -1))
    }
    @Test fun explainsAlreadyPresent() {
        assertTrue(HashtagNormalizer.explain("#a", listOf("#A")).any { it.contains("Уже есть") })
    }
}

class CaptionBuilderTest {
    private val zone = ZoneId.of("Europe/Tallinn")
    private val at = ZonedDateTime.of(2026, 10, 22, 18, 30, 0, 0, zone).toInstant().toEpochMilli()
    private val horror = HashtagSet(id = 1, name = "Хоррор", tags = "#horror #gaming #indiegame #scary")
    private val games = HashtagSet(id = 2, name = "Игры", tags = "#gaming #pc")
    private val sets = listOf(horror, games)
    private val unlimited = CaptionOptions(maxHashtags = 0)

    private fun item(caption: String = "", sets: String = "1,2", extra: String = "", auto: Boolean = true, override: String? = null) = QueueItem(
        videoUri = "content://x", displayName = "boss_fight.mp4", durationMs = 10_000, sizeBytes = 1, mimeType = "video/mp4",
        position = 2, customCaption = caption, seriesName = "Outlast", hashtagSetIds = sets, extraHashtags = extra,
        autoHashtags = auto, captionOverride = override, timeZone = zone.id
    )

    @Test fun templateVariablesAndOrderedSetsWithoutDuplicates() {
        val r = CaptionBuilder.build(item(), CaptionTemplate(name = "t", body = "{series} — часть {n}, {time}"), sets, unlimited, at, zone)
        assertEquals("Outlast — часть 3, 18:30\n\n#horror #gaming #indiegame #scary #pc", r.text)
        assertEquals(1, r.skippedDuplicates)
    }
    @Test fun setOrderFollowsSelection() {
        val r = CaptionBuilder.build(item(caption = "x", sets = "2,1"), null, sets, unlimited, at, zone)
        assertTrue(r.text.endsWith("#gaming #pc #horror #indiegame #scary"))
    }
    @Test fun customCaptionWinsAndTagsInTextAreNotRepeated() {
        val r = CaptionBuilder.build(item(caption = "Мой текст #horror"), CaptionTemplate(name = "t", body = "{title}"), sets, unlimited, at, zone)
        assertEquals("Мой текст #horror\n\n#gaming #indiegame #scary #pc", r.text)
    }
    @Test fun autoHashtagsCanBeDisabledButOwnTagsStay() {
        val r = CaptionBuilder.build(item(caption = "x", extra = "#mine", auto = false), null, sets, unlimited, at, zone)
        assertEquals("x\n\n#mine", r.text)
    }
    @Test fun respectsHashtagLimit() {
        val r = CaptionBuilder.build(item(caption = "x"), null, sets, CaptionOptions(maxHashtags = 3), at, zone)
        assertEquals("x\n\n#horror #gaming #indiegame", r.text)
        assertEquals(2, r.droppedByLimit)
    }
    @Test fun placesAfterSeparator() {
        val o = CaptionOptions(placement = HashtagPlacement.AFTER_SEPARATOR, separator = "👇", maxHashtags = 2)
        val r = CaptionBuilder.build(item(caption = "Смотри 👇 до конца"), null, sets, o, at, zone)
        assertEquals("Смотри 👇 #horror #gaming\nдо конца", r.text)
    }
    @Test fun templatePlaceholder() {
        val t = CaptionTemplate(name = "t", body = "Новый ролик уже здесь!\n{hashtags}\nСмотри до конца 👀")
        val r = CaptionBuilder.build(item(sets = "2"), t, sets, unlimited, at, zone)
        assertEquals("Новый ролик уже здесь!\n#gaming #pc\nСмотри до конца 👀", r.text)
    }
    @Test fun manualOverrideIsUsedAsIs() {
        val r = CaptionBuilder.build(item(override = "Только это #one"), null, sets, unlimited, at, zone)
        assertEquals("Только это #one", r.text)
        assertTrue(r.overridden)
    }
    /** Баг: хештеги не подставлялись — видео «замораживало» пустой набор по умолчанию. */
    @Test fun emptySelectionUsesCurrentDefaultSets() {
        val defaults = listOf(horror.copy(isDefault = true), games)
        val r = CaptionBuilder.build(item(caption = "x", sets = ""), null, defaults, unlimited, at, zone)
        assertEquals("x\n\n#horror #gaming #indiegame #scary", r.text)
        assertFalse(r.emptySets)
    }
    @Test fun explicitNoneAddsNoSets() {
        val r = CaptionBuilder.build(item(caption = "x", sets = NO_SETS, extra = "#mine"), null, sets.map { it.copy(isDefault = true) }, unlimited, at, zone)
        assertEquals("x\n\n#mine", r.text)
    }
    @Test fun flagsEmptySets() {
        val empty = listOf(HashtagSet(id = 9, name = "Основные", tags = "", isDefault = true))
        assertTrue(CaptionBuilder.build(item(caption = "x", sets = ""), null, empty, unlimited, at, zone).emptySets)
    }
    @Test fun detectsOverLimit() {
        assertTrue(CaptionBuilder.build(item(caption = "a".repeat(2201), sets = ""), null, sets, unlimited, at, zone).overLimit)
    }
    @Test fun resolvesTemplateByCategory() {
        val general = CaptionTemplate(id = 1, name = "g", body = "g", isDefault = true)
        val anime = CaptionTemplate(id = 2, name = "a", body = "a", category = "Аниме", isDefault = true)
        assertEquals(anime, TemplateResolver.resolve(item().copy(category = "аниме"), listOf(general, anime)))
        assertEquals(general, TemplateResolver.resolve(item().copy(category = "Игры"), listOf(general, anime)))
        assertEquals(general, TemplateResolver.resolve(item().copy(category = "Аниме", templateId = 1), listOf(general, anime)))
    }
}
