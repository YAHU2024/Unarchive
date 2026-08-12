"""
视频抓取模块 - 抽象基类与数据模型

定义所有平台抓取器的接口规范和数据模型。
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum, auto
from typing import Optional


class VideoAvailability(Enum):
    """视频可用性检查结果（三态）"""
    AVAILABLE = auto()            # 可正常访问
    UNAVAILABLE = auto()          # 明确不可用（删除/下架/私密），应永久跳过
    TEMPORARY_ERROR = auto()      # 临时错误（网络/风控/Cookie 失效），应重试或报告失败


@dataclass
class SubtitleSegment:
    """字幕片段"""
    start: float  # 开始时间（秒）
    end: float    # 结束时间（秒）
    text: str     # 字幕文本


@dataclass
class VideoInfo:
    """视频信息"""
    video_id: str
    title: str
    url: str
    duration: Optional[float] = None  # 时长（秒）
    author: str = ""
    description: str = ""
    cover_url: str = ""
    tags: list[str] = field(default_factory=list)
    extra: dict = field(default_factory=dict)  # 平台特有的额外数据


@dataclass
class FavoriteFolder:
    """收藏夹信息"""
    folder_id: str
    title: str
    video_count: int = 0
    videos: list[VideoInfo] = field(default_factory=list)
    url: str = ""


class ScraperBase(ABC):
    """
    平台抓取器抽象基类

    所有平台（B站、抖音、小红书）的抓取器都必须继承此类并实现所有抽象方法。
    """

    @abstractmethod
    async def login(self) -> None:
        """
        登录平台

        通过 Playwright 加载 cookies 或执行登录流程。
        """
        ...

    @abstractmethod
    async def get_favorites(self) -> list[FavoriteFolder]:
        """
        获取用户的所有收藏夹列表

        Returns:
            收藏夹列表，不包含具体视频内容
        """
        ...

    @abstractmethod
    async def get_favorite_videos(self, folder_id: str) -> list[VideoInfo]:
        """
        获取指定收藏夹中的所有视频

        Args:
            folder_id: 收藏夹 ID

        Returns:
            该收藏夹中的视频信息列表
        """
        ...

    @abstractmethod
    async def get_video_subtitle(self, video_id: str) -> list[SubtitleSegment] | None:
        """
        获取视频字幕

        优先获取平台提供的字幕，若无则返回 None（后续由 Whisper 处理）。

        Args:
            video_id: 视频 ID

        Returns:
            字幕片段列表，若无字幕则返回 None
        """
        ...

    @abstractmethod
    async def get_video_audio_url(self, video_id: str) -> str | None:
        """
        获取视频的音频下载地址

        用于后续 Whisper 语音转文字。

        Args:
            video_id: 视频 ID

        Returns:
            音频文件 URL，若无法获取则返回 None
        """
        ...

    async def download_audio_to_file(self, audio_url: str, output_path: str) -> bool:
        """通过浏览器上下文下载音频文件，绕过 CDN 鉴权（默认返回 False）

        子类可重写以使用已登录的 BrowserContext 下载。
        """
        return False

    async def download_video(self, video_id: str, output_path: str) -> bool:
        """下载无水印视频文件（默认返回 False）

        子类可重写以实现平台特定的视频下载。
        """
        return False

    async def get_audio_cookies(self) -> dict:
        """获取浏览器上下文中的 Cookie，用于 httpx 音频下载回退路径（默认返回空字典）"""
        return {}

    async def close(self) -> None:
        """清理浏览器资源（默认空操作）"""
        return

    async def check_video_available(self, video_id: str) -> VideoAvailability:
        """检查视频是否可访问

        返回 VideoAvailability 枚举值：
        - AVAILABLE: 可正常访问
        - UNAVAILABLE: 明确不可用（删除/下架/私密），应永久跳过
        - TEMPORARY_ERROR: 临时错误（网络/风控等），应重试或报告失败
        """
        return VideoAvailability.AVAILABLE

    async def get_video_owner(self, video_id: str) -> str:
        """获取视频的真实作者名（默认返回空字符串）"""
        return ""

    async def get_video_ai_summary(self, video_id: str) -> str | None:
        """获取平台 AI 视频摘要/总结（默认返回 None）"""
        return None
