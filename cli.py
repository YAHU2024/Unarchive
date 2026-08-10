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
import json
import logging
import sys
from pathlib import Path

from config import get_config
from src.knowledge_store import load_knowledge_card, save_knowledge_card
from src.scraper import BilibiliScraper, DouyinScraper
from src.scraper.base import VideoAvailability
from src.sync.ima_state import (
    get_ima_state_path,
    kb_state_decision,
    load_ima_sync_state,
    save_ima_sync_state,
    state_key,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("cli")


def _create_scraper(platform: str):
    config = get_config()
    if platform.lower() == "bilibili":
        return BilibiliScraper(cookie_path=str(Path(config.cookies_dir) / "bilibili.json"))
    elif platform.lower() in ("douyin", "抖音"):
        return DouyinScraper(
            cookie_path=str(Path(config.cookies_dir) / "douyin.json"),
            chrome_profile_dir=config.chrome_profile_dir,
        )
    else:
        raise ValueError(f"不支持的平台: {platform}")


def _load_ima_state_for_cli(state_file: Path) -> dict:
    """Load per-(video, kb) ima sync state for the CLI.

    Thin wrapper around the shared ``load_ima_sync_state`` so GUI and CLI
    agree on the on-disk format. Kept as a separate name for backward
    compat with tests that import it directly.
    """
    return load_ima_sync_state(state_file)





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
    from src.transcript import SubtitleParser, WhisperTranscriber, get_transcript
    from src.analyzer.llm_analyzer import LLMAnalyzer, LLMQuotaExceededError

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

        for i, video in enumerate(videos):
            print(f"\n[{i+1}/{len(videos)}] {video.title}")

            existing = load_knowledge_card(video.video_id, config)
            resume_llm = False
            preserve_complete_card = bool(existing and not existing.get("_partial"))
            transcript_segments = []

            if existing and not args.force:
                if existing.get("_partial"):
                    resume_llm = True
                    transcript_text = existing.get("transcript", "")
                    transcript_source = existing.get("transcript_source", "unknown")
                    transcript_segments = existing.get("transcript_segments", [])
                    real_author = existing.get("author", video.author)
                    print("  ↳ 检测到部分知识卡片，跳过转录并恢复 LLM 分析")
                else:
                    print(f"  ↳ 已存在，跳过")
                    continue

            if not resume_llm:
                availability = await scraper.check_video_available(video.video_id)
                if availability == VideoAvailability.UNAVAILABLE:
                    print("  ↳ 视频不可用（已删除/下架/私密），永久跳过")
                    continue
                if availability == VideoAvailability.TEMPORARY_ERROR:
                    print("  ↳ 视频可用性检查临时失败，跳过本次并保留重试机会")
                    continue

                # 获取逐字稿
                try:
                    segments, source = await get_transcript(
                        video.video_id, scraper, whisper,
                        download_headers=await scraper.get_audio_cookies(),
                    )
                    transcript_text = SubtitleParser.segments_to_text(segments)
                    transcript_source = source
                    transcript_segments = SubtitleParser.segments_to_records(segments)
                    print(f"  ↳ 逐字稿: {source}, {len(segments)} 条片段")
                except Exception as e:
                    print(f"  ✗ 逐字稿失败: {e}")
                    continue

                real_author = video.author
                owner = await scraper.get_video_owner(video.video_id)
                if owner:
                    real_author = owner

            # LLM 分析
            try:
                analysis = await analyzer.analyze_video(
                    title=video.title, author=real_author, transcript=transcript_text,
                )
                print(f"  ↳ LLM 分析完成")
            except LLMQuotaExceededError as e:
                if preserve_complete_card:
                    print(f"  ✗ LLM 配额耗尽，保留原有完整卡片: {e}")
                else:
                    partial = {
                        "video_id": video.video_id,
                        "title": video.title,
                        "author": real_author,
                        "source_url": video.url,
                        "platform": args.platform,
                        "transcript_source": transcript_source,
                        "transcript": transcript_text,
                        "transcript_segments": transcript_segments,
                        "_partial": True,
                    }
                    save_knowledge_card(video.video_id, partial, config)
                    print(f"  ✗ LLM 配额耗尽，已保存部分卡片: {e}")
                print("  ↳ 停止处理后续视频")
                break
            except Exception as e:
                if preserve_complete_card:
                    print(f"  ✗ LLM 分析失败，保留原有完整卡片: {e}")
                else:
                    partial = {
                        "video_id": video.video_id,
                        "title": video.title,
                        "author": real_author,
                        "source_url": video.url,
                        "platform": args.platform,
                        "transcript_source": transcript_source,
                        "transcript": transcript_text,
                        "transcript_segments": transcript_segments,
                        "_partial": True,
                    }
                    save_knowledge_card(video.video_id, partial, config)
                    print(f"  ✗ LLM 分析失败，已保存部分卡片: {e}")
                continue

            # 保存
            card = {
                "video_id": video.video_id, "title": video.title,
                "author": real_author, "source_url": video.url,
                "platform": args.platform, "transcript_source": transcript_source,
                "transcript": transcript_text,
                "transcript_segments": transcript_segments,
                **analysis,
            }
            save_knowledge_card(video.video_id, card, config)
            print(f"  ✅ 已保存")

        print(f"\n完成！")
    finally:
        await analyzer.close()
        if whisper:
            whisper.cleanup()
        await scraper.close()


async def cmd_download(args):
    config = get_config()
    scraper = _create_scraper(args.platform)
    try:
        await scraper.login()
        output_path = args.output or str(Path(config.video_download_dir) / f"{args.video_id}.mp4")
        Path(output_path).parent.mkdir(parents=True, exist_ok=True)
        result = await scraper.download_video(args.video_id, output_path)
        if result:
            print(f"✅ 下载完成: {output_path}")
        else:
            print(f"❌ 下载失败")
    finally:
        await scraper.close()


async def cmd_sync(args):
    import json

    config = get_config()

    if args.to_ima:
        await _cmd_sync_ima(config, args)
        return

    from src.sync.feishu import FeishuSync
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


async def _cmd_sync_ima(config, args):
    """同步知识卡片到腾讯 ima（建笔记 + 可选加入知识库）"""
    from src.sync.ima import ImaSync, ImaQuotaExceededError
    from datetime import datetime as _dt

    if not config.ima_client_id or not config.ima_api_key:
        print("请先配置 ima Client ID 和 API Key")
        print("   环境变量: IMA_CLIENT_ID / IMA_API_KEY，或写入 config.py / .env")
        return

    knowledge_base_id = args.ima_knowledge_base_id or config.ima_knowledge_base_id
    ima = ImaSync(
        client_id=config.ima_client_id,
        api_key=config.ima_api_key,
        knowledge_base_id=knowledge_base_id,
    )

    # Resolve the state file from AppConfig.data_dir so a custom DATA_DIR
    # (or per-workspace .env) keeps the state colocated with the knowledge
    # cards. GUI and CLI both go through the same helper.
    _ima_state_file = get_ima_state_path(config)
    ima_sync_state = _load_ima_state_for_cli(_ima_state_file)

    def _decide(vid: str, kb: str, folder: str) -> str:
        """Decide what to do when an existing note is found.
        Thin wrapper over the shared kb_state_decision so the CLI/UI agree.
        Returns one of "skip" / "attempt_kb" / "retry_kb" / "retry_folder".
        """
        return kb_state_decision(ima_sync_state, vid, kb or "", folder or "")

    def _save_state():
        save_ima_sync_state(_ima_state_file, ima_sync_state)

    try:
        ok = await ima.connect()
        if not ok:
            print("ima 连接失败，请检查 Client ID / API Key")
            return
        print("ima 连接成功")

        kb_dir = Path(config.knowledge_base_dir)
        cards = []
        for fp in kb_dir.glob("*.json"):
            with open(fp, "r", encoding="utf-8") as f:
                cards.append(json.load(f))

        folder_id = args.ima_folder_id or await ima.create_folder("视频知识库")
        if folder_id:
            print(f"目标笔记本: {folder_id}")

        # 解析知识库目标文件夹（优先 id，否则按名）
        kb_folder_id = ""
        if knowledge_base_id:
            fid = args.ima_knowledge_base_folder_id or config.ima_knowledge_base_folder_id
            fnam = args.ima_knowledge_base_folder_name or config.ima_knowledge_base_folder_name
            kb_folder_id = await ima.resolve_kb_folder(knowledge_base_id, fid, fnam)
            if kb_folder_id:
                print(f"目标知识库文件夹: {kb_folder_id}")

        synced = 0
        kb_added = 0
        kb_failed = 0
        kb_retried = 0
        for i, card in enumerate(cards):
            vid = card.get("video_id", "")
            title = card.get("title", "未知")
            existing = await ima.check_document_exists(vid)
            if existing:
                kb_active = bool(knowledge_base_id) and bool(ima.knowledge_base_id)
                composite_key = state_key(vid, knowledge_base_id or "")
                decision = _decide(vid, knowledge_base_id or "", kb_folder_id)

                if decision == "skip":
                    print(f"[{i+1}/{len(cards)}] {title} — 已存在，跳过")
                    synced += 1
                    continue

                if not kb_active:
                    print(f"[{i+1}/{len(cards)}] {title} — 已存在，跳过")
                    synced += 1
                    continue

                # One of: attempt_kb / retry_kb / retry_folder
                if decision == "attempt_kb":
                    print(f"[{i+1}/{len(cards)}] {title} — 已存在，未关联过该知识库，尝试关联...")
                elif decision == "retry_folder":
                    prev_folder = (
                        ima_sync_state.get(composite_key, {}).get("kb_folder_id", "") or ""
                    )
                    print(
                        f"[{i+1}/{len(cards)}] {title} — 已存在，目标文件夹不同"
                        f"（{prev_folder or '根'} → {kb_folder_id or '根'}），重新关联..."
                    )
                else:
                    print(f"[{i+1}/{len(cards)}] {title} — 已存在，重试知识库关联...")
                try:
                    await ima.add_to_knowledge_base(
                        existing, f"[{vid}] {title}", kb_folder_id)
                    kb_added += 1
                    kb_retried += 1
                    synced += 1
                    print(f"  ↳ 知识库关联成功: {existing}")
                    ima_sync_state[composite_key] = {
                        "note_id": existing,
                        "knowledge_base_id": knowledge_base_id,
                        "kb_added": True, "kb_error": "",
                        "kb_folder_id": kb_folder_id,
                        "synced_at": _dt.now().isoformat(),
                    }
                    _save_state()
                except Exception as e:
                    kb_failed += 1
                    print(f"  ↳ 知识库关联失败: {e}")
                    ima_sync_state[composite_key] = {
                        "note_id": existing,
                        "knowledge_base_id": knowledge_base_id,
                        "kb_added": False, "kb_error": str(e),
                        "kb_folder_id": kb_folder_id,
                        "synced_at": _dt.now().isoformat(),
                    }
                    _save_state()
                continue
            try:
                note_id = await ima.create_document(
                    title=f"[{vid}] {title}", content=card, folder_id=folder_id,
                )
            except ImaQuotaExceededError as e:
                # import_doc 阶段就已耗尽：没有笔记被创建，无需持久化部分成功状态。
                print(f"[{i+1}/{len(cards)}] {title} — ima 配额已耗尽，停止同步: {e}")
                break
            synced += 1
            # 笔记创建成功。立即把 note_id + kb_added=False 写入状态，
            # 确保后续 KB 关联失败（任何原因）都不会让这条笔记孤立。
            composite_key = state_key(vid, knowledge_base_id or "")
            ima_sync_state[composite_key] = {
                "note_id": note_id,
                "knowledge_base_id": knowledge_base_id or "",
                "kb_added": False,
                "kb_error": "",
                "kb_folder_id": kb_folder_id,
                "synced_at": _dt.now().isoformat(),
            }
            _save_state()

            # 第二步：可选加入知识库
            if knowledge_base_id and ima.knowledge_base_id:
                try:
                    await ima.add_to_knowledge_base(
                        note_id, f"[{vid}] {title}", kb_folder_id)
                    kb_added += 1
                    ima_sync_state[composite_key] = {
                        "note_id": note_id,
                        "knowledge_base_id": knowledge_base_id,
                        "kb_added": True, "kb_error": "",
                        "kb_folder_id": kb_folder_id,
                        "synced_at": _dt.now().isoformat(),
                    }
                    _save_state()
                    print(f"[{i+1}/{len(cards)}] {title} → {note_id} + 知识库")
                except ImaQuotaExceededError as e:
                    # 配额耗尽：note_id 已在 ima_sync_state 里持久化（kb_added=False），
                    # 下次运行通过 check_document_exists + add_to_knowledge_base 恢复。
                    print(f"[{i+1}/{len(cards)}] {title} → {note_id} (知识库关联失败：ima 配额耗尽，停止同步)")
                    ima_sync_state[composite_key] = {
                        "note_id": note_id,
                        "knowledge_base_id": knowledge_base_id,
                        "kb_added": False,
                        "kb_error": f"ImaQuotaExceededError: {e}",
                        "kb_folder_id": kb_folder_id,
                        "synced_at": _dt.now().isoformat(),
                    }
                    _save_state()
                    print(
                        f"  ↳ 笔记 {note_id} 已创建但未关联知识库，"
                        f"状态已保存，下次运行恢复"
                    )
                    break
                except Exception as e:
                    # 笔记已建成功，KB 关联失败仅记录，不中断整体同步。
                    kb_failed += 1
                    print(f"[{i+1}/{len(cards)}] {title} → {note_id} (知识库关联失败: {e})")
                    ima_sync_state[composite_key] = {
                        "note_id": note_id,
                        "knowledge_base_id": knowledge_base_id,
                        "kb_added": False, "kb_error": str(e),
                        "kb_folder_id": kb_folder_id,
                        "synced_at": _dt.now().isoformat(),
                    }
                    _save_state()
            else:
                print(f"[{i+1}/{len(cards)}] {title} → {note_id}")

        if knowledge_base_id:
            retry_op = f"，恢复 {kb_retried}" if kb_retried else ""
            kb_summary = (
                f"（笔记 {synced}/{len(cards)}，知识库关联成功 {kb_added}"
                + retry_op
                + (f"，失败 {kb_failed}" if kb_failed else "")
                + "）"
            )
            print(f"\n同步完成！{kb_summary}")
        else:
            print(f"\n同步完成！共 {synced}/{len(cards)} 个笔记")
    finally:
        await ima.close()


async def cmd_ima_kbs(args):
    """列出知识库（或某知识库下的文件夹），辅助发现 ID"""
    from src.sync.ima import ImaSync

    config = get_config()
    if not config.ima_client_id or not config.ima_api_key:
        print("❌ 请先配置 ima Client ID 和 API Key")
        print("   环境变量: IMA_CLIENT_ID / IMA_API_KEY，或写入 config.py / .env")
        return

    ima = ImaSync(client_id=config.ima_client_id, api_key=config.ima_api_key)
    try:
        ok = await ima.connect()
        if not ok:
            print("❌ ima 连接失败，请检查 Client ID / API Key")
            return
        print("✅ ima 连接成功\n")

        if args.folders:
            print(f"知识库 {args.folders} 下的文件夹：")
            folders = await ima.list_folders(args.folders)
            if not folders:
                print("  （根目录下没有子文件夹；留空 IMA_KNOWLEDGE_BASE_FOLDER_ID 即同步到知识库根目录）")
            for f in folders:
                indent = "  " * f.get("depth", 0)
                print(f"  {indent}[{f['folder_id']}] {f['name']}")
            print("\n将目标 folder_id 写入 .env 的 IMA_KNOWLEDGE_BASE_FOLDER_ID")
        else:
            print("你的知识库列表（可添加内容）：")
            kbs = await ima.list_addable_knowledge_bases(search=args.search or "")
            if not kbs:
                print("  （未发现任何知识库）")
                print("\n说明：ima OpenAPI 暂不支持通过接口创建知识库。")
                print("  请先在 ima 客户端（桌面端 / 网页端）新建一个知识库，")
                print("  然后再运行此命令即可发现其 ID。")
            else:
                print(f"  共 {len(kbs)} 个知识库：")
                for i, kb in enumerate(kbs, 1):
                    desc = kb.get("description") or ""
                    line = f"  {i}. [{kb['id']}] {kb['name']}"
                    if desc:
                        line += f" — {desc}"
                    print(line)
                print()
                if len(kbs) == 1:
                    print(f"  你当前只有 1 个知识库，将上面 id 写入即可：")
                    print(f"  IMA_KNOWLEDGE_BASE_ID={kbs[0]['id']}")
                else:
                    print("  将目标 id 写入 .env 的 IMA_KNOWLEDGE_BASE_ID")
                print("  用 python cli.py ima-kbs --folders <知识库ID> 查看其文件夹")
                if not args.search:
                    print("  （加 --search <关键词> 可按名称搜索更多知识库）")
    finally:
        await ima.close()


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
    p.add_argument("--to-ima", action="store_true", help="同步到腾讯 ima")
    p.add_argument("--ima-folder-id", help="ima 目标笔记本 ID（留空则按名称解析/根目录）")
    p.add_argument("--ima-knowledge-base-id", help="ima 知识库 ID（覆盖配置，加入知识库）")
    p.add_argument("--ima-knowledge-base-folder-id", help="ima 知识库目标文件夹 ID（覆盖配置）")
    p.add_argument("--ima-knowledge-base-folder-name", help="ima 知识库目标文件夹名称（按名解析，覆盖配置）")

    # ima-kbs
    p = sub.add_parser("ima-kbs", help="列出 ima 知识库 / 文件夹，辅助发现 ID")
    p.add_argument("--folders", help="知识库 ID，列出其下的文件夹")
    p.add_argument("--search", help="按名称搜索知识库（并集到可添加列表结果）")

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
        "ima-kbs": cmd_ima_kbs,
    }
    asyncio.run(cmd_map[args.command](args))


if __name__ == "__main__":
    main()
