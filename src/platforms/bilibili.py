"""
B站（Bilibili）视频抓取器

通过 Playwright 模拟浏览器操作，抓取 B站收藏视频信息。
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import time
import urllib.parse
from functools import reduce
from pathlib import Path
from typing import Optional

from playwright.async_api import async_playwright, Browser, BrowserContext, Page

from src.scraper.base import ScraperBase, FavoriteFolder, VideoInfo, SubtitleSegment

logger = logging.getLogger(__name__)

# Bilibili API 基础 URL
_API_BASE = "https://api.bilibili.com"
# 通用请求头，模拟浏览器访问
_HEADERS = {
    "Referer": "https://www.bilibili.com",
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Safari/537.36"
    ),
}


class BilibiliScraper(ScraperBase):
    """B站抓取器，基于 Playwright 异步 API 实现"""

    def __init__(self, cookie_path: str = "data/cookies/bilibili.json") -> None:
        self._cookie_path = Path(cookie_path)
        self._browser: Optional[Browser] = None
        self._context: Optional[BrowserContext] = None
        self._page: Optional[Page] = None
        self._view_cache: dict[str, dict] = {}  # video_id -> /x/web-interface/view 的 data
        self._wbi_cache: dict = {}  # 缓存 wbi keys + 过期时间

    # ------------------------------------------------------------------
    # 内部工具方法
    # ------------------------------------------------------------------

    # 需要 wbi 签名的 API 错误码（参考用）
    _WBI_REQUIRED_CODES = {-403, 10004}
    # 视频不可见的 API 错误码（-400=请求错误, -352=风控拦截, 62xxx=稿件状态异常）
    _VIDEO_UNAVAILABLE_CODES = {-400, -352, -404, 62001, 62002, 62003, 62004}

    async def _api_request(self, url: str) -> dict:
        """
        通过浏览器 context 发送带 Cookie 的 API 请求。
        返回解析后的 JSON dict。
        """
        if self._context is None:
            raise RuntimeError("浏览器未初始化，请先调用 login()")

        resp = await self._context.request.get(
            url,
            headers=_HEADERS,
        )
        if resp.status != 200:
            raise RuntimeError(f"API 请求失败: {resp.status} {url}")

        body_text = await resp.text()
        if not body_text.strip():
            raise RuntimeError(f"API 返回空响应, url={url}")

        try:
            data = json.loads(body_text)
        except json.JSONDecodeError:
            preview = body_text[:200]
            raise RuntimeError(
                f"API 返回非 JSON 内容 (前200字符): {preview!r}, url={url}"
            )

        code = data.get("code", -1)
        if code != 0:
            # 风险控制异常：给出更清晰的提示
            if code == -352:
                raise RuntimeError(
                    f"B站风控拦截 (code=-352)，请求过于频繁或 Cookie 可能失效，"
                    f"请稍后重试或重新登录, url={url}"
                )
            raise RuntimeError(
                f"API 返回错误: code={code}, "
                f"message={data.get('message', 'unknown')}, url={url}"
            )
        return data

    def _save_cookies(self, cookies: list[dict]) -> None:
        """将 Cookie 列表保存到本地 JSON 文件"""
        self._cookie_path.parent.mkdir(parents=True, exist_ok=True)
        with open(self._cookie_path, "w", encoding="utf-8") as f:
            json.dump(cookies, f, ensure_ascii=False, indent=2)
        logger.info("Cookie 已保存到 %s", self._cookie_path)

    def _load_cookies(self) -> Optional[list[dict]]:
        """从本地 JSON 文件加载 Cookie，文件不存在则返回 None"""
        if not self._cookie_path.exists():
            return None
        try:
            with open(self._cookie_path, "r", encoding="utf-8") as f:
                cookies = json.load(f)
            logger.info("已从 %s 加载 Cookie", self._cookie_path)
            return cookies
        except (json.JSONDecodeError, OSError) as e:
            logger.warning("加载 Cookie 文件失败: %s", e)
            return None

    # ------------------------------------------------------------------
    # wbi 签名（部分新接口需要，如 AI 总结）
    # ------------------------------------------------------------------

    # 混淆表，B站固定值
    _MIXIN_KEY_ENC_TAB = [
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    ]

    async def _get_wbi_keys(self) -> tuple[str, str] | None:
        """
        从 /x/web-interface/nav 获取 wbi 签名所需的 img_key 和 sub_key。
        结果缓存 30 分钟，避免重复请求。
        返回 (img_key, sub_key)，失败返回 None。
        """
        # 检查缓存是否有效
        cached = self._wbi_cache
        if cached and cached.get("expire_at", 0) > time.time():
            return cached.get("img_key"), cached.get("sub_key")

        try:
            data = await self._api_request(f"{_API_BASE}/x/web-interface/nav")
            wbi_img = data.get("data", {}).get("wbi_img", {})
            img_url = wbi_img.get("img_url", "")
            sub_url = wbi_img.get("sub_url", "")
            if not img_url or not sub_url:
                return None
            # 从 URL 提取文件名（去掉扩展名）
            img_key = img_url.rsplit("/", 1)[-1].split(".")[0]
            sub_key = sub_url.rsplit("/", 1)[-1].split(".")[0]
            # 缓存 30 分钟
            self._wbi_cache = {
                "img_key": img_key,
                "sub_key": sub_key,
                "expire_at": time.time() + 1800,
            }
            return img_key, sub_key
        except Exception as e:
            logger.warning("获取 wbi keys 失败: %s", e)
            return None

    def _get_mixin_key(self, raw_key: str) -> str:
        """
        对 img_key + sub_key 拼接后按混淆表重排，取前 32 位。
        """
        return reduce(
            lambda acc, idx: acc + raw_key[idx],
            self._MIXIN_KEY_ENC_TAB,
            "",
        )[:32]

    async def _sign_url_with_wbi(self, url: str) -> str:
        """
        对 URL 添加 wbi 签名参数（w_rid + wts）。
        返回签名后的完整 URL。
        """
        keys = await self._get_wbi_keys()
        if keys is None:
            logger.warning("无法获取 wbi keys，返回原始 URL")
            return url

        img_key, sub_key = keys
        raw_key = img_key + sub_key
        mixin_key = self._get_mixin_key(raw_key)

        curr_time = round(time.time())
        parsed = urllib.parse.urlparse(url)
        params = dict(urllib.parse.parse_qsl(parsed.query))
        params["wts"] = curr_time
        # 过滤特殊字符
        params = {
            k: "".join(c for c in str(v) if c not in "!'()*")
            for k, v in sorted(params.items())
        }
        query = urllib.parse.urlencode(params)
        wbi_sign = hashlib.md5((query + mixin_key).encode()).hexdigest()
        params["w_rid"] = wbi_sign
        new_query = urllib.parse.urlencode(params)

        return f"{parsed.scheme}://{parsed.netloc}{parsed.path}?{new_query}"

    async def _check_login_status(self) -> bool:
        """
        通过 B站导航栏 API 检查当前 Cookie 是否仍有效。
        code=0 且 data.isLogin=True 表示已登录。
        """
        try:
            data = await self._api_request(
                f"{_API_BASE}/x/web-interface/nav"
            )
            return data.get("data", {}).get("isLogin", False)
        except Exception:
            return False

    async def get_audio_cookies(self) -> dict:
        """
        返回当前浏览器会话的 Cookie 字典（name=value; 拼接格式）
        用于音频 CDN 下载时注入鉴权信息，解决 403 问题。
        """
        if self._context is None:
            return {}
        cookies = await self._context.cookies()
        # Playwright 返回 list[dict]，拼成 Cookie 请求头格式
        cookie_str = "; ".join(f"{c['name']}={c['value']}" for c in cookies)
        return {"Cookie": cookie_str} if cookie_str else {}

    async def check_video_available(self, video_id: str) -> bool:
        """
        检查视频是否可用（未被删除/下架/设为私密）。

        通过 /x/web-interface/view 接口判断，同时缓存结果供后续复用。
        返回 True 表示可用，False 表示不可用。
        """
        # 已有缓存说明之前检查过
        if video_id in self._view_cache:
            return True

        url = f"{_API_BASE}/x/web-interface/view?bvid={video_id}"
        try:
            data = await self._api_request(url)
            inner = data.get("data") or {}
            self._view_cache[video_id] = inner
            return True
        except RuntimeError as e:
            msg = str(e)
            # 识别不可用错误码
            for code in self._VIDEO_UNAVAILABLE_CODES:
                if f"code={code}" in msg:
                    logger.info("视频 %s 不可用 (code=%s)，已跳过", video_id, code)
                    return False
            # 风控等临时错误也跳过，避免后续接口连环报错
            logger.warning("视频 %s 可用性检查失败: %s", video_id, e)
            return False

    async def get_video_owner(self, video_id: str) -> str:
        """
        获取视频的真实作者名称

        收藏夹接口返回的 upper.name 可能不准确（如 AI生成视频显示 "AI视频"），
        此方法优先从 /x/web-interface/view 的 data.owner.name 获取真实作者。
        若 _fetch_subtitle_url 已缓存了 view 数据则直接读取，不重复发请求。
        """
        # 优先读缓存（get_video_subtitle 调用后已写入）
        inner = self._view_cache.get(video_id)
        if inner is None:
            url = f"{_API_BASE}/x/web-interface/view?bvid={video_id}"
            try:
                data = await self._api_request(url)
                inner = data.get("data") or {}
                self._view_cache[video_id] = inner
            except Exception as e:
                logger.warning("获取视频 %s 详情失败: %s", video_id, e)
                return ""
        owner = inner.get("owner", {}) if isinstance(inner, dict) else {}
        return owner.get("name", "") if isinstance(owner, dict) else ""

    async def download_audio_to_file(self, audio_url: str, output_path: str) -> bool:
        """
        通过 Playwright 浏览器上下文下载音频文件

        使用完整浏览器指纹（Cookie + 指纹 + TLS 特征），绕过 CDN 403。
        比 httpx 裸请求更可靠。

        Args:
            audio_url: 音频 URL
            output_path: 保存路径

        Returns:
            下载成功返回 True，否则 False
        """
        if self._context is None:
            logger.warning("浏览器未初始化，无法下载音频")
            return False
        try:
            logger.info("通过 Playwright 下载音频: %s", audio_url[:120])
            resp = await self._context.request.get(
                audio_url,
                headers={"Referer": "https://www.bilibili.com"},
            )
            if resp.status != 200:
                logger.warning("音频下载失败: HTTP %s", resp.status)
                return False
            body = await resp.body()
            with open(output_path, "wb") as f:
                f.write(body)
            logger.info("音频已保存: %s (%d bytes)", output_path, len(body))
            return True
        except Exception as e:
            logger.warning("Playwright 音频下载异常: %s", e)
            return False

    async def get_video_ai_summary(self, video_id: str) -> str | None:
        """
        获取 B站 AI 视频总结（"AI视频总结" 功能）

        作为 CC字幕和 Whisper 之间的兆底方案。
        尝试多个 API 端点，某些可能需要 wbi 签名，失败则返回 None。

        Args:
            video_id: 视频 BV号

        Returns:
            AI 总结文本，若不可用则返回 None
        """
        # 从缓存获取 aid / cid
        inner = self._view_cache.get(video_id) or {}
        if not inner:
            try:
                url = f"{_API_BASE}/x/web-interface/view?bvid={video_id}"
                data = await self._api_request(url)
                inner = data.get("data") or {}
                self._view_cache[video_id] = inner
            except Exception as e:
                logger.warning("获取视频 %s 详情失败: %s", video_id, e)
                return None

        if not isinstance(inner, dict) or not inner:
            return None
        aid = inner.get("aid", "")
        cid = inner.get("cid", "")
        owner = inner.get("owner") or {}
        up_mid = owner.get("mid", "")
        if not aid or not cid:
            return None

        # 尝试多个 AI 总结 API 端点（需要 wbi 签名）
        base_endpoints = [
            f"{_API_BASE}/x/web-interface/view/conclusion/get?bvid={video_id}&cid={cid}&up_mid={up_mid}",
            f"{_API_BASE}/x/web-interface/view/conclusion/get?aid={aid}&cid={cid}&up_mid={up_mid}",
        ]

        for ep_url in base_endpoints:
            try:
                # 添加 wbi 签名
                signed_url = await self._sign_url_with_wbi(ep_url)
                logger.info("尝试 B站 AI 总结接口 (wbi): %s", signed_url[:120])
                resp_data = await self._api_request(signed_url)
                model_result = (
                    resp_data.get("data", {})
                    .get("model_result", {})
                )
                summary = model_result.get("summary", "")
                if not summary:
                    # 尝试从 outline 中提取
                    outline = model_result.get("outline", [])
                    if outline:
                        parts = []
                        for item in outline:
                            parts.append(item.get("title", ""))
                            for sub in item.get("part_outline", []):
                                parts.append(sub.get("content", ""))
                        summary = "\n".join(p for p in parts if p)
                if summary:
                    logger.info("B站 AI 总结获取成功 (%d 字符)", len(summary))
                    return summary
            except Exception as e:
                logger.debug("AI 总结接口尝试失败: %s", e)
                continue

        logger.info("视频 %s B站 AI 总结不可用", video_id)
        return None

    # ------------------------------------------------------------------
    # 抽象方法实现
    # ------------------------------------------------------------------

    async def login(self) -> None:
        """
        登录 B站

        流程：
        1. 启动 Playwright Chromium 浏览器
        2. 如果本地有 Cookie 文件，先加载并验证有效性
        3. 若 Cookie 无效或不存在，打开登录页等待用户手动扫码
        4. 登录成功后保存 Cookie 到本地
        """
        pw = await async_playwright().start()
        self._browser = await pw.chromium.launch(headless=False)
        self._context = await self._browser.new_context(
            user_agent=_HEADERS["User-Agent"],
            extra_http_headers=_HEADERS,
        )
        self._page = await self._context.new_page()

        # 尝试加载已有 Cookie
        saved_cookies = self._load_cookies()
        if saved_cookies:
            await self._context.add_cookies(saved_cookies)
            # 验证 Cookie 是否仍然有效
            if await self._check_login_status():
                logger.info("Cookie 有效，无需重新登录")
                return
            logger.info("Cookie 已过期，需要重新登录")

        # 打开 B站登录页面，等待用户手动扫码
        login_url = "https://passport.bilibili.com/login"
        logger.info("正在打开 B站登录页面: %s", login_url)
        await self._page.goto(login_url, wait_until="domcontentloaded")

        # 等待登录成功（检测 URL 跳转或 Cookie 变化），最长等待 120 秒
        try:
            # 登录成功后页面会跳转离开 passport 域名
            await self._page.wait_for_url(
                lambda url: "passport.bilibili.com" not in url,
                timeout=120_000,
            )
        except Exception:
            # 超时后再次检查登录状态（有些情况下 URL 不变但 Cookie 已写入）
            if not await self._check_login_status():
                raise TimeoutError("登录超时（120 秒），请重试")

        # 登录成功，保存 Cookie
        cookies = await self._context.cookies()
        self._save_cookies(cookies)
        logger.info("B站登录成功，Cookie 已保存")

    async def _get_user_mid(self) -> str:
        """
        通过 B站导航栏 API 获取当前登录用户的 mid (UID)。
        """
        data = await self._api_request(
            f"{_API_BASE}/x/web-interface/nav"
        )
        mid = data.get("data", {}).get("mid")
        if not mid:
            raise RuntimeError("无法获取用户 mid，请确认登录状态")
        return str(mid)

    async def get_favorites(self) -> list[FavoriteFolder]:
        """
        获取当前用户的所有收藏夹列表

        调用 B站 API: /x/v3/fav/folder/created/list-all
        需要 up_mid 参数（当前用户的 UID）
        """
        up_mid = await self._get_user_mid()
        url = f"{_API_BASE}/x/v3/fav/folder/created/list-all?up_mid={up_mid}"
        data = await self._api_request(url)

        folders_raw = data.get("data", {}).get("list", None)
        if not folders_raw:
            logger.warning("未获取到收藏夹列表（可能为空）")
            return []

        folders: list[FavoriteFolder] = []
        for item in folders_raw:
            folder = FavoriteFolder(
                folder_id=str(item["id"]),
                title=item["title"],
                video_count=item.get("media_count", 0),
                url=f"https://space.bilibili.com/fav/detail?fid={item['id']}",
            )
            folders.append(folder)

        logger.info("获取到 %d 个收藏夹", len(folders))
        return folders

    async def get_favorite_videos(self, folder_id: str) -> list[VideoInfo]:
        """
        获取指定收藏夹中的所有视频（自动处理分页）

        调用 B站 API: /x/v3/fav/resource/list
        """
        videos: list[VideoInfo] = []
        page = 1

        while True:
            url = (
                f"{_API_BASE}/x/v3/fav/resource/list"
                f"?media_id={folder_id}&pn={page}&ps=20"
            )
            data = await self._api_request(url)
            resource_list = data.get("data", {}).get("medias", [])
            if not resource_list:
                break  # 没有更多数据

            for item in resource_list:
                # 收藏夹中的视频 item 包含 ugc_season / 普通视频等类型
                # 普通视频字段: id (aid), title, bvid, cover, upper, duration
                aid = str(item.get("id", ""))
                bvid = item.get("bvid", "")
                title = item.get("title", "")
                cover = item.get("cover", "")
                duration = item.get("duration", 0)  # 单位：秒
                upper = item.get("upper", {})
                author = upper.get("name", "") if isinstance(upper, dict) else ""

                # 构造视频 URL（优先使用 bvid）
                if bvid:
                    video_url = f"https://www.bilibili.com/video/{bvid}"
                    vid = bvid
                else:
                    video_url = f"https://www.bilibili.com/video/av{aid}"
                    vid = aid

                video = VideoInfo(
                    video_id=vid,
                    title=title,
                    url=video_url,
                    duration=float(duration) if duration else None,
                    author=author,
                    cover_url=cover,
                    extra={"aid": aid, "fid": folder_id},
                )
                videos.append(video)

            # 检查是否还有下一页
            has_more = data.get("data", {}).get("has_more", False)
            if not has_more:
                break
            page += 1

        logger.info(
            "收藏夹 %s 共获取到 %d 个视频", folder_id, len(videos)
        )
        return videos

    async def _fetch_subtitle_url(self, video_id: str) -> str | None:
        """
        获取视频字幕下载 URL（内部方法）

        策略：
        1. 优先从 /x/web-interface/view 的 data.subtitle 取字幕
        2. 若为空，用 aid+cid 请求 /x/player/wbi/v2 获取更完整的字幕信息
        """
        # Step 1: 获取视频详情，同时拿到 aid / cid
        view_url = f"{_API_BASE}/x/web-interface/view?bvid={video_id}"
        view_data = await self._api_request(view_url)
        inner = view_data.get("data") or {}
        # 缓存 view 数据，供 get_video_owner 复用，避免重复请求
        self._view_cache[video_id] = inner
        aid = inner.get("aid", "")
        cid = inner.get("cid", "")

        # 尝试从 view 接口直接拿字幕
        subtitles_info = inner.get("subtitle", {}).get("subtitles", [])
        # 也检查 player_info 路径（部分视频字幕在此处）
        if not subtitles_info:
            subtitles_info = (
                inner.get("player_info", {})
                .get("subtitle", {})
                .get("subtitles", [])
            )

        # Step 2: 若 view 接口无字幕，用 player/wbi/v2 补充请求
        if not subtitles_info and aid and cid:
            logger.info(
                "视频 %s view 接口未返回字幕，尝试 /x/player/wbi/v2 (aid=%s, cid=%s)",
                video_id, aid, cid,
            )
            player_url = (
                f"{_API_BASE}/x/player/wbi/v2"
                f"?aid={aid}&cid={cid}&bvid={video_id}"
            )
            try:
                player_data = await self._api_request(player_url)
                subtitles_info = (
                    player_data.get("data", {})
                    .get("subtitle", {})
                    .get("subtitles", [])
                )
            except Exception as e:
                logger.warning("请求 /x/player/wbi/v2 失败: %s", e)

        if not subtitles_info:
            logger.info("视频 %s 所有接口均未返回 CC 字幕", video_id)
            return None

        logger.info(
            "视频 %s 发现 %d 条字幕轨道: %s",
            video_id,
            len(subtitles_info),
            [s.get("lan_doc", s.get("lan", "")) for s in subtitles_info],
        )

        # 优先选择中文字幕
        for sub in subtitles_info:
            lang = sub.get("lan", "")
            if lang.startswith("zh"):
                return sub.get("subtitle_url", "") or None
        # 没有中文则取第一条
        return subtitles_info[0].get("subtitle_url", "") or None

    async def get_video_subtitle(self, video_id: str) -> list[SubtitleSegment] | None:
        """
        获取视频的 CC 字幕

        流程：
        1. 通过 _fetch_subtitle_url 获取字幕下载链接（兼容多种接口路径）
        2. 下载字幕 JSON 文件
        3. 解析为 SubtitleSegment 列表
        """
        subtitle_url = await self._fetch_subtitle_url(video_id)
        if not subtitle_url:
            return None

        # 字幕 URL 可能是协议相对路径，补全为 https
        if subtitle_url.startswith("//"):
            subtitle_url = "https:" + subtitle_url

        logger.info("视频 %s 正在下载字幕: %s", video_id, subtitle_url[:120])

        # 下载字幕 JSON
        resp = await self._context.request.get(
            subtitle_url, headers=_HEADERS
        )
        if resp.status != 200:
            logger.warning("下载字幕失败: HTTP %s, url=%s", resp.status, subtitle_url)
            return None

        subtitle_data = await resp.json()
        body = subtitle_data.get("body", [])
        if not body:
            logger.warning("视频 %s 字幕 JSON body 为空", video_id)
            return None

        segments: list[SubtitleSegment] = []
        for item in body:
            segments.append(
                SubtitleSegment(
                    start=float(item.get("from", 0)),
                    end=float(item.get("to", 0)),
                    text=item.get("content", ""),
                )
            )

        logger.info("视频 %s 成功获取 %d 条字幕", video_id, len(segments))
        return segments

    async def get_video_audio_url(self, video_id: str) -> str | None:
        """
        获取视频的音频流 URL（DASH 格式）

        调用 B站 API: /x/player/playurl，fnval=16 返回 DASH 格式
        从 data.dash.audio 中选择带宽最高的音频流
        """
        base_url = (
            f"{_API_BASE}/x/player/playurl"
            f"?bvid={video_id}&fnval=16&fnver=0&fourk=1"
        )
        url = await self._sign_url_with_wbi(base_url)
        data = await self._api_request(url)

        dash = data.get("data", {}).get("dash", {})
        audio_list = dash.get("audio", [])
        if not audio_list:
            logger.warning("视频 %s 未找到音频流", video_id)
            return None

        # 选择带宽（bandwidth）最高的音频流
        best_audio = max(audio_list, key=lambda a: a.get("bandwidth", 0))
        audio_url = best_audio.get("baseUrl") or best_audio.get("base_url")

        if audio_url:
            logger.info("视频 %s 获取到音频 URL", video_id)
        return audio_url
