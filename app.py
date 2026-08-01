"""
视频收藏夹同步工具 - Gradio 主入口

功能：
- 同步 B站/抖音 收藏视频
- 语音转文字（Whisper）
- LLM 内容分析
- 飞书文档同步
"""

from __future__ import annotations

import json
import logging
import os
import time
from pathlib import Path
from typing import Optional

import gradio as gr

from config import AppConfig, get_config
from src.scraper.base import VideoAvailability
from src.scraper.bilibili import BilibiliScraper
from src.scraper.douyin import DouyinScraper
from src.transcript import SubtitleParser, WhisperTranscriber, get_transcript
from src.analyzer.llm_analyzer import LLMAnalyzer
from src.sync.feishu import FeishuSync
from src.sync.ima import ImaSync, ImaQuotaExceededError

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# 全局常量
# ---------------------------------------------------------------------------
PLATFORM_CHOICES = ["Bilibili", "抖音"]
WHISPER_MODEL_CHOICES = [
    "tiny", "base", "small", "medium",
    "large-v1", "large-v2", "large-v3",
    "tiny.en", "base.en", "small.en", "medium.en", "large-v3.en",
    "distil-small.en", "distil-medium.en",
    "distil-large-v2", "distil-large-v3",
]

# ima local sync state: tracks per-(video, knowledge_base) note association
# status so that notes with kb_added=False can be retried on the next sync
# without re-creating the note.
#
# Schema: keyed by f"{video_id}::{knowledge_base_id or ''}", so each entry
# is the KB-association status for ONE (video, knowledge_base) pair. This
# isolates cross-KB retries — same video synced to KB-A then to KB-B does
# NOT see stale kb_added=True from KB-A and is correctly re-attempted for
# KB-B. State value carries the resolved kb_folder_id so a folder change
# between runs is also detected and triggers a re-attempt.
from datetime import datetime as _dt
_IMA_SYNC_STATE_FILE = Path("data/ima_sync_state.json")
_IMA_STATE_KEY_SEP = "::"


def _state_key(video_id: str, knowledge_base_id: str) -> str:
    """Build the (vid, kb_id) composite state key.

    An empty KB targets the "no KB" bucket for note-only syncs.
    """
    return f"{video_id}{_IMA_STATE_KEY_SEP}{knowledge_base_id or ''}"


def _parse_state_key(key: str) -> tuple[str, str]:
    """Inverse of _state_key for legacy migration. Tolerates old 'vid'-only
    keys by returning ('vid', '')."""
    if _IMA_STATE_KEY_SEP in key:
        vid, _, kb = key.partition(_IMA_STATE_KEY_SEP)
        return vid, kb
    return key, ""


def _kb_state_decision(
    state: dict,
    vid: str,
    knowledge_base_id: str,
    resolved_folder_id: str,
) -> str:
    """Decide what to do when an ima note already exists for `vid`.

    Returns one of:
      "skip"          — note present and KB-association for (vid, kb_id)
                        matches the current target config; idempotent hit.
      "retry_kb"      — prior KB-association for (vid, kb_id) failed
                        (kb_added=False); retry add_to_knowledge_base.
      "retry_folder"  — KB link exists for (vid, kb_id) but the requested
                        folder differs from the one recorded in state;
                        re-attempt so the new folder wins.
      "attempt_kb"    — no state entry for (vid, kb_id). Either legacy
                        (no prior state at all) or cross-KB (a different
                        kb_id has an entry but this one does not); in both
                        cases we should attempt add_to_knowledge_base for
                        this KB instead of silently skipping.
    """
    if not knowledge_base_id:
        # No KB configured → nothing to associate; always skip (record-only
        # callers handle state persistence themselves).
        return "skip"

    key = _state_key(vid, knowledge_base_id)
    st = state.get(key)

    if not st:
        return "attempt_kb"

    prev_kb_added = bool(st.get("kb_added"))
    prev_folder = st.get("kb_folder_id", "") or ""

    if prev_kb_added and prev_folder == (resolved_folder_id or ""):
        return "skip"
    if prev_kb_added:
        return "retry_folder"
    return "retry_kb"


def _load_ima_sync_state() -> dict:
    """Load per-(video, kb) ima sync state from disk.

    Returns {} if the file is missing/corrupt. Performs a one-time migration
    of legacy `vid`-only keys (which lacked KB isolation) by promoting each
    old entry to f"{vid}::{knowledge_base_id_or_empty}" using the kb_id
    embedded in the legacy entry value.
    """
    raw: dict = {}
    try:
        if _IMA_SYNC_STATE_FILE.exists():
            raw = json.loads(_IMA_SYNC_STATE_FILE.read_text(encoding="utf-8"))
    except Exception as e:
        logger.warning("Failed to load ima sync state: %s", e)
        return {}

    migrated: dict = {}
    needs_resave = False
    for key, value in raw.items():
        if not isinstance(value, dict):
            # drop malformed entries silently
            needs_resave = True
            continue
        if _IMA_STATE_KEY_SEP in key:
            migrated[key] = value
            continue
        # Legacy key — promote using embedded knowledge_base_id
        legacy_kb = value.get("knowledge_base_id", "") or ""
        new_key = _state_key(key, legacy_kb)
        # Preserve folder id if already recorded under old schema
        if "kb_folder_id" in value and "kb_folder_id" not in migrated.get(new_key, {}):
            migrated.setdefault(new_key, value)
        else:
            migrated[new_key] = value
        needs_resave = True
        logger.info(
            "ima sync state: migrated legacy key %r -> %r", key, new_key
        )

    if needs_resave:
        try:
            _save_ima_sync_state(migrated)
        except Exception as e:
            logger.warning("Failed to persist migrated ima sync state: %s", e)
    return migrated


def _save_ima_sync_state(state: dict) -> None:
    """Persist per-video ima sync state to disk (atomic write)."""
    _IMA_SYNC_STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = _IMA_SYNC_STATE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(_IMA_SYNC_STATE_FILE)

# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------

def _kb_dir() -> Path:
    """获取知识库目录（从 AppConfig 读取，确保 GUI/CLI 共用同一目录）"""
    from config import get_config
    p = Path(get_config().knowledge_base_dir)
    p.mkdir(parents=True, exist_ok=True)
    return p


def _save_knowledge_card(video_id: str, data: dict) -> Path:
    """保存知识卡片为 JSON 文件"""
    path = _kb_dir() / f"{video_id}.json"
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    return path


def _load_knowledge_card(video_id: str) -> Optional[dict]:
    """加载知识卡片 JSON"""
    path = _kb_dir() / f"{video_id}.json"
    if path.exists():
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    return None


def _list_knowledge_cards() -> list[dict]:
    """列出所有已保存的知识卡片"""
    cards = []
    for fp in _kb_dir().glob("*.json"):
        try:
            with open(fp, "r", encoding="utf-8") as f:
                cards.append(json.load(f))
        except Exception:
            continue
    return cards


def _create_scraper(platform: str) -> object:
    """根据平台名称创建 Scraper 实例"""
    if platform == "Bilibili":
        return BilibiliScraper(cookie_path="data/cookies/bilibili.json")
    elif platform == "抖音":
        return DouyinScraper()
    else:
        raise ValueError(f"不支持的平台: {platform}")


def _format_log(msg: str) -> str:
    """格式化日志消息（带时间戳）"""
    from datetime import datetime
    ts = datetime.now().strftime("%H:%M:%S")
    return f"[{ts}] {msg}\n"


def _choice_value(v):
    """从下拉选项的返回值中取出真正的 value。

    Gradio 6.0 在 allow_custom_value=True + 字典 choices 下，.change 事件回传的是
    整个 choice dict（{"label":..., "value":...}）而非 value 字段。这里统一归一化：
    dict → 取其 value；其余原样返回（None/"" 保持空）。
    """
    if isinstance(v, dict):
        return v.get("value", "")
    return v or ""


