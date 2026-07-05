"""
视频抓取模块

支持多平台视频收藏夹抓取：B站、抖音、小红书
"""

from src.scraper.base import ScraperBase, FavoriteFolder, VideoInfo, SubtitleSegment


def __getattr__(name):
    """延迟导入平台抓取器，避免循环引用"""
    if name == "BilibiliScraper":
        from src.platforms.bilibili import BilibiliScraper
        return BilibiliScraper
    if name == "DouyinScraper":
        from src.scraper.douyin import DouyinScraper
        return DouyinScraper
    if name == "XhsScraper":
        from src.scraper.xhs import XhsScraper
        return XhsScraper
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


__all__ = [
    "ScraperBase",
    "FavoriteFolder",
    "VideoInfo",
    "SubtitleSegment",
    "BilibiliScraper",
    "DouyinScraper",
    "XhsScraper",
]
