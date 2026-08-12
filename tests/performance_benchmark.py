"""Repeatable local ASR/LLM benchmark for the fixed Douyin sample.

This benchmark is deliberately read-only with respect to knowledge cards and
configuration. ASR reads existing media cache files; LLM reads existing card
transcripts. Results are JSON and Markdown under ``data/logs/performance``.

Examples:
  python tests/performance_benchmark.py --stage asr --whisper-model small --whisper-model base
  python tests/performance_benchmark.py --stage llm --llm-model Qwen/Qwen3-8B
  python tests/performance_benchmark.py --stage all --include-candidates
"""

from __future__ import annotations

import argparse
import asyncio
from difflib import SequenceMatcher
import json
import logging
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from config import AppConfig, get_config
from src.analyzer.llm_analyzer import LLMAnalyzer
from src.knowledge_store import load_knowledge_card
from src.transcript.whisper_asr import WhisperTranscriber

logger = logging.getLogger("performance_benchmark")

DEFAULT_VIDEO_IDS = [
    "7445539589361634595",
    "7429370120201456931",
    "7415703448252861747",
    "7356908570262228250",
]

# These are opt-in examples only. Availability, price and quality must be
# verified against the user's provider account before adopting a default.
OPTIONAL_LLM_CANDIDATES = [
    "Qwen/Qwen2.5-7B-Instruct",
    "deepseek-ai/DeepSeek-V3",
]


def _media_path(video_id: str, config: AppConfig) -> Path:
    return Path(config.audio_cache_dir) / f"audio_douyin_{video_id}.mp4"


def _parse_ids(values: list[str] | None) -> list[str]:
    candidates = values or DEFAULT_VIDEO_IDS
    result: list[str] = []
    seen: set[str] = set()
    for value in candidates:
        for item in value.split(","):
            video_id = item.strip()
            if video_id and video_id not in seen:
                seen.add(video_id)
                result.append(video_id)
    return result


def _build_config(base: AppConfig, *, llm_model: str | None = None) -> AppConfig:
    updates: dict[str, Any] = {}
    if llm_model:
        updates["llm_model"] = llm_model
    return base.model_copy(update=updates) if updates else base


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _normalized_text(value: str) -> str:
    return " ".join(value.lower().split())


def _transcript_metrics(segments: list[Any], reference: str) -> dict[str, Any]:
    text = " ".join(segment.text.strip() for segment in segments if segment.text.strip())
    normalized = _normalized_text(text)
    normalized_reference = _normalized_text(reference)
    similarity = None
    if normalized and normalized_reference:
        similarity = round(
            SequenceMatcher(None, normalized_reference, normalized, autojunk=False).ratio(),
            4,
        )
    return {
        "segments": len(segments),
        "transcript_chars": len(text),
        "transcript_words": len(text.split()),
        "reference_similarity": similarity,
    }