# ---------------------------------------------------------------------------
# 核心处理管道
# ---------------------------------------------------------------------------

async def process_videos(
    platform: str,
    folder_ids: list[str],
    whisper_enabled: bool,
    whisper_model: str,
    max_videos: int,
    config: AppConfig,
):
    """处理选中收藏夹中的视频"""
    logs = ""
    results = []
    total_processed = 0
    analyzer = None
    whisper_transcriber = None
    current = "等待开始..."

    # 统计
    stats = {"skipped": 0, "transcript_fail": 0, "llm_fail": 0, "unavailable": 0}
    t_start = time.time()

    try:
        # 1. 登录
        logs += _format_log(f"正在登录 {platform}...")
        current = f"正在登录 {platform}..."
        yield results, logs, current, gr.update(value=0, label="进度")

        t0 = time.time()
        scraper = _create_scraper(platform)
        await scraper.login()
        logs += _format_log(f"{platform} 登录成功 (耗时 {time.time() - t0:.1f}s)")
        current = f"{platform} 登录成功"
        yield results, logs, current, gr.update()

        # 2. 获取收藏夹视频
        all_videos = []
        seen_video_ids: set[str] = set()
        for fid in folder_ids:
            t0 = time.time()
            logs += _format_log(f"正在获取收藏夹 {fid} 的视频列表...")
            current = f"正在获取收藏夹: {fid}"
            yield results, logs, current, gr.update()
            videos = await scraper.get_favorite_videos(fid)
            new_count = 0
            for v in videos:
                if v.video_id not in seen_video_ids:
                    seen_video_ids.add(v.video_id)
                    all_videos.append(v)
                    new_count += 1
            dup_count = len(videos) - new_count
            dup_msg = f" (去重 {dup_count} 个)" if dup_count else ""
            logs += _format_log(f"获取到 {len(videos)} 个视频{dup_msg} (耗时 {time.time() - t0:.1f}s)")
            yield results, logs, current, gr.update()

        if max_videos > 0:
            all_videos = all_videos[:max_videos]

        total = len(all_videos)
        if total == 0:
            logs += _format_log("没有需要处理的视频")
            current = "没有需要处理的视频"
            yield results, logs, current, gr.update(value=0, maximum=1, label="进度 0/0")
            return

        logs += _format_log(f"共 {total} 个视频待处理")
        current = f"共 {total} 个视频待处理"
        yield results, logs, current, gr.update()

        # 3. Whisper
        whisper_transcriber = None
        if whisper_enabled:
            t0 = time.time()
            logs += _format_log(f"初始化 Whisper 模型: {whisper_model}")
            current = f"正在加载 Whisper 模型: {whisper_model}"
            yield results, logs, current, gr.update()
            whisper_transcriber = WhisperTranscriber(model_name=whisper_model)
            logs += _format_log(f"Whisper 模型 {whisper_model} 加载完成 (耗时 {time.time() - t0:.1f}s)")
            yield results, logs, current, gr.update()

        # 4. 音频下载头
        t0 = time.time()
        download_headers = await scraper.get_audio_cookies()
        if download_headers:
            logs += _format_log(f"已提取 {platform} Cookie 用于音频下载 (耗时 {time.time() - t0:.1f}s)")
            yield results, logs, current, gr.update()

        # 5. LLM 分析器
        analyzer = LLMAnalyzer(config=config)

        # 6. 处理每个视频
        for idx, video in enumerate(all_videos):
            t_video_start = time.time()
            current = f"[{idx+1}/{total}] {video.title}"
            logs += _format_log(f"[{idx+1}/{total}] 正在处理: {video.title}")
            yield results, logs, current, gr.update(value=idx, maximum=total, label=f"进度 {idx}/{total}")

            existing = _load_knowledge_card(video.video_id)
            if existing:
                logs += _format_log(f"  ↳ 已存在知识卡片，跳过")
                results.append(existing)
                total_processed += 1
                stats["skipped"] += 1
                yield results, logs, current, gr.update()
                continue

            availability = await scraper.check_video_available(video.video_id)
            if availability == VideoAvailability.UNAVAILABLE:
                logs += _format_log(f"  ↳ 视频不可用（已删除/下架/私密），永久跳过")
                stats["unavailable"] += 1
                yield results, logs, current, gr.update()
                continue
            elif availability == VideoAvailability.TEMPORARY_ERROR:
                logs += _format_log(f"  ↳ 视频可用性检查临时失败（网络/风控等），跳过本次，下次重试")
                stats["temp_error"] = stats.get("temp_error", 0) + 1
                yield results, logs, current, gr.update()
                continue

            # a. 逐字稿
            transcript_text = ""
            transcript_source = "unknown"
            try:
                t0 = time.time()
                segments, source = await get_transcript(
                    video.video_id, scraper, whisper_transcriber,
                    download_headers=download_headers,
                )
                transcript_text = SubtitleParser.segments_to_text(segments)
                transcript_source = source
                logs += _format_log(
                    f"  ↳ 逐字稿获取成功 (来源: {source}, {len(segments)} 条片段, "
                    f"耗时 {time.time() - t0:.1f}s)"
                )
            except RuntimeError as e:
                logs += _format_log(f"  ↳ 逐字稿获取失败: {e}")
                stats["transcript_fail"] += 1
                yield results, logs, current, gr.update()
                continue

            # b. 作者修正
            real_author = video.author
            owner = await scraper.get_video_owner(video.video_id)
            if owner:
                if owner != video.author:
                    logs += _format_log(f"  ↳ 作者修正: {video.author!r} → {owner!r}")
                real_author = owner

            # c. LLM 分析
            try:
                t0 = time.time()
                logs += _format_log(f"  ↳ 正在调用 LLM 分析...")
                current = f"[{idx+1}/{total}] LLM 分析中: {video.title}"
                yield results, logs, current, gr.update()
                analysis = await analyzer.analyze_video(
                    title=video.title,
                    author=real_author,
                    transcript=transcript_text,
                )
                logs += _format_log(f"  ↳ LLM 分析完成 (耗时 {time.time() - t0:.1f}s)")
            except Exception as e:
                logs += _format_log(f"  ↳ LLM 分析失败: {e}")
                stats["llm_fail"] += 1
                yield results, logs, current, gr.update()
                continue

            # d. 保存
            card = {
                "video_id": video.video_id,
                "title": video.title,
                "author": real_author,
                "source_url": video.url,
                "platform": platform,
                "transcript_source": transcript_source,
                "transcript": transcript_text,
                **analysis,
            }
            _save_knowledge_card(video.video_id, card)
            results.append(card)
            total_processed += 1
            logs += _format_log(
                f"  ↳ 知识卡片已保存 (视频总耗时 {time.time() - t_video_start:.1f}s)"
            )
            yield results, logs, current, gr.update()

        # 汇总
        elapsed = time.time() - t_start
        temp_error_count = stats.get("temp_error", 0)
        logs += _format_log(
            f"全部完成！处理 {total_processed}/{total} 个 (跳过 {stats['skipped']}、"
            f"不可用 {stats['unavailable']}、临时错误 {temp_error_count}、"
            f"转录失败 {stats['transcript_fail']}、LLM失败 {stats['llm_fail']})，"
            f"总耗时 {elapsed:.0f}s"
        )
        current = f"全部完成！共处理 {total_processed}/{total} 个视频"
        yield results, logs, current, gr.update(value=total, maximum=total, label=f"进度 {total}/{total}")

    except Exception as e:
        logs += _format_log(f"处理出错: {e}")
        logger.exception("处理管道异常")
        current = f"处理出错: {e}"
        yield results, logs, current, gr.update()
    finally:
        if analyzer:
            try:
                await analyzer.close()
            except Exception:
                pass
        if whisper_transcriber:
            try:
                whisper_transcriber.cleanup()
            except Exception:
                pass


