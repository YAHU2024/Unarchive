package com.unarchive.android.platform.bilibili

import com.unarchive.android.platform.AudioStream
import com.unarchive.android.platform.PlatformVideoId
import com.unarchive.android.platform.VideoMetadata
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

fun interface TextTransport {
    suspend fun get(url: String, headers: Map<String, String>): String
}

class BilibiliApi(private val transport: TextTransport) {
    suspend fun fetchMetadata(id: PlatformVideoId): VideoMetadata {
        require(id.platform == PLATFORM) { "Unsupported platform: ${id.platform}" }
        val identityQuery = when {
            id.value.startsWith("BV") -> "bvid=${encode(id.value)}"
            id.value.startsWith("av") -> "aid=${encode(id.value.substring(2))}"
            else -> throw IllegalArgumentException("Unsupported Bilibili video ID")
        }
        val root = responseRoot(
            transport.get("$API_BASE/x/web-interface/view?$identityQuery", DEFAULT_HEADERS),
            "metadata",
        )
        val data = root.requiredObject("data", "Bilibili metadata is missing")
        val bvid = data.requiredString("bvid", "Bilibili metadata has no BV ID")
        require(BVID_PATTERN.matches(bvid)) { "Bilibili metadata returned an invalid BV ID" }
        val cid = data.requiredLong("cid", "Bilibili metadata has no cid")
        require(cid > 0) { "Bilibili metadata returned an invalid cid" }
        val duration = data.requiredLong("duration", "Bilibili metadata has no duration")
        require(duration >= 0) { "Bilibili metadata returned an invalid duration" }

        return VideoMetadata(
            id = PlatformVideoId(PLATFORM, bvid),
            canonicalUrl = "https://www.bilibili.com/video/$bvid",
            title = data.requiredString("title", "Bilibili metadata has no title"),
            ownerName = data.optJSONObject("owner")?.optString("name").orEmpty(),
            durationSeconds = duration,
            cid = cid,
        )
    }

    suspend fun resolveAudio(metadata: VideoMetadata): AudioStream {
        require(metadata.id.platform == PLATFORM && BVID_PATTERN.matches(metadata.id.value)) {
            "Bilibili audio resolution requires a canonical BV ID"
        }
        require(metadata.cid > 0) { "Bilibili audio resolution requires a valid cid" }
        val query = "bvid=${encode(metadata.id.value)}&cid=${metadata.cid}&fnval=16&fnver=0&fourk=1"
        val root = responseRoot(
            transport.get("$API_BASE/x/player/playurl?$query", PLAYURL_HEADERS),
            "audio stream",
        )
        val audio = root.optJSONObject("data")
            ?.optJSONObject("dash")
            ?.optJSONArray("audio")
            ?: throw IllegalArgumentException("Bilibili response has no DASH audio")
        require(audio.length() > 0) { "Bilibili response has no DASH audio" }

        val candidates = (0 until audio.length()).mapNotNull { index ->
            audio.optJSONObject(index)?.let { item ->
                val url = item.optString("baseUrl").ifBlank { item.optString("base_url") }
                if (url.isBlank()) null else AudioStream(
                    url = checkedMediaUrl(url),
                    backupUrls = (item.optJSONArray("backupUrl") ?: item.optJSONArray("backup_url"))
                        ?.let { values ->
                            (0 until values.length()).mapNotNull { position ->
                                values.optString(position).takeIf(String::isNotBlank)?.let(::checkedMediaUrl)
                            }
                        }
                        .orEmpty(),
                    bandwidth = item.optLong("bandwidth", 0),
                    mimeType = item.optString("mimeType").takeIf(String::isNotBlank),
                    codecs = item.optString("codecs").takeIf(String::isNotBlank),
                )
            }
        }
        return candidates.maxByOrNull(AudioStream::bandwidth)
            ?: throw IllegalArgumentException("Bilibili response has no usable DASH audio")
    }

    private fun responseRoot(body: String, purpose: String): JSONObject {
        val root = runCatching { JSONObject(body) }
            .getOrElse { throw IllegalArgumentException("Bilibili $purpose response is invalid") }
        val code = root.optInt("code", Int.MIN_VALUE)
        require(code == 0) {
            val message = root.optString("message").takeIf(String::isNotBlank) ?: "unknown error"
            "Bilibili $purpose failed ($code): $message"
        }
        return root
    }

    private fun checkedMediaUrl(url: String): String {
        require(url.startsWith("https://", ignoreCase = true)) {
            "Bilibili audio URL must use HTTPS"
        }
        return url
    }

    private fun JSONObject.requiredObject(name: String, message: String): JSONObject =
        optJSONObject(name) ?: throw IllegalArgumentException(message)

    private fun JSONObject.requiredString(name: String, message: String): String =
        optString(name).takeIf(String::isNotBlank) ?: throw IllegalArgumentException(message)

    private fun JSONObject.requiredLong(name: String, message: String): Long =
        if (has(name) && !isNull(name)) optLong(name, -1) else throw IllegalArgumentException(message)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        const val PLATFORM = "bilibili"
        const val API_BASE = "https://api.bilibili.com"
        val BVID_PATTERN = Regex("BV[0-9A-Za-z]{10}")
        val DEFAULT_HEADERS = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) Unarchive/0.1")
        val PLAYURL_HEADERS = DEFAULT_HEADERS +
            ("Referer" to "https://www.bilibili.com/")
    }
}
