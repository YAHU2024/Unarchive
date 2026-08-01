"""
Tests for Bilibili video availability classification:
- UNAVAILABLE for known error codes (deleted/private/taken-down: -404, 62xxx)
- TEMPORARY_ERROR for transient errors (-352=fengkong, -400=bad request, network/HTTP)
- AVAILABLE for normal responses
"""
from __future__ import annotations

from unittest.mock import AsyncMock, patch
import pytest

from src.scraper.base import VideoAvailability
from src.scraper.bilibili import BilibiliScraper


class TestBilibiliAvailability:
    """check_video_available returns correct VideoAvailability enum values."""

    @pytest.mark.asyncio
    async def test_available_on_success(self):
        """Returns AVAILABLE when /x/web-interface/view succeeds."""
        scraper = BilibiliScraper()
        with patch.object(scraper, "_api_request", AsyncMock(return_value={"data": {"aid": 1}})):
            result = await scraper.check_video_available("BV123")
            assert result == VideoAvailability.AVAILABLE

    @pytest.mark.asyncio
    async def test_available_from_cache(self):
        """Returns AVAILABLE from _view_cache without calling API."""
        scraper = BilibiliScraper()
        scraper._view_cache["BV123"] = {"aid": 1}
        result = await scraper.check_video_available("BV123")
        assert result == VideoAvailability.AVAILABLE

    @pytest.mark.asyncio
    @pytest.mark.parametrize("error_code", [-404, 62001, 62002, 62003, 62004])
    async def test_unavailable_for_known_codes(self, error_code):
        """Known unavailable error codes → UNAVAILABLE (permanent skip)."""
        scraper = BilibiliScraper()
        async def mock_request(url):
            raise RuntimeError(f"API error code={error_code} message=deleted")
        with patch.object(scraper, "_api_request", side_effect=mock_request):
            result = await scraper.check_video_available("BV123")
            assert result == VideoAvailability.UNAVAILABLE

    @pytest.mark.asyncio
    @pytest.mark.parametrize("error_msg", [
        "Network error: connection refused",
        "HTTP 503 Service Unavailable",
        "Cookie expired, please re-login",
        "code=10003 unknown error",
        "timeout",
        # -352 and -400 are now classified as TEMPORARY_ERROR (not permanently unavailable)
        "API 返回错误: code=-352, message=风控拦截",
        "API 返回错误: code=-400, message=请求错误",
    ])
    async def test_temporary_error_for_unknown_errors(self, error_msg):
        """Transient/transient-like errors → TEMPORARY_ERROR (retry allowed)."""
        scraper = BilibiliScraper()
        async def mock_request(url):
            raise RuntimeError(error_msg)
        with patch.object(scraper, "_api_request", side_effect=mock_request):
            result = await scraper.check_video_available("BV123")
            assert result == VideoAvailability.TEMPORARY_ERROR