async def sync_to_feishu(
    video_ids: list[str],
    folder_token: str,
    config: AppConfig,
    progress=gr.Progress(),
):
    """同步知识卡片到飞书文档

    流程：
    1. 初始化 FeishuSync 并获取 token
    2. 创建/获取知识库文件夹
    3. 遍历视频，创建飞书文档
    4. 返回同步日志
    """
    logs = ""
    synced = 0
    feishu_sync = None

    try:
        progress(0, desc="正在连接飞书...")
        logs += _format_log("正在连接飞书...")
        yield logs, gr.update()

        feishu_sync = FeishuSync(app_id=config.feishu_app_id, app_secret=config.feishu_app_secret)
        connected = await feishu_sync.connect()
        if not connected:
            logs += _format_log("飞书连接失败，请检查 App ID 和 App Secret")
            yield logs, gr.update()
            return

        logs += _format_log("飞书连接成功")

        # 创建/获取知识库文件夹
        if not folder_token:
            logs += _format_log("正在创建知识库文件夹...")
            yield logs, gr.update()
            folder_token = await feishu_sync.create_folder("视频知识库")
            logs += _format_log(f"文件夹已创建: {folder_token}")

        total = len(video_ids)
        for idx, vid in enumerate(video_ids):
            progress((idx, total), desc=f"同步中: {vid}")
            logs += _format_log(f"[{idx+1}/{total}] 同步视频: {vid}")
            yield logs, gr.update()

            card = _load_knowledge_card(vid)
            if not card:
                logs += _format_log(f"  ↳ 未找到知识卡片，跳过")
                continue

            # 检查是否已存在
            existing_doc = await feishu_sync.check_document_exists(vid, folder_token)
            if existing_doc:
                logs += _format_log(f"  ↳ 文档已存在 ({existing_doc})，跳过")
                synced += 1
                continue

            # 创建飞书文档
            try:
                doc_id = await feishu_sync.create_document(
                    title=f"[{vid}] {card.get('title', '未知')}",
                    content=card,
                    folder_id=folder_token,
                )
                logs += _format_log(f"  ↳ 文档创建成功: {doc_id}")
                synced += 1
            except Exception as e:
                logs += _format_log(f"  ↳ 文档创建失败: {e}")

            yield logs, gr.update()

        progress(1.0, desc="同步完成")
        logs += _format_log(f"同步完成！成功 {synced}/{total} 个文档")
        yield logs, gr.update()

    except RuntimeError as e:
        if "权限" in str(e):
            logs += _format_log(f"⚠️ {e}")
        else:
            logs += _format_log(f"同步出错: {e}")
        logger.exception("飞书同步异常")
        yield logs, gr.update()
    except Exception as e:
        logs += _format_log(f"同步出错: {e}")
        logger.exception("飞书同步异常")
        yield logs, gr.update()
    finally:
        if feishu_sync:
            try:
                await feishu_sync.close()
            except Exception:
                pass


