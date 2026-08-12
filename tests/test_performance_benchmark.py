"""Performance benchmark helpers remain deterministic and read-only."""

from __future__ import annotations

from types import SimpleNamespace

from config import AppConfig
from tests.performance_benchmark import _build_config, _parse_ids, _transcript_metrics


def test_parse_ids_preserves_order_and_deduplicates():
    assert _parse_ids(["v2,v1", "v2", " v3 "]) == ["v2", "v1", "v3"]


def test_transcript_metrics_include_quality_proxies():
    segments = [
        SimpleNamespace(text=" Hello world "),
        SimpleNamespace(text="again"),
    ]

    metrics = _transcript_metrics(segments, "hello world again")

    assert metrics["segments"] == 2
    assert metrics["transcript_chars"] == 17
    assert metrics["transcript_words"] == 3
    assert metrics["reference_similarity"] == 1.0
    assert metrics["samples"] == {
        "start": "Hello world again",
        "middle": "Hello world again",
        "end": "Hello world again",
    }


def test_build_config_overrides_benchmark_only_fields():
    base = AppConfig(
        llm_model="current",
        llm_enable_thinking=False,
        llm_thinking_budget=0,
    )

    candidate = _build_config(
        base,
        llm_model="candidate",
        enable_thinking=True,
        thinking_budget=512,
    )

    assert candidate.llm_model == "candidate"
    assert candidate.llm_enable_thinking is True
    assert candidate.llm_thinking_budget == 512
    assert base.llm_model == "current"
