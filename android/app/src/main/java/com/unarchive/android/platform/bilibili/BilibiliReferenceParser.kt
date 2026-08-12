package com.unarchive.android.platform.bilibili

import com.unarchive.android.platform.PlatformVideoId
import com.unarchive.android.platform.VideoReference
import java.net.URI

object BilibiliReferenceParser {
    fun parse(input: String): VideoReference {
        val trimmed = input.trim()
        parseVideoId(trimmed)?.let { return canonical(it) }

        val candidate = URL_PATTERN.find(trimmed)?.value?.trimEnd('.', ',', '。', '，', ')', '）')
            ?: throw IllegalArgumentException("Enter a Bilibili BV/av ID or video link")
        val uri = runCatching { URI(candidate) }
            .getOrElse { throw IllegalArgumentException("Bilibili link is invalid") }
        val host = uri.host?.lowercase()
            ?: throw IllegalArgumentException("Bilibili link has no host")
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Bilibili links must use HTTPS"
        }

        if (host == SHORT_HOST) {
            return VideoReference.Redirect("https://$SHORT_HOST${uri.rawPath.orEmpty()}")
        }
        require(host == ROOT_HOST || host.endsWith(".$ROOT_HOST")) {
            "Only bilibili.com and b23.tv links are supported"
        }
        val pathId = VIDEO_PATH_PATTERN.find(uri.path.orEmpty())?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Bilibili link is not a video page")
        return canonical(
            parseVideoId(pathId)
                ?: throw IllegalArgumentException("Bilibili video ID is invalid"),
        )
    }

    private fun parseVideoId(value: String): String? = when {
        BV_PATTERN.matches(value) -> "BV" + value.substring(2)
        AV_PATTERN.matches(value) -> "av" + value.substring(2)
        else -> null
    }

    private fun canonical(value: String) = VideoReference.Canonical(
        id = PlatformVideoId(PLATFORM, value),
        url = "https://$CANONICAL_HOST/video/$value",
    )

    private const val PLATFORM = "bilibili"
    private const val ROOT_HOST = "bilibili.com"
    private const val CANONICAL_HOST = "www.bilibili.com"
    private const val SHORT_HOST = "b23.tv"
    private val BV_PATTERN = Regex("BV[0-9A-Za-z]{10}", RegexOption.IGNORE_CASE)
    private val AV_PATTERN = Regex("av[0-9]+", RegexOption.IGNORE_CASE)
    private val VIDEO_PATH_PATTERN = Regex("^/video/(BV[0-9A-Za-z]{10}|av[0-9]+)(?:/|$)", RegexOption.IGNORE_CASE)
    private val URL_PATTERN = Regex("https://[^\\s]+", RegexOption.IGNORE_CASE)
}