async def sync_to_ima(
    video_ids: list[str],
    knowledge_base_id: str,
    kb_folder_id: str,
    kb_folder_name: str,
    config: AppConfig,
    progress=gr.Progress(),
):
    """同步知识卡片到腾讯 ima（建笔记 + 可选加入知识库指定文件夹）

    流程：
    1. 初始化 ImaSync 并验证凭证
    2. 解析/创建目标笔记本
    3. 按知识库 ID + 文件夹 ID/名称解析目标文件夹
    4. 遍历视频，建笔记（去重跳过），可选加入知识库对应文件夹
    5. 返回同步日志
    """
    logs = ""
    synced = 0
    kb_added = 0      # 知识库关联成功数
    kb_failed = 0     # 知识库关联失败数
    kb_retried = 0    # 知识库关联恢复重试数
    ima_sync = None

    # Load per-video local sync state for kb-association recovery
    ima_sync_state = _load_ima_sync_state()

    try:
        progress(0, desc="正在连接 ima...")
        logs += _format_log("正在连接 ima...")
        yield logs, gr.update()

        ima_sync = ImaSync(
            client_id=config.ima_client_id,
            api_key=config.ima_api_key,
            knowledge_base_id=knowledge_base_id or "",
        )
        logger.info(
            "sync_to_ima: 启动 video_ids=%d knowledge_base_id=%r kb_folder_id=%r kb_folder_name=%r",
            len(video_ids), knowledge_base_id, kb_folder_id, kb_folder_name,
        )
        connected = await ima_sync.connect()
        if not connected:
            logs += _format_log("ima 连接失败，请检查 Client ID 和 API Key")
            logger.warning("sync_to_ima: ima 连接失败")
            yield logs, gr.update()
            return

        logs += _format_log("ima 连接成功")
        logger.info("sync_to_ima: ima 连接成功")

        # 解析/创建目标笔记本（笔记存放的笔记本，与知识库文件夹是两套体系）
        folder_id = await ima_sync.create_folder("视频知识库")
        if folder_id:
            logs += _format_log(f"目标笔记本: {folder_id}")
            logger.info("sync_to_ima: 目标笔记本=%s", folder_id)

        # 解析知识库目标文件夹：优先 folder_id，否则按名称，都没有则根目录
        resolved_folder_id = ""
        if knowledge_base_id:
            logs += _format_log(f"知识库: {knowledge_base_id}")
            logger.info("sync_to_ima: 解析知识库目标文件夹 kb_id=%s folder_id=%r name=%r", knowledge_base_id, kb_folder_id, kb_folder_name)
            resolved_folder_id = await ima_sync.resolve_kb_folder(
                knowledge_base_id, kb_folder_id, kb_folder_name
            )
            if resolved_folder_id:
                logs += _format_log(f"知识库目标文件夹: {resolved_folder_id}")
                logger.info("sync_to_ima: 解析到文件夹=%s", resolved_folder_id)
            else:
                logs += _format_log("知识库目标文件夹: 根目录（留空）")
                logger.info("sync_to_ima: 使用知识库根目录")
        else:
            logs += _format_log("未选择知识库，仅建笔记")

        total = len(video_ids)
        for idx, vid in enumerate(video_ids):
            progress((idx, total), desc=f"同步中: {vid}")
            logs += _format_log(f"[{idx+1}/{total}] 同步视频: {vid}")
            yield logs, gr.update()

            card = _load_knowledge_card(vid)
            if not card:
                logs += _format_log(f"  ↳ 未找到知识卡片，跳过")
                continue

            # 检查是否已存在
            try:
                existing_note = await ima_sync.check_document_exists(vid)
            except ImaQuotaExceededError as e:
                logs += _format_log(f"  ↳ ima 配额已耗尽，停止同步: {e}")
                logger.warning("sync_to_ima: ima 配额耗尽，提前结束")
                break
            except Exception as e:
                logs += _format_log(f"  ↳ 查重失败，跳过: {e}")
                continue

            if existing_note:
                # Local sync state is keyed by (vid, knowledge_base_id) so
                # that cross-KB retries don't see stale kb_added=True from a
                # prior KB. Skip is only safe when (vid, kb, folder) all
                # match the current request; otherwise we attempt (or
                # re-attempt) the KB association.
                kb_active = bool(knowledge_base_id) and bool(ima_sync.knowledge_base_id)
                state_key = _state_key(vid, knowledge_base_id or "")
                decision = _kb_state_decision(
                    ima_sync_state, vid, knowledge_base_id or "", resolved_folder_id
                )

                if decision == "skip":
                    logs += _format_log(f"  ↳ 笔记已存在 ({existing_note})，跳过")
                    synced += 1
                    continue

                if not kb_active:
                    # No KB configured at runtime but state thinks there is
                    # something more to do — fall through with a skip.
                    logs += _format_log(f"  ↳ 笔记已存在 ({existing_note})，跳过")
                    synced += 1
                    continue

                # From here on we will call add_to_knowledge_base for the
                # current (kb_id, folder_id) pair. Distinguish the three
                # reason messages for the user-visible log.
                if decision == "attempt_kb":
                    logs += _format_log(
                        f"  ↳ 笔记已存在 ({existing_note})，未关联过该知识库，尝试关联..."
                    )
                    logger.info(
                        "sync_to_ima: vid=%s kb_id=%s cross-kb/legacy "
                        "attempt add_knowledge note_id=%s folder=%r",
                        vid, knowledge_base_id, existing_note, resolved_folder_id,
                    )
                elif decision == "retry_folder":
                    prev_folder = (
                        ima_sync_state.get(state_key, {}).get("kb_folder_id", "") or ""
                    )
                    logs += _format_log(
                        f"  ↳ 笔记已存在 ({existing_note})，目标文件夹不同"
                        f"（{prev_folder or '根'} → {resolved_folder_id or '根'}），重新关联..."
                    )
                    logger.info(
                        "sync_to_ima: vid=%s kb_id=%s folder_change "
                        "prev=%r new=%r",
                        vid, knowledge_base_id, prev_folder, resolved_folder_id,
                    )
                else:  # retry_kb
                    logs += _format_log(
                        f"  ↳ 笔记已存在 ({existing_note})，知识库关联未完成，正在重试..."
                    )
                    logger.info(
                        "sync_to_ima: vid=%s kb_id=%s retry add_knowledge "
                        "note_id=%s (prior kb_added=False)",
                        vid, knowledge_base_id, existing_note,
                    )

                try:
                    await ima_sync.add_to_knowledge_base(
                        existing_note,
                        f"[{vid}] {card.get('title', '未知')}",
                        resolved_folder_id,
                    )
                    kb_added += 1
                    kb_retried += 1
                    synced += 1
                    logs += _format_log(f"  ↳ 知识库关联成功: {existing_note}")
                    ima_sync_state[state_key] = {
                        "note_id": existing_note,
                        "knowledge_base_id": knowledge_base_id,
                        "kb_added": True,
                        "kb_error": "",
                        "kb_folder_id": resolved_folder_id,
                        "synced_at": _dt.now().isoformat(),
                    }
                    _save_ima_sync_state(ima_sync_state)
                except ImaQuotaExceededError as e:
                    kb_failed += 1
                    logs += _format_log(f"  ↳ 知识库关联失败（配额耗尽）: {e}")
                    logger.warning("sync_to_ima: 配额耗尽于 kb 关联")
                    ima_sync_state[state_key] = {
                        "note_id": existing_note,
                        "knowledge_base_id": knowledge_base_id,
                        "kb_added": False,
                        "kb_error": f"ImaQuotaExceededError: {e}",
                        "kb_folder_id": resolved_folder_id,
                        "synced_at": _dt.now().isoformat(),
                    }
                    _save_ima_sync_state(ima_sync_state)
                    break
                except Exception as e:
                    kb_failed += 1
                    logs += _format_log(f"  ↳ 知识库关联失败: {e}")
                    logger.warning("sync_to_ima: kb 关联失败 vid=%s: %s", vid, e)
                    ima_sync_state[state_key] = {
                        "note_id": existing_note,
                        "knowledge_base_id": knowledge_base_id,
                        "kb_added": False,
                        "kb_error": str(e),
                        "kb_folder_id": resolved_folder_id,
                        "synced_at": _dt.now().isoformat(),
                    }
                    _save_ima_sync_state(ima_sync_state)
                continue

            # 创建 ima 笔记
            try:
                result = await ima_sync.create_document(
                    title=f"[{vid}] {card.get('title', '未知')}",
                    content=card,
                    folder_id=folder_id,
                    kb_folder_id=resolved_folder_id,
                )
                synced += 1
                # Persist local sync state for future kb-association recovery.
                # Key by (vid, kb_id) so subsequent runs to a different KB
                # don't see this entry as an idempotent hit.
                ima_sync_state[_state_key(vid, knowledge_base_id or "")] = {
                    "note_id": result.note_id,
                    "knowledge_base_id": knowledge_base_id or "",
                    "kb_added": result.kb_added,
                    "kb_error": result.kb_error,
                    "kb_folder_id": resolved_folder_id,
                    "synced_at": _dt.now().isoformat(),
                }
                _save_ima_sync_state(ima_sync_state)
                if knowledge_base_id:
                    if result.kb_added:
                        kb_added += 1
                        logs += _format_log(f"  ↳ 笔记 + 知识库 创建成功: {result.note_id}")
                    elif result.kb_error:
                        kb_failed += 1
                        logs += _format_log(
                            f"  ↳ 笔记创建成功 ({result.note_id})，但知识库关联失败: {result.kb_error}"
                        )
                    else:
                        logs += _format_log(f"  ↳ 笔记创建成功: {result.note_id}")
                else:
                    logs += _format_log(f"  ↳ 笔记创建成功: {result.note_id}")
            except ImaQuotaExceededError as e:
                logs += _format_log(f"  ↳ ima 配额已耗尽，停止同步: {e}")
                logger.warning("sync_to_ima: ima 配额耗尽，提前结束")
                break
            except Exception as e:
                logs += _format_log(f"  ↳ 笔记创建失败: {e}")

            yield logs, gr.update()

        progress(1.0, desc="同步完成")
        if knowledge_base_id:
            retry_part = f"，恢复 {kb_retried}" if kb_retried else ""
            kb_summary = (
                f"（笔记 {synced}/{total}，知识库关联成功 {kb_added}"
                + retry_part
                + (f"，失败 {kb_failed}" if kb_failed else "")
                + "）"
            )
            logs += _format_log(f"同步完成！{kb_summary}")
        else:
            logs += _format_log(f"同步完成！成功 {synced}/{total} 个笔记")
        yield logs, gr.update()

    except Exception as e:
        logs += _format_log(f"同步出错: {e}")
        logger.exception("ima 同步异常")
        yield logs, gr.update()
    finally:
        if ima_sync:
            try:
                await ima_sync.close()
            except Exception:
                pass


# ---------------------------------------------------------------------------
# Gradio 事件处理函数（Gradio 原生支持 async，所有 handler 运行在同一事件循环上）
# ---------------------------------------------------------------------------

async def do_login(platform: str, state: dict):
    """登录平台（Gradio async handler，运行在 uvicorn 事件循环上）"""
    try:
        scraper = _create_scraper(platform)
        await scraper.login()
        state["scraper"] = scraper
        state["platform"] = platform
        return f"✅ {platform} 登录成功", state
    except Exception as e:
        return f"❌ 登录失败: {e}", state


async def do_get_favorites(state: dict):
    """获取收藏夹列表"""
    scraper = state.get("scraper")
    if scraper is None:
        return [], "请先登录", state

    try:
        folders = await scraper.get_favorites()
        state["folders"] = folders
        choices = [f"{f.title} ({f.video_count}个视频)" for f in folders]
        folder_ids = [f.folder_id for f in folders]
        state["folder_ids"] = folder_ids
        return gr.update(choices=choices, value=[]), f"获取到 {len(folders)} 个收藏夹", state
    except Exception as e:
        return [], f"获取收藏夹失败: {e}", state


