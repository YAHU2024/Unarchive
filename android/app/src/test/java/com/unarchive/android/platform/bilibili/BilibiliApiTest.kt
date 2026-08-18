package com.unarchive.android.platform.bilibili

import com.unarchive.android.platform.PlatformVideoId
import com.unarchive.android.platform.SubtitleSegment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BilibiliApiTest {
    @Test
    fun fetchesFavoriteFoldersForLoggedInUser() = runTest {
        val requested = mutableListOf<String>()
        val api = BilibiliApi(TextTransport { url, headers ->
            requested += "$url|${headers["Cookie"]}"
            when {
                url.contains("/x/web-interface/nav") ->
                    """{"code":0,"data":{"mid":42}}"""
                url.contains("/x/v3/fav/folder/created/list-all") ->
                    """{"code":0,"data":{"list":[{"id":7,"title":"学习","media_count":3}]}}"""
                else -> error("unexpected url: $url")
            }
        }, cookieHeader = { "SESSDATA=session" })

        val folders = api.fetchFavoriteFolders()

        assertEquals(listOf(BilibiliFavoriteFolder("7", "学习", 3)), folders)
        assertTrue(requested.all { it.endsWith("|SESSDATA=session") })
        assertTrue(requested.last().contains("up_mid=42"))
    }

    @Test
    fun fetchesFavoriteVideosAcrossPagesAndDeduplicatesByVideoId() = runTest {
        val api = BilibiliApi(TextTransport { url, _ ->
            when {
                url.contains("pn=1") ->
                    """{"code":0,"data":{"has_more":true,"medias":[
                        {"id":1,"bvid":"BV1PS42197aM","title":"第一条","duration":12,"upper":{"name":"UP"}}
                    ]}}"""
                url.contains("pn=2") ->
                    """{"code":0,"data":{"has_more":false,"medias":[
                        {"id":1,"bvid":"BV1PS42197aM","title":"重复","duration":12},
                        {"id":2,"bvid":"BV1xx42197aM","title":"第二条","duration":0}
                    ]}}"""
                else -> error("unexpected url: $url")
            }
        })

        val videos = api.fetchFavoriteVideos("7")

        assertEquals(2, videos.size)
        assertEquals("BV1PS42197aM", videos[0].videoId?.value)
        assertEquals("BV1xx42197aM", videos[1].videoId?.value)
        assertEquals(null, videos[1].durationSeconds)
    }

    @Test
    fun keepsUnavailableFavoriteVideosWithAReason() = runTest {
        val api = BilibiliApi(TextTransport { _, _ ->
            """{"code":0,"data":{"has_more":false,"medias":[
                {"id":9,"title":"已失效视频"}
            ]}}"""
        })

        val video = api.fetchFavoriteVideos("7").single()

        assertEquals(false, video.isAvailable)
        assertTrue(video.unavailableReason!!.contains("失效"))
    }

    @Test
    fun rejectsInvalidFavoriteFolderId() {
        val api = BilibiliApi(TextTransport { _, _ -> error("must not request") })

        assertThrows(IllegalArgumentException::class.java) {
            runTest { api.fetchFavoriteVideos("bad") }
        }
    }

    @Test
    fun fetchesMetadataAndCanonicalizesAvid() = runTest {
        var requestedUrl = ""
        val api = BilibiliApi(TextTransport { url, _ ->
            requestedUrl = url
            """{"code":0,"data":{"bvid":"BV1PS42197aM","cid":123,"title":"Test","duration":42,"owner":{"name":"UP"}}}"""
        })

        val result = api.fetchMetadata(PlatformVideoId("bilibili", "av170001"))

        assertTrue(requestedUrl.endsWith("?aid=170001"))
        assertEquals("BV1PS42197aM", result.id.value)
        assertEquals("Test", result.title)
        assertEquals("UP", result.ownerName)
        assertEquals(42, result.durationSeconds)
        assertEquals(123, result.cid)
    }

    @Test
    fun choosesHighestBandwidthDashAudioAndSendsReferer() = runTest {
        var requestedUrl = ""
        var requestedHeaders = emptyMap<String, String>()
        val api = BilibiliApi(TextTransport { url, headers ->
            requestedUrl = url
            requestedHeaders = headers
            """{"code":0,"data":{"dash":{"audio":[
                {"bandwidth":64000,"baseUrl":"https://audio.example/low"},
                {"bandwidth":128000,"base_url":"https://audio.example/high","backupUrl":["https://backup.example/high"],"mimeType":"audio/mp4","codecs":"mp4a.40.2"}
            ]}}}"""
        })
        val metadata = sampleMetadata()

        val result = api.resolveAudio(metadata)

        assertTrue(requestedUrl.contains("bvid=BV1PS42197aM&cid=123&fnval=16"))
        assertEquals("https://www.bilibili.com", requestedHeaders["Referer"])
        assertEquals("https://audio.example/high", result.url)
        assertEquals(128000, result.bandwidth)
        assertEquals(listOf("https://backup.example/high"), result.backupUrls)
    }

    @Test
    fun choosesLowestBandwidthDashVideo() = runTest {
        val api = BilibiliApi(TextTransport { _, _ ->
            """{"code":0,"data":{"dash":{"video":[
                {"bandwidth":800000,"baseUrl":"https://video.example/720p","width":1280,"height":720},
                {"bandwidth":300000,"base_url":"https://video.example/360p","width":640,"height":360,"codecs":"avc1.64001f","mimeType":"video/mp4"}
            ]}}}"""
        })
        val metadata = sampleMetadata()

        val result = api.resolveVideo(metadata)

        assertEquals("https://video.example/360p", result.url)
        assertEquals(300_000, result.bandwidth)
        assertEquals(640, result.width)
        assertEquals(360, result.height)
    }

    @Test
    fun rejectsApiErrorsMissingAudioAndInsecureMediaUrls() {
        listOf(
            """{"code":-400,"message":"bad request"}""",
            """{"code":0,"data":{"dash":{"audio":[]}}}""",
            """{"code":0,"data":{"dash":{"audio":[{"bandwidth":1,"baseUrl":"http://audio.example/file"}]}}}""",
        ).forEach { body ->
            val api = BilibiliApi(TextTransport { _, _ -> body })

            assertThrows(IllegalArgumentException::class.java) {
                runTest { api.resolveAudio(sampleMetadata()) }
            }
        }
    }

    @Test
    fun fetchesChineseCcSubtitleAndMapsSecondsToMilliseconds() = runTest {
        var subtitleUrl = ""
        val api = BilibiliApi(
            TextTransport { _, _ ->
                """{"code":0,"data":{"subtitle":{"list":[
                    {"lan":"en","lan_doc":"英语","subtitle_url":"//aisubtitle.hdslb.com/en.json"},
                    {"lan":"zh-CN","lan_doc":"中文（自动生成）","subtitle_url":"//aisubtitle.hdslb.com/zh.json"}
                ]}}}"""
            },
            TextTransport { url, _ ->
                subtitleUrl = url
                """{"body":[{"from":0.0,"to":2.5,"content":"第一句"},{"from":2.5,"to":5.0,"content":"第二句"}]}"""
            },
        )

        val result = api.fetchSubtitles(sampleMetadata())

        assertEquals("https://aisubtitle.hdslb.com/zh.json", subtitleUrl)
        assertEquals(2, result?.size)
        assertEquals(SubtitleSegment(0, 2_500, "第一句"), result!![0])
        assertEquals(SubtitleSegment(2_500, 5_000, "第二句"), result[1])
    }

    @Test
    fun fetchesAiSubtitleViaWbiV2WhenViewHasNoCc() = runTest {
        var subtitleUrl = ""
        val api = BilibiliApi(
            TextTransport { url, _ ->
                when {
                    url.contains("/x/web-interface/view") ->
                        """{"code":0,"data":{"aid":515345690,"cid":825851971,"subtitle":{"list":[]}}}"""
                    url.contains("/x/web-interface/nav") ->
                        """{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}"""
                    url.contains("/x/player/wbi/v2") ->
                        """{"code":0,"data":{"subtitle":{"subtitles":[{"lan":"ai-zh","subtitle_url":"//aisubtitle.hdslb.com/ai.json"}]}}}"""
                    else -> throw IllegalArgumentException("unexpected url: $url")
                }
            },
            TextTransport { url, _ ->
                subtitleUrl = url
                """{"body":[{"from":0.0,"to":3.0,"content":"AI字幕"}]}"""
            },
        )

        val result = api.fetchSubtitles(sampleMetadata())

        assertEquals("https://aisubtitle.hdslb.com/ai.json", subtitleUrl)
        assertEquals(SubtitleSegment(0, 3_000, "AI字幕"), result?.single())
    }

    @Test
    fun returnsNullWhenNoSubtitlesAnywhere() = runTest {
        val api = BilibiliApi(
            TextTransport { url, _ ->
                when {
                    url.contains("/x/web-interface/view") ->
                        """{"code":0,"data":{"aid":1,"cid":1,"subtitle":{"list":[]}}}"""
                    url.contains("/x/web-interface/nav") ->
                        """{"code":0,"data":{"wbi_img":{"img_url":"https://x/i.png","sub_url":"https://x/s.png"}}}"""
                    url.contains("/x/player/wbi/v2") ->
                        """{"code":0,"data":{"subtitle":{"subtitles":[]}}}"""
                    else -> throw IllegalArgumentException("unexpected url: $url")
                }
            },
        )

        assertEquals(null, api.fetchSubtitles(sampleMetadata()))
    }

    @Test
    fun returnsNullWhenSubtitleDownloadFails() = runTest {
        val api = BilibiliApi(
            TextTransport { _, _ ->
                """{"code":0,"data":{"subtitle":{"list":[{"lan":"zh-CN","subtitle_url":"//aisubtitle.hdslb.com/zh.json"}]}}}"""
            },
            TextTransport { _, _ -> throw IllegalArgumentException("boom") },
        )

        assertEquals(null, api.fetchSubtitles(sampleMetadata()))
    }

    @Test
    fun clampsNegativeSubtitleStartToZero() = runTest {
        val api = BilibiliApi(
            TextTransport { _, _ ->
                """{"code":0,"data":{"subtitle":{"list":[{"lan":"zh-CN","subtitle_url":"//aisubtitle.hdslb.com/zh.json"}]}}}"""
            },
            TextTransport { _, _ ->
                """{"body":[{"from":-0.5,"to":2.0,"content":"开头"}]}"""
            },
        )

        val result = api.fetchSubtitles(sampleMetadata())

        assertEquals(SubtitleSegment(0, 2_000, "开头"), result?.single())
    }

    private suspend fun sampleMetadata() =
        BilibiliApi(TextTransport { _, _ ->
            """{"code":0,"data":{"bvid":"BV1PS42197aM","cid":123,"title":"Test","duration":42}}"""
        }).fetchMetadata(PlatformVideoId("bilibili", "BV1PS42197aM"))
}
