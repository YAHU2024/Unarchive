"""
Unarchive CLI — 命令行入口

用法:
  python cli.py login douyin              # 登录平台
  python cli.py list-favorites douyin     # 列出收藏夹
  python cli.py process douyin --folder-id X --max 10  # 处理收藏夹
  python cli.py download BV123456789      # 下载视频
  python cli.py sync --to-feishu          # 同步到飞书
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import sys
from pathlib import Path

from config import get_config
from src.scraper import BilibiliScraper, DouyinScraper

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("cli")


def _create_scraper(platform: str):
    if platform.lower() == "bilibili":
        return BilibiliScraper(cookie_path="data/cookies/bilibili.json")
    elif platform.lower() in ("douyin", "抖音"):
        return DouyinScraper()
    else:
        raise ValueError(f"不支持的平台: {platform}")


async def cmd_login(args):
    scraper = _create_scraper(args.platform)
    try:
        await scraper.login()
        print(f"✅ {args.platform} 登录成功")
    finally:
        await scraper.close()


async def cmd_list_favorites(args):
    scraper = _create_scraper(args.platform)
    try:
        await scraper.login()
        folders = await scraper.get_favorites()
        for f in folders:
            print(f"  [{f.folder_id}] {f.title} — {f.video_count} 个视频")
        print(f"\n共 {len(folders)} 个收藏夹")
    finally:
        await scraper.close()


async def cmd_process(args):
    import json
    from src.transcript import SubtitleParser, WhisperTranscriber, get_transcript
    from src.analyzer.llm_analyzer import LLMAnalyzer

    config = get_config()
    scraper = _create_scraper(args.platform)
    analyzer = LLMAnalyzer(config=config)
    whisper = WhisperTranscriber(model_name=config.whisper_model) if not args.no_whisper else None

    try:
        await scraper.login()
        print(f"✅ {args.platform} 登录成功")

        videos = await scraper.get_favorite_videos(args.folder_id)
        if args.max_videos > 0:
            videos = videos[:args.max_videos]
        print(f"获取到 {len(videos)} 个视频")

        output_dir = Path(config.knowledge_base_dir)
        output_dir.mkdir(parents=True, exist_ok=True)

        for i, video in enumerate(videos):
            print(f"\n[{i+1}/{len(videos)}] {video.title}")

            output_path = output_dir / f"{video.video_id}.json"
            if output_path.exists() and not args.force:
                print(f"  ↳ 已存在，跳过")
                continue

            # 获取逐字稿
            try:
                segments, source = await get_transcript(
                    video.video_id, scraper, whisper,
                    download_headers=await scraper.get_audio_cookies(),
                )
                transcript_text = SubtitleParser.segments_to_text(segments)
                print(f"  ↳ 逐字稿: {source}, {len(segments)} 条片段")
            except Exception as e:
                print(f"  ✗ 逐字稿失败: {e}")
                continue

            # LLM 分析
            try:
                analysis = await analyzer.analyze_video(
                    title=video.title, author=video.author, transcript=transcript_text,
                )
                print(f"  ↳ LLM 分析完成")
            except Exception as e:
                print(f"  ✗ LLM 分析失败: {e}")
                continue

            # 保存
            card = {
                "video_id": video.video_id, "title": video.title,
                "author": video.author, "source_url": video.url,
                "platform": args.platform, "transcript_source": source,
                "transcript": transcript_text, **analysis,
            }
            with open(output_path, "w", encoding="utf-8") as f:
                json.dump(card, f, ensure_ascii=False, indent=2)
            print(f"  ✅ 已保存")

        print(f"\n完成！")
    finally:
        await analyzer.close()
        if whisper:
            whisper.cleanup()
        await scraper.close()


async def cmd_download(args):
    scraper = _create_scraper(args.platform)
    try:
        await scraper.login()
        output_path = args.output or f"data/videos/{args.video_id}.mp4"
        Path(output_path).parent.mkdir(parents=True, exist_ok=True)
        result = await scraper.download_video(args.video_id, output_path)
        if result:
            print(f"✅ 下载完成: {output_path}")
        else:
            print(f"❌ 下载失败")
    finally:
        await scraper.close()


async def cmd_sync(args):
    from src.sync.feishu import FeishuSync
    import json

    config = get_config()
    if not config.feishu_app_id or not config.feishu_app_secret:
        print("❌ 请先配置飞书 App ID 和 App Secret")
        return

    feishu = FeishuSync(app_id=config.feishu_app_id, app_secret=config.feishu_app_secret)
    try:
        ok = await feishu.connect()
        if not ok:
            print("❌ 飞书连接失败")
            return
        print("✅ 飞书连接成功")

        kb_dir = Path(config.knowledge_base_dir)
        cards = []
        for fp in kb_dir.glob("*.json"):
            with open(fp, "r", encoding="utf-8") as f:
                cards.append(json.load(f))

        folder_token = args.folder_token or await feishu.create_folder("视频知识库")
        print(f"目标文件夹: {folder_token}")

        for i, card in enumerate(cards):
            vid = card.get("video_id", "")
            title = card.get("title", "未知")
            existing = await feishu.check_document_exists(vid, folder_token)
            if existing:
                print(f"[{i+1}/{len(cards)}] {title} — 已存在，跳过")
                continue
            doc_id = await feishu.create_document(
                title=f"[{vid}] {title}", content=card, folder_id=folder_token,
            )
            print(f"[{i+1}/{len(cards)}] {title} → {doc_id}")

        print(f"\n同步完成！")
    finally:
        await feishu.close()


def main():
    parser = argparse.ArgumentParser(description="Unarchive CLI")
    sub = parser.add_subparsers(dest="command")

    # login
    p = sub.add_parser("login")
    p.add_argument("platform", choices=["bilibili", "douyin"])

    # list-favorites
    p = sub.add_parser("list-favorites")
    p.add_argument("platform", choices=["bilibili", "douyin"])

    # process
    p = sub.add_parser("process")
    p.add_argument("platform", choices=["bilibili", "douyin"])
    p.add_argument("--folder-id", required=True)
    p.add_argument("--max-videos", type=int, default=20)
    p.add_argument("--no-whisper", action="store_true")
    p.add_argument("--force", action="store_true", help="强制重新处理已有卡片")

    # download
    p = sub.add_parser("download")
    p.add_argument("video_id")
    p.add_argument("--platform", default="bilibili")
    p.add_argument("--output")

    # sync
    p = sub.add_parser("sync")
    p.add_argument("--to-feishu", action="store_true")
    p.add_argument("--folder-token")

    args = parser.parse_args()
    if not args.command:
        parser.print_help()
        return

    # 清除系统代理
    import os
    for key in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
        os.environ.pop(key, None)

    cmd_map = {
        "login": cmd_login,
        "list-favorites": cmd_list_favorites,
        "process": cmd_process,
        "download": cmd_download,
        "sync": cmd_sync,
    }
    asyncio.run(cmd_map[args.command](args))


if __name__ == "__main__":
    main()