async def do_process(
    platform, selected_folders, whisper_enabled, whisper_model, max_videos,
    llm_api_key, llm_base_url, llm_model,
    feishu_app_id, feishu_app_secret,
    state: dict,
):
    """启动视频处理管道（async generator，Gradio 原生支持流式更新）"""
    if not selected_folders:
        yield "请先选择收藏夹", "", "等待开始...", gr.update(), state
        return

    # 映射选中的收藏夹名称到 folder_id
    folders = state.get("folders", [])
    folder_ids = state.get("folder_ids", [])
    selected_ids = []
    for sel in selected_folders:
        for i, f in enumerate(folders):
            label = f"{f.title} ({f.video_count}个视频)"
            if label == sel:
                selected_ids.append(folder_ids[i])
                break

    if not selected_ids:
        yield "未选择有效的收藏夹", "", "等待开始...", gr.update(), state
        return

    # 构建临时配置
    config = AppConfig(
        llm_api_key=llm_api_key,
        llm_base_url=llm_base_url,
        llm_model=llm_model,
        feishu_app_id=feishu_app_id,
        feishu_app_secret=feishu_app_secret,
    )

    # 运行异步处理管道（async for 迭代 async generator）
    results = []
    logs = ""
    async for r, l, cur, p in process_videos(
        platform=platform,
        folder_ids=selected_ids,
        whisper_enabled=whisper_enabled,
        whisper_model=whisper_model,
        max_videos=int(max_videos),
        config=config,
    ):
        results = r
        logs = l
        # 生成处理结果预览文本
        preview = ""
        for card in results:
            preview += f"## {card.get('title', '未知')}\n"
            preview += f"**作者**: {card.get('author', '')}  |  **平台**: {card.get('platform', '')}\n"
            preview += f"**摘要**: {card.get('summary', '')[:200]}...\n"
            kw = card.get("keywords", [])
            if kw:
                preview += f"**关键词**: {', '.join(kw)}\n"
            preview += "---\n"
        state["process_results"] = results
        yield preview, logs, cur, p, state


async def do_test_feishu(feishu_app_id, feishu_app_secret):
    """测试飞书连接"""
    try:
        feishu = FeishuSync(app_id=feishu_app_id, app_secret=feishu_app_secret)
        ok = await feishu.connect()
        if ok:
            return "✅ 飞书连接成功"
        return "❌ 飞书连接失败"
    except Exception as e:
        return f"❌ 连接失败: {e}"


async def do_test_ima(ima_client_id, ima_api_key):
    """测试 ima 连接"""
    if not ima_client_id or not ima_api_key:
        return "❌ 请先配置 ima Client ID 和 API Key"
    try:
        ima = ImaSync(client_id=ima_client_id, api_key=ima_api_key)
        ok = await ima.connect()
        if ok:
            return "✅ ima 连接成功"
        return "❌ ima 连接失败"
    except Exception as e:
        return f"❌ 连接失败: {e}"


async def do_load_kbs(ima_client_id, ima_api_key, search, state: dict):
    """加载可添加内容的知识库列表（对应 python cli.py ima-kbs）

    返回 (下拉更新, 状态文本, state)。state 缓存 id→name 映射，供选择时回显名称。
    """
    if not ima_client_id or not ima_api_key:
        logger.warning("do_load_kbs: 缺少 ima Client ID 或 API Key，无法加载知识库")
        return (
            gr.update(choices=[], value=None),
            "请先配置 ima Client ID 和 API Key",
            state,
        )
    try:
        logger.info(
            "do_load_kbs: 开始加载知识库 search=%r client_id=%s... api_key_set=%s",
            search, (ima_client_id[:4] + "****" if ima_client_id else ""), bool(ima_api_key),
        )
        ima = ImaSync(client_id=ima_client_id, api_key=ima_api_key)
        kbs = await ima.list_addable_knowledge_bases(search=search or "")
        await ima.close()
        state = dict(state)
        state["kb_map"] = {kb["id"]: kb.get("name", "") for kb in kbs}
        logger.info(
            "do_load_kbs: 成功，得到 %d 个知识库: %s",
            len(kbs), [(kb["id"], kb.get("name", "")) for kb in kbs],
        )
        if not kbs:
            # ima 不能创建知识库，给出明确引导，避免用户困惑
            return (
                gr.update(choices=[], value=None),
                "未发现知识库，请先在 ima 客户端新建一个，再点加载",
                state,
            )
        tuple_choices = [
            {
                "label": f"{kb['name']} — {kb['description']}" if kb.get("description") else kb["name"],
                "value": kb["id"],
            }
            for kb in kbs
        ]
        return (
            gr.update(choices=tuple_choices, value=None),
            f"已加载 {len(kbs)} 个知识库",
            state,
        )
    except Exception as e:
        logger.exception("do_load_kbs: 加载知识库异常")
        return gr.update(choices=[], value=None), f"加载知识库失败: {e}", state


async def on_kb_change(kb_id, state: dict):
    """知识库选择变化：写入 state 并重置文件夹选择（防旧 KB 的 folder_id 串用）"""
    try:
        # Gradio 6.0 回传的可能是整个 choice dict，需归一化为 value
        kb_id = _choice_value(kb_id)
        logger.info("on_kb_change: 选择变化 kb_id=%r", kb_id)
        logger.debug("on_kb_change: 当前 kb_map 内容=%s", state.get("kb_map", {}))
        state = dict(state)
        state["kb_id"] = kb_id or ""
        state["kb_name"] = state.get("kb_map", {}).get(kb_id or "", kb_id or "")
        state["kb_folder_value"] = ""
        kb_label = state["kb_name"] or "未选择"
        logger.info("on_kb_change: 切换至知识库=%s (id=%s)，已重置文件夹选择", kb_label, kb_id)
        # 输出：state + 重置 Tab5 文件夹下拉 + 状态提示 + Tab5 当前知识库回显
        return (
            state,
            gr.update(choices=[], value=None),
            f"已切换知识库: {kb_label}，请在「同步管理」重新加载文件夹",
            kb_label,
        )
    except Exception as e:
        logger.exception("on_kb_change: 异常")
        state = dict(state)
        state["kb_id"] = ""
        state["kb_name"] = ""
        state["kb_folder_value"] = ""
        return (
            state,
            gr.update(choices=[], value=None),
            f"切换知识库出错: {e}",
            "出错",
        )


async def do_load_folders(ima_client_id, ima_api_key, state: dict, progress=gr.Progress()):
    """加载当前知识库下的文件夹树（对应 python cli.py ima-kbs --folders）"""
    kb_id = state.get("kb_id", "")
    if not ima_client_id or not ima_api_key:
        logger.warning("do_load_folders: 缺少 ima 凭证")
        return gr.update(choices=[], value=None), "请先配置 ima Client ID 和 API Key"
    if not kb_id:
        logger.warning("do_load_folders: 尚未选择知识库 (state.kb_id 为空)")
        return gr.update(choices=[], value=None), "请先在「登录与配置」选择一个知识库"
    try:
        logger.info("do_load_folders: 开始加载 kb_id=%s", kb_id)
        progress(0, desc="正在加载文件夹...")
        ima = ImaSync(client_id=ima_client_id, api_key=ima_api_key, knowledge_base_id=kb_id)
        folders = await ima.list_folders(kb_id)
        await ima.close()
        choices = [{"label": "📁 根目录（留空）", "value": ""}]
        for f in folders:
            indent = "    " * f.get("depth", 0)
            label = f"{indent}📂 {f.get('name', '')}"
            choices.append({"label": label, "value": f.get("folder_id", "")})
        progress(1.0, desc="加载完成")
        logger.info("do_load_folders: 完成，%d 个文件夹", len(folders))
        return gr.update(choices=choices, value=""), f"已加载 {len(folders)} 个文件夹"
    except Exception as e:
        logger.exception("do_load_folders: 加载文件夹异常")
        return gr.update(choices=[], value=None), f"加载文件夹失败: {e}"


