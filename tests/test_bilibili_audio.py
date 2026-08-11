"""Bilibili audio URL request construction."""

from __future__ import annotations

from unittest.mock import AsyncMock

import pytest

from src.scraper.bilibili import BilibiliScraper


@pytest.mark.asyncio
async def test_audio_playurl_includes_cached_cid(monkeypatch):
    scraper = BilibiliScraper()
    scraper._view_cache["BV1TEST"] = {"cid": 987654321}
    api_request = AsyncMock(return_value={
        "data": {
            "dash": {
                "audio": [
                    {"bandwidth": 64000, "baseUrl": "https://audio/low"},
                    {"bandwidth": 128000, "base_url": "https://audio/high"},
                ]
            }
        }
    })

    async def identity_sign(url):
        return url

    monkeypatch.setattr(scraper, "_api_request", api_request)
    monkeypatch.setattr(scraper, "_sign_url_with_wbi", identity_sign)

    result = await scraper.get_video_audio_url("BV1TEST")

    assert result == "https://audio/high"
    requested_url = api_request.await_args.args[0]
    assert "bvid=BV1TEST" in requested_url
    assert "cid=987654321" in requested_url


@pytest.mark.asyncio
async def test_audio_playurl_loads_view_when_cache_is_empty(monkeypatch):
    scraper = BilibiliScraper()
    requested_urls = []

    async def api_request(url):
        requested_urls.append(url)
        if "/x/web-interface/view" in url:
            return {"data": {"cid": 12345}}
        return {
            "data": {
                "dash": {
                    "audio": [{"bandwidth": 1, "baseUrl": "https://audio/file"}]
                }
            }
        }

    async def identity_sign(url):
        return url

    monkeypatch.setattr(scraper, "_api_request", api_request)
    monkeypatch.setattr(scraper, "_sign_url_with_wbi", identity_sign)

    result = await scraper.get_video_audio_url("BV1EMPTY")

    assert result == "https://audio/file"
    assert len(requested_urls) == 2
    assert "cid=12345" in requested_urls[1]
