"""Real Douyin media download acceptance using an automatically cleaned file.

Usage:
  python tests/douyin_media_acceptance.py --video-id VIDEO_ID
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from cli import _create_scraper


async def run(video_id: str) -> None:
    scraper = _create_scraper("douyin")
    try:
        await scraper.login()
        media_url = await scraper.get_video_audio_url(video_id)
        if not media_url:
            raise RuntimeError("Douyin did not return a full-audio media URL")

        with tempfile.TemporaryDirectory(prefix="unarchive-douyin-") as temp_dir:
            output = Path(temp_dir) / f"{video_id}.mp4"
            started = time.monotonic()
            downloaded = await scraper.download_audio_to_file(
                media_url, str(output)
            )
            elapsed = time.monotonic() - started
            if not downloaded or not output.exists() or output.stat().st_size == 0:
                raise RuntimeError("Douyin media download produced no content")
            print(
                f"MEDIA_DOWNLOAD_OK bytes={output.stat().st_size} "
                f"elapsed={elapsed:.1f}s"
            )
    finally:
        await scraper.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video-id", required=True)
    args = parser.parse_args()
    asyncio.run(run(args.video_id))


if __name__ == "__main__":
    main()