async def on_folder_change(folder_value, state: dict):
    """文件夹选择变化：记录到 state（folder_ 前缀视为 ID，否则视为名称）"""
    try:
        state = dict(state)
        fv = _choice_value(folder_value)
        state["kb_folder_value"] = fv
        logger.info(
            "on_folder_change: folder_value=%r 视为 %s",
            fv, "folder_id(前缀folder_)" if fv.startswith("folder_") else "文件夹名称",
        )
        return state
    except Exception as e:
        logger.exception("on_folder_change: 异常")
        return dict(state)


async def do_sync_ima(
    video_ids_text,
    ima_client_id, ima_api_key,
    state: dict,
    progress=gr.Progress()
):
    """启动 ima 同步（async generator）。知识库与文件夹从 state 读取。"""
    if not ima_client_id or not ima_api_key:
        yield "请先配置 ima Client ID 和 API Key", gr.update()
        return

    kb_id = state.get("kb_id", "")
    fv = state.get("kb_folder_value", "")
    # 自定义值或下拉值：folder_ 前缀视为 ID，否则当作文件夹名称
    if fv and fv.startswith("folder_"):
        kb_folder_id, kb_folder_name = fv, ""
    else:
        kb_folder_id, kb_folder_name = "", fv

    # 解析视频 ID 列表
    if video_ids_text:
        vid_list = [v.strip() for v in video_ids_text.split(",") if v.strip()]
    else:
        cards = _list_knowledge_cards()
        vid_list = [c.get("video_id", "") for c in cards if c.get("video_id")]

    if not vid_list:
        yield "没有可同步的视频", gr.update()
        return

    config = AppConfig(
        ima_client_id=ima_client_id,
        ima_api_key=ima_api_key,
        ima_knowledge_base_id=kb_id,
    )

    async for logs, upd in sync_to_ima(
        video_ids=vid_list,
        knowledge_base_id=kb_id,
        kb_folder_id=kb_folder_id,
        kb_folder_name=kb_folder_name,
        config=config,
        progress=progress,
    ):
        yield logs, upd


async def do_sync(
    video_ids_text, folder_token,
    feishu_app_id, feishu_app_secret,
    progress=gr.Progress()
):
    """启动飞书同步（async generator）"""
    if not feishu_app_id or not feishu_app_secret:
        yield "请先配置飞书 App ID 和 App Secret", gr.update()
        return

    # 解析视频 ID 列表
    if video_ids_text:
        vid_list = [v.strip() for v in video_ids_text.split(",") if v.strip()]
    else:
        # 默认同步所有知识库中的视频
        cards = _list_knowledge_cards()
        vid_list = [c.get("video_id", "") for c in cards if c.get("video_id")]

    if not vid_list:
        yield "没有可同步的视频", gr.update()
        return

    config = AppConfig(feishu_app_id=feishu_app_id, feishu_app_secret=feishu_app_secret)

    async for logs, upd in sync_to_feishu(
        video_ids=vid_list,
        folder_token=folder_token or "",
        config=config,
        progress=progress,
    ):
        yield logs, upd


def do_save_config(llm_api_key, llm_base_url, llm_model, feishu_app_id, feishu_app_secret,
                   ima_client_id, ima_api_key, ima_knowledge_base_id, state: dict):
    """保存配置到 .env 文件（含 ima 知识库与文件夹选择）

    读取现有 .env 文件，仅更新表单编辑的字段，保留 LLM_PROVIDER、Whisper、
    数据目录、CDP 等未在表单中的配置不变。对换行和等号做安全序列化。
    """
    try:
        # 下拉框回传可能是 choice dict，归一化为 value 再处理
        kb_id = _choice_value(ima_knowledge_base_id) or state.get("kb_id", "")
        fv = state.get("kb_folder_value", "")
        if fv and fv.startswith("folder_"):
            kb_folder_id, kb_folder_name = fv, ""
        else:
            kb_folder_id, kb_folder_name = "", fv

        # 构建本次要更新的字段映射（key -> value）
        # 对含换行/等号的值做安全处理
        updates = {
            "LLM_API_KEY": (llm_api_key or "").replace("\n", "").replace("\r", ""),
            "LLM_BASE_URL": (llm_base_url or "").replace("\n", "").replace("\r", ""),
            "LLM_MODEL": (llm_model or "").replace("\n", "").replace("\r", ""),
            "FEISHU_APP_ID": (feishu_app_id or "").replace("\n", "").replace("\r", ""),
            "FEISHU_APP_SECRET": (feishu_app_secret or "").replace("\n", "").replace("\r", ""),
            "IMA_CLIENT_ID": (ima_client_id or "").replace("\n", "").replace("\r", ""),
            "IMA_API_KEY": (ima_api_key or "").replace("\n", "").replace("\r", ""),
            "IMA_KNOWLEDGE_BASE_ID": (kb_id or "").replace("\n", "").replace("\r", ""),
            "IMA_KNOWLEDGE_BASE_FOLDER_ID": (kb_folder_id or "").replace("\n", "").replace("\r", ""),
            "IMA_KNOWLEDGE_BASE_FOLDER_NAME": (kb_folder_name or "").replace("\n", "").replace("\r", ""),
        }

        # 读取现有 .env 行（如果存在），逐行更新匹配的 key
        existing_lines: list[str] = []
        updated_keys: set = set()
        env_path = ".env"
        if os.path.exists(env_path):
            with open(env_path, "r", encoding="utf-8") as f:
                for line in f:
                    stripped = line.rstrip("\n\r")
                    if not stripped or stripped.startswith("#"):
                        existing_lines.append(stripped)
                        continue
                    # 解析 KEY=VALUE 行（允许等号出现在值中）
                    if "=" in stripped:
                        key = stripped.split("=", 1)[0].strip()
                        if key in updates:
                            existing_lines.append(f"{key}={updates[key]}")
                            updated_keys.add(key)
                        else:
                            existing_lines.append(stripped)
                    else:
                        existing_lines.append(stripped)

        # 追加尚未在 .env 中出现的新字段
        for key, val in updates.items():
            if key not in updated_keys:
                existing_lines.append(f"{key}={val}")

        with open(env_path, "w", encoding="utf-8") as f:
            f.write("\n".join(existing_lines) + "\n")

        logger.info(
            "do_save_config: 已保存 %d 个字段, 保留 %d 行现有配置",
            len(updates), len(existing_lines) - len(updates),
        )
        return "配置已保存到 .env 文件"
    except Exception as e:
        logger.exception("do_save_config 失败")
        return f"保存失败: {e}"


def do_load_knowledge(search_query):
    """加载知识库卡片并搜索"""
    cards = _list_knowledge_cards()
    if search_query:
        q = search_query.lower()
        cards = [
            c for c in cards
            if q in c.get("title", "").lower()
            or q in c.get("author", "").lower()
            or q in c.get("summary", "").lower()
            or q in " ".join(c.get("keywords", [])).lower()
        ]
    # 构建展示列表
    display = []
    for c in cards:
        title = c.get("title", "未知")
        author = c.get("author", "")
        summary = c.get("summary", "")[:100]
        display.append(f"📄 {title} — {author}\n   {summary}...")
    return gr.update(choices=display, value=[]), cards


def do_show_detail(selected_cards, all_cards):
    """显示知识卡片详情"""
    if not selected_cards or not all_cards:
        return "", "", "", "", ""

    # 用与 do_load_knowledge 完全相同的格式重建显示字符串，通过精确匹配定位索引
    idx = 0
    for i, card in enumerate(all_cards):
        title = card.get("title", "未知")
        author = card.get("author", "")
        summary = card.get("summary", "")[:100]
        display_label = f"📄 {title} — {author}\n   {summary}..."
        if display_label == selected_cards[0]:
            idx = i
            break

    card = all_cards[idx]
    title = card.get("title", "未知")
    author = card.get("author", "")
    platform = card.get("platform", "")
    source_url = card.get("source_url", "")
    summary = card.get("summary", "")
    keywords = "、".join(card.get("keywords", []))
    key_points = "\n".join(
        f"• {kp.get('point', kp) if isinstance(kp, dict) else kp}"
        for kp in card.get("key_points", [])
    )
    transcript = card.get("transcript", "")
    header = f"## {title}\n**作者**: {author}  |  **来源**: {platform}  |  [原文链接]({source_url})"
    return header, summary, keywords, key_points, transcript


