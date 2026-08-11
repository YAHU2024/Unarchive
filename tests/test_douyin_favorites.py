"""Regression tests for Douyin favorite-folder isolation and detail reuse."""

from __future__ import annotations

from unittest.mock import AsyncMock

import pytest

from src.scraper.base import FavoriteFolder
from src.scraper.douyin import DouyinScraper


def _aweme(video_id: str, *, caption: str = "") -> dict:
    item = {
        "aweme_id": video_id,
        "desc": f"video-{video_id}",
        "author": {"nickname": f"author-{video_id}"},
        "duration": 12_000,
        "music": {"play_url": {"url_list": [f"https://cdn/music-{video_id}.mp3"]}},
        "video": {
            "cover": {"url_list": [f"https://cdn/cover-{video_id}.jpg"]},
            "play_addr": {"url_list": [f"https://cdn/video-{video_id}.mp4"]},
        },
    }
    if caption:
        item["caption_infos"] = [
            {"content": caption, "start_time": 0, "end_time": 1.5}
        ]
    return item


def _page(items: list[dict], *, cursor: int, has_more: bool) -> dict:
    return {"aweme_list": items, "cursor": cursor, "has_more": int(has_more)}


class FakeResponse:
    def __init__(self, url: str, data: dict):
        self.url = url
        self.status = 200
        self._data = data

    async def json(self) -> dict:
        return self._data


class FailingRequestContext:
    async def get(self, *_args, **_kwargs):
        raise AssertionError("cached collection details should avoid a detail request")


class FakeContext:
    def __init__(self):
        self.request = FailingRequestContext()


class FakePage:
    def __init__(self, folder_id: str):
        self.folder_id = folder_id
        self.listeners = []
        self.goto_calls: list[str] = []
        self.scroll_count = 0

    def on(self, event: str, callback) -> None:
        if event == "response":
            self.listeners.append(callback)

    def remove_listener(self, event: str, callback) -> None:
        if event == "response" and callback in self.listeners:
            self.listeners.remove(callback)

    async def _emit(self, url: str, data: dict) -> None:
        for callback in list(self.listeners):
            await callback(FakeResponse(url, data))

    async def goto(self, url: str, **_kwargs) -> None:
        self.goto_calls.append(url)
        endpoint = "https://www.douyin.com/aweme/v1/web/collects/video/list/"
        await self._emit(
            "https://www.douyin.com/aweme/v1/web/collects/list/?cursor=0",
            {
                "collects_list": [
                    {
                        "collects_id": self.folder_id,
                        "collects_id_str": self.folder_id,
                        "collects_name": "target title",
                        "total_number": 2,
                    }
                ],
                "cursor": 0,
                "has_more": 0,
                "status_code": 0,
                "total_number": 1,
            },
        )
        await self._emit(
            f"{endpoint}?collects_id=other&cursor=0",
            _page([_aweme("wrong-1")], cursor=0, has_more=False),
        )
        await self._emit(
            f"{endpoint}?collects_id={self.folder_id}&cursor=0",
            _page([_aweme("a", caption="cached caption")], cursor=10, has_more=True),
        )

    async def evaluate(self, _script: str):
        self.scroll_count += 1
        endpoint = "https://www.douyin.com/aweme/v1/web/collects/video/list/"
        if self.scroll_count == 1:
            await self._emit(
                f"{endpoint}?collects_id=other&cursor=10",
                _page([_aweme("wrong-2")], cursor=0, has_more=False),
            )
            await self._emit(
                f"{endpoint}?collects_id={self.folder_id}&cursor=10",
                _page([_aweme("b")], cursor=20, has_more=True),
            )
        elif self.scroll_count == 2:
            await self._emit(
                f"{endpoint}?collects_id={self.folder_id}&cursor=0",
                _page([_aweme("duplicate")], cursor=10, has_more=True),
            )
            await self._emit(
                f"{endpoint}?collects_id={self.folder_id}&cursor=20",
                _page([_aweme("c")], cursor=0, has_more=False),
            )
        return None

    def get_by_text(self, _text: str, exact: bool = False):
        return FakeTextLocator()

    def locator(self, _selector: str):
        return FakeRouteLocator()


class FakeTextLocator:
    async def count(self) -> int:
        return 1

    def nth(self, _index: int):
        return self

    async def is_visible(self) -> bool:
        return True

    async def click(self) -> None:
        return None


class FakeRouteLocator:
    @property
    def first(self):
        return self

    async def bounding_box(self):
        return None


class TrackingCandidate:
    def __init__(self):
        self.clicked = False

    async def is_visible(self) -> bool:
        return True

    async def click(self) -> None:
        self.clicked = True


class TrackingLocator:
    def __init__(self, candidates):
        self.candidates = candidates

    async def count(self) -> int:
        return len(self.candidates)

    def nth(self, index: int):
        return self.candidates[index]


class DuplicateTitlePage:
    def __init__(self):
        self.candidates = [TrackingCandidate(), TrackingCandidate()]

    def get_by_text(self, _text: str, exact: bool = False):
        return TrackingLocator(self.candidates)


def test_folder_response_matching_uses_exact_query_value():
    target = "7385778090397669130"

    assert DouyinScraper._response_matches_folder(
        f"https://www.douyin.com/api?cursor=0&collects_id={target}", target
    )
    assert not DouyinScraper._response_matches_folder(
        f"https://www.douyin.com/api?collects_id=1{target}", target
    )
    assert not DouyinScraper._response_matches_folder(
        "https://www.douyin.com/api?cursor=0", target
    )


