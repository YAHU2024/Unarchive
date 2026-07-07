"""
腾讯 ima 同步模块

通过 ima OpenAPI 将知识卡片同步到 ima：
1. 使用 import_doc 创建 Markdown 笔记（必选）
2. 按配置（可选）使用 add_knowledge 关联到指定知识库

凭证通过 HTTP Header 传递：
    ima-openapi-clientid: <client_id>
    ima-openapi-apikey:  <api_key>

响应统一结构：{"code": 0, "msg": "...", "data": {...}}，code=0 为成功。
"""

import logging
import re
from datetime import datetime
from typing import Optional, Dict, List, Tuple

import httpx

from .base import SyncBase

logger = logging.getLogger(__name__)


# 本地图片引用正则（ima 笔记不支持本地图片，需过滤）
_LOCAL_IMAGE_RE = re.compile(
    r"!\[[^\]]*\]\((file:///|/Users/|/home/|C:\\|D:\\|E:\\)[^)]*\)",
    re.IGNORECASE,
)


class ImaSync(SyncBase):
    """腾讯 ima 同步实现（建笔记 + 可选加入知识库）"""

    BASE_URL = "https://ima.qq.com"
    NOTE_BASE = "openapi/note/v1"
    WIKI_BASE = "openapi/wiki/v1"

    # 接口路径
    PATH_SEARCH_NOTE = f"{NOTE_BASE}/search_note"
    PATH_IMPORT_DOC = f"{NOTE_BASE}/import_doc"
    PATH_LIST_NOTEBOOK = f"{NOTE_BASE}/list_notebook"
    PATH_ADD_KNOWLEDGE = f"{WIKI_BASE}/add_knowledge"

    def __init__(self, client_id: str, api_key: str, knowledge_base_id: str = ""):
        """初始化 ima 同步模块

        Args:
            client_id: ima OpenAPI Client ID
            api_key: ima OpenAPI API Key
            knowledge_base_id: 知识库 ID，留空则仅建笔记不进知识库
        """
        self.client_id = client_id
        self.api_key = api_key
        self.knowledge_base_id = knowledge_base_id or ""
        self._client: Optional[httpx.AsyncClient] = None

    # ------------------------------------------------------------------
    # 连接与请求
    # ------------------------------------------------------------------

    async def connect(self) -> bool:
        """验证 ima 凭证有效性（通过一次 list_notebook）"""
        if not self.client_id or not self.api_key:
            logger.error("ima 凭证缺失：client_id / api_key 未配置")
            return False
        try:
            # 用 list_notebook 做凭证校验：无需 query_info，参数恒定合法，
            # 同时避免 search_note 对空 query_info 返回 100001 参数错误。
            await self._call(self.PATH_LIST_NOTEBOOK, {"cursor": "0", "limit": 1})
            logger.info("ima 连接成功")
            return True
        except Exception as e:
            logger.error("ima 连接失败: %s", e)
            return False

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(timeout=30, trust_env=False)
        return self._client

    async def close(self):
        """关闭 HTTP 客户端"""
        if self._client and not self._client.is_closed:
            await self._client.aclose()

    async def _call(self, path: str, body: dict) -> dict:
        """统一的 ima API 请求方法

        自动携带 ima-openapi-* 头，处理响应结构 {code, msg, data}。
        """
        url = f"{self.BASE_URL}/{path}"
        headers = {
            "ima-openapi-clientid": self.client_id,
            "ima-openapi-apikey": self.api_key,
            "ima-openapi-ctx": "skill_version=unknown",
            "Content-Type": "application/json",
        }

        client = await self._get_client()
        resp = await client.post(url, headers=headers, json=body)

        if resp.status_code >= 400:
            logger.error("ima HTTP 错误 [%s]: status=%s, body=%s", path, resp.status_code, resp.text)
            resp.raise_for_status()

        data = resp.json()
        if data.get("code") != 0:
            logger.error("ima API 错误 [%s]: code=%s, msg=%s", path, data.get("code"), data.get("msg"))
            raise RuntimeError(f"ima API 错误: {data.get('msg')} (code={data.get('code')})")

        return data.get("data", {})

    # ------------------------------------------------------------------
    # 文件夹（笔记本）操作
    # ------------------------------------------------------------------

    async def create_folder(self, name: str, parent_id: str = None) -> str:
        """解析/创建 ima 笔记目标笔记本

        ima OpenAPI 未直接提供"创建笔记本"接口，此处按名称查找已有笔记本，
        未找到则返回 ""（默认根目录）。
        """
        try:
            folder_id = await self._find_notebook(name)
        except Exception as e:
            logger.warning("查找 ima 笔记本失败，回退到根目录: %s", e)
            folder_id = ""
        if folder_id:
            logger.info("使用已有 ima 笔记本: %s -> %s", name, folder_id)
        else:
            logger.info("未找到 ima 笔记本 %r，使用根目录", name)
        return folder_id

    async def _find_notebook(self, name: str) -> str:
        """按名称查找笔记本 folder_id（游标分页）"""
        cursor = "0"
        while True:
            # list_notebook: 0 < limit <= 20，响应字段为 note_folder_infos
            data = await self._call(
                self.PATH_LIST_NOTEBOOK, {"cursor": cursor, "limit": 20}
            )
            folders = (
                data.get("note_folder_infos")
                or data.get("note_folder_info_list")
                or data.get("folders")
                or []
            )
            for f in folders:
                if f.get("name") == name:
                    return f.get("folder_id", "")

            if data.get("is_end", True):
                break
            cursor = data.get("next_cursor", "")
            if not cursor:
                break
        return ""

    # ------------------------------------------------------------------
    # 文档操作
    # ------------------------------------------------------------------

    async def create_document(self, title: str, content: dict, folder_id: str = None) -> str:
        """创建 ima 笔记（Markdown），成功后可选加入知识库

        返回 note_id
        """
        # 1. 构建并校验 Markdown
        markdown = self._build_markdown(title, content)
        markdown, filtered = self._filter_local_images(markdown)
        if filtered:
            logger.warning(
                "ima 笔记不支持本地图片，已过滤 %d 处引用: %s", len(filtered), filtered
            )
        # 严格 UTF-8 校验（避免代理字符导致不可逆乱码）
        markdown.encode("utf-8", "strict")

        # 2. 新建笔记
        body: dict = {"content_format": 1, "content": markdown}
        if folder_id:
            body["folder_id"] = folder_id

        data = await self._call(self.PATH_IMPORT_DOC, body)
        note_id = data.get("note_id", "")
        logger.info("ima 笔记创建成功: %s", note_id)

        # 3. 可选：加入知识库
        if self.knowledge_base_id and note_id:
            try:
                await self._add_to_knowledge_base(note_id, title)
            except Exception as e:
                # 笔记已建成功，知识库失败仅告警，不中断整体同步
                logger.warning("笔记已创建，但加入知识库失败: %s", e)

        return note_id

    async def update_document(self, document_id: str, content: dict) -> bool:
        """更新已有 ima 笔记

        ima 不支持替换正文，唯一"更新"方式是 append_doc 追加内容。
        因同步去重已跳过已存在笔记，本方法仅用于显式更新场景，
        这里采用追加方式，避免静默覆盖用户已有笔记。
        """
        markdown = self._build_markdown("", content)
        markdown, filtered = self._filter_local_images(markdown)
        if filtered:
            logger.warning("ima 更新时过滤了 %d 处本地图片引用", len(filtered))
        markdown.encode("utf-8", "strict")

        body = {
            "note_id": document_id,
            "content_format": 1,
            "content": f"\n\n---\n\n{markdown}",
        }
        await self._call(self.PATH_IMPORT_DOC.replace("import_doc", "append_doc"), body)
        logger.info("ima 笔记追加更新成功: %s", document_id)
        return True

    async def check_document_exists(self, video_id: str, folder_token: str = None) -> Optional[str]:
        """检查笔记是否已存在（按标题前缀 [video_id] 去重）

        搜索接口按标题检索，匹配标题以 `[video_id]` 开头的笔记。
        返回 note_id 或 None
        """
        prefix = f"[{video_id}]"
        start, page = 0, 20
        while True:
            data = await self._call(
                self.PATH_SEARCH_NOTE,
                {
                    "search_type": 0,
                    "query_info": {"title": video_id},
                    "start": start,
                    "end": start + page,
                },
            )
            infos = data.get("search_note_infos", []) or []
            for info in infos:
                nb = info.get("note_book_info", {})
                title = nb.get("title", "")
                note_id = nb.get("note_id", "")
                if title.startswith(prefix) and note_id:
                    logger.info("ima 发现已有笔记: %s -> %s", title, note_id)
                    return note_id

            if data.get("is_end", True):
                break
            start += page
            total = data.get("total", start + page)
            if start >= total:
                break

        return None

    # ------------------------------------------------------------------
    # 知识库关联
    # ------------------------------------------------------------------

    async def _add_to_knowledge_base(self, note_id: str, title: str) -> None:
        """将已有笔记关联到知识库（media_type=11 表示笔记）"""
        body = {
            "media_type": 11,
            "note_info": {"content_id": note_id},
            "title": title,
            "knowledge_base_id": self.knowledge_base_id,
        }
        await self._call(self.PATH_ADD_KNOWLEDGE, body)
        logger.info("ima 笔记已加入知识库 %s: %s", self.knowledge_base_id, note_id)

    # ------------------------------------------------------------------
    # Markdown 构建与清洗
    # ------------------------------------------------------------------

    @staticmethod
    def _build_markdown(title: str, content: dict) -> str:
        """将知识卡片内容转换为 Markdown（结构与飞书 block 等价）"""
        lines: List[str] = []

        title = content.get("title") or title or "未知标题"
        lines.append(f"# {title}")

        # ---- 元信息 ----
        author = content.get("author", "")
        source_url = content.get("source_url", "")
        now_str = datetime.now().strftime("%Y-%m-%d %H:%M")

        meta: List[str] = []
        if author:
            meta.append(f"**作者**: {author}")
        meta.append(f"**同步时间**: {now_str}")
        if meta:
            lines.append(" | ".join(meta))
        if source_url:
            lines.append(f"**来源**: [{source_url}]({source_url})")

        lines.append("---")

        # ---- 摘要 ----
        summary = content.get("summary", "")
        if summary:
            lines.append("## 摘要")
            lines.append(summary)

        # ---- 关键词 ----
        keywords = content.get("keywords", [])
        if keywords:
            lines.append("## 关键词")
            lines.append("、".join(keywords))

        # ---- 核心要点 ----
        key_points = content.get("key_points", [])
        if key_points:
            lines.append("## 核心要点")
            for idx, kp in enumerate(key_points, 1):
                if isinstance(kp, dict):
                    point = kp.get("point", "")
                    detail = kp.get("detail", "")
                    text = f"{idx}. {point}"
                    if detail:
                        text += f" — {detail}"
                else:
                    text = f"{idx}. {kp}"
                lines.append(text)

        # ---- 行动建议 ----
        action_items = content.get("action_items", [])
        if action_items:
            lines.append("## 行动建议")
            for item in action_items:
                lines.append(f"- {item}")

        lines.append("---")

        # ---- 逐字稿 ----
        transcript = content.get("transcript", "")
        if transcript:
            lines.append("## 完整逐字稿")
            lines.append(transcript)

        return "\n\n".join(lines)

    @staticmethod
    def _filter_local_images(markdown: str) -> Tuple[str, List[str]]:
        """过滤本地图片引用，返回 (清洗后文本, 被过滤的引用列表)"""
        filtered = _LOCAL_IMAGE_RE.findall(markdown)
        cleaned = _LOCAL_IMAGE_RE.sub("", markdown)
        return cleaned, filtered
