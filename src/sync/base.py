"""
同步模块抽象基类

定义文档同步的通用接口，预留 IMA 等平台扩展。
"""

from abc import ABC, abstractmethod


class SyncBase(ABC):
    """同步模块抽象基类，预留 IMA 等平台扩展"""

    @abstractmethod
    async def connect(self) -> bool:
        """连接到目标平台"""

    @abstractmethod
    async def create_folder(self, name: str, parent_id: str | None = None) -> str:
        """创建文件夹，返回文件夹 ID"""

    @abstractmethod
    async def create_document(self, title: str, content: dict, folder_id: str | None = None) -> str:
        """创建文档，返回文档 ID"""

    @abstractmethod
    async def update_document(self, document_id: str, content: dict) -> bool:
        """更新已有文档"""

    @abstractmethod
    async def check_document_exists(self, video_id: str, folder_token: str | None = None) -> str | None:
        """检查文档是否已存在（基于视频 ID 去重），返回文档 ID 或 None"""
