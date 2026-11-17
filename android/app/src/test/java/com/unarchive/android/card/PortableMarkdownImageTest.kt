package com.unarchive.android.card

import org.junit.Assert.*
import org.junit.Test

class PortableMarkdownImageTest {
    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()

    @Test fun realJpegEmbedsAndATruncatedOrMislabeledJpegDoesNot() {
        val bytes = PortableImageFixtures.jpeg
        val asset = PortableImageFixtures.asset("assets/photo.jpg", bytes = bytes).copy(mimeType = "image/jpeg")
        val accepted = MarkdownCardRenderer.embedAssetsWithStats("![photo](assets/photo.jpg)", listOf(asset)) { bytes }
        assertEquals(1, accepted.embeddedCount)
        assertTrue(accepted.markdown.contains("data:image/jpeg;base64,"))
        val truncated = bytes.copyOfRange(0, bytes.size - 10)
        val invalid = asset.copy(byteCount = truncated.size.toLong(), sha256 = KnowledgeCard.sha256(truncated))
        val rejected = MarkdownCardRenderer.embedAssetsWithStats("![photo](assets/photo.jpg)", listOf(invalid)) { truncated }
        assertEquals(1, rejected.missingCount)
    }

    @Test fun managedFileReadsAreBoundedAndRequireTheExactManifestSize() {
        val file = temporary.newFile("synthetic.png").apply { writeBytes(PortableImageFixtures.png) }
        val asset = PortableImageFixtures.asset()
        assertArrayEquals(PortableImageFixtures.png, KnowledgeCardExport.readManagedAsset(asset, file))
        assertNull(KnowledgeCardExport.readManagedAsset(asset.copy(byteCount = asset.byteCount - 1L), file))
        assertNull(KnowledgeCardExport.readManagedAsset(asset, file, PortableAssetBudget(maxImageBytes = 8L)))
        assertNull(KnowledgeCardExport.readManagedAsset(asset, null))
    }

    @Test fun inlineImageKeepsAltAndTitleWithoutMutatingTheOriginalSource() {
        val source = "前文\r\n\r\n![说明 **粗体**](assets/synthetic.png \"保留标题\")\r\n后文"
        val result = MarkdownCardRenderer.embedAssetsWithStats(source, listOf(PortableImageFixtures.asset())) { PortableImageFixtures.png }
        assertTrue(result.markdown.contains("![说明 **粗体**](data:image/png;base64,"))
        assertTrue(result.markdown.contains(" \"保留标题\")\r\n后文"))
        assertTrue(result.markdown.startsWith("前文\r\n\r\n"))
        assertTrue(source.contains("assets/synthetic.png"))
        assertEquals(1, result.embeddedCount)
    }

    @Test fun repeatedAndReferenceImagesReadAndCountAnAssetOnce() {
        val source = "![first](assets/synthetic.png)\n![second][photo]\n![photo][]\n![photo]\n\n[photo]: assets/synthetic.png \"图像标题\"\n"
        var reads = 0
        val result = MarkdownCardRenderer.embedAssetsWithStats(source, listOf(PortableImageFixtures.asset())) {
            reads++; PortableImageFixtures.png
        }
        assertEquals(1, reads)
        assertEquals(1, result.requestedCount)
        assertEquals(1, result.embeddedCount)
        assertEquals(4, result.referenceCount)
        assertEquals(PortableImageFixtures.png.size.toLong(), result.embeddedBytes)
        assertEquals(4, result.markdown.split("data:image/png;base64,").size - 1)
        assertTrue(result.markdown.contains("![second](data:image/png;base64,"))
        assertTrue(result.markdown.contains(" \"图像标题\")"))
        assertFalse(result.markdown.contains("assets/synthetic.png"))
        assertFalse(result.markdown.contains("[photo]:"))
    }

    @Test fun anOrdinaryReferenceLinkRetainsItsDefinitionAndDestination() {
        val source = "![picture][same]\n[normal link][same]\n\n[same]: assets/synthetic.png 'title'\n"
        val result = MarkdownCardRenderer.embedAssetsWithStats(source, listOf(PortableImageFixtures.asset())) { PortableImageFixtures.png }
        assertTrue(result.markdown.contains("![picture](data:image/png;base64,"))
        assertTrue(result.markdown.contains("[normal link][same]"))
        assertTrue(result.markdown.contains("[same]: assets/synthetic.png 'title'"))
    }

    @Test fun imageLookingTextInsideCodeAndFrontMatterDoesNotReadAssets() {
        val source = "---\r\nimage: ![hidden](assets/synthetic.png)\r\n---\r\n\r\n`![inline](assets/synthetic.png)`\r\n\r\n```md\r\n![code](assets/synthetic.png)\r\n```\r\n"
        var reads = 0
        val result = MarkdownCardRenderer.embedAssetsWithStats(source, listOf(PortableImageFixtures.asset())) { reads++; PortableImageFixtures.png }
        assertEquals(0, reads)
        assertEquals(0, result.requestedCount)
        assertEquals(source, result.markdown)
    }

