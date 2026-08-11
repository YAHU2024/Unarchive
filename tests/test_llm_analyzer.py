"""Focused tests for provider endpoint normalization."""

from __future__ import annotations

import pytest

from src.analyzer.llm_analyzer import LLMAnalyzer


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
