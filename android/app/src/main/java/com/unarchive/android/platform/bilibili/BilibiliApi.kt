package com.unarchive.android.platform.bilibili

import com.unarchive.android.log.AppLogger
import com.unarchive.android.platform.AudioStream
import com.unarchive.android.platform.PlatformVideoId
import com.unarchive.android.platform.SubtitleSegment
import com.unarchive.android.platform.VideoMetadata
import com.unarchive.android.platform.VideoStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

fun interface TextTransport {
    suspend fun get(url: String, headers: Map<String, String>): String
}

class BilibiliApiException(
    val code: Int,
    val apiMessage: String,
    val purpose: String,
) : IllegalArgumentException("Bilibili $purpose failed ($code): $apiMessage")

val BilibiliApiException.isTerminalUnavailable: Boolean
    get() = code == -404 || code in 62001..62004

class BilibiliApi(
    private val transport: TextTransport,
    private val subtitleTransport: TextTransport = HttpsSubtitleTransport(),
    private val cookieHeader: () -> String = { "" },
) {
    /** Adds the login `Cookie` header when a session is present. */
    private fun headers(base: Map<String, String>): Map<String, String> {
        val cookie = cookieHeader()
        return if (cookie.isBlank()) base else base + ("Cookie" to cookie)
    }
    suspend fun fetchMetadata(id: PlatformVideoId): VideoMetadata {
        require(id.platform == PLATFORM) { "Unsupported platform: ${id.platform}" }
        val identityQuery = when {
            id.value.startsWith("BV") -> "bvid=${encode(id.value)}"
            id.value.startsWith("av") -> "aid=${encode(id.value.substring(2))}"
            else -> throw IllegalArgumentException("Unsupported Bilibili video ID")
        }
        val root = responseRoot(
            transport.get("$API_BASE/x/web-interface/view?$identityQuery", headers(DEFAULT_HEADERS)),
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

    suspend fun fetchFavoriteFolders(): List<BilibiliFavoriteFolder> {
        val nav = responseRoot(
            transport.get("$API_BASE/x/web-interface/nav", headers(DEFAULT_HEADERS)),
            "current user",
        ).optJSONObject("data")
        val mid = nav?.optLong("mid", 0L) ?: 0L
        require(mid > 0) { "Bilibili login is required to access favorites" }
        val root = responseRoot(
            transport.get(
                "$API_BASE/x/v3/fav/folder/created/list-all?up_mid=$mid",
                headers(DEFAULT_HEADERS),
            ),
            "favorite folders",
        )
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optLong("id", 0L)
            if (id <= 0) return@mapNotNull null
            BilibiliFavoriteFolder(
                id = id.toString(),
                title = item.optString("title").ifBlank { "未命名收藏夹" },
                videoCount = item.optInt("media_count", 0).coerceAtLeast(0),
            )
        }
    }

    suspend fun fetchFavoriteVideos(folderId: String): List<BilibiliFavoriteVideo> {
        require(folderId.toLongOrNull()?.let { it > 0 } == true) {
            "Bilibili favorite folder ID is invalid"
        }
        val videos = mutableListOf<BilibiliFavoriteVideo>()
        val seen = mutableSetOf<String>()
        var page = 1
        while (true) {
            require(page <= MAX_FAVORITE_PAGES) { "Bilibili favorites pagination did not finish" }
            val root = responseRoot(
                transport.get(
                    "$API_BASE/x/v3/fav/resource/list?media_id=$folderId&pn=$page&ps=$FAVORITE_PAGE_SIZE",
                    headers(DEFAULT_HEADERS),
                ),
                "favorite videos",
            )
            val data = root.optJSONObject("data") ?: break
            val medias = data.optJSONArray("medias") ?: break
            if (medias.length() == 0) break
            for (index in 0 until medias.length()) {
                val item = medias.optJSONObject(index) ?: continue
                val aid = item.optLong("id", 0L)
                val bvid = item.optString("bvid").takeIf { BVID_PATTERN.matches(it) }
                val title = item.optString("title").ifBlank { "未命名视频" }
                val key = bvid ?: if (aid > 0) "av$aid" else "unavailable:$folderId:$page:$index"
                if (!seen.add(key)) continue
                val unavailableReason = when {
                    bvid == null && title.contains("失效") -> "视频已失效，B站不再提供播放内容"
                    bvid == null -> "视频标识缺失，可能已删除或不可见"
                    else -> null
                }
                val owner = item.optJSONObject("upper")?.optString("name").orEmpty()
                videos += BilibiliFavoriteVideo(
                    folderId = folderId,
                    title = title,
                    videoId = bvid?.let { PlatformVideoId("bilibili", it) },
                    durationSeconds = item.optLong("duration", 0L).takeIf { it > 0 },
                    author = owner,
                    unavailableReason = unavailableReason,
                )
            }
            if (!data.optBoolean("has_more", false)) break
            page++
        }
        return videos
    }

    /**
     * Fetches the video's CC/AI subtitles, or returns null when there are none
     * or they cannot be fetched.
     *
     * Two sources, in order: the view endpoint's `data.subtitle.list` (human
     * CC subtitles, no login needed), then `/x/player/wbi/v2`'s
     * `data.subtitle.subtitles` (AI subtitles, requires a login session). The
     * chosen track's JSON is downloaded and its `body[].{from,to,content}`
     * cues (seconds) are mapped to millisecond segments.
     *
     * All failures are swallowed and reported as null so callers fall back to
     * local ASR instead of surfacing a hard error.
     */
    suspend fun fetchSubtitles(metadata: VideoMetadata): List<SubtitleSegment>? {
        require(metadata.id.platform == PLATFORM && BVID_PATTERN.matches(metadata.id.value)) {
            "Bilibili subtitle fetch requires a canonical BV ID"
        }
        return try {
            fetchSubtitlesOrNull(metadata)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchSubtitlesOrNull(metadata: VideoMetadata): List<SubtitleSegment>? {
        val viewUrl = "$API_BASE/x/web-interface/view?bvid=${encode(metadata.id.value)}"
        val root = responseRoot(transport.get(viewUrl, headers(DEFAULT_HEADERS)), "subtitle")
        val data = root.optJSONObject("data") ?: return null

        // Source 1: human-uploaded CC subtitles (no login needed).
        // The view endpoint only returns subtitle metadata for AI tracks
        // (real `subtitle_url` is empty); drop those and fall through to
        // wbi/v2, which is the authoritative source for download URLs.
        var tracks = data.optJSONObject("subtitle")?.optJSONArray("list")
        if (tracks != null && tracks.length() > 0) {
            val withUrl = JSONArray()
            for (i in 0 until tracks.length()) {
                val track = tracks.optJSONObject(i) ?: continue
                if (track.optString("subtitle_url").isNotBlank()) withUrl.put(track)
            }
            tracks = if (withUrl.length() > 0) withUrl else null
        }
        // Source 2: AI subtitles from wbi/v2 (requires a login session).
        if (tracks == null || tracks.length() == 0) {
            val aid = data.optLong("aid", -1)
            if (aid > 0) tracks = fetchWbiSubtitleTracks(aid, metadata.cid)
            AppLogger.info("BilibiliApi", "wbi/v2 后的字幕轨数：${tracks?.length() ?: 0}")
        }
        if (tracks == null || tracks.length() == 0) return null

        val subtitleUrl = selectSubtitleUrl(tracks) ?: return null
        val normalized = if (subtitleUrl.startsWith("//")) "https:$subtitleUrl" else subtitleUrl

        val body = subtitleTransport.get(normalized, headers(BilibiliHeaders.api))
        val items = runCatching { JSONObject(body).optJSONArray("body") }.getOrNull() ?: return null
        if (items.length() == 0) return null

        val segments = mutableListOf<SubtitleSegment>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val text = item.optString("content", "").trim()
            if (text.isEmpty()) continue
            val startMs = (item.optDouble("from", 0.0) * 1_000).toLong().coerceAtLeast(0)
            val endMs = (item.optDouble("to", 0.0) * 1_000).toLong()
            if (endMs <= startMs) continue
            segments += SubtitleSegment(startMs, endMs, text)
        }
        return segments.takeIf { it.isNotEmpty() }
    }

    /** Queries `/x/player/wbi/v2` (WBI-signed) for AI subtitle tracks. */
    private suspend fun fetchWbiSubtitleTracks(aid: Long, cid: Long): JSONArray? {
        val keys = fetchWbiKeys() ?: return null
        val mixinKey = WbiSigner.mixinKey(keys.first, keys.second)
        val query = WbiSigner.sign(
            params = mapOf("aid" to aid.toString(), "cid" to cid.toString()),
            mixinKey = mixinKey,
            wts = System.currentTimeMillis() / 1_000,
        )
        val root = responseRoot(
            transport.get("$API_BASE/x/player/wbi/v2?$query", headers(DEFAULT_HEADERS)),
            "subtitle",
        )
        return root.optJSONObject("data")
            ?.optJSONObject("subtitle")
            ?.optJSONArray("subtitles")
    }

    /** Fetches the WBI signing keys from `/x/web-interface/nav`. */
    private suspend fun fetchWbiKeys(): Pair<String, String>? {
        val root = responseRoot(
            transport.get("$API_BASE/x/web-interface/nav", headers(DEFAULT_HEADERS)),
            "wbi keys",
        )
        val nav = root.optJSONObject("data")
        AppLogger.info("BilibiliApi", "登录状态=${nav?.optBoolean("isLogin")}，用户名=${nav?.optString("uname")}")
        val wbiImg = nav?.optJSONObject("wbi_img") ?: return null
        val imgUrl = wbiImg.optString("img_url")
        val subUrl = wbiImg.optString("sub_url")
        if (imgUrl.isBlank() || subUrl.isBlank()) return null
        val imgKey = imgUrl.substringAfterLast('/').substringBefore('.')
        val subKey = subUrl.substringAfterLast('/').substringBefore('.')
        return imgKey to subKey
    }

    private fun selectSubtitleUrl(tracks: JSONArray): String? {
        var first: String? = null
        for (index in 0 until tracks.length()) {
            val track = tracks.optJSONObject(index) ?: continue
            val url = track.optString("subtitle_url", "").takeIf(String::isNotBlank) ?: continue
            val lan = track.optString("lan", "")
            if (first == null) first = url
            if (lan.startsWith("zh") || lan.startsWith("ai-zh")) return url
        }
        return first
    }

    suspend fun resolveAudio(metadata: VideoMetadata): AudioStream {
        require(metadata.id.platform == PLATFORM && BVID_PATTERN.matches(metadata.id.value)) {
            "Bilibili audio resolution requires a canonical BV ID"
        }
        require(metadata.cid > 0) { "Bilibili audio resolution requires a valid cid" }
        val query = "bvid=${encode(metadata.id.value)}&cid=${metadata.cid}&fnval=16&fnver=0&fourk=1"
        val root = responseRoot(
            transport.get("$API_BASE/x/player/playurl?$query", headers(PLAYURL_HEADERS)),
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

    suspend fun resolveVideo(metadata: VideoMetadata): VideoStream {
        require(metadata.id.platform == PLATFORM && BVID_PATTERN.matches(metadata.id.value)) {
            "Bilibili video resolution requires a canonical BV ID"
        }
        require(metadata.cid > 0) { "Bilibili video resolution requires a valid cid" }
        val query = "bvid=${encode(metadata.id.value)}&cid=${metadata.cid}&fnval=16&fnver=0&fourk=1"
        val root = responseRoot(
            transport.get("$API_BASE/x/player/playurl?$query", headers(PLAYURL_HEADERS)),
            "video stream",
        )
        val video = root.optJSONObject("data")
            ?.optJSONObject("dash")
            ?.optJSONArray("video")
            ?: throw IllegalArgumentException("Bilibili response has no DASH video")
        require(video.length() > 0) { "Bilibili response has no DASH video" }

        val candidates = (0 until video.length()).mapNotNull { index ->
            video.optJSONObject(index)?.let { item ->
                val url = item.optString("baseUrl").ifBlank { item.optString("base_url") }
                if (url.isBlank()) null else VideoStream(
                    url = checkedMediaUrl(url),
                    backupUrls = (item.optJSONArray("backupUrl") ?: item.optJSONArray("backup_url"))
                        ?.let { values ->
                            (0 until values.length()).mapNotNull { position ->
                                values.optString(position).takeIf(String::isNotBlank)?.let(::checkedMediaUrl)
                            }
                        }
                        .orEmpty(),
                    bandwidth = item.optLong("bandwidth", 0),
                    width = item.optInt("width", 0),
                    height = item.optInt("height", 0),
                    mimeType = item.optString("mimeType").takeIf(String::isNotBlank),
                    codecs = item.optString("codecs").takeIf(String::isNotBlank),
                )
            }
        }
        return candidates.minByOrNull(VideoStream::bandwidth)
            ?: throw IllegalArgumentException("Bilibili response has no usable DASH video")
    }

    private fun responseRoot(body: String, purpose: String): JSONObject {
        val root = runCatching { JSONObject(body) }
            .getOrElse { throw IllegalArgumentException("Bilibili $purpose response is invalid") }
        val code = root.optInt("code", Int.MIN_VALUE)
        if (code != 0) {
            val message = root.optString("message").takeIf(String::isNotBlank) ?: "unknown error"
            throw BilibiliApiException(code, message, purpose)
        }
        return root
    }

    private fun checkedMediaUrl(url: String): String {
        require(url.startsWith("https://", ignoreCase = true)) {
            "Bilibili media URL must use HTTPS"
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
        val DEFAULT_HEADERS = BilibiliHeaders.api
        val PLAYURL_HEADERS = BilibiliHeaders.media
        const val FAVORITE_PAGE_SIZE = 20
        const val MAX_FAVORITE_PAGES = 1_000
    }
}
