"""Focused tests for provider endpoint normalization."""

from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from src.analyzer.llm_analyzer import LLMAnalyzer


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
    analyzer._call_llm = AsyncMock(return_value='{"summary":"ok","topics":["t"]}')

    result = await analyzer.analyze_video("Title", "Author", "short transcript")

    assert result["summary"] == "ok"
    assert result["topics"] == ["t"]
    assert analyzer._call_llm.await_count == 1
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
