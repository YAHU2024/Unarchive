"""Browser runtime resources are released when scrapers close."""

from __future__ import annotations

from unittest.mock import AsyncMock

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
