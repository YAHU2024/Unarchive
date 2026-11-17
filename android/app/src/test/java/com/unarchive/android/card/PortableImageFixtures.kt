package com.unarchive.android.card

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** A generated 1x1 RGBA PNG, not a header-only or arbitrary-byte image stub. */
internal object PortableImageFixtures {
    val jpeg: ByteArray = ByteArrayOutputStream().also { output ->
        check(javax.imageio.ImageIO.write(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", output))
    }.toByteArray()

    val png: ByteArray = run {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, bytes: ByteArray) {
            val stream = DataOutputStream(output)
            val tag = type.toByteArray(Charsets.US_ASCII)
            stream.writeInt(bytes.size); stream.write(tag); stream.write(bytes)
            stream.writeInt(CRC32().apply { update(tag); update(bytes) }.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply { writeInt(1); writeInt(1); write(byteArrayOf(8, 6, 0, 0, 0)) }
        chunk("IHDR", header.toByteArray())
        val data = ByteArrayOutputStream()
        DeflaterOutputStream(data).use { it.write(byteArrayOf(0, 0, 0, 0, 0)) }
        chunk("IDAT", data.toByteArray()); chunk("IEND", byteArrayOf())
        output.toByteArray()
    }

    fun asset(path: String = "assets/synthetic.png", id: String = path, bytes: ByteArray = png) = CardAsset(
        id, CardAssetKind.CHAPTER_SCREENSHOT, "image/png", path, bytes.size.toLong(), KnowledgeCard.sha256(bytes),
    )
}
