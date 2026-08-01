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

import asyncio
import logging
import re
import time
from datetime import datetime
from typing import Dict, List, Tuple

import httpx

from .base import SyncBase

logger = logging.getLogger(__name__)


class ImaQuotaExceededError(RuntimeError):
    """ima 每日配额耗尽（code 200005），不可重试，应停止同步。

    Note:
        "create_document()" 仅做 import_doc 一步，失败时本异常没有 note_id；
        KB 关联由调用方显式调 ``add_to_knowledge_base(note_id, ...)``，部分成功
        状态（笔记已建、KB 未关联）由调用方通过 ``ima_sync_state.json`` 持久化。
    """


class ImaRateLimitError(RuntimeError):
    """ima 请求频率超限（code 200001），可重试"""


# 本地图片引用正则（ima 笔记不支持本地图片，需过滤）
_LOCAL_IMAGE_RE = re.compile(
    r"!\[[^\]]*\]\((file:///|/Users/|/home/|C:\\|D:\\|E:\\)[^)]*\)",
    re.IGNORECASE,
)


def _match_legacy_title(title: str, video_id: str) -> bool:
    """Match legacy title format: raw video_id at start followed by a boundary.

    Before strict [video_id] prefix enforcement, some notes were created with
    titles like "BV123 - Some Title" (no brackets). This provides backward
    compatibility with explicit boundary matching to avoid false positives.

    Boundaries: whitespace, dash, colon, pipe, underscore, or title ends,
    or title continues with '[' or '('.
    """
    if not title.startswith(video_id):
        return False
    after = title[len(video_id):]
    if not after:
        return True
    return after[0] in (" ", "\t", "-", "_", ":", "|") or after[:1] in ("[", "(")


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
    PATH_SEARCH_KB = f"{WIKI_BASE}/search_knowledge_base"
    PATH_GET_KB_LIST = f"{WIKI_BASE}/get_knowledge_list"
    PATH_SEARCH_KB_ITEM = f"{WIKI_BASE}/search_knowledge"
    PATH_GET_ADDABLE_KB = f"{WIKI_BASE}/get_addable_knowledge_base_list"
    PATH_GET_KB_INFO = f"{WIKI_BASE}/get_knowledge_base"

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
        self._client: httpx.AsyncClient | None = None

        # 请求节流：相邻 API 调用最小间隔（秒），降低频率限制（200001）概率
        self._last_call = 0.0
        self._min_interval = 0.5
        # 频率限制重试：指数退避参数
        self._max_retry = 5
        self._retry_base = 2.0
        self._retry_cap = 30.0

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

    @staticmethod
    def _handle_business_error(code: int, msg: str) -> None:
        """统一处理 ima 业务错误码（与 HTTP 状态码无关）

        IMA API 在 HTTP 200 / 429 等不同状态码下均可能返回业务错误。
        此函数将判定逻辑集中到一处，调用方无需关心 HTTP 状态码。

        Raises:
            ImaQuotaExceededError: code 200005（每日配额耗尽，不可重试）
            ImaRateLimitError:    code 200001（频率超限，可指数退避重试）
            RuntimeError:         其它非零业务码
        """
        if code == 0:
            return
        if code == 200005:
            raise ImaQuotaExceededError(msg or "请求超量，请明日再试")
        if code == 200001:
            raise ImaRateLimitError(msg or "请求频率超限，请稍后重试")
        raise RuntimeError(f"ima API 错误: {msg} (code={code})")

    async def _call(self, path: str, body: dict, _retry: int = 0) -> dict:
        """统一的 ima API 请求方法

        自动携带 ima-openapi-* 头，处理响应结构 {code, msg, data}。

        业务错误通过 _handle_business_error 统一处理，与 HTTP 状态码无关：
        - code 200005（请求超量/每日配额耗尽）：抛出 ImaQuotaExceededError，调用方应停止同步
        - code 200001（请求频率超限）：指数退避重试，耗尽后抛 ImaRateLimitError
        - 其它错误：抛出 RuntimeError，附上 API 返回的 msg
        """
        url = f"{self.BASE_URL}/{path}"
        headers = {
            "ima-openapi-clientid": self.client_id,
            "ima-openapi-apikey": self.api_key,
            "ima-openapi-ctx": "skill_version=unknown",
            "Content-Type": "application/json",
        }

        # 请求间节流，降低频率限制概率
        now = time.monotonic()
        gap = self._min_interval - (now - self._last_call)
        if gap > 0:
            await asyncio.sleep(gap)
        self._last_call = time.monotonic()

        client = await self._get_client()
        resp = await client.post(url, headers=headers, json=body)

        # 解析响应体（无论 HTTP 状态码，IMA 都在 body 里返回业务码）
        try:
            data = resp.json()
        except Exception:
            raise RuntimeError(
                f"ima API 响应非 JSON (status={resp.status_code}, path={path})"
            )

        code = data.get("code", 0)
        msg = data.get("msg", "")
        logger.debug(
            "ima 响应 [%s] HTTP=%s code=%s msg=%s",
            path, resp.status_code, code, msg,
        )

        # 统一业务错误处理（适用于 HTTP 200、429 等任意状态码）
        try:
            self._handle_business_error(code, msg)
        except ImaRateLimitError as e:
            if _retry < self._max_retry:
                wait = min(2 ** _retry * self._retry_base, self._retry_cap)
                logger.warning(
                    "ima 频率超限，%0.1fs 后重试 [%s] (%d/%d)",
                    wait, path, _retry + 1, self._max_retry,
                )
                await asyncio.sleep(wait)
                return await self._call(path, body, _retry=_retry + 1)
            raise  # 重试耗尽

        # 保险：HTTP 错误但业务码为 0（不常见）
        if resp.status_code >= 400:
            raise RuntimeError(
                f"ima HTTP 错误: status={resp.status_code}, path={path}"
            )

        return data.get("data", {})

    # ------------------------------------------------------------------
    # 文件夹（笔记本）操作
    # ------------------------------------------------------------------

    async def create_folder(self, name: str, parent_id: str | None = None) -> str:
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

    async def create_document(self, title: str, content: dict, folder_id: str | None = None) -> str:
        """创建 ima 笔记（Markdown），返回笔记 ID（note_id）

        此方法只做 ``import_doc`` 一步，与基类 ``SyncBase.create_document() -> str``
        契约一致；知识库关联是 ima 特有的扩展点，由调用方显式调
        ``add_to_knowledge_base(note_id, title, kb_folder_id)`` 触发。

        调用方应按以下顺序组合两步：

        1. ``note_id = await sync.create_document(...)`` —— 创建笔记并持久化
           ``{note_id, kb_added=False}`` 状态到 ``ima_sync_state.json``，避免 KB
           关联失败时这条笔记被丢失；
        2. 若配置了 ``knowledge_base_id``，再调
           ``await sync.add_to_knowledge_base(note_id, title, kb_folder_id)``；
           成功则更新 ``kb_added=True``，失败（非配额异常）保留 ``kb_added=False``
           让下次同步重试；配额耗尽则让同步循环 break，下次运行通过
           ``check_document_exists + add_to_knowledge_base`` 恢复关联。
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

    async def check_document_exists(self, video_id: str, folder_token: str | None = None) -> str | None:
        """检查笔记是否已存在（按标题前缀 [video_id] 去重）

        搜索接口按标题检索，匹配标题以 `[video_id]` 开头的笔记。
        返回 note_id 或 None。

        为确保搜索覆盖到含前缀的标题，同时用 video_id 和 [video_id]
        两种 query 做并集搜索，避免 API 忽略方括号导致漏检。
        """
        prefix = f"[{video_id}]"
        seen: set = set()
        # 双 query 并集：video_id 本身 + 带方括号的 [video_id] 前缀
        for query_title in (video_id, f"[{video_id}]"):
            start, page = 0, 20
            while True:
                data = await self._call(
                    self.PATH_SEARCH_NOTE,
                    {
                        "search_type": 0,
                        "query_info": {"title": query_title},
                        "start": start,
                        "end": start + page,
                    },
                )
                infos = data.get("search_note_infos", []) or []
                for info in infos:
                    nb = info.get("note_book_info", {})
                    title = nb.get("title", "")
                    note_id = nb.get("note_id", "")
                    if note_id and note_id not in seen:
                        seen.add(note_id)
                        # Standard: title starts with [video_id] prefix
                        if title.startswith(prefix):
                            logger.info("ima found existing note: %s -> %s", title, note_id)
                            return note_id
                        # Legacy: raw video_id at title start followed by a boundary
                        if _match_legacy_title(title, video_id):
                            logger.info(
                                "ima found existing note (legacy format): %s -> %s",
                                title, note_id,
                            )
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

    async def add_to_knowledge_base(self, note_id: str, title: str, kb_folder_id: str = "") -> None:
        """Public: 将已有笔记关联到知识库（media_type=11）。

        这是 ima 同步的扩展入口（KB 关联是 ima 特有的能力，不属于基类
        ``SyncBase.create_document()`` 契约）。``create_document()`` 只做
        ``import_doc``，关联需由调用方显式调用本方法。

        典型用法：

        1. ``create_document()`` 成功后，调用方立即把 ``{note_id, kb_added=False}``
           写入 ``ima_sync_state.json``，确保即使 KB 关联失败也能被下次同步恢复；
        2. 调用本方法，关联成功后更新 ``kb_added=True``；
        3. 关联失败（非配额异常）保留 ``kb_added=False`` 让下次同步重试；
           配额耗尽由 ``ImaQuotaExceededError`` 抛出，同步循环 break。

        kb_folder_id 为空时加入知识库根目录，否则加入指定文件夹。
        """
        await self._add_to_knowledge_base(note_id, title, kb_folder_id)

    async def _add_to_knowledge_base(self, note_id: str, title: str, kb_folder_id: str = "") -> None:
        """将已有笔记关联到知识库（media_type=11 表示笔记）

        kb_folder_id 为空时加入知识库根目录，否则加入指定文件夹。
        """
        body = {
            "media_type": 11,
            "note_info": {"content_id": note_id},
            "title": title,
            "knowledge_base_id": self.knowledge_base_id,
        }
        if kb_folder_id:
            body["folder_id"] = kb_folder_id
        await self._call(self.PATH_ADD_KNOWLEDGE, body)
        logger.info("ima 笔记已加入知识库 %s: %s", self.knowledge_base_id, note_id)

    # ------------------------------------------------------------------
    # 知识库发现与文件夹解析
    # ------------------------------------------------------------------

    async def list_addable_knowledge_bases(self, search: str = "") -> List[dict]:
        """列出可添加内容的知识库（含默认库），返回 [{"id", "name", "description"}]

        主发现路径用 get_addable_knowledge_base_list（默认库会出现在此），
        search 非空时再并集 search_knowledge_base 的按名搜索结果。
        id 即 knowledge_base_id。最后批量调 get_knowledge_base 补 description。
        """
        seen: set = set()
        result: List[dict] = []

        # 1. 可添加列表（主发现路径）
        cursor = ""
        while True:
            data = await self._call(
                self.PATH_GET_ADDABLE_KB, {"cursor": cursor, "limit": 50}
            )
            logger.debug(
                "get_addable_knowledge_base_list 原始响应 keys=%s 条目数=%d",
                list(data.keys()),
                len(data.get("addable_knowledge_base_list") or []),
            )
            for it in data.get("addable_knowledge_base_list") or []:
                kid = it.get("id", "")
                if kid and kid not in seen:
                    seen.add(kid)
                    result.append({"id": kid, "name": it.get("name", ""), "description": ""})
            if data.get("is_end", True):
                break
            cursor = data.get("next_cursor", "")
            if not cursor:
                break

        # 2. 可选：按名搜索做并集
        if search:
            searched = await self._search_knowledge_bases(search)
            logger.debug("search_knowledge_base 命中 %d 个: %s", len(searched), [s.get("id") for s in searched])
            for it in searched:
                kid = it.get("id", "")
                if kid and kid not in seen:
                    seen.add(kid)
                    result.append({"id": kid, "name": it.get("name", ""), "description": ""})

        # 3. 批量补 description（get_knowledge_base，ids 1-20）
        if result:
            ids = [r["id"] for r in result]
            infos = await self.get_knowledge_base_info(ids)
            logger.debug("get_knowledge_base 返回 infos 数=%d ids=%s", len(infos), ids)
            for r in result:
                info = infos.get(r["id"]) or {}
                r["name"] = info.get("name") or r["name"]
                r["description"] = info.get("description", "") or ""

        logger.info(
            "list_addable_knowledge_bases 共得到 %d 个知识库: %s",
            len(result), [(r["id"], r["name"]) for r in result],
        )
        return result

    async def _search_knowledge_bases(self, query: str) -> List[dict]:
        """按关键词搜索知识库列表（search_knowledge_base），返回 [{"id", "name"}]"""
        result: List[dict] = []
        cursor = ""
        while True:
            data = await self._call(
                self.PATH_SEARCH_KB, {"query": query, "cursor": cursor, "limit": 20}
            )
            for it in data.get("info_list") or []:
                result.append({"id": it.get("id", ""), "name": it.get("name", "")})
            if data.get("is_end", True):
                break
            cursor = data.get("next_cursor", "")
            if not cursor:
                break
        return result

    async def get_knowledge_base_info(self, ids: List[str]) -> Dict[str, dict]:
        """批量获取知识库详情（name/description/cover_url），返回 {id: {...}}

        get_knowledge_base 的 ids 限制 1-20 个且不重复，这里直接取前 20。
        """
        if not ids:
            return {}
        data = await self._call(self.PATH_GET_KB_INFO, {"ids": ids[:20]})
        return data.get("infos", {}) or {}

    async def list_folders(self, kb_id: str, parent: str = "", depth: int = 0) -> List[dict]:
        """递归列出知识库下的文件夹

        通过 get_knowledge_list 浏览。实测返回的 knowledge_list 中，文件夹与文件
        混在一起，且条目均无 folder_id 字段，区分方式为：
          - 文件夹：media_id 以 "folder_" 开头、media_type == 99（该 media_id
            即文件夹的规范 ID，可作为 folder_id 参数进入子目录）
          - 文件：media_id 为 note_/word_/wechatarticle_ 等、media_type 为具体类型
        返回 [{"folder_id", "name", "depth"}]，folder_id 即文件夹的 media_id。
        """
        if depth == 0:
            logger.info("list_folders 开始: kb_id=%s", kb_id)
        result: List[dict] = []
        cursor = ""
        while True:
            body = {"knowledge_base_id": kb_id, "cursor": cursor, "limit": 50}
            if parent:
                body["folder_id"] = parent
            data = await self._call(self.PATH_GET_KB_LIST, body)
            logger.debug(
                "get_knowledge_list kb_id=%s parent=%r 返回 keys=%s 条目数=%d",
                kb_id, parent, list(data.keys()), len(data.get("knowledge_list") or []),
            )
            for it in data.get("knowledge_list") or []:
                mid = it.get("media_id", "") or ""
                # 文件夹特征：media_id 以 folder_ 开头，或 media_type 为 99
                is_folder = mid.startswith("folder_") or it.get("media_type") == 99
                if is_folder and mid:
                    result.append(
                        {"folder_id": mid, "name": it.get("title", ""), "depth": depth}
                    )
                    result.extend(
                        await self.list_folders(kb_id, parent=mid, depth=depth + 1)
                    )
            if data.get("is_end", True):
                break
            cursor = data.get("next_cursor", "")
            if not cursor:
                break
        if depth == 0:
            logger.info("list_folders 完成: 共发现 %d 个文件夹", len(result))
        return result

    async def resolve_kb_folder(self, kb_id: str, folder_id: str, folder_name: str) -> str:
        """解析知识库目标文件夹 ID

        优先使用显式 folder_id；否则按 folder_name 精确匹配；都没有则根目录。
        """
        logger.info(
            "resolve_kb_folder: kb_id=%s folder_id=%r folder_name=%r",
            kb_id, folder_id, folder_name,
        )
        if folder_id:
            logger.info("resolve_kb_folder: 使用显式 folder_id=%s", folder_id)
            return folder_id
        if folder_name:
            resolved = await self._find_folder_by_name(kb_id, folder_name)
            logger.info("resolve_kb_folder: 按名称 %r 解析结果=%r", folder_name, resolved)
            return resolved
        logger.info("resolve_kb_folder: 无 folder_id/name，返回根目录(空)")
        return ""

    async def _find_folder_by_name(self, kb_id: str, name: str) -> str:
        """按名称精确查找知识库文件夹 folder_id（基于 list_folders 递归结果）"""
        folders = await self.list_folders(kb_id)
        logger.debug("_find_folder_by_name 候选: %s", [(f.get("name"), f.get("folder_id")) for f in folders])
        for f in folders:
            if f.get("name") == name:
                return f.get("folder_id", "")
        return ""

    # ------------------------------------------------------------------
    # Markdown 构建与清洗
    # ------------------------------------------------------------------

    @staticmethod
    def _build_markdown(title: str, content: dict) -> str:
        """将知识卡片内容转换为 Markdown（结构与飞书 block 等价）

        title 参数应包含 [video_id] 前缀（由调用方传入），确保笔记标题与
        check_document_exists 的前缀匹配逻辑一致，实现幂等查重。
        """
        lines: List[str] = []

        # 优先使用传入的 title（含 [video_id] 前缀），fallback 到卡片原始标题
        display_title = title or content.get("title") or "未知标题"
        # 转义标题中的 # 防止破坏 Markdown 标题层级
        safe_title = display_title.replace("#", "&#35;")
        lines.append(f"# {safe_title}")

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
