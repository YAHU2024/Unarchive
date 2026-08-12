"""Feishu folder discovery and reuse tests."""

from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

import app
from src.sync.feishu import FeishuSync


@pytest.mark.asyncio
async def test_find_folder_matches_exact_folder_across_pages(monkeypatch):
    sync = FeishuSync("app", "secret")
    request = AsyncMock(side_effect=[
        {
            "files": [
                {"name": "视频知识库", "type": "docx", "token": "doc-token"},
                {"name": "其他目录", "type": "folder", "token": "other-token"},
            ],
            "has_more": True,
            "next_page_token": "page-2",
        },
        {
            "files": [
                {"name": "视频知识库", "type": "folder", "token": "folder-token"},
            ],
            "has_more": False,
        },
    ])
    monkeypatch.setattr(sync, "_request", request)

    result = await sync.find_folder("视频知识库", "root-token")

    assert result == "folder-token"
    assert request.await_count == 2
    first_params = request.await_args_list[0].kwargs["params"]
    assert first_params["order_by"] == "CreatedTime"
    assert first_params["direction"] == "ASC"
    assert request.await_args_list[1].kwargs["params"]["page_token"] == "page-2"


@pytest.mark.asyncio
async def test_get_or_create_folder_reuses_existing(monkeypatch):
    sync = FeishuSync("app", "secret")
    monkeypatch.setattr(sync, "get_root_folder_token", AsyncMock(return_value="root-token"))
    monkeypatch.setattr(sync, "find_folder", AsyncMock(return_value="existing-token"))
    create = AsyncMock(return_value="new-token")
    monkeypatch.setattr(sync, "create_folder", create)

    token, created = await sync.get_or_create_folder("视频知识库")

    assert (token, created) == ("existing-token", False)
    create.assert_not_awaited()


@pytest.mark.asyncio
async def test_get_or_create_folder_creates_when_missing(monkeypatch):
    sync = FeishuSync("app", "secret")
    monkeypatch.setattr(sync, "get_root_folder_token", AsyncMock(return_value="root-token"))
    monkeypatch.setattr(sync, "find_folder", AsyncMock(return_value=None))
    create = AsyncMock(return_value="new-token")
    monkeypatch.setattr(sync, "create_folder", create)

    token, created = await sync.get_or_create_folder("视频知识库")

    assert (token, created) == ("new-token", True)
    create.assert_awaited_once_with("视频知识库", "root-token")


@pytest.mark.asyncio
async def test_check_document_exists_uses_next_page_token(monkeypatch):
    sync = FeishuSync("app", "secret")
    request = AsyncMock(side_effect=[
        {
            "files": [{"name": "other", "type": "docx", "token": "other-doc"}],
            "has_more": True,
            "next_page_token": "page-2",
        },
        {
            "files": [
                {"name": "[BV1TEST] Target", "type": "docx", "token": "target-doc"},
            ],
            "has_more": False,
        },
    ])
    monkeypatch.setattr(sync, "_request", request)

    result = await sync.check_document_exists("BV1TEST", "folder-token")

    assert result == "target-doc"
    assert request.await_args_list[1].kwargs["params"]["page_token"] == "page-2"


class FakeFeishu:
    def __init__(self, *args, **kwargs):
        self.closed = False

    async def connect(self):
        return True

    async def get_or_create_folder(self, name):
        return "existing-folder", False

    async def check_document_exists(self, video_id, folder_token):
        return "existing-doc"

    async def close(self):
        self.closed = True


@pytest.mark.asyncio
async def test_sync_to_feishu_reuses_default_folder(monkeypatch):
    fake = FakeFeishu()
    monkeypatch.setattr(app, "FeishuSync", lambda *args, **kwargs: fake)
    monkeypatch.setattr(app, "_load_knowledge_card", lambda video_id: {"title": "Title"})

    updates = []
    async for logs, _ in app.sync_to_feishu(
        ["BV1TEST"],
        "",
        SimpleNamespace(feishu_app_id="app", feishu_app_secret="secret"),
        progress=lambda *args, **kwargs: None,
    ):
        updates.append(logs)

    assert "文件夹已复用: existing-folder" in updates[-1]
    assert "文档已存在 (existing-doc)，跳过" in updates[-1]
    assert fake.closed is True
