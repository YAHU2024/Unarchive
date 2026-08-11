"""Long-transcript coverage: overlap and no silent middle truncation."""

from __future__ import annotations

from unittest.mock import AsyncMock

import pytest

from src.analyzer.llm_analyzer import LLMAnalyzer


def test_split_transcript_uses_overlap_and_covers_boundaries():
    transcript = "A" * 9000 + "B" * 6000

    chunks = LLMAnalyzer._split_transcript(transcript)

    assert len(chunks) == 2
    assert chunks[0][0] == "A"
    assert chunks[-1][-1] == "B"
    assert sum(len(chunk) for chunk in chunks) > len(transcript)


@pytest.mark.asyncio
async def test_long_transcript_chunk_summary_keeps_all_chunk_results():
    analyzer = object.__new__(LLMAnalyzer)
    analyzer._load_prompt = lambda _name, **kwargs: kwargs["transcript"]
    analyzer._call_llm = AsyncMock(side_effect=["facts-1", "facts-2"])
    transcript = "A" * 9000 + "B" * 6000

    result = await analyzer._summarize_long_transcript(
        "Title", "Author", transcript
    )

    assert "facts-1" in result
    assert "facts-2" in result
    assert analyzer._call_llm.await_count == 2


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("transcript", "expected_chunked"),
    [("short transcript", False), ("L" * 15000, True)],
)
async def test_analysis_records_whether_transcript_was_chunked(
    transcript, expected_chunked
):
    analyzer = object.__new__(LLMAnalyzer)
    analyzer._summarize_long_transcript = AsyncMock(return_value="chunk facts")
    analyzer._analyze_structure = AsyncMock(return_value={})
    analyzer._summarize = AsyncMock(return_value={})

    result = await analyzer.analyze_video("Title", "Author", transcript)

    assert result["analysis_chunked"] is expected_chunked
    if expected_chunked:
        analyzer._summarize_long_transcript.assert_awaited_once()
    else:
        analyzer._summarize_long_transcript.assert_not_awaited()
