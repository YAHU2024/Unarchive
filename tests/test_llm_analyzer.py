"""Focused tests for provider endpoint normalization."""

from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock
import logging

import pytest

from src.analyzer.llm_analyzer import LLMAnalyzer


def _valid_combined_json(**overrides) -> str:
    result = {
        "summary": "S" * 200,
        "keywords": ["one", "two", "three"],
        "one_line_summary": "One line.",
        "topics": ["topic"],
        "key_points": [
            {"point": "one", "detail": "one"},
            {"point": "two", "detail": "two"},
            {"point": "three", "detail": "three"},
        ],
        "knowledge_tags": ["one", "two", "three"],
        "target_audience": "reader",
        "action_items": ["act"],
        "mindmap_structure": {
            "center": "center",
            "branches": [{"topic": "topic", "subtopics": []}],
        },
    }
    result.update(overrides)
    import json

    return json.dumps(result)


class _StreamResponse:
    status_code = 200

    def __init__(self, lines):
        self._lines = lines

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return None

    async def aiter_lines(self):
        for line in self._lines:
            yield line

    async def aread(self):
        return b""


class _StreamClient:
    def __init__(self, lines):
        self.lines = lines
        self.payload = None

    def stream(self, _method, _url, *, json):
        self.payload = json
        return _StreamResponse(self.lines)

    async def aclose(self):
        return None


@pytest.mark.parametrize(
    ("base_url", "expected"),
    [
        (
            "https://api.deepseek.com",
            "https://api.deepseek.com/v1/chat/completions",
        ),
        (
            "https://api.siliconflow.cn/v1",
            "https://api.siliconflow.cn/v1/chat/completions",
        ),
        (
            "https://example.com/openai/v1/",
            "https://example.com/openai/v1/chat/completions",
        ),
        (
            "https://example.com/v1/chat/completions/",
            "https://example.com/v1/chat/completions",
        ),
    ],
)
def test_chat_completions_url(base_url, expected):
    assert LLMAnalyzer._chat_completions_url(base_url) == expected


@pytest.mark.asyncio
async def test_combined_analysis_uses_one_final_llm_request():
    analyzer = LLMAnalyzer(config=_config())
    analyzer._call_llm = AsyncMock(return_value=_valid_combined_json(topics=["t"]))

    result = await analyzer.analyze_video("Title", "Author", "short transcript")

    assert result["summary"]
    assert result["topics"] == ["t"]
    assert analyzer._call_llm.await_count == 1
    await analyzer.close()


@pytest.mark.asyncio
async def test_analysis_logs_do_not_include_title_or_author(caplog):
    analyzer = LLMAnalyzer(config=_config())
    analyzer._call_llm = AsyncMock(return_value=_valid_combined_json())

    with caplog.at_level(logging.INFO, logger="src.analyzer.llm_analyzer"):
        await analyzer.analyze_video(
            "private title marker",
            "private author marker",
            "private transcript marker",
        )

    messages = "\n".join(record.getMessage() for record in caplog.records)
    assert "private title marker" not in messages
    assert "private author marker" not in messages
    assert "private transcript marker" not in messages
    assert "transcript_chars=25" in messages
    await analyzer.close()


