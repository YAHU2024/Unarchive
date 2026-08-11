"""Gradio processing pipeline lifecycle tests."""

from __future__ import annotations

from types import SimpleNamespace

import pytest

import app


class EmptyScraper:
    def __init__(self):
        self.closed = False

    async def login(self):
        return None

    async def get_favorite_videos(self, folder_id):
        return []

    async def close(self):
        self.closed = True


@pytest.mark.asyncio
async def test_process_videos_closes_scraper_on_early_return(monkeypatch, tmp_path):
    scraper = EmptyScraper()
    config = SimpleNamespace(knowledge_base_dir=str(tmp_path / "cards"))
    monkeypatch.setattr(app, "_create_scraper", lambda platform: scraper)

    updates = []
    async for update in app.process_videos(
        platform="Bilibili",
        folder_ids=["empty"],
        whisper_enabled=False,
        whisper_model="small",
        max_videos=1,
        config=config,
    ):
        updates.append(update)

    assert updates
    assert scraper.closed is True
