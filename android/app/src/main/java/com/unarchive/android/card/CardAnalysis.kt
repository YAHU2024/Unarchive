package com.unarchive.android.card

import org.json.JSONArray
import org.json.JSONObject

/** LLM-generated enhancement for a knowledge card. */
data class CardAnalysis(
    val summary: String,
    val keyPoints: List<String>,
    val chapters: List<CardChapter>,
) {
    companion object {
        /**
         * Parses and lightly validates the LLM's structured output.
         *
         * The summary is required (an empty one fails, so the caller can fall back
         * to the base card). Chapters and points are optional and their timestamps
         * are clamped into the transcription range rather than rejected, since the
         * model's numbers can be slightly off.
         */
        fun fromJson(json: JSONObject, audioDurationMs: Long): CardAnalysis {
            val summary = json.optString("summary").trim()
            require(summary.isNotEmpty()) { "LLM 未返回摘要" }

            val upperBound = maxOf(audioDurationMs, 1L)
            val keyPoints = json.optJSONArray("key_points")
                ?.let { array -> (0 until array.length()).map { array.getString(it).trim() } }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

            val chaptersJson = json.optJSONArray("chapters") ?: JSONArray()
            val chapters = mutableListOf<CardChapter>()
            var previousEnd = 0L
            for (i in 0 until chaptersJson.length()) {
                val chapter = chaptersJson.optJSONObject(i) ?: continue
                val title = chapter.optString("title").trim()
                if (title.isEmpty()) continue
                val start = chapter.optLong("start_ms", previousEnd).coerceIn(0L, upperBound)
                val end = chapter.optLong("end_ms", upperBound).coerceAtLeast(start).coerceIn(0L, upperBound)
                val points = chapter.optJSONArray("points")
                    ?.let { array ->
                        (0 until array.length()).mapNotNull { index ->
                            val point = array.optJSONObject(index) ?: return@mapNotNull null
                            val text = point.optString("text").trim()
                            if (text.isEmpty()) null else CardPoint(
                                timestampMs = point.optLong("timestamp_ms", start).coerceIn(start, end),
                                text = text,
                            )
                        }
                    }
                    .orEmpty()
                chapters.add(CardChapter(title, start, end, points))
                previousEnd = end
            }

            return CardAnalysis(
                summary = summary,
                keyPoints = keyPoints,
                chapters = chapters.sortedBy { it.startMs },
            )
        }
    }
}

data class CardChapter(
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val points: List<CardPoint>,
)

data class CardPoint(
    val timestampMs: Long,
    val text: String,
)
