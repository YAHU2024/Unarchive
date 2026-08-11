"""Read-only helper for selecting real platform acceptance samples.

This script never downloads media, calls the LLM, or writes knowledge cards.
It checks login, enumerates one favorite folder, and reports which transcript
path each candidate would use.

Usage:
  python tests/platform_acceptance.py bilibili --folder-id FOLDER_ID --limit 10
  python tests/platform_acceptance.py douyin --folder-id FOLDER_ID --limit 5
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from cli import _create_scraper
from config import get_config
from src.knowledge_store import load_knowledge_card
from src.scraper.base import VideoAvailability


async def probe(platform: str, folder_id: str, limit: int, offset: int = 0) -> int:
    config = get_config()
    scraper = _create_scraper(platform)
    results = []
    try:
        await scraper.login()
        videos = await scraper.get_favorite_videos(folder_id)
        for video in videos[offset:offset + limit]:
            availability = await scraper.check_video_available(video.video_id)
            transcript_path = "not_checked"
            segment_count = 0
            error = ""

            if availability == VideoAvailability.AVAILABLE:
                try:
                    segments = await scraper.get_video_subtitle(video.video_id)
                    if segments:
                        transcript_path = "subtitle"
                        segment_count = len(segments)
                    else:
                        ai_summary = await scraper.get_video_ai_summary(video.video_id)
                        transcript_path = "ai_summary" if ai_summary else "whisper_required"
                except Exception as exc:
                    transcript_path = "probe_error"
                    error = str(exc)

            results.append({
                "video_id": video.video_id,
                "title": video.title,
                "duration_seconds": video.duration,
                "availability": availability.name,
                "transcript_path": transcript_path,
                "segment_count": segment_count,
                "existing_card": load_knowledge_card(video.video_id, config) is not None,
                "error": error,
            })
    finally:
        await scraper.close()

    print(json.dumps(results, ensure_ascii=False, indent=2))
    return 0 if results else 2


def main() -> int:
    parser = argparse.ArgumentParser(description="Probe real platform acceptance samples")
    parser.add_argument("platform", choices=["bilibili", "douyin"])
    parser.add_argument("--folder-id", required=True)
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--offset", type=int, default=0)
    args = parser.parse_args()
    return asyncio.run(probe(args.platform, args.folder_id, args.limit, args.offset))


if __name__ == "__main__":
    raise SystemExit(main())
