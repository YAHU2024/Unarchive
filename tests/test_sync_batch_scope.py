"""Regression coverage for current-batch sync scope and ima state fast paths."""

from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

import app


def test_resolve_sync_video_ids_prefers_explicit_order_and_deduplicates():
    state = {"last_processed_video_ids": ["old-1", "old-2"]}

    resolved = app._resolve_sync_video_ids(" new-2, new-1, new-2, ", state)

    assert resolved == ["new-2", "new-1"]


def test_resolve_sync_video_ids_uses_only_latest_batch_when_input_is_empty():
    state = {"last_processed_video_ids": ["v2", "v1", "v2", ""]}

    resolved = app._resolve_sync_video_ids("", state)

    assert resolved == ["v2", "v1"]


@pytest.mark.asyncio
async def test_do_sync_without_explicit_or_current_batch_stops_before_api():
    updates = []

    async for logs, _ in app.do_sync(
        "", "", "app-id", "secret", {}, progress=lambda *_args, **_kwargs: None
    ):
        updates.append(logs)

    assert updates == ["没有本次处理结果，请显式填写要同步的视频 ID"]


@pytest.mark.asyncio
async def test_do_sync_ima_without_explicit_or_current_batch_stops_before_api():
    updates = []

    async for logs, _ in app.do_sync_ima(
        "", "client", "key", {}, progress=lambda *_args, **_kwargs: None
    ):
        updates.append(logs)

    assert updates == ["没有本次处理结果，请显式填写要同步的视频 ID"]


class _FakeIma:
    def __init__(self, *args, **kwargs):
        self.knowledge_base_id = kwargs.get("knowledge_base_id", "")
        self.check_document_exists = AsyncMock(
            side_effect=AssertionError("local target state should avoid remote search")
        )
        self.add_to_knowledge_base = AsyncMock()
        self.closed = False

    async def connect(self):
        return True

    async def create_folder(self, _name):
        return ""

    async def resolve_kb_folder(self, _kb_id, folder_id, _folder_name):
        return folder_id

    async def close(self):
        self.closed = True


async def _run_ima_sync(monkeypatch, state: dict) -> tuple[_FakeIma, str]:
    fake = _FakeIma(knowledge_base_id="kb")
    monkeypatch.setattr(app, "ImaSync", lambda *args, **kwargs: fake)
    monkeypatch.setattr(app, "_load_ima_sync_state", lambda: state)
    monkeypatch.setattr(app, "_save_ima_sync_state", lambda _state: None)
    monkeypatch.setattr(app, "_load_knowledge_card", lambda _vid: {"title": "Title"})

    updates = []
    config = SimpleNamespace(ima_client_id="client", ima_api_key="key")
    async for logs, _ in app.sync_to_ima(
        ["v1"], "kb", "folder", "", config,
        progress=lambda *_args, **_kwargs: None,
    ):
        updates.append(logs)
    return fake, updates[-1]


@pytest.mark.asyncio
async def test_ima_completed_local_target_skips_remote_search(monkeypatch):
    state = {
        app._state_key("v1", "kb"): {
            "note_id": "note-1",
            "knowledge_base_id": "kb",
            "kb_added": True,
            "kb_folder_id": "folder",
        }
    }

    fake, logs = await _run_ima_sync(monkeypatch, state)

    fake.check_document_exists.assert_not_awaited()
    fake.add_to_knowledge_base.assert_not_awaited()
    assert "跳过远端查重" in logs


@pytest.mark.asyncio
async def test_ima_partial_local_target_resumes_without_remote_search(monkeypatch):
    state = {
        app._state_key("v1", "kb"): {
            "note_id": "note-1",
            "knowledge_base_id": "kb",
            "kb_added": False,
            "kb_folder_id": "folder",
        }
    }

    fake, logs = await _run_ima_sync(monkeypatch, state)

    fake.check_document_exists.assert_not_awaited()
    fake.add_to_knowledge_base.assert_awaited_once_with(
        "note-1", "[v1] Title", "folder"
    )
    assert "知识库关联成功" in logs