async def do_download_video(selected_cards, all_cards, state: dict):
    """下载选中知识卡片对应的视频文件"""
    if not selected_cards or not all_cards:
        return "请先选择一个知识卡片"

    # 定位选中的卡片
    idx = 0
    for i, card in enumerate(all_cards):
        title = card.get("title", "未知")
        author = card.get("author", "")
        summary = card.get("summary", "")[:100]
        display_label = f"📄 {title} — {author}\n   {summary}..."
        if display_label == selected_cards[0]:
            idx = i
            break

    card = all_cards[idx]
    video_id = card.get("video_id", "")
    platform = card.get("platform", "")
    title = card.get("title", "未知")

    if not video_id:
        return f"卡片中未找到视频 ID"

    scraper = state.get("scraper")
    if scraper is None:
        return "请先在「登录与配置」中登录平台"

    from config import get_config
    config = get_config()
    download_dir = Path(config.video_download_dir)
    download_dir.mkdir(parents=True, exist_ok=True)
    output_path = str(download_dir / f"{video_id}.mp4")

    if Path(output_path).exists():
        return f"✅ 视频已存在: {output_path}"

    try:
        result = await scraper.download_video(video_id, output_path)
        if result:
            return f"✅ 下载完成: {output_path}"
        else:
            return f"❌ 下载失败: {title}"
    except Exception as e:
        return f"❌ 下载异常: {e}"


# ---------------------------------------------------------------------------
# Gradio 界面构建
# ---------------------------------------------------------------------------

