package com.unarchive.android.card

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CardAnalysisTest {

    @Test
    fun parsesCompleteJson() {
        val json = JSONObject(
            """
            {
              "summary": "视频讲了四个跑姿错误",
              "key_points": ["要点1", "要点2"],
              "chapters": [
                {"title": "错误一", "start_ms": 0, "end_ms": 5000, "points": [{"timestamp_ms": 2000, "text": "跨步过大"}]},
                {"title": "错误二", "start_ms": 5000, "end_ms": 10000, "points": []}
              ]
            }
            """.trimIndent(),
        )

        val analysis = CardAnalysis.fromJson(json, 10_000)

        assertEquals("视频讲了四个跑姿错误", analysis.summary)
        assertEquals(listOf("要点1", "要点2"), analysis.keyPoints)
        assertEquals(2, analysis.chapters.size)
        assertEquals("错误一", analysis.chapters[0].title)
        assertEquals(1, analysis.chapters[0].points.size)
        assertEquals(2_000L, analysis.chapters[0].points[0].timestampMs)
    }

    @Test
    fun requiresSummary() {
        val json = JSONObject("""{"key_points": ["x"]}""")

        assertThrows(IllegalArgumentException::class.java) {
            CardAnalysis.fromJson(json, 10_000)
        }
    }

    @Test
    fun clampsTimestampsIntoRange() {
        val json = JSONObject(
            """
            {
              "summary": "s",
              "chapters": [
                {"title": "c", "start_ms": -100, "end_ms": 999999, "points": [{"timestamp_ms": -5, "text": "p"}]}
              ]
            }
            """.trimIndent(),
        )

        val chapter = CardAnalysis.fromJson(json, 5_000).chapters[0]

        assertEquals(0L, chapter.startMs)
        assertEquals(5_000L, chapter.endMs)
        assertEquals(0L, chapter.points[0].timestampMs)
    }

    @Test
    fun sortsChaptersByStartTime() {
        val json = JSONObject(
            """
            {
              "summary": "s",
              "chapters": [
                {"title": "later", "start_ms": 5000, "end_ms": 10000},
                {"title": "earlier", "start_ms": 0, "end_ms": 5000}
              ]
            }
            """.trimIndent(),
        )

        val analysis = CardAnalysis.fromJson(json, 10_000)

        assertEquals(listOf("earlier", "later"), analysis.chapters.map { it.title })
    }

    @Test
    fun skipsEmptyTitlesAndPoints() {
        val json = JSONObject(
            """
            {
              "summary": "s",
              "chapters": [
                {"title": "", "start_ms": 0, "end_ms": 5000},
                {"title": "ok", "start_ms": 0, "end_ms": 5000, "points": [
                  {"timestamp_ms": 1000, "text": ""},
                  {"timestamp_ms": 2000, "text": "valid"}
                ]}
              ]
            }
            """.trimIndent(),
        )

        val analysis = CardAnalysis.fromJson(json, 5_000)

        assertEquals(1, analysis.chapters.size)
        assertEquals("ok", analysis.chapters[0].title)
        assertEquals(1, analysis.chapters[0].points.size)
        assertEquals("valid", analysis.chapters[0].points[0].text)
    }
}
