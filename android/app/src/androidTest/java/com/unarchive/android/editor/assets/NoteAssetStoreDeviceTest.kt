package com.unarchive.android.editor.assets

import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unarchive.android.asr.TranscriptTimingAccuracy
import com.unarchive.android.card.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteAssetStoreDeviceTest {
    @Test fun jpegOrientationIsAppliedAndSensitiveExifIsNotCopiedToTheManagedImage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "asset-codec-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = File(root, "camera.jpg")
            val image = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
            image.eraseColor(android.graphics.Color.BLUE)
            input.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            image.recycle()
            ExifInterface(input.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "31/1,0/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_MAKE, "synthetic-private-camera")
                saveAttributes()
            }
            val repository = FileKnowledgeCardRepository(File(root, "cards"))
            val card = repository.save(card())
            val store = NoteAssetStore(repository, card, root)
            val asset = store.importImage { input.inputStream() }
            val managed = repository.assetFile(card, asset)!!
            val info = AndroidNoteImageCodec.inspect(managed)
            assertEquals(4, info.width)
            assertEquals(8, info.height)
            val exif = ExifInterface(managed.path)
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
            // Freshly encoded upright pixels may omit orientation or use the neutral undefined value.
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            assertTrue(orientation == ExifInterface.ORIENTATION_UNDEFINED || orientation == ExifInterface.ORIENTATION_NORMAL)
            val exported = KnowledgeCardExport.portable(card.copy(markdown = "![camera](${asset.relativePath})", assets = listOf(asset))) {
                KnowledgeCardExport.readManagedAsset(it, repository.assetFile(card, it))
            }
            assertTrue(exported.isFullyPortable)
            assertEquals(1, exported.assets.embeddedCount)
        } finally { root.deleteRecursively() }
    }

    @Test fun transparentPngRemainsPngAndItsPixelsAreReadable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "asset-png-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = File(root, "transparent.png")
            val image = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
            image.eraseColor(android.graphics.Color.TRANSPARENT)
            input.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            image.recycle()
            val repository = FileKnowledgeCardRepository(File(root, "cards"))
            val card = repository.save(card())
            val asset = NoteAssetStore(repository, card, root).importImage { input.inputStream() }
            assertEquals("image/png", asset.mimeType)
            val file = repository.assetFile(card, asset)!!
            val decoded = android.graphics.BitmapFactory.decodeFile(file.path)
            assertTrue(decoded.hasAlpha())
            assertEquals(0, android.graphics.Color.alpha(decoded.getPixel(0, 0)))
            decoded.recycle()
        } finally { root.deleteRecursively() }
    }

    private fun card() = KnowledgeCard(KnowledgeCardId("bilibili", "BV_ASSET_DEVICE"), "v1",
        "https://www.bilibili.com/video/BV_ASSET_DEVICE", "synthetic", "owner", 1L, TranscriptTimingAccuracy.EXACT,
        emptyList(), analysisState = CardStageState.SKIPPED, screenshotsState = CardStageState.SKIPPED,
        createdAtEpochMs = 1L, updatedAtEpochMs = 1L, markdown = "# synthetic")
}
