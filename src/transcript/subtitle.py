"""
字幕处理模块

处理平台提供的字幕数据解析和格式化，以及统一逐字稿获取接口。
"""

from __future__ import annotations

import logging
import re
from pathlib import Path
from typing import TYPE_CHECKING

from src.scraper.base import SubtitleSegment

if TYPE_CHECKING:
    from src.scraper.base import ScraperBase
    from src.transcript.whisper_asr import WhisperTranscriber

logger = logging.getLogger(__name__)


def _audio_cache_path(video_id: str, scraper: "ScraperBase") -> Path:
    """Return a stable cache path independent of expiring CDN signatures."""
    from config import get_config

    platform = scraper.__class__.__name__.removesuffix("Scraper").lower() or "video"
    safe_video_id = re.sub(r"[^A-Za-z0-9._-]+", "_", str(video_id)).strip("._")
    cache_dir = Path(get_config().audio_cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)
    return cache_dir / f"audio_{platform}_{safe_video_id or 'unknown'}.mp4"


class SubtitleParser:
    """字幕解析器，负责解析各平台的字幕数据并转换为统一格式"""

    @staticmethod
    def parse_bilibili_subtitle(subtitle_json: dict) -> list[SubtitleSegment]:
        """解析 Bilibili 字幕 JSON

        Bilibili 字幕格式:
        {
            "body": [
                {"from": 0.0, "to": 2.5, "content": "大家好"},
                {"from": 2.5, "to": 5.0, "content": "今天我们来聊..."},
                ...
            ]
        }

        Args:
            subtitle_json: Bilibili 字幕原始 JSON 数据

        Returns:
            SubtitleSegment 列表
        """
        body = subtitle_json.get("body", [])
        if not body:
            logger.warning("Bilibili 字幕 JSON 中 body 为空")
            return []

        segments = []
        for item in body:
            try:
                segment = SubtitleSegment(
                    start=float(item.get("from", 0)),
                    end=float(item.get("to", 0)),
                    text=item.get("content", "").strip(),
                )
                # 跳过空文本片段
                if segment.text:
                    segments.append(segment)
            except (ValueError, TypeError) as e:
                logger.warning(f"解析字幕片段失败: {item}, 错误: {e}")
                continue

        logger.info(f"成功解析 {len(segments)} 条 Bilibili 字幕片段")
        return segments

    @staticmethod
    def merge_segments(segments: list[SubtitleSegment], max_gap: float = 1.0) -> list[SubtitleSegment]:
        """合并相邻的字幕片段，减少碎片化

        如果两个片段间隔小于 max_gap，合并为一个片段。

        Args:
            segments: 原始字幕片段列表
            max_gap: 最大间隔（秒），小于此值的片段将被合并

        Returns:
            合并后的字幕片段列表
        """
        if not segments:
            return []

        merged: list[SubtitleSegment] = [segments[0]]

        for seg in segments[1:]:
            last = merged[-1]
            # 如果当前片段与上一个片段的间隔小于阈值，合并
            if seg.start - last.end <= max_gap:
                merged[-1] = SubtitleSegment(
                    start=last.start,
                    end=seg.end,
                    text=last.text + seg.text,
                )
            else:
                merged.append(seg)

        logger.info(f"合并字幕片段: {len(segments)} -> {len(merged)}")
        return merged

    @staticmethod
    def segments_to_text(segments: list[SubtitleSegment]) -> str:
        """将字幕片段转换为纯文本（用于送入 LLM 分析）

        直接拼接所有 text 字段。

        Args:
            segments: 字幕片段列表

        Returns:
            纯文本字符串
        """
        return "".join(seg.text for seg in segments)

    @staticmethod
    def segments_to_text_with_timestamps(segments: list[SubtitleSegment]) -> str:
        """将字幕片段转换为带时间戳的文本

        格式: [HH:MM:SS] 文本内容

        Args:
            segments: 字幕片段列表

        Returns:
            带时间戳的文本字符串
        """

        def _format_time(seconds: float) -> str:
            """将秒数格式化为 HH:MM:SS"""
            h = int(seconds // 3600)
            m = int((seconds % 3600) // 60)
            s = int(seconds % 60)
            return f"{h:02d}:{m:02d}:{s:02d}"

        lines = [f"[{_format_time(seg.start)}] {seg.text}" for seg in segments]
        return "\n".join(lines)

    @staticmethod
    def segments_to_records(segments: list[SubtitleSegment]) -> list[dict]:
        """Convert transcript segments into JSON-serializable evidence records."""
        return [
            {"start": segment.start, "end": segment.end, "text": segment.text}
            for segment in segments
        ]


async def get_transcript(
    video_id: str,
    scraper: "ScraperBase",
    whisper_transcriber: "WhisperTranscriber | None" = None,
    download_headers: dict | None = None,
) -> tuple[list[SubtitleSegment], str]:
    """统一逐字稿获取接口

    流程：
    1. 先尝试从平台获取 CC 字幕
    2. 若无字幕，尝试 B站 AI 视频总结（需 scraper 支持 get_video_ai_summary）
    3. 若仍无内容且有 Whisper，下载音频用 Whisper 转写
    4. 返回 (字幕片段列表, 来源标识 "subtitle"/"ai_summary"/"whisper")

    Args:
        video_id: 视频 ID
        scraper: 平台抓取器实例
        whisper_transcriber: Whisper 转写器实例（可选）
        download_headers: 音频下载时注入的额外 HTTP 头（含平台 Cookie，解决 CDN 403）

    Returns:
        (字幕片段列表, 来源标识) 元组
        来源标识为 "subtitle"、"ai_summary" 或 "whisper"

    Raises:
        RuntimeError: 无法获取逐字稿时抛出
    """
    # 第一步：尝试从平台获取 CC 字幕
    logger.info(f"[{video_id}] 尝试从平台获取字幕...")
    try:
        segments = await scraper.get_video_subtitle(video_id)
        if segments:
            logger.info(f"[{video_id}] 成功获取平台字幕，共 {len(segments)} 条片段")
            return segments, "subtitle"
    except Exception as e:
        logger.warning(f"[{video_id}] 获取平台字幕失败: {e}")

    # 第二步：尝试 B站 AI 视频总结（在 Whisper 前兆底）
    ai_summary = await scraper.get_video_ai_summary(video_id)
    if ai_summary:
        logger.info(f"[{video_id}] B站 AI 总结获取成功，用作逐字稿兆底")
        segments = [SubtitleSegment(start=0.0, end=0.0, text=ai_summary)]
        return segments, "ai_summary"

    # 第三步：无字幕/AI总结，尝试 Whisper 兆底
    if whisper_transcriber is None:
        raise RuntimeError(
            f"[{video_id}] 平台无字幕/AI总结且未提供 Whisper 转写器，无法获取逐字稿"
        )

    logger.info(f"[{video_id}] 平台无字幕/AI总结，尝试 Whisper 语音识别...")
    local_audio_path = _audio_cache_path(video_id, scraper)
    if local_audio_path.exists() and local_audio_path.stat().st_size > 0:
        logger.info("[%s] 复用媒体缓存: %s", video_id, local_audio_path)
        try:
            segments = await whisper_transcriber.transcribe_audio_file(
                str(local_audio_path)
            )
            if segments:
                logger.info(f"[{video_id}] Whisper 转写成功，共 {len(segments)} 条片段")
                return segments, "whisper"
        except Exception as e:
            logger.warning("[%s] 媒体缓存不可用，将重新下载: %s", video_id, e)
            local_audio_path.unlink(missing_ok=True)

    audio_url = await scraper.get_video_audio_url(video_id)
    if not audio_url:
        raise RuntimeError(
            f"[{video_id}] 无法获取音频地址，Whisper 转写失败"
        )

    downloaded = await scraper.download_audio_to_file(audio_url, str(local_audio_path))
    if downloaded and local_audio_path.exists() and local_audio_path.stat().st_size > 0:
        try:
            segments = await whisper_transcriber.transcribe_audio_file(str(local_audio_path))
            if segments:
                logger.info(f"[{video_id}] Whisper 转写成功，共 {len(segments)} 条片段")
                return segments, "whisper"
            else:
                raise RuntimeError(f"[{video_id}] Whisper 转写结果为空")
        except Exception as e:
            raise RuntimeError(f"[{video_id}] Whisper 转写失败: {e}") from e

    logger.warning(f"[{video_id}] 平台媒体下载失败，使用通用下载回退...")

    # 回退：通过 httpx 下载（带 Cookie headers）
    try:
        segments = await whisper_transcriber.transcribe_audio_url(
            audio_url,
            output_path=str(local_audio_path),
            download_headers=download_headers,
        )
        if segments:
            logger.info(f"[{video_id}] Whisper 转写成功，共 {len(segments)} 条片段")
            return segments, "whisper"
        else:
            raise RuntimeError(f"[{video_id}] Whisper 转写结果为空")
    except Exception as e:
        raise RuntimeError(f"[{video_id}] Whisper 转写失败: {e}") from e
