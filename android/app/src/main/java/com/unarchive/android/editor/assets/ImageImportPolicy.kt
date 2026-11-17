package com.unarchive.android.editor.assets

import java.io.InputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/** Shared import limits; defaults also fit the formal Markdown renderer/export. */
data class ImageImportPolicy(
    val maxInputBytes: Long = 16L * 1024L * 1024L,
    val maxOutputBytes: Long = 16L * 1024L * 1024L,
    val maxSourcePixels: Long = 24_000_000L,
    val maxDecodedPixels: Long = 4_000_000L,
    val maxLongestEdge: Int = 2560,
    val jpegQuality: Int = 85,
    val freeSpaceReserveBytes: Long = 1024L * 1024L,
) {
    init {
        require(maxInputBytes in 1..Int.MAX_VALUE.toLong() && maxOutputBytes in 1..Int.MAX_VALUE.toLong())
        require(maxSourcePixels > 0L && maxDecodedPixels > 0L && maxLongestEdge > 0)
        require(jpegQuality in 1..100 && freeSpaceReserveBytes >= 0L)
    }

    fun validateBounds(width: Int, height: Int) {
        if (width <= 0 || height <= 0) throw ImageImportException(ImageImportFailureReason.Corrupt)
        if (width.toLong() * height > maxSourcePixels) throw ImageImportException(ImageImportFailureReason.TooManyPixels)
    }

    fun sampleSize(width: Int, height: Int): Int {
        validateBounds(width, height)
        var sample = 1
        while (max(1L, (width.toLong() + sample - 1L) / sample) * max(1L, (height.toLong() + sample - 1L) / sample) > maxDecodedPixels) sample *= 2
        return sample
    }

    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) throw ImageImportException(ImageImportFailureReason.Corrupt)
        val scale = min(1.0, maxLongestEdge.toDouble() / max(width, height))
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }

    fun ensureFreeSpace(availableBytes: Long) {
        val required = maxInputBytes + maxOutputBytes + freeSpaceReserveBytes
        if (availableBytes < required) throw ImageImportException(ImageImportFailureReason.LowSpace)
    }

    /** Stops after at most limit+1 input bytes; never first reads an arbitrary URI into memory. */
    fun copyBounded(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(8 * 1024)
        var written = 0L
        while (true) {
            val count = input.read(buffer, 0, min(buffer.size.toLong(), maxInputBytes - written + 1L).toInt())
            if (count == -1) break
            if (count == 0) {
                val single = input.read()
                if (single == -1) break
                if (written == maxInputBytes) throw ImageImportException(ImageImportFailureReason.TooLarge)
                output.write(single); written++
                continue
            }
            if (count > maxInputBytes - written) throw ImageImportException(ImageImportFailureReason.TooLarge)
            output.write(buffer, 0, count)
            written += count
        }
        if (written == 0L) throw ImageImportException(ImageImportFailureReason.Corrupt)
        return written
    }
}

enum class ImageImportFailureReason(val message: String) {
    Unsupported("请选择 JPEG 或 PNG 图片"),
    TooLarge("图片超过大小上限，请换一张"),
    TooManyPixels("图片尺寸过大，请换一张"),
    LowSpace("本地空间不足，请释放空间后重试"),
    Corrupt("图片无法读取，请换一张"),
    Unavailable("原图片已不可用，请重新选择"),
    ReadFailed("图片读取失败，请重试"),
    WriteFailed("图片保存失败，正文未改变，请重试"),
}

class ImageImportException(val reason: ImageImportFailureReason) : Exception(reason.message)
