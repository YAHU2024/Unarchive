"""
Whisper 语音转文字模块

使用 OpenAI Whisper 模型将音频转换为文字。
作为字幕不可用时的兜底方案。
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import os
from pathlib import Path
from typing import Optional

import httpx

from src.scraper.base import SubtitleSegment
from config import get_config

logger = logging.getLogger(__name__)


class WhisperTranscriber:
    """Whisper 语音转写器

    支持延迟加载模型，首次调用时才加载，避免启动时占用内存。
    """

    def __init__(self, model_name: str = "medium", device: str = "auto"):
        """初始化 Whisper 模型配置（延迟加载）

        Args:
            model_name: 模型名称 (tiny/base/small/medium/large)
            device: "auto" 自动检测 CUDA, "cpu" 强制 CPU, "cuda" 强制 GPU
        """
        self.model_name = model_name
        self.device = self._resolve_device(device)
        self._model = None  # 延迟加载，首次调用时才初始化
        logger.info(
            f"WhisperTranscriber 初始化: model={model_name}, device={self.device}（模型将在首次调用时加载）"
        )

    @staticmethod
    def _resolve_device(device: str) -> str:
        """解析设备配置

        Args:
            device: "auto" / "cpu" / "cuda"

        Returns:
            实际设备字符串 "cpu" 或 "cuda"
        """
        if device == "auto":
            try:
                import torch
                return "cuda" if torch.cuda.is_available() else "cpu"
            except ImportError:
                logger.warning("未安装 torch，无法自动检测设备，回退到 CPU")
                return "cpu"
        return device

    def _load_model(self):
        """加载 Whisper 模型（内部方法，线程安全由调用方保证）"""
        if self._model is not None:
            return

        logger.info(f"正在加载 Whisper 模型: {self.model_name} (设备: {self.device})...")
        try:
            import whisper
            self._model = whisper.load_model(self.model_name, device=self.device)
            logger.info(f"Whisper 模型 {self.model_name} 加载成功")
        except Exception as e:
            logger.error(f"Whisper 模型加载失败: {e}")
            raise RuntimeError(f"Whisper 模型加载失败: {e}") from e

    async def transcribe_audio_url(
        self, audio_url: str, output_path: Optional[str] = None,
        download_headers: Optional[dict] = None,
    ) -> list[SubtitleSegment]:
        """从音频 URL 下载音频并用 Whisper 转写

        1. 下载音频到本地临时文件
        2. 调用 Whisper 模型进行转写
        3. 返回 SubtitleSegment 列表

        Args:
            audio_url: 音频文件 URL
            output_path: 可选的本地保存路径，不指定则使用临时文件
            download_headers: 可选的额外 HTTP 请求头（含 Cookie 等鉴权信息）

        Returns:
            SubtitleSegment 列表
        """
        # 确定保存路径
        if output_path is None:
            config = get_config()
            cache_dir = Path(config.audio_cache_dir)
            cache_dir.mkdir(parents=True, exist_ok=True)
            # 从 URL 生成确定性哈希文件名
            url_hash = hashlib.sha256(audio_url.encode()).hexdigest()[:8]
            output_path = str(cache_dir / f"audio_{url_hash}.mp4")

        # 下载音频
        local_path = await asyncio.to_thread(
            self._download_audio, audio_url, output_path, download_headers
        )

        try:
            # 使用 Whisper 转写
            segments = await asyncio.to_thread(self._run_whisper, local_path)
            return segments
        finally:
            # 如果使用的是临时路径，清理文件
            if output_path and os.path.exists(local_path) and output_path != local_path:
                try:
                    os.remove(local_path)
                except OSError:
                    pass

    async def transcribe_audio_file(self, file_path: str) -> list[SubtitleSegment]:
        """从本地音频文件进行 Whisper 转写

        Args:
            file_path: 本地音频文件路径

        Returns:
            SubtitleSegment 列表

        Raises:
            FileNotFoundError: 音频文件不存在
            RuntimeError: 转写失败
        """
        if not os.path.exists(file_path):
            raise FileNotFoundError(f"音频文件不存在: {file_path}")

        logger.info(f"开始 Whisper 转写本地文件: {file_path}")
        segments = await asyncio.to_thread(self._run_whisper, file_path)
        logger.info(f"Whisper 转写完成，共 {len(segments)} 条片段")
        return segments

    def _download_audio(
        self, url: str, output_path: str,
        extra_headers: Optional[dict] = None,
    ) -> str:
        """下载音频文件

        优先使用 httpx 直接下载，如果失败回退到 yt-dlp 命令行。

        Args:
            url: 音频 URL
            output_path: 保存路径
            extra_headers: 额外请求头（Cookie 等鉴权信息）

        Returns:
            本地文件路径
        """
        # 构建基础请求头
        headers = {
            "Referer": "https://www.bilibili.com",
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/120.0.0.0 Safari/537.36"
            ),
        }
        # 注入平台 Cookie 等额外头（解决 CDN 403 问题）
        if extra_headers:
            headers.update(extra_headers)

        # 优先尝试 httpx 直接下载
        try:
            logger.info(f"使用 httpx 下载音频: {url[:120]}")
            with httpx.Client(timeout=120.0, follow_redirects=True) as client:
                resp = client.get(url, headers=headers)
                resp.raise_for_status()
                with open(output_path, "wb") as f:
                    f.write(resp.content)
            logger.info(f"音频下载成功: {output_path}")
            return output_path
        except Exception as e:
            logger.warning(f"httpx 下载失败: {e}，尝试 yt-dlp 回退...")

        # 回退到 yt-dlp
        try:
            logger.info(f"使用 yt-dlp 下载音频: {url}")
            import subprocess

            result = subprocess.run(
                [
                    "yt-dlp",
                    "-x",  # 仅提取音频
                    "--audio-format", "mp4",
                    "-o", output_path,
                    "--no-playlist",
                    url,
                ],
                capture_output=True,
                text=True,
                timeout=300,
            )
            if result.returncode != 0:
                raise RuntimeError(f"yt-dlp 退出码 {result.returncode}: {result.stderr}")
            logger.info(f"yt-dlp 下载成功: {output_path}")
            return output_path
        except FileNotFoundError:
            raise RuntimeError(
                "yt-dlp 未安装，无法下载音频。请安装: pip install yt-dlp"
            )
        except Exception as e:
            raise RuntimeError(f"音频下载失败: {e}") from e

    def _run_whisper(self, file_path: str) -> list[SubtitleSegment]:
        """运行 Whisper 推理

        使用 whisper.transcribe() 方法，解析结果转换为 SubtitleSegment。

        Args:
            file_path: 本地音频文件路径

        Returns:
            SubtitleSegment 列表
        """
        # 确保模型已加载
        self._load_model()

        logger.info(f"Whisper 开始转写: {file_path}")
        try:
            result = self._model.transcribe(
                file_path,
                language="zh",  # 默认中文
                task="transcribe",
            )
        except Exception as e:
            logger.error(f"Whisper 转写失败: {e}")
            raise RuntimeError(f"Whisper 转写失败: {e}") from e

        # 解析 Whisper 输出 segments
        segments = []
        for seg in result.get("segments", []):
            text = seg.get("text", "").strip()
            if text:
                segments.append(
                    SubtitleSegment(
                        start=float(seg.get("start", 0.0)),
                        end=float(seg.get("end", 0.0)),
                        text=text,
                    )
                )

        logger.info(f"Whisper 转写完成: {len(segments)} 条片段")
        return segments

    def cleanup(self):
        """清理临时文件和释放模型内存"""
        # 释放模型
        if self._model is not None:
            logger.info("释放 Whisper 模型内存...")
            self._model = None
            try:
                import torch
                torch.cuda.empty_cache()
            except (ImportError, RuntimeError):
                pass

        # 清理音频缓存目录中的临时文件
        try:
            config = get_config()
            cache_dir = Path(config.audio_cache_dir)
            if cache_dir.exists():
                for f in cache_dir.iterdir():
                    if f.is_file():
                        f.unlink()
                logger.info("音频缓存已清理")
        except Exception as e:
            logger.warning(f"清理音频缓存失败: {e}")
