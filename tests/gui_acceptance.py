"""Run the Gradio processing generator against one real folder.

This exercises the same backend generator used by the UI without requiring
manual clicking. It intentionally uses a subtitle-only sample so the check is
bounded and does not initialize Whisper.
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import app
from config import get_config


async def main() -> None:
    updates = 0
    last_logs = ""
    async for _results, logs, current, _progress in app.process_videos(
        platform="Bilibili",
        folder_ids=["3622515022"],
        whisper_enabled=False,
        whisper_model="small",
        max_videos=1,
        config=get_config(),
    ):
        updates += 1
        last_logs = logs
    print(f"GUI_PIPELINE_UPDATES={updates}")
    print(f"GUI_PIPELINE_CURRENT={current}")
    print(last_logs[-2000:])


if __name__ == "__main__":
    asyncio.run(main())
