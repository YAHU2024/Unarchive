"""
飞书同步模块

将分析结果同步到飞书文档。
"""

from .base import SyncBase
from .feishu import FeishuSync

__all__ = ["SyncBase", "FeishuSync"]
