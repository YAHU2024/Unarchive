"""Integration-style tests for CLI processing orchestration."""

from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import pytest

import cli
from src.analyzer.llm_analyzer import LLMQuotaExceededError
from src.scraper.base import SubtitleSegment, VideoAvailability, VideoInfo


class FakeScraper:
    def __init__(self, videos, availability=VideoAvailability.AVAILABLE):
        self.videos = videos
        self.availability = availability
        self.closed = False
        self.availability_calls = 0

    async def login(self):
        return None

    async def get_favorite_videos(self, folder_id):
        return self.videos

    async def get_audio_cookies(self):
        return {}

    async def check_video_available(self, video_id):
        self.availability_calls += 1
        return self.availability

    async def get_video_owner(self, video_id):
        return "Verified Author"

    async def close(self):
        self.closed = True


class FakeAnalyzer:
    error = None
    calls = []

    def __init__(self, config=None):
        pass

    async def analyze_video(self, title, author, transcript):
        type(self).calls.append((title, author, transcript))
        if type(self).error:
            raise type(self).error
        return {"summary": "done", "keywords": []}

    async def close(self):
        return None


def _config(tmp_path: Path):
    return SimpleNamespace(
        knowledge_base_dir=str(tmp_path / "cards"),
        whisper_model="tiny",
    )


def _args(**overrides):
    values = {
        "platform": "bilibili",
        "folder_id": "folder-1",
        "max_videos": 20,
        "no_whisper": True,
        "force": False,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


@pytest.fixture(autouse=True)
def _reset_analyzer():
    FakeAnalyzer.error = None
    FakeAnalyzer.calls = []


@pytest.mark.asyncio
async def test_partial_card_resumes_without_retranscribing(tmp_path, monkeypatch):
    config = _config(tmp_path)
    card_dir = Path(config.knowledge_base_dir)
    card_dir.mkdir(parents=True)
    (card_dir / "v1.json").write_text(json.dumps({
        "video_id": "v1",
        "title": "Partial",
        "author": "Saved Author",
        "source_url": "https://example/v1",
        "platform": "bilibili",
        "transcript_source": "subtitle",
        "transcript": "saved transcript",
        "transcript_segments": [{"start": 1.0, "end": 2.0, "text": "saved transcript"}],
        "_partial": True,
    }), encoding="utf-8")
    scraper = FakeScraper([VideoInfo("v1", "Partial", "https://example/v1")])

    async def unexpected_transcript(*args, **kwargs):
        raise AssertionError("partial recovery must not transcribe again")

    monkeypatch.setattr(cli, "get_config", lambda: config)
    monkeypatch.setattr(cli, "_create_scraper", lambda platform: scraper)
    monkeypatch.setattr("src.transcript.get_transcript", unexpected_transcript)
    monkeypatch.setattr("src.analyzer.llm_analyzer.LLMAnalyzer", FakeAnalyzer)

    await cli.cmd_process(_args())

    saved = json.loads((card_dir / "v1.json").read_text(encoding="utf-8"))
    assert saved["summary"] == "done"
    assert "_partial" not in saved
    assert saved["transcript_segments"][0]["start"] == 1.0
    assert scraper.availability_calls == 0
    assert FakeAnalyzer.calls == [("Partial", "Saved Author", "saved transcript")]


@pytest.mark.asyncio
async def test_quota_saves_partial_card_and_stops_batch(tmp_path, monkeypatch):
    config = _config(tmp_path)
    videos = [
        VideoInfo("v1", "First", "https://example/v1", author="A"),
        VideoInfo("v2", "Second", "https://example/v2", author="B"),
    ]
    scraper = FakeScraper(videos)
    transcript_calls = []

    async def fake_transcript(video_id, *args, **kwargs):
        transcript_calls.append(video_id)
        return [SubtitleSegment(1.5, 3.0, "spoken words")], "subtitle"

    FakeAnalyzer.error = LLMQuotaExceededError("quota exhausted")
    monkeypatch.setattr(cli, "get_config", lambda: config)
    monkeypatch.setattr(cli, "_create_scraper", lambda platform: scraper)
    monkeypatch.setattr("src.transcript.get_transcript", fake_transcript)
    monkeypatch.setattr("src.analyzer.llm_analyzer.LLMAnalyzer", FakeAnalyzer)

    await cli.cmd_process(_args())

    first = json.loads(
        (Path(config.knowledge_base_dir) / "v1.json").read_text(encoding="utf-8")
    )
    assert first["_partial"] is True
    assert first["transcript"] == "spoken words"
    assert first["transcript_segments"] == [
        {"start": 1.5, "end": 3.0, "text": "spoken words"}
    ]
    assert not (Path(config.knowledge_base_dir) / "v2.json").exists()
    assert transcript_calls == ["v1"]


@pytest.mark.asyncio
async def test_temporary_availability_error_does_not_create_card(tmp_path, monkeypatch):
    config = _config(tmp_path)
    scraper = FakeScraper(
        [VideoInfo("v1", "Temporary", "https://example/v1")],
        availability=VideoAvailability.TEMPORARY_ERROR,
    )

    async def unexpected_transcript(*args, **kwargs):
        raise AssertionError("temporary failures must not enter transcription")

    monkeypatch.setattr(cli, "get_config", lambda: config)
    monkeypatch.setattr(cli, "_create_scraper", lambda platform: scraper)
    monkeypatch.setattr("src.transcript.get_transcript", unexpected_transcript)
    monkeypatch.setattr("src.analyzer.llm_analyzer.LLMAnalyzer", FakeAnalyzer)

    await cli.cmd_process(_args())

    assert not (Path(config.knowledge_base_dir) / "v1.json").exists()
    assert FakeAnalyzer.calls == []


@pytest.mark.asyncio
async def test_force_failure_preserves_existing_complete_card(tmp_path, monkeypatch):
    config = _config(tmp_path)
    card_dir = Path(config.knowledge_base_dir)
    card_dir.mkdir(parents=True)
    original = {"video_id": "v1", "title": "Original", "summary": "keep me"}
    card_path = card_dir / "v1.json"
    card_path.write_text(json.dumps(original), encoding="utf-8")
    scraper = FakeScraper([VideoInfo("v1", "Refresh", "https://example/v1")])

    async def fake_transcript(*args, **kwargs):
        return [SubtitleSegment(0.0, 1.0, "new transcript")], "subtitle"

    FakeAnalyzer.error = RuntimeError("provider unavailable")
    monkeypatch.setattr(cli, "get_config", lambda: config)
    monkeypatch.setattr(cli, "_create_scraper", lambda platform: scraper)
    monkeypatch.setattr("src.transcript.get_transcript", fake_transcript)
    monkeypatch.setattr("src.analyzer.llm_analyzer.LLMAnalyzer", FakeAnalyzer)

    await cli.cmd_process(_args(force=True))

    assert json.loads(card_path.read_text(encoding="utf-8")) == original
