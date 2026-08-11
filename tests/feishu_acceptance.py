"""Single-card real Feishu write and second-round deduplication acceptance."""

import argparse
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import app
from config import get_config


async def _collect(video_ids: list[str], folder_token: str) -> str:
    last_log = ""
    async for logs, _ in app.sync_to_feishu(
        video_ids,
        folder_token,
        get_config(),
        progress=lambda *args, **kwargs: None,
    ):
        last_log = logs
    return last_log


def _value_after(logs: str, marker: str) -> str:
    if marker not in logs:
        raise RuntimeError(f"acceptance marker missing: {marker}\n{logs}")
    return logs.split(marker, 1)[1].splitlines()[0].strip()


async def run(video_ids: list[str]) -> None:
    expected_summary = f"同步完成！成功 {len(video_ids)}/{len(video_ids)} 个文档"
    first_log = await _collect(video_ids, "")
    if "文件夹已创建: " not in first_log and "文件夹已复用: " not in first_log:
        raise RuntimeError(f"first round did not resolve the default folder\n{first_log}")
    if expected_summary not in first_log:
        raise RuntimeError(f"first-round sync did not complete\n{first_log}")

    second_log = await _collect(video_ids, "")
    _value_after(second_log, "文件夹已复用: ")
    if second_log.count("文档已存在 (") != len(video_ids):
        raise RuntimeError(f"second round did not deduplicate every document\n{second_log}")
    if expected_summary not in second_log:
        raise RuntimeError(f"second-round sync did not complete\n{second_log}")

    print("FIRST_LOG_BEGIN")
    print(first_log, end="")
    print("FIRST_LOG_END")
    print("SECOND_LOG_BEGIN")
    print(second_log, end="")
    print("SECOND_LOG_END")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video-id", required=True, action="append")
    args = parser.parse_args()
    asyncio.run(run(args.video_id))


if __name__ == "__main__":
    main()
