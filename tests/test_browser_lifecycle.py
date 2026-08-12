"""Browser runtime resources are released when scrapers close."""

from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pytest

from src.scraper.bilibili import BilibiliScraper
from src.scraper.douyin import DouyinScraper
from src.utils.cdp import CDPClient


@pytest.mark.asyncio
async def test_bilibili_close_stops_playwright():
    scraper = BilibiliScraper()
    playwright = AsyncMock()
    scraper._playwright = playwright

    await scraper.close()

    playwright.stop.assert_awaited_once()
    assert scraper._playwright is None


@pytest.mark.asyncio
async def test_douyin_playwright_close_stops_playwright():
    scraper = DouyinScraper()
    scraper._login_mode = "playwright"
    playwright = AsyncMock()
    scraper._playwright = playwright

    await scraper.close()

    playwright.stop.assert_awaited_once()
    assert scraper._playwright is None


@pytest.mark.asyncio
async def test_cdp_failed_connection_cleanup_stops_playwright():
    client = CDPClient()
    playwright = AsyncMock()
    client._playwright = playwright

    await client._cleanup_connection()

    playwright.stop.assert_awaited_once()
    assert client._playwright is None


@pytest.mark.asyncio
async def test_cdp_disconnect_stops_playwright():
    client = CDPClient()
    playwright = AsyncMock()
    client._playwright = playwright

    await client.disconnect()

    playwright.stop.assert_awaited_once()
    assert client._playwright is None


@pytest.mark.asyncio
async def test_douyin_saved_cookies_skip_cdp_login_wait(tmp_path):
    cookie_path = tmp_path / "douyin.json"
    cookie_path.write_text('[{"name": "sessionid", "value": "saved"}]', encoding="utf-8")
    scraper = DouyinScraper(cookie_path=str(cookie_path))

    fake_cdp = AsyncMock()
    fake_cdp.connect.return_value = True
    fake_cdp.launched_by_us = True
    fake_cdp.page = object()
    fake_cdp.context = object()
    fake_cdp._browser = object()

    with patch("src.scraper.douyin.CDPClient", return_value=fake_cdp), \
         patch.object(scraper, "_check_login_status", AsyncMock(return_value=False)), \
         patch.object(scraper, "_login_playwright", AsyncMock()) as login_playwright, \
         patch("src.scraper.douyin.asyncio.sleep", AsyncMock()) as sleep:
        await scraper.login()

    sleep.assert_not_awaited()
    fake_cdp.disconnect.assert_awaited_once()
    login_playwright.assert_awaited_once()
