"""
飞书文档同步模块

通过飞书开放 API 将分析结果写入飞书文档。
"""

import time
import logging
from datetime import datetime
from typing import Optional, List, Dict

import httpx

from .base import SyncBase

logger = logging.getLogger(__name__)


class FeishuSync(SyncBase):
    """飞书文档同步实现"""

    BASE_URL = "https://open.feishu.cn/open-apis"

    # 飞书 docx API block_type 数字映射（来源：飞书开放平台官方文档）
    BLOCK_PAGE = 1
    BLOCK_TEXT = 2
    BLOCK_HEADING1 = 3
    BLOCK_HEADING2 = 4
    BLOCK_HEADING3 = 5
    # 6-11 为 heading4-heading9，项目中暂不使用
    BLOCK_BULLET = 12   # 无序列表
    BLOCK_ORDERED = 13  # 有序列表
    BLOCK_CODE = 14     # 代码块
    BLOCK_QUOTE = 15    # 引用
    BLOCK_TODO = 17     # 待办事项
    BLOCK_CALLOUT = 19  # 高亮块
    BLOCK_DIVIDER = 22  # 分割线
    BLOCK_IMAGE = 27    # 图片
    BLOCK_TABLE = 31    # 表格

    def __init__(self, app_id: str, app_secret: str):
        """初始化飞书同步模块

        Args:
            app_id: 飞书应用的 App ID
            app_secret: 飞书应用的 App Secret
        """
        self.app_id = app_id
        self.app_secret = app_secret
        self._token: Optional[str] = None
        self._token_expire_at: float = 0
        self._client: Optional[httpx.AsyncClient] = None

    # ------------------------------------------------------------------
    # 连接与认证
    # ------------------------------------------------------------------

    async def connect(self) -> bool:
        """获取 tenant_access_token

        POST /open-apis/auth/v3/tenant_access_token/internal
        Body: {"app_id": "...", "app_secret": "..."}
        返回: {"tenant_access_token": "...", "expire": 7200}
        """
        url = f"{self.BASE_URL}/auth/v3/tenant_access_token/internal"
        payload = {"app_id": self.app_id, "app_secret": self.app_secret}

        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(url, json=payload)
            resp.raise_for_status()
            data = resp.json()

        if data.get("code") != 0:
            logger.error("获取飞书 token 失败: %s", data.get("msg"))
            return False

        self._token = data["tenant_access_token"]
        # 提前 5 分钟刷新，避免临界过期
        self._token_expire_at = time.time() + data.get("expire", 7200) - 300
        logger.info("飞书 token 获取成功，有效期 %s 秒", data.get("expire", 7200))
        return True

    async def _ensure_token(self):
        """确保 token 有效，过期前自动刷新"""
        if self._token is None or time.time() >= self._token_expire_at:
            await self.connect()

    async def _get_client(self) -> httpx.AsyncClient:
        """获取或创建 httpx 客户端"""
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(timeout=30)
        return self._client

    async def close(self):
        """关闭 HTTP 客户端"""
        if self._client and not self._client.is_closed:
            await self._client.aclose()

    async def _request(self, method: str, path: str, **kwargs) -> dict:
        """统一的 API 请求方法

        自动携带 Authorization: Bearer {token} 头。
        处理响应格式: {"code": 0, "msg": "success", "data": {...}}
        """
        await self._ensure_token()

        url = f"{self.BASE_URL}{path}"
        headers = kwargs.pop("headers", {})
        headers["Authorization"] = f"Bearer {self._token}"

        client = await self._get_client()
        resp = await client.request(method, url, headers=headers, **kwargs)

        if resp.status_code >= 400:
            logger.error("飞书 HTTP 错误 [%s %s]: status=%s, body=%s", method, path, resp.status_code, resp.text)
            # 解析响应体，提取权限错误信息
            try:
                err_data = resp.json()
                err_code = err_data.get("code", 0)
                if err_code == 99991672:
                    violations = err_data.get("error", {}).get("permission_violations", [])
                    scopes = [v.get("subject", "") for v in violations]
                    raise RuntimeError(
                        f"飞书应用缺少必要权限，请到开发者后台开通以下任一权限：{scopes}\n"
                        f"开通链接：https://open.feishu.cn/app/{self.app_id}/auth"
                    )
            except (ValueError, KeyError):
                pass
            resp.raise_for_status()

        data = resp.json()

        if data.get("code") != 0:
            logger.error("飞书 API 错误 [%s]: code=%s, msg=%s", path, data.get("code"), data.get("msg"))
            raise RuntimeError(f"飞书 API 错误: {data.get('msg')} (code={data.get('code')})")

        return data.get("data", {})

    # ------------------------------------------------------------------
    # 文件夹操作
    # ------------------------------------------------------------------

    async def get_root_folder_token(self) -> str:
        """获取应用根文件夹 token

        GET /open-apis/drive/explorer/v2/root_folder/meta
        返回根文件夹 token
        """
        data = await self._request("GET", "/drive/explorer/v2/root_folder/meta")
        token = data.get("token", "")
        logger.info("获取根文件夹 token: %s", token)
        return token

    async def create_folder(self, name: str, parent_id: str = None) -> str:
        """创建飞书文件夹

        POST /open-apis/drive/v1/files/create_folder
        folder_token 为必填字段，未指定时自动使用根文件夹。
        返回文件夹 token
        """
        if not parent_id:
            parent_id = await self.get_root_folder_token()

        body: dict = {"name": name, "folder_token": parent_id}
        logger.info("创建文件夹请求: name=%s, folder_token=%s", name, parent_id)

        data = await self._request("POST", "/drive/v1/files/create_folder", json=body)
        token = data.get("token", "")
        logger.info("创建文件夹成功: %s -> %s", name, token)
        return token

    # ------------------------------------------------------------------
    # 文档操作
    # ------------------------------------------------------------------

    async def create_document(self, title: str, content: dict, folder_id: str = None) -> str:
        """创建飞书文档并写入内容

        1. 创建空文档
        2. 获取文档根 block
        3. 向文档写入内容

        返回 document_id
        """
        # 1. 创建文档
        create_body: dict = {"title": title}
        if folder_id:
            create_body["folder_token"] = folder_id

        doc_data = await self._request("POST", "/docx/v1/documents", json=create_body)
        document_id = doc_data.get("document", {}).get("document_id", "")
        logger.info("创建文档成功: %s -> %s", title, document_id)

        # 2. 构建并写入内容 blocks
        blocks = self._build_document_blocks(content)
        if blocks:
            # 获取根 block（文档根 block id 等于 document_id）
            root_block = await self._request(
                "GET", f"/docx/v1/documents/{document_id}/blocks/{document_id}"
            )
            block_id = root_block.get("block", {}).get("block_id", document_id)

            # 分批写入，每批最多 50 个 block（飞书 API 限制）
            batch_size = 50
            for i in range(0, len(blocks), batch_size):
                batch = blocks[i : i + batch_size]
                await self._request(
                    "POST",
                    f"/docx/v1/documents/{document_id}/blocks/{block_id}/children",
                    json={"children": batch, "index": -1},
                )

        return document_id

    async def update_document(self, document_id: str, content: dict) -> bool:
        """更新已有文档内容

        删除旧内容 block，写入新内容。
        """
        # 1. 获取文档根 block 下的所有子 block
        root_data = await self._request(
            "GET", f"/docx/v1/documents/{document_id}/blocks/{document_id}/children"
        )
        children = root_data.get("items", [])

        # 2. 逐个删除旧 block（每次删除索引 0 处的 block，后续 block 自动前移）
        for child in children:
            child_id = child.get("block_id")
            if child_id:
                try:
                    await self._request(
                        "DELETE",
                        f"/docx/v1/documents/{document_id}/blocks/{document_id}/children/batch_delete",
                        json={"start_index": 0, "end_index": 1},
                    )
                except Exception as e:
                    logger.warning("删除旧 block 失败: %s", e)

        # 3. 写入新内容
        blocks = self._build_document_blocks(content)
        if blocks:
            batch_size = 50
            for i in range(0, len(blocks), batch_size):
                batch = blocks[i : i + batch_size]
                await self._request(
                    "POST",
                    f"/docx/v1/documents/{document_id}/blocks/{document_id}/children",
                    json={"children": batch, "index": -1},
                )

        logger.info("更新文档成功: %s", document_id)
        return True

    async def check_document_exists(self, video_id: str, folder_token: str = None) -> Optional[str]:
        """检查文档是否已存在

        通过文件夹内文件列表查找匹配 video_id 的文档。
        文档标题格式约定: "[video_id] 视频标题"
        返回文档 ID 或 None
        """
        if not folder_token:
            return None

        page_token = ""
        while True:
            params: dict = {"folder_token": folder_token, "page_size": 50}
            if page_token:
                params["page_token"] = page_token

            data = await self._request("GET", "/drive/v1/files", params=params)
            files = data.get("files", [])

            for f in files:
                # 文件名格式: [video_id] xxx
                name = f.get("name", "")
                if name.startswith(f"[{video_id}]"):
                    logger.info("发现已存在文档: %s -> %s", name, f.get("token"))
                    return f.get("token")

            # 翻页
            if not data.get("has_more"):
                break
            page_token = data.get("page_token", "")
            if not page_token:
                break

        return None

    # ------------------------------------------------------------------
    # Block 构建
    # ------------------------------------------------------------------

    @staticmethod
    def _make_text_element(text: str, bold: bool = False) -> dict:
        """构建单个文本元素"""
        element: dict = {"text_run": {"content": text}}
        if bold:
            element["text_run"]["text_element_style"] = {"bold": True}
        return element

    @staticmethod
    def _make_link_element(text: str, url: str) -> dict:
        """构建链接文本元素"""
        return {
            "text_run": {
                "content": text,
                "text_element_style": {"link": {"url": url}},
            }
        }

    def _make_block(self, block_type: int, text_content: str = "", elements: list = None) -> dict:
        """构建飞书文档 Block

        Args:
            block_type: block 类型数字
            text_content: 纯文本快捷方式
            elements: 自定义元素列表（优先使用）
        """
        # block_type 到字段名的映射
        type_field_map = {
            self.BLOCK_TEXT: "text",
            self.BLOCK_HEADING1: "heading1",
            self.BLOCK_HEADING2: "heading2",
            self.BLOCK_HEADING3: "heading3",
            self.BLOCK_BULLET: "bullet",
            self.BLOCK_ORDERED: "ordered",
            self.BLOCK_QUOTE: "quote",
            self.BLOCK_TODO: "todo",
            self.BLOCK_CALLOUT: "callout",
        }

        field_name = type_field_map.get(block_type, "text")

        if elements is None:
            elements = [self._make_text_element(text_content)]

        block: dict = {
            "block_type": block_type,
            field_name: {"elements": elements, "style": {}},
        }
        return block

    def _build_document_blocks(self, content: dict) -> list:
        """将知识卡片内容转换为飞书文档 Block 结构

        content 字段:
            video_id, title, author, source_url, summary,
            keywords, key_points, action_items, transcript, analysis
        """
        blocks: list = []

        # ---- Heading1: 视频标题 ----
        title = content.get("title", "未知标题")
        blocks.append(self._make_block(self.BLOCK_HEADING1, title))

        # ---- 元信息区 ----
        author = content.get("author", "")
        source_url = content.get("source_url", "")
        now_str = datetime.now().strftime("%Y-%m-%d %H:%M")

        meta_elements = []
        if author:
            meta_elements.append(self._make_text_element(f"作者: {author}"))
            meta_elements.append(self._make_text_element("  |  "))
        meta_elements.append(self._make_text_element(f"同步时间: {now_str}"))
        blocks.append(self._make_block(self.BLOCK_TEXT, elements=meta_elements))

        if source_url:
            blocks.append(
                self._make_block(
                    self.BLOCK_TEXT,
                    elements=[
                        self._make_text_element("来源: "),
                        self._make_link_element(source_url, source_url),
                    ],
                )
            )

        # 分割线
        blocks.append({"block_type": self.BLOCK_DIVIDER, "divider": {}})

        # ---- 摘要 ----
        summary = content.get("summary", "")
        if summary:
            blocks.append(self._make_block(self.BLOCK_HEADING2, "摘要"))
            blocks.append(self._make_block(self.BLOCK_TEXT, summary))

        # ---- 关键词 ----
        keywords = content.get("keywords", [])
        if keywords:
            blocks.append(self._make_block(self.BLOCK_HEADING2, "关键词"))
            kw_text = "、".join(keywords)
            blocks.append(self._make_block(self.BLOCK_TEXT, kw_text))

        # ---- 核心要点 ----
        key_points = content.get("key_points", [])
        if key_points:
            blocks.append(self._make_block(self.BLOCK_HEADING2, "核心要点"))
            for idx, kp in enumerate(key_points, 1):
                if isinstance(kp, dict):
                    point = kp.get("point", "")
                    detail = kp.get("detail", "")
                    text = f"{idx}. {point}"
                    if detail:
                        text += f" — {detail}"
                else:
                    text = f"{idx}. {kp}"
                blocks.append(self._make_block(self.BLOCK_BULLET, text))

        # ---- 行动建议 ----
        action_items = content.get("action_items", [])
        if action_items:
            blocks.append(self._make_block(self.BLOCK_HEADING2, "行动建议"))
            for item in action_items:
                blocks.append(self._make_block(self.BLOCK_ORDERED, item))

        # 分割线
        blocks.append({"block_type": self.BLOCK_DIVIDER, "divider": {}})

        # ---- 逐字稿 ----
        transcript = content.get("transcript", "")
        if transcript:
            blocks.append(self._make_block(self.BLOCK_HEADING2, "完整逐字稿"))
            blocks.append(self._make_block(self.BLOCK_TEXT, transcript))

        return blocks
