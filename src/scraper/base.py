"""
视频抓取模块 - 抽象基类与数据模型

定义所有平台抓取器的接口规范和数据模型。
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Optional


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
