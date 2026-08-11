"""Whisper fast/quality parameters and media-prefetch orchestration."""

from __future__ import annotations

import asyncio
from pathlib import Path
from types import SimpleNamespace

import pytest

import app
from src.scraper.base import SubtitleSegment, VideoAvailability, VideoInfo
from src.transcript.whisper_asr import WhisperTranscriber


class _Model:
    def __init__(self):
        self.options = None

    def transcribe(self, _file_path, **options):
        self.options = options
        segments = [SimpleNamespace(start=0.0, end=1.0, text=" hello ")]
        return iter(segments), SimpleNamespace(duration=1.0)


def test_fast_profile_uses_beam_one_and_language_detection():
    transcriber = WhisperTranscriber(
        model_name="tiny", device="cpu", beam_size=1, language=None
    )
    model = _Model()
    transcriber._model = model

    segments = transcriber._run_faster_whisper("sample.mp4")

    assert [segment.text for segment in segments] == ["hello"]
    assert model.options["beam_size"] == 1
    assert "language" not in model.options


def test_quality_profile_can_force_language_and_beam_five():
    transcriber = WhisperTranscriber(
        model_name="small", device="cpu", beam_size=5, language="zh"
    )
    model = _Model()
    transcriber._model = model

    transcriber._run_faster_whisper("sample.mp4")

    assert model.options["beam_size"] == 5
    assert model.options["language"] == "zh"


class _PipelineScraper:
    def __init__(self):
        self.closed = False

    async def login(self):
        return None

    async def get_favorite_videos(self, _folder_id):
        return [
            VideoInfo("v1", "First", "https://example/v1", author="A"),
            VideoInfo("v2", "Second", "https://example/v2", author="B"),
        ]

    async def get_audio_cookies(self):
        return {}

    async def check_video_available(self, _video_id):
        return VideoAvailability.AVAILABLE

    async def get_video_owner(self, _video_id):
        return ""

    async def close(self):
        self.closed = True


class _Whisper:
    init_options = None

    def __init__(self, **options):
        type(self).init_options = options

    def cleanup(self):
        return None


@pytest.mark.asyncio
async def test_pipeline_prefetches_next_media_during_llm(monkeypatch, tmp_path):
    prefetch_started = asyncio.Event()
    prefetch_calls = []

    async def fake_prefetch(video_id, _scraper):
        prefetch_calls.append(video_id)
        prefetch_started.set()
        return Path(tmp_path / f"{video_id}.mp4")

    class Analyzer:
        calls = 0

        def __init__(self, config=None):
            pass

        async def analyze_video(self, **_kwargs):
            if self.calls == 0:
                await asyncio.wait_for(prefetch_started.wait(), timeout=0.5)
            self.calls += 1
            return {"summary": "done", "keywords": []}

        async def close(self):
            return None

    async def fake_transcript(video_id, *_args, **_kwargs):
        return [SubtitleSegment(0.0, 1.0, video_id)], "whisper"

    scraper = _PipelineScraper()
    monkeypatch.setattr(app, "_create_scraper", lambda _platform: scraper)
    monkeypatch.setattr(app, "_load_knowledge_card", lambda _video_id: None)
    monkeypatch.setattr(app, "_save_knowledge_card", lambda *_args: None)
    monkeypatch.setattr(app, "WhisperTranscriber", _Whisper)
    monkeypatch.setattr(app, "LLMAnalyzer", Analyzer)
    monkeypatch.setattr(app, "get_transcript", fake_transcript)
    monkeypatch.setattr(app, "prefetch_transcript_media", fake_prefetch)
    config = SimpleNamespace(
        whisper_language="auto",
        whisper_device="cpu",
        whisper_compute_type="int8",
    )

    async for _update in app.process_videos(
        platform="抖音",
        folder_ids=["folder"],
        whisper_enabled=True,
        whisper_model="base",
        max_videos=2,
        config=config,
        whisper_profile="fast",
    ):
        pass

    assert prefetch_calls == ["v2"]
    assert _Whisper.init_options["beam_size"] == 1
    assert _Whisper.init_options["language"] is None
    assert scraper.closed is True

