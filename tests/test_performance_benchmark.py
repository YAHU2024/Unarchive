"""Performance benchmark helpers remain deterministic and read-only."""

from __future__ import annotations

from types import SimpleNamespace

from tests.performance_benchmark import _parse_ids, _transcript_metrics


def test_parse_ids_preserves_order_and_deduplicates():
    assert _parse_ids(["v2,v1", "v2", " v3 "]) == ["v2", "v1", "v3"]


def test_transcript_metrics_include_quality_proxies():
    segments = [
        SimpleNamespace(text=" Hello world "),
        SimpleNamespace(text="again"),
    ]

    metrics = _transcript_metrics(segments, "hello world again")

    assert metrics == {
        "segments": 2,
        "transcript_chars": 17,
        "transcript_words": 3,
        "reference_similarity": 1.0,
    }