def _config(**overrides):
    values = {
        "llm_api_key": "test-key",
        "llm_base_url": "https://api.siliconflow.cn/v1",
        "llm_model": "Qwen/Qwen3-8B",
        "llm_provider": "qwen",
        "llm_enable_thinking": False,
        "llm_thinking_budget": 0,
        "llm_max_tokens": 2048,
        "llm_structured_max_tokens": 3072,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


@pytest.mark.asyncio
async def test_qwen3_siliconflow_disables_thinking_by_default():
    analyzer = LLMAnalyzer(config=_config())
    client = _StreamClient([
        'data: {"choices":[{"delta":{"content":"{"}}]}',
        'data: {"choices":[{"delta":{"content":"}"}}]}',
        "data: [DONE]",
    ])
    await analyzer._client.aclose()
    analyzer._client = client

    await analyzer._call_llm("prompt")

    payload = client.payload
    assert payload["enable_thinking"] is False
    assert payload["stream"] is True
    assert payload["max_tokens"] == 2048
    assert "thinking_budget" not in payload
    await analyzer.close()


@pytest.mark.asyncio
async def test_combined_analysis_retries_once_after_incomplete_json():
    analyzer = LLMAnalyzer(config=_config())
    analyzer._call_llm = AsyncMock(
        side_effect=['{"summary":"partial"', _valid_combined_json()]
    )

    result = await analyzer._analyze_combined("Title", "Author", "transcript")

    assert result["summary"]
    assert analyzer._call_llm.await_count == 2
    assert all(
        call.kwargs["max_tokens"] == 3072
        for call in analyzer._call_llm.await_args_list
    )
    await analyzer.close()


@pytest.mark.asyncio
async def test_combined_analysis_trims_field_count_above_contract():
    analyzer = LLMAnalyzer(config=_config())
    analyzer._call_llm = AsyncMock(
        return_value=_valid_combined_json(
            key_points=[
                {"point": str(index), "detail": str(index)}
                for index in range(6)
            ]
        )
    )

    result = await analyzer._analyze_combined("Title", "Author", "transcript")

    assert len(result["key_points"]) == 5
    assert analyzer._call_llm.await_count == 1
    await analyzer.close()


@pytest.mark.asyncio
async def test_combined_analysis_accepts_summary_outside_suggested_length():
    analyzer = LLMAnalyzer(config=_config())
    analyzer._call_llm = AsyncMock(
        return_value=_valid_combined_json(summary="S" * 400)
    )

    result = await analyzer._analyze_combined("Title", "Author", "transcript")

    assert len(result["summary"]) == 400
    assert analyzer._call_llm.await_count == 1
    await analyzer.close()


@pytest.mark.asyncio
async def test_qwen3_siliconflow_can_enable_bounded_thinking():
    analyzer = LLMAnalyzer(
        config=_config(llm_enable_thinking=True, llm_thinking_budget=1024)
    )
    client = _StreamClient(['data: {"choices":[{"delta":{"content":"{}"}}]}'])
    await analyzer._client.aclose()
    analyzer._client = client

    await analyzer._call_llm("prompt")

    payload = client.payload
    assert payload["enable_thinking"] is True
    assert payload["thinking_budget"] == 1024
    await analyzer.close()


@pytest.mark.asyncio
async def test_other_provider_does_not_receive_thinking_fields():
    analyzer = LLMAnalyzer(
        config=_config(
            llm_base_url="https://api.deepseek.com",
            llm_model="deepseek-chat",
            llm_enable_thinking=True,
            llm_thinking_budget=1024,
        )
    )
    response = SimpleNamespace(
        status_code=200,
        json=lambda: {"choices": [{"message": {"content": "{}"}}]},
    )
    analyzer._client.post = AsyncMock(return_value=response)

    await analyzer._call_llm("prompt")

    payload = analyzer._client.post.await_args.kwargs["json"]
    assert "enable_thinking" not in payload
    assert "thinking_budget" not in payload
    assert "stream" not in payload
    await analyzer.close()


@pytest.mark.asyncio
async def test_siliconflow_stream_joins_content_and_ignores_reasoning():
    analyzer = LLMAnalyzer(config=_config())
    client = _StreamClient([
        ": keepalive",
        'data: {"choices":[{"delta":{"reasoning_content":"hidden"}}]}',
        'data: {"choices":[{"delta":{"content":"hello "}}]}',
        'data: {"choices":[{"delta":{"content":"world"}}]}',
        "data: [DONE]",
    ])
    await analyzer._client.aclose()
    analyzer._client = client

    result = await analyzer._call_llm("prompt")

    assert result == "hello world"
    await analyzer.close()
