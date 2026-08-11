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