def build_ui():
    """构建 Gradio Blocks 界面"""

    # 加载当前配置
    config = get_config()

    with gr.Blocks(
        title="视频收藏夹同步工具",
    ) as app:

        # 全局状态（state 字典统一管理跨 Tab 的 ima 选择；初始带入 .env 已存值）
        state = gr.State({
            "kb_id": config.ima_knowledge_base_id,
            "kb_name": "",
            "kb_folder_value": config.ima_knowledge_base_folder_id
            or config.ima_knowledge_base_folder_name,
            "kb_map": {},
        })
        all_cards_state = gr.State([])

        gr.Markdown("# 📚 视频收藏夹同步工具")
        gr.Markdown("同步多平台收藏视频，转写分析并同步到飞书文档")

        # ===================== Tab 1: 登录与配置 =====================
        with gr.Tab("🔑 登录与配置"):
            with gr.Row():
                with gr.Column(scale=1):
                    gr.Markdown("### 平台登录")
                    platform_dropdown = gr.Dropdown(
                        choices=PLATFORM_CHOICES,
                        value="Bilibili",
                        label="选择平台",
                    )
                    login_btn = gr.Button("🌐 登录", variant="primary")
                    login_status = gr.Textbox(
                        label="登录状态", value="未登录", interactive=False
                    )

                with gr.Column(scale=2):
                    gr.Markdown("### API 配置")
                    with gr.Row():
                        llm_api_key_input = gr.Textbox(
                            label="LLM API Key",
                            value=config.llm_api_key,
                            type="password",
                        )
                        llm_base_url_input = gr.Textbox(
                            label="LLM Base URL",
                            value=config.llm_base_url,
                        )
                    with gr.Row():
                        llm_model_input = gr.Textbox(
                            label="LLM 模型名称",
                            value=config.llm_model,
                        )
                    with gr.Row():
                        feishu_app_id_input = gr.Textbox(
                            label="飞书 App ID",
                            value=config.feishu_app_id,
                        )
                        feishu_app_secret_input = gr.Textbox(
                            label="飞书 App Secret",
                            value=config.feishu_app_secret,
                            type="password",
                        )
                    with gr.Row():
                        ima_client_id_input = gr.Textbox(
                            label="ima Client ID",
                            value=config.ima_client_id,
                        )
                        ima_api_key_input = gr.Textbox(
                            label="ima API Key",
                            value=config.ima_api_key,
                            type="password",
                        )
                    with gr.Row():
                        ima_kb_search = gr.Textbox(
                            label="知识库搜索（可选）",
                            value="",
                            placeholder="按名称搜索知识库",
                            scale=3,
                        )
                        ima_load_kb_btn = gr.Button(
                            "📥 加载知识库", variant="secondary", scale=1
                        )
                    ima_kb_dropdown = gr.Dropdown(
                        label="ima 知识库（选择后同步将笔记加入该库；留空仅建笔记）",
                        choices=[],
                        value=config.ima_knowledge_base_id or None,
                        allow_custom_value=True,
                    )
                    ima_kb_status = gr.Textbox(
                        label="知识库状态", value="", interactive=False
                    )
                    save_config_btn = gr.Button("💾 保存配置", variant="secondary")
                    save_config_status = gr.Textbox(
                        label="保存状态", value="", interactive=False
                    )

        # ===================== Tab 2: 收藏夹处理 =====================
        with gr.Tab("📁 收藏夹处理"):
            with gr.Row():
                with gr.Column(scale=2):
                    get_favorites_btn = gr.Button("📥 获取收藏夹", variant="primary")
                    favorites_display = gr.CheckboxGroup(
                        label="收藏夹列表（勾选要处理的收藏夹）",
                        choices=[],
                    )
                    favorites_status = gr.Textbox(
                        label="状态", value="", interactive=False
                    )

                with gr.Column(scale=1):
                    gr.Markdown("### 处理参数")
                    whisper_model_select = gr.Dropdown(
                        choices=WHISPER_MODEL_CHOICES,
                        value=config.whisper_model,
                        label="Whisper 模型 (faster-whisper)",
                        info="仅支持 faster-whisper 引擎；中文语音建议选 multilingual 模型（如 small/medium），en 系列仅适用于英文",
                    )
                    whisper_enabled_check = gr.Checkbox(
                        value=True,
                        label="启用 Whisper（无字幕时使用）",
                    )
                    max_videos_input = gr.Slider(
                        minimum=0, maximum=200, value=20, step=1,
                        label="最大处理视频数（0=不限）",
                    )
                    process_btn = gr.Button("🚀 开始处理", variant="primary")

            gr.Markdown("### 处理结果预览")
            process_preview = gr.Markdown(value="*尚未处理*")

        # ===================== Tab 3: 处理进度 =====================
        with gr.Tab("📊 处理进度"):
            progress_bar = gr.Slider(
                minimum=0, maximum=1, value=0,
                label="进度", interactive=False,
            )
            current_video = gr.Textbox(
                label="当前处理", value="等待开始...", interactive=False
            )
            process_log = gr.Textbox(
                label="处理日志", value="", lines=15,
                interactive=False, max_lines=50,
            )

        # ===================== Tab 4: 知识库浏览 =====================
        with gr.Tab("📖 知识库浏览"):
            with gr.Row():
                search_input = gr.Textbox(
                    label="搜索", placeholder="输入关键词搜索...", scale=3
                )
                search_btn = gr.Button("🔍 搜索", scale=1)

            with gr.Row():
                card_list = gr.CheckboxGroup(
                    label="知识卡片列表", choices=[], scale=1
                )
                with gr.Column(scale=2):
                    detail_header = gr.Markdown(value="*选择一个卡片查看详情*")
                    detail_summary = gr.Textbox(label="摘要", lines=3, interactive=False)
                    detail_keywords = gr.Textbox(label="关键词", interactive=False)
                    detail_keypoints = gr.Textbox(label="核心要点", lines=5, interactive=False)
                    detail_transcript = gr.Textbox(
                        label="逐字稿", lines=10, interactive=False,
                        visible=True,
                    )
                    with gr.Row():
                        download_btn = gr.Button("📥 下载视频", variant="secondary", scale=1)
                        download_status = gr.Textbox(label="下载状态", interactive=False, scale=3)

            # 搜索事件
            search_btn.click(
                fn=do_load_knowledge,
                inputs=[search_input],
                outputs=[card_list, all_cards_state],
            )
            card_list.change(
                fn=do_show_detail,
                inputs=[card_list, all_cards_state],
                outputs=[detail_header, detail_summary, detail_keywords, detail_keypoints, detail_transcript],
            )
            download_btn.click(
                fn=do_download_video,
                inputs=[card_list, all_cards_state, state],
                outputs=[download_status],
            )

        # ===================== Tab 5: 同步管理 =====================
        with gr.Tab("🔄 同步管理"):
            with gr.Row():
                with gr.Column(scale=1):
                    gr.Markdown("### 同步到飞书")
                    feishu_status = gr.Textbox(
                        label="飞书连接状态", value="未连接", interactive=False
                    )
                    test_feishu_btn = gr.Button("🔗 测试连接", variant="secondary")
                    feishu_folder_input = gr.Textbox(
                        label="飞书目标文件夹 Token（留空则自动创建）",
                        value="",
                    )
                    sync_video_ids = gr.Textbox(
                        label="同步视频 ID（逗号分隔，留空=全部）",
                        value="",
                    )
                    sync_btn = gr.Button("📤 一键同步到飞书", variant="primary")

                with gr.Column(scale=1):
                    gr.Markdown("### 同步到 ima")
                    ima_status = gr.Textbox(
                        label="ima 连接状态", value="未连接", interactive=False
                    )
                    test_ima_btn = gr.Button("🔗 测试 ima 连接", variant="secondary")
                    ima_current_kb = gr.Textbox(
                        label="当前知识库（在「登录与配置」选择）",
                        value=config.ima_knowledge_base_id or "未选择",
                        interactive=False,
                    )
                    with gr.Row():
                        ima_load_folder_btn = gr.Button(
                            "📂 加载文件夹", variant="secondary", scale=1
                        )
                        ima_folder_status = gr.Textbox(
                            label="文件夹状态", value="", interactive=False, scale=3
                        )
                    ima_folder_dropdown = gr.Dropdown(
                        label="知识库目标文件夹（留空=根目录）",
                        choices=[{"label": "📁 根目录（留空）", "value": ""}],
                        value="",
                        allow_custom_value=True,
                    )
                    ima_video_ids = gr.Textbox(
                        label="同步视频 ID（逗号分隔，留空=全部）",
                        value="",
                    )
                    sync_ima_btn = gr.Button("📤 一键同步到 ima", variant="primary")

                with gr.Column(scale=2):
                    sync_log = gr.Textbox(
                        label="同步日志", value="", lines=20,
                        interactive=False,
                    )

        # ------------------------------------------------------------------
        # 事件绑定
        # ------------------------------------------------------------------

        # Tab 1: 登录
        login_btn.click(
            fn=do_login,
            inputs=[platform_dropdown, state],
            outputs=[login_status, state],
        )

        # Tab 1: 保存配置
        save_config_btn.click(
            fn=do_save_config,
            inputs=[
                llm_api_key_input, llm_base_url_input, llm_model_input,
                feishu_app_id_input, feishu_app_secret_input,
                ima_client_id_input, ima_api_key_input, ima_kb_dropdown, state,
            ],
            outputs=[save_config_status],
        )

        # Tab 1: 加载知识库列表
        ima_load_kb_btn.click(
            fn=do_load_kbs,
            inputs=[ima_client_id_input, ima_api_key_input, ima_kb_search, state],
            outputs=[ima_kb_dropdown, ima_kb_status, state],
        )

        # Tab 1: 切换知识库 → 同步 state + 重置 Tab5 文件夹选择
        ima_kb_dropdown.change(
            fn=on_kb_change,
            inputs=[ima_kb_dropdown, state],
            outputs=[state, ima_folder_dropdown, ima_kb_status, ima_current_kb],
        )

        # Tab 2: 获取收藏夹
        get_favorites_btn.click(
            fn=do_get_favorites,
            inputs=[state],
            outputs=[favorites_display, favorites_status, state],
        )

        # Tab 2: 开始处理
        process_btn.click(
            fn=do_process,
            inputs=[
                platform_dropdown, favorites_display,
                whisper_enabled_check, whisper_model_select, max_videos_input,
                llm_api_key_input, llm_base_url_input, llm_model_input,
                feishu_app_id_input, feishu_app_secret_input,
                state,
            ],
            outputs=[process_preview, process_log, current_video, progress_bar, state],
        )

        # Tab 5: 测试飞书连接
        test_feishu_btn.click(
            fn=do_test_feishu,
            inputs=[feishu_app_id_input, feishu_app_secret_input],
            outputs=[feishu_status],
        )

        # Tab 5: 同步到飞书
        sync_btn.click(
            fn=do_sync,
            inputs=[
                sync_video_ids, feishu_folder_input,
                feishu_app_id_input, feishu_app_secret_input,
            ],
            outputs=[sync_log, progress_bar],
        )

        # Tab 5: 测试 ima 连接
        test_ima_btn.click(
            fn=do_test_ima,
            inputs=[ima_client_id_input, ima_api_key_input],
            outputs=[ima_status],
        )

        # Tab 5: 加载知识库文件夹树
        ima_load_folder_btn.click(
            fn=do_load_folders,
            inputs=[ima_client_id_input, ima_api_key_input, state],
            outputs=[ima_folder_dropdown, ima_folder_status],
        )

        # Tab 5: 文件夹选择变化 → 写入 state
        ima_folder_dropdown.change(
            fn=on_folder_change,
            inputs=[ima_folder_dropdown, state],
            outputs=[state],
        )

        # Tab 5: 同步到 ima
        sync_ima_btn.click(
            fn=do_sync_ima,
            inputs=[
                ima_video_ids,
                ima_client_id_input, ima_api_key_input, state,
            ],
            outputs=[sync_log, progress_bar],
        )

    return app


def main():
    """启动 Gradio 应用"""
    import os

    # 日志配置：控制台 INFO + 文件 DEBUG（持久化用于排查问题）
    log_dir = Path("data/logs")
    log_dir.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)-5s] %(name)s: %(message)s",
        datefmt="%H:%M:%S",
        handlers=[
            logging.StreamHandler(),
            logging.FileHandler(log_dir / "app.log", encoding="utf-8"),
        ],
    )
    # 降低第三方库日志噪音
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("playwright").setLevel(logging.WARNING)
    logging.getLogger("urllib3").setLevel(logging.WARNING)

    # 针对新开发的 ima 知识库功能开启 DEBUG，输出详细诊断日志（控制台 + 文件）
    logging.getLogger("src.sync.ima").setLevel(logging.DEBUG)
    logging.getLogger(__name__).setLevel(logging.DEBUG)
    logger.info("已开启 ima / app 模块 DEBUG 诊断日志")

    logger.info("=" * 50)
    logger.info("Unarchive 启动")
    logger.info("=" * 50)

    # 清除系统代理环境变量，避免 httpx 走代理导致连接失败
    for key in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
        os.environ.pop(key, None)

    # 确保数据目录存在
    config = get_config()
    config.ensure_dirs()

    app = build_ui()
    app.launch(
        server_name="127.0.0.1",
        server_port=7860,
        share=False,
        theme=gr.themes.Soft(),
    )


if __name__ == "__main__":
    main()
