package com.unarchive.android.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioExportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // --- resolveCachedAudio -------------------------------------------------

    @Test
    fun resolvesCachedAudioIgnoringMetadataAndTransientFiles() {
        val cache = temporaryFolder.newFolder("cache")
        cache.resolve("BV1PS42197aM.m4a").writeBytes(byteArrayOf(1, 2, 3))
        cache.resolve("BV1PS42197aM.m4a.json").writeText("{}")
        cache.resolve("BV1PS42197aM.m4a.part").writeBytes(byteArrayOf(9))
        cache.resolve("BV1PS42197aM.m4a.backup").writeBytes(byteArrayOf(8))

        val resolved = resolveCachedAudio(cache, "BV1PS42197aM")

        assertEquals("BV1PS42197aM.m4a", resolved?.name)
        assertTrue(resolved!!.isFile)
    }

    @Test
    fun returnsNullWhenNoAudioFileIsCached() {
        val cache = temporaryFolder.newFolder("cache")
        cache.resolve("BV1PS42197aM.m4a.json").writeText("{}")
        cache.resolve("BV1PS42197aM.m4a.part").writeBytes(byteArrayOf(9))

        assertNull(resolveCachedAudio(cache, "BV1PS42197aM"))
    }

    @Test
    fun returnsNullWhenCacheDirectoryDoesNotExist() {
        val missing = File(temporaryFolder.root, "does-not-exist")
        assertNull(resolveCachedAudio(missing, "BV1PS42197aM"))
    }

    @Test
    fun ignoresZeroByteFilesAndPrefersLargestWhenMultipleExtensionsExist() {
        val cache = temporaryFolder.newFolder("cache")
        cache.resolve("BV1PS42197aM.webm").writeBytes(byteArrayOf(1))
        cache.resolve("BV1PS42197aM.m4a").writeBytes(byteArrayOf(1, 2, 3, 4))
        cache.resolve("BV1PS42197aM.audio").writeBytes(byteArrayOf())
        cache.resolve("BV1PS42197aM.txt").writeBytes(byteArrayOf(7))

        val resolved = resolveCachedAudio(cache, "BV1PS42197aM")

        assertEquals("BV1PS42197aM.m4a", resolved?.name)
    }

    @Test
    fun doesNotMatchAnotherVideoIdPrefix() {
        val cache = temporaryFolder.newFolder("cache")
        cache.resolve("BV1PS42197aM.m4a").writeBytes(byteArrayOf(1))
        cache.resolve("BV1PS42197aMX.m4a").writeBytes(byteArrayOf(2))

        assertEquals("BV1PS42197aM.m4a", resolveCachedAudio(cache, "BV1PS42197aM")?.name)
    }

    // --- exportAudioFileName ------------------------------------------------

    @Test
    fun exportFileNameKeepsChineseTitleAndReplacesForbiddenCharacters() {
        val name = exportAudioFileName("BV1PS42197aM", "4种常见错误跑姿: 扫雷!", "M4A")

        assertEquals("BV1PS42197aM_4种常见错误跑姿_扫雷!.m4a", name)
    }

    @Test
    fun exportFileNameFallsBackWhenTitleOrVideoIdIsBlank() {
        assertEquals("video_audio.webm", exportAudioFileName("", "", "webm"))
        assertEquals("BV1PS42197aM_audio.m4a", exportAudioFileName("BV1PS42197aM", "   ", "m4a"))
    }

    @Test
    fun exportFileNameCapsTitleLength() {
        val longTitle = "x".repeat(200)
        val name = exportAudioFileName("BV1PS42197aM", longTitle, "m4a")

        assertTrue(name.startsWith("BV1PS42197aM_"))
        assertEquals(EXPORT_FILE_NAME_MAX_TITLE_LENGTH + "BV1PS42197aM_".length + ".m4a".length, name.length)
    }

    @Test
    fun exportFileNameFallsBackToAudioExtensionWhenBlank() {
        assertEquals("BV1PS42197aM_title.audio", exportAudioFileName("BV1PS42197aM", "title", ""))
    }

    // --- sanitizeFileNamePart -----------------------------------------------

    @Test
    fun sanitizeCollapsesWhitespaceAndTrimsSeparators() {
        assertEquals("a_b_c", sanitizeFileNamePart("  a  b  c  "))
        assertEquals("a_b", sanitizeFileNamePart("a/b"))
        assertEquals("a", sanitizeFileNamePart("a...."))
    }

    @Test
    fun sanitizeRejectsOversizedInput() {
        assertEquals("x".repeat(60), sanitizeFileNamePart("x".repeat(500)))
    }

    // --- audioMimeType ------------------------------------------------------

    @Test
    fun audioMimeTypeMapsKnownExtensionsAndDefaultsToAudioStar() {
        assertEquals("audio/mp4", audioMimeType("m4a"))
        assertEquals("audio/mp4", audioMimeType("M4A"))
        assertEquals("audio/mp4", audioMimeType("mp4"))
        assertEquals("audio/webm", audioMimeType("webm"))
        assertEquals("audio/wav", audioMimeType("wav"))
        assertEquals("audio/mpeg", audioMimeType("mp3"))
        assertEquals("audio/*", audioMimeType("audio"))
        assertEquals("audio/*", audioMimeType(""))
    }
}
