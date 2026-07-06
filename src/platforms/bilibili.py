"""弃用模块 - 请改用 src.scraper.bilibili"""

import warnings

from src.scraper.bilibili import BilibiliScraper

warnings.warn(
    "从 src.platforms.bilibili 导入已弃用，请改用 src.scraper.bilibili",
    DeprecationWarning,
    stacklevel=2,
)

__all__ = ["BilibiliScraper"]