def test_extract_folders_deduplicates_paginated_responses():
    scraper = DouyinScraper()
    responses = [
        (
            "/aweme/v1/web/collects/list/",
            {
                "collects_list": [
                    {"collects_id_str": "folder-1", "collects_name": "old", "total_number": 2}
                ]
            },
        ),
        (
            "/aweme/v1/web/collects/list/",
            {
                "collects_list": [
                    {"collects_id_str": "folder-1", "collects_name": "new", "total_number": 3}
                ]
            },
        ),
    ]

    folders = scraper._extract_folders(responses)

    assert [(folder.folder_id, folder.title, folder.video_count) for folder in folders] == [
        ("folder-1", "new", 3)
    ]
    assert scraper._favorite_counts == {"folder-1": 3}


@pytest.mark.asyncio
async def test_open_folder_disambiguates_duplicate_titles_by_folder_order():
    scraper = DouyinScraper()
    page = DuplicateTitlePage()
    scraper._page = page
    folders = [
        FavoriteFolder(folder_id="first", title="same"),
        FavoriteFolder(folder_id="second", title="same"),
    ]

    await scraper._open_favorite_folder("second", folders)

    assert not page.candidates[0].clicked
    assert page.candidates[1].clicked


def test_parse_video_duration_falls_back_to_nested_video_field():
    scraper = DouyinScraper()
    item = _aweme("nested-duration")
    item.pop("duration")
    item["video"]["duration"] = 12_000

    video = scraper._parse_video_info(item)

    assert video is not None
    assert video.duration == 12.0


def test_select_low_bitrate_h264_prefers_smallest_compatible_stream():
    video_info = {
        "bit_rate": [
            {
                "bit_rate": 900_000,
                "play_addr": {"url_list": ["https://cdn/high.mp4"]},
            },
            {
                "bit_rate": 250_000,
                "play_addr": {"url_list": ["//cdn/low.mp4"]},
            },
            {
                "bit_rate": 100_000,
                "is_h265": 1,
                "play_addr": {"url_list": ["https://cdn/h265.mp4"]},
            },
        ]
    }

    result = DouyinScraper._select_low_bitrate_h264(video_info)

    assert result == "https://cdn/low.mp4"


def test_select_low_bitrate_h264_returns_empty_for_invalid_variants():
    assert DouyinScraper._select_low_bitrate_h264({"bit_rate": "invalid"}) == ""


class _StreamResponse:
    status = 200

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    def raise_for_status(self):
        return None

    async def aiter_bytes(self, _size):
        yield b"first"
        yield b"second"

    async def body(self):
        return b"browser"


class _HttpxClient:
    def __init__(self, *_args, **_kwargs):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    def stream(self, *_args, **_kwargs):
        return _StreamResponse()


class _DownloadContext:
    def __init__(self):
        self.request = type("Request", (), {"get": AsyncMock(return_value=_StreamResponse())})()

    async def cookies(self):
        return [{"name": "session", "value": "secret"}]


@pytest.mark.asyncio
async def test_douyin_download_streams_with_httpx_before_browser(monkeypatch, tmp_path):
    scraper = DouyinScraper()
    scraper._context = _DownloadContext()
    monkeypatch.setattr("src.scraper.douyin.httpx.AsyncClient", _HttpxClient)
    output = tmp_path / "audio.mp4"

    result = await scraper.download_audio_to_file(
        "https://cdn.example/media.mp4?signature=secret", str(output)
    )

    assert result is True
    assert output.read_bytes() == b"firstsecond"
    scraper._context.request.get.assert_not_awaited()
    assert not (tmp_path / "audio.mp4.part").exists()


class _FailingHttpxClient(_HttpxClient):
    def stream(self, *_args, **_kwargs):
        raise RuntimeError("network")


@pytest.mark.asyncio
async def test_douyin_download_falls_back_to_short_browser_request(monkeypatch, tmp_path):
    scraper = DouyinScraper()
    scraper._context = _DownloadContext()
    monkeypatch.setattr("src.scraper.douyin.httpx.AsyncClient", _FailingHttpxClient)
    output = tmp_path / "audio.mp4"

    result = await scraper.download_audio_to_file(
        "https://cdn.example/media.mp4?signature=secret", str(output)
    )

    assert result is True
    assert output.read_bytes() == b"browser"
    assert scraper._context.request.get.await_args.kwargs["timeout"] == 8_000


@pytest.mark.asyncio
async def test_favorite_videos_ignore_prefetch_and_reuse_cached_details():
    folder_id = "target-folder"
    scraper = DouyinScraper()
    scraper._page = FakePage(folder_id)
    scraper._context = FakeContext()
    scraper._favorite_counts[folder_id] = 2  # Deliberately stale; has_more must win.

    videos = await scraper.get_favorite_videos(folder_id)

    assert [video.video_id for video in videos] == ["a", "b", "c"]
    assert scraper._page.scroll_count == 2
    assert len(scraper._page.goto_calls) == 1

    segments = await scraper.get_video_subtitle("a")
    audio_url = await scraper.get_video_audio_url("a")

    assert [segment.text for segment in segments] == ["cached caption"]
    assert audio_url == "https://cdn/video-a.mp4"
    assert len(scraper._page.goto_calls) == 1