def _write_results(output_dir: Path, results: dict[str, Any]) -> tuple[Path, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    json_path = output_dir / f"benchmark-{stamp}.json"
    md_path = output_dir / f"benchmark-{stamp}.md"
    json_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")

    lines = [
        f"# Performance Benchmark ({results['started_at']})",
        "",
        f"Stage: `{results['stage']}`",
        f"Videos: {', '.join(results['video_ids'])}",
        "",
        "| Kind | Model | Video | Status | Elapsed (s) | Detail |",
        "|---|---|---|---|---:|---|",
    ]
    for row in results["measurements"]:
        detail = str(row.get("detail", "")).replace("|", "\\|").replace("\n", " ")
        lines.append(
            f"| {row['kind']} | `{row['model']}` | `{row['video_id']}` "
            f"| {row['status']} | {row.get('elapsed_seconds', '')} | {detail} |"
        )
    lines.extend(["", "The benchmark does not modify knowledge cards or `.env`."])
    md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return json_path, md_path


def _run_asr(video_ids: list[str], config: AppConfig, models: list[str], profile: str) -> list[dict[str, Any]]:
    measurements: list[dict[str, Any]] = []
    language = config.whisper_language.strip()
    language_value = None if not language or language.lower() == "auto" else language
    for model in models:
        transcriber = WhisperTranscriber(
            model_name=model,
            device=config.whisper_device,
            compute_type=config.whisper_compute_type,
            beam_size=5 if profile == "quality" else 1,
            language=language_value,
        )
        try:
            for video_id in video_ids:
                media = _media_path(video_id, config)
                row: dict[str, Any] = {"kind": "asr", "model": model, "video_id": video_id}
                if not media.exists() or media.stat().st_size == 0:
                    row.update({"status": "missing_media", "detail": str(media)})
                    measurements.append(row)
                    continue
                started = time.perf_counter()
                try:
                    segments = asyncio.run(transcriber.transcribe_audio_file(str(media)))
                    card = load_knowledge_card(video_id, config) or {}
                    metrics = _transcript_metrics(segments, card.get("transcript", ""))
                    row.update(
                        status="ok",
                        elapsed_seconds=round(time.perf_counter() - started, 1),
                        detail=(
                            f"segments={metrics['segments']} chars={metrics['transcript_chars']} "
                            f"words={metrics['transcript_words']} "
                            f"similarity={metrics['reference_similarity']} bytes={media.stat().st_size}"
                        ),
                        metrics=metrics,
                    )
                except Exception as exc:
                    row.update(status="error", elapsed_seconds=round(time.perf_counter() - started, 1), detail=type(exc).__name__)
                measurements.append(row)
        finally:
            transcriber.cleanup()
    return measurements


async def _run_llm_async(video_ids: list[str], config: AppConfig, models: list[str]) -> list[dict[str, Any]]:
    measurements: list[dict[str, Any]] = []
    for model in models:
        analyzer = LLMAnalyzer(config=_build_config(config, llm_model=model))
        try:
            for video_id in video_ids:
                card = load_knowledge_card(video_id, config)
                row: dict[str, Any] = {"kind": "llm", "model": model, "video_id": video_id}
                transcript = (card or {}).get("transcript", "") if card else ""
                if not card or not transcript:
                    row.update(status="missing_transcript", detail="knowledge card transcript unavailable")
                    measurements.append(row)
                    continue
                started = time.perf_counter()
                try:
                    analysis = await analyzer.analyze_video(
                        title=card.get("title", video_id),
                        author=card.get("author", ""),
                        transcript=transcript,
                    )
                    row.update(
                        status="ok",
                        elapsed_seconds=round(time.perf_counter() - started, 1),
                        detail=f"chars={len(transcript)} chunked={analysis.get('analysis_chunked', False)}",
                    )
                except Exception as exc:
                    row.update(status="error", elapsed_seconds=round(time.perf_counter() - started, 1), detail=type(exc).__name__)
                measurements.append(row)
        finally:
            await analyzer.close()
    return measurements


def main() -> int:
    parser = argparse.ArgumentParser(description="Read-only ASR/LLM performance benchmark")
    parser.add_argument("--stage", choices=["asr", "llm", "all"], default="asr")
    parser.add_argument("--video-id", action="append", help="Video ID; repeat or use comma-separated values")
    parser.add_argument("--whisper-model", action="append", help="Whisper model; repeat for comparison")
    parser.add_argument("--llm-model", action="append", help="LLM model ID; repeat for comparison")
    parser.add_argument("--include-candidates", action="store_true", help="Include opt-in LLM candidate IDs")
    parser.add_argument("--whisper-profile", choices=["fast", "quality"], default=None)
    parser.add_argument("--output-dir", default=None)
    args = parser.parse_args()

    config = get_config()
    video_ids = _parse_ids(args.video_id)
    whisper_models = args.whisper_model or [config.whisper_model]
    llm_models = args.llm_model or [config.llm_model]
    if args.include_candidates:
        llm_models.extend(model for model in OPTIONAL_LLM_CANDIDATES if model not in llm_models)
    profile = args.whisper_profile or config.whisper_profile
    started_at = _now()
    measurements: list[dict[str, Any]] = []
    if args.stage in ("asr", "all"):
        measurements.extend(_run_asr(video_ids, config, whisper_models, profile))
    if args.stage in ("llm", "all"):
        measurements.extend(asyncio.run(_run_llm_async(video_ids, config, llm_models)))
    results = {
        "started_at": started_at,
        "stage": args.stage,
        "video_ids": video_ids,
        "whisper_profile": profile,
        "measurements": measurements,
    }
    output_dir = Path(args.output_dir or Path(config.logs_dir) / "performance")
    json_path, md_path = _write_results(output_dir, results)
    print(f"BENCHMARK_OK json={json_path} markdown={md_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