    @Test fun missingInlineAndReferenceImagesKeepAltTextInThePortableVariant() {
        val source = "![第一张](assets/missing.png 'inline')\n![第二张][missing]\n\n[missing]: assets/missing.png \"title\"\n"
        val missing = PortableImageFixtures.asset("assets/missing.png")
        val result = MarkdownCardRenderer.embedAssetsWithStats(source, listOf(missing)) { null }
        assertEquals(1, result.missingCount)
        assertEquals(2, result.referenceCount)
        assertEquals(source, result.markdown)
        assertTrue(result.markdownWithoutMissingAssets.contains("第一张"))
        assertTrue(result.markdownWithoutMissingAssets.contains("第二张"))
        assertFalse(result.markdownWithoutMissingAssets.contains("assets/missing.png"))
        assertFalse(result.markdownWithoutMissingAssets.contains("[missing]:"))
    }

    @Test fun onlyAManifestOwnedManagedImageCanBeRead() {
        val source = "![unknown](assets/not-owned.png)\n![outside](../private.png)\n![encoded](assets/%2e%2e/private.png)"
        val assets = listOf(PortableImageFixtures.asset("../private.png"), PortableImageFixtures.asset("assets/../private.png"))
        var reads = 0
        val result = MarkdownCardRenderer.embedAssetsWithStats(source, assets) { reads++; PortableImageFixtures.png }
        assertEquals(0, reads)
        assertEquals(3, result.requestedCount)
        assertEquals(3, result.missingCount)
        assertFalse(result.markdownWithoutMissingAssets.contains("!"))
    }

    @Test fun mimeHashLengthAndRealPngStructureMustAgree() {
        val bytes = PortableImageFixtures.png
        val base = PortableImageFixtures.asset()
        val corrupt = bytes.copyOf().apply { this[40] = (this[40].toInt() xor 1).toByte() }
        val cases = listOf(
            base.copy(mimeType = "image/jpeg") to bytes,
            base.copy(sha256 = "0".repeat(64)) to bytes,
            base.copy(byteCount = bytes.size + 1L) to bytes,
            base.copy(sha256 = KnowledgeCard.sha256(corrupt)) to corrupt,
        )
        cases.forEach { (asset, payload) ->
            val result = MarkdownCardRenderer.embedAssetsWithStats("![alt](assets/synthetic.png)", listOf(asset)) { payload }
            assertEquals(0, result.embeddedCount)
            assertEquals(1, result.missingCount)
            assertEquals("alt", result.markdownWithoutMissingAssets)
        }
    }

    @Test fun totalManifestBudgetFailsBeforeReadingOrBase64Allocation() {
        val first = PortableImageFixtures.asset("assets/first.png")
        val second = PortableImageFixtures.asset("assets/second.png")
        var reads = 0
        assertThrows(PortableAssetBudgetExceededException::class.java) {
            MarkdownCardRenderer.embedAssetsWithStats(
                "![](assets/first.png)\n![](assets/second.png)", listOf(first, second),
                PortableAssetBudget(maxImageBytes = 1_024L, maxTotalBytes = PortableImageFixtures.png.size.toLong()),
            ) { reads++; PortableImageFixtures.png }
        }
        assertEquals(0, reads)
    }

    @Test fun actualByteBudgetIsCheckedEvenWhenManifestUnderreportsItsLength() {
        val asset = PortableImageFixtures.asset().copy(byteCount = 1L)
        assertThrows(PortableAssetBudgetExceededException::class.java) {
            MarkdownCardRenderer.embedAssetsWithStats("![](assets/synthetic.png)", listOf(asset),
                PortableAssetBudget(maxImageBytes = 8L, maxTotalBytes = 8L)) { PortableImageFixtures.png }
        }
    }

    @Test fun pixelBudgetIsCheckedWithoutDecodingTheImage() {
        val result = MarkdownCardRenderer.embedAssetsWithStats("![](assets/synthetic.png)", listOf(PortableImageFixtures.asset()),
            PortableAssetBudget(maxImagePixels = 1L)) { PortableImageFixtures.png }
        assertEquals(1, result.embeddedCount)
        val bytes = PortableImageFixtures.png.copyOf()
        bytes[19] = 2 // width = 2, so the declared dimensions exceed a one-pixel budget.
        val crc = java.util.zip.CRC32().apply { update(bytes, 12, 17) }.value
        for (offset in 0..3) bytes[29 + offset] = (crc ushr (24 - 8 * offset)).toByte()
        val tooLarge = PortableImageFixtures.asset(bytes = bytes)
        val rejected = MarkdownCardRenderer.embedAssetsWithStats("![](assets/synthetic.png)", listOf(tooLarge),
            PortableAssetBudget(maxImagePixels = 1L)) { bytes }
        assertEquals(1, rejected.missingCount)
    }

    @Test fun encodedAndEscapedDestinationsResolveToTheManifestPath() {
        val assets = listOf(PortableImageFixtures.asset("assets/a x.png"), PortableImageFixtures.asset("assets/one(two).png"))
        val source = "![space](<assets/a%20x.png>)\n![paren](assets/one\\(two\\).png \"title\")"
        val result = MarkdownCardRenderer.embedAssetsWithStats(source, assets) { PortableImageFixtures.png }
        assertEquals(2, result.embeddedCount)
        assertEquals(0, result.missingCount)
        assertTrue(result.markdown.contains(" \"title\")"))
    }

    @Test fun ordinaryWebImagesArePreservedWithoutFetchingThem() {
        val source = "![web](https://example.com/image.png)\n![inline](data:image/png;base64,original)"
        val result = MarkdownCardRenderer.embedAssetsWithStats(source, emptyList()) { error("must not fetch") }
        assertEquals(source, result.markdown)
        assertEquals(0, result.requestedCount)
    }
}
