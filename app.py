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
import time
from pathlib import Path
from typing import Optional

import gradio as gr

from config import AppConfig, get_config
from src.scraper.bilibili import BilibiliScraper
from src.scraper.douyin import DouyinScraper
from src.transcript import SubtitleParser, WhisperTranscriber, get_transcript
from src.analyzer.llm_analyzer import LLMAnalyzer
from src.sync.feishu import FeishuSync
from src.sync.ima import ImaSync

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# 全局常量
# ---------------------------------------------------------------------------
KNOWLEDGE_BASE_DIR = Path("data/knowledge_base")
KNOWLEDGE_BASE_DIR.mkdir(parents=True, exist_ok=True)

PLATFORM_CHOICES = ["Bilibili", "抖音"]
WHISPER_MODEL_CHOICES = [
    "tiny", "base", "small", "medium",
    "large-v1", "large-v2", "large-v3",
    "tiny.en", "base.en", "small.en", "medium.en", "large-v3.en",
    "distil-small.en", "distil-medium.en",
    "distil-large-v2", "distil-large-v3",
]

# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------

def _save_knowledge_card(video_id: str, data: dict) -> Path:
    """保存知识卡片为 JSON 文件"""
    path = KNOWLEDGE_BASE_DIR / f"{video_id}.json"
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    return path


def _load_knowledge_card(video_id: str) -> Optional[dict]:
    """加载知识卡片 JSON"""
    path = KNOWLEDGE_BASE_DIR / f"{video_id}.json"
    if path.exists():
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    return None


def _list_knowledge_cards() -> list[dict]:
    """列出所有已保存的知识卡片"""
    cards = []
    for fp in KNOWLEDGE_BASE_DIR.glob("*.json"):
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

            available = await scraper.check_video_available(video.video_id)
            if not available:
                logs += _format_log(f"  ↳ 视频不可用（已删除/下架/私密），跳过")
                stats["unavailable"] += 1
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
        logs += _format_log(
            f"全部完成！处理 {total_processed}/{total} 个 (跳过 {stats['skipped']}、"
            f"不可用 {stats['unavailable']}、转录失败 {stats['transcript_fail']}、"
            f"LLM失败 {stats['llm_fail']})，总耗时 {elapsed:.0f}s"
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
    config: AppConfig,
    progress=gr.Progress(),
):
    """同步知识卡片到腾讯 ima（建笔记 + 可选加入知识库）

    流程：
    1. 初始化 ImaSync 并验证凭证
    2. 解析/创建目标笔记本
    3. 遍历视频，建笔记（去重跳过），可选加入知识库
    4. 返回同步日志
    """
    logs = ""
    synced = 0
    ima_sync = None

    try:
        progress(0, desc="正在连接 ima...")
        logs += _format_log("正在连接 ima...")
        yield logs, gr.update()

        ima_sync = ImaSync(
            client_id=config.ima_client_id,
            api_key=config.ima_api_key,
            knowledge_base_id=knowledge_base_id or "",
        )
        connected = await ima_sync.connect()
        if not connected:
            logs += _format_log("ima 连接失败，请检查 Client ID 和 API Key")
            yield logs, gr.update()
            return

        logs += _format_log("ima 连接成功")

        # 解析/创建目标笔记本
        folder_id = await ima_sync.create_folder("视频知识库")
        if folder_id:
            logs += _format_log(f"目标笔记本: {folder_id}")
        if knowledge_base_id:
            logs += _format_log(f"同步后加入知识库: {knowledge_base_id}")

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
            existing_note = await ima_sync.check_document_exists(vid)
            if existing_note:
                logs += _format_log(f"  ↳ 笔记已存在 ({existing_note})，跳过")
                synced += 1
                continue

            # 创建 ima 笔记
            try:
                note_id = await ima_sync.create_document(
                    title=f"[{vid}] {card.get('title', '未知')}",
                    content=card,
                    folder_id=folder_id,
                )
                logs += _format_log(f"  ↳ 笔记创建成功: {note_id}")
                synced += 1
            except Exception as e:
                logs += _format_log(f"  ↳ 笔记创建失败: {e}")

            yield logs, gr.update()

        progress(1.0, desc="同步完成")
        kb_suffix = f" +知识库" if knowledge_base_id else ""
        logs += _format_log(f"同步完成！成功 {synced}/{total} 个笔记{kb_suffix}")
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


async def do_sync_ima(
    video_ids_text, knowledge_base_id,
    ima_client_id, ima_api_key,
    progress=gr.Progress()
):
    """启动 ima 同步（async generator）"""
    if not ima_client_id or not ima_api_key:
        yield "请先配置 ima Client ID 和 API Key", gr.update()
        return

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
        ima_knowledge_base_id=knowledge_base_id,
    )

    async for logs, upd in sync_to_ima(
        video_ids=vid_list,
        knowledge_base_id=knowledge_base_id or "",
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
                   ima_client_id, ima_api_key, ima_knowledge_base_id):
    """保存配置到 .env 文件"""
    try:
        lines = [
            f"LLM_API_KEY={llm_api_key}",
            f"LLM_BASE_URL={llm_base_url}",
            f"LLM_MODEL={llm_model}",
            f"FEISHU_APP_ID={feishu_app_id}",
            f"FEISHU_APP_SECRET={feishu_app_secret}",
            f"IMA_CLIENT_ID={ima_client_id}",
            f"IMA_API_KEY={ima_api_key}",
            f"IMA_KNOWLEDGE_BASE_ID={ima_knowledge_base_id}",
        ]
        with open(".env", "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
        return "✅ 配置已保存到 .env 文件"
    except Exception as e:
        return f"❌ 保存失败: {e}"


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
        theme=gr.themes.Soft(),
    ) as app:

        # 全局状态
        state = gr.State({})
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
                    ima_knowledge_base_id_input = gr.Textbox(
                        label="ima 知识库 ID（可选，留空仅建笔记）",
                        value=config.ima_knowledge_base_id,
                        placeholder="配置后同步会将笔记加入该知识库",
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
                    ima_kb_input = gr.Textbox(
                        label="ima 知识库 ID（留空仅建笔记）",
                        value=config.ima_knowledge_base_id,
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
                ima_client_id_input, ima_api_key_input, ima_knowledge_base_id_input,
            ],
            outputs=[save_config_status],
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

        # Tab 5: 同步到 ima
        sync_ima_btn.click(
            fn=do_sync_ima,
            inputs=[
                ima_video_ids, ima_kb_input,
                ima_client_id_input, ima_api_key_input,
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
    )


if __name__ == "__main__":
    main()
