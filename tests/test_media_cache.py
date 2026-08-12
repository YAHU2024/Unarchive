"""Stable transcript media-cache and bounded pruning behavior."""

from __future__ import annotations

import os
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

import config
from src.scraper.base import SubtitleSegment
from src.transcript.subtitle import _audio_cache_path, get_transcript
from src.transcript.whisper_asr import WhisperTranscriber


class FakeScraper:
    get_video_subtitle = AsyncMock(return_value=None)
    get_video_ai_summary = AsyncMock(return_value=None)
    get_video_audio_url = AsyncMock(
        side_effect=AssertionError("valid stable cache should avoid signed URL lookup")
    )


@pytest.mark.asyncio
async def test_get_transcript_reuses_platform_video_cache(monkeypatch, tmp_path):
    monkeypatch.setattr(
        config,
        "get_config",
        lambda: SimpleNamespace(audio_cache_dir=str(tmp_path)),
    )
    scraper = FakeScraper()
    cache_path = _audio_cache_path("video-1", scraper)
    cache_path.write_bytes(b"cached-media")
    transcriber = SimpleNamespace(
        transcribe_audio_file=AsyncMock(
            return_value=[SubtitleSegment(start=0, end=1, text="cached")]
        )
    )

    segments, source = await get_transcript("video-1", scraper, transcriber)

    assert source == "whisper"
    assert [segment.text for segment in segments] == ["cached"]
    scraper.get_video_audio_url.assert_not_awaited()
    transcriber.transcribe_audio_file.assert_awaited_once_with(str(cache_path))


def test_prune_audio_cache_removes_stale_partial_and_enforces_size(tmp_path):
    stale = tmp_path / "stale.mp4"
    partial = tmp_path / "active.mp4.part"
    older = tmp_path / "older.mp4"
    newest = tmp_path / "newest.mp4"
    for path in (stale, partial, older, newest):
        path.write_bytes(b"x" * 10)

    now = time.time()
    os.utime(stale, (now - 9 * 86400, now - 9 * 86400))
    os.utime(older, (now - 100, now - 100))
    os.utime(newest, (now, now))

    removed = WhisperTranscriber.prune_audio_cache(
        tmp_path, max_age_days=7, max_bytes=10
    )

    assert removed == 3
    assert not stale.exists()
    assert not partial.exists()
    assert not older.exists()
    assert newest.exists()

