"""
抖音视频抓取器

支持两种模式：
1. CDP 模式（推荐）：通过 Chrome DevTools Protocol 连接用户日常浏览器，
   复用已有登录态，稳定性最高。
2. 自管浏览器模式（回退）：Playwright 启动独立浏览器，手动登录后
   拦截 API 请求获取数据。

抖音 Web API 需要复杂的签名（X-Bogus / a_bogus），因此采用
浏览器端拦截/提取的方式获取 API 数据，而非直接构造请求。
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from pathlib import Path
from typing import Optional

from playwright.async_api import async_playwright, Browser, BrowserContext, Page, Response

from src.scraper.base import ScraperBase, FavoriteFolder, VideoInfo, SubtitleSegment
from src.utils.cdp import CDPClient

logger = logging.getLogger(__name__)

# 抖音基础 URL
_DOUYIN_BASE = "https://www.douyin.com"
# 通用请求头，模拟浏览器访问
_HEADERS = {
    "Referer": "https://www.douyin.com",
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/120.0.0.0 Safari/537.36"
    ),
}

# 需要拦截的 API 路径关键字
_FAV_FOLDER_API = "/aweme/v1/web/collects/list"          # 收藏夹列表
_FAV_VIDEO_API = "/aweme/v1/web/collects/video/list"    # 收藏夹内视频
_VIDEO_DETAIL_API = "/aweme/v1/web/aweme/detail"         # 视频详情

# 收藏页面所有可能的 API 路径关键字（用于拦截）
_ALL_FAV_API_KEYWORDS = [
    _FAV_FOLDER_API,
    _FAV_VIDEO_API,
    "collects/list",
    "collects/video/list",
    "/aweme/v1/web/aweme/listcollection",
]


class DouyinScraper(ScraperBase):
    """抖音抓取器

    优先通过 CDP 连接用户浏览器（免重复登录），
    失败时回退到 Playwright 自管浏览器模式。
    """

    def __init__(self, cookie_path: str = "data/cookies/douyin.json",
                 cdp_port: int = 9222) -> None:
        self._cookie_path = Path(cookie_path)
        self._cdp_port = cdp_port
        self._browser: Optional[Browser] = None
        self._context: Optional[BrowserContext] = None
        self._page: Optional[Page] = None
        self._cdp: Optional[CDPClient] = None
        self._login_mode: str = ""  # "cdp" | "playwright"

    # ------------------------------------------------------------------
    # 内部工具方法
    # ------------------------------------------------------------------

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

    async def _check_login_status(self, navigate: bool = True) -> bool:
        """
        通过访问抖音首页检查当前 Cookie 是否仍有效。
        检测页面是否存在用户头像/登录标识元素。

        Args:
            navigate: 是否导航到首页重新检查。设为 False 时仅在当前页面
                      检查 Cookie，不会打断用户的登录操作。
        """
        try:
            if self._page is None:
                return False
            if navigate:
                # 导航到抖音首页
                await self._page.goto(_DOUYIN_BASE, wait_until="domcontentloaded", timeout=15_000)
                # 等待一小段时间让页面渲染
                await asyncio.sleep(2)
            # 检查是否存在登录按钮（未登录状态会显示登录按钮）
            login_btn = await self._page.query_selector('[data-e2e="user-login"]')
            if login_btn:
                return False
            # 尝试检查是否有用户头像或侧边栏用户入口
            avatar = await self._page.query_selector('[data-e2e="user-info"]')
            if avatar:
                return True
            # 兜底：检查 Cookie 中是否有 sessionid
            cookies = await self._context.cookies() if self._context else []
            for c in cookies:
                if c.get("name") in ("sessionid", "sessionid_ss", "sid_guard"):
                    return True
            return False
        except Exception as e:
            logger.warning("检查登录状态异常: %s", e)
            return False

    # ------------------------------------------------------------------
    # 抽象方法实现
    # ------------------------------------------------------------------

    async def login(self) -> None:
        """登录抖音

        三级策略：
        1. CDP 连接用户已有浏览器（免重复登录）
        2. CDP 连接失败 → 自动拉起 Chrome → 重试 CDP
        3. CDP 彻底不可用 → Playwright 自管浏览器 + 扫码登录
        """
        # 方案1: CDP 连接（优先复用已有，失败则自动拉起 Chrome）
        self._cdp = CDPClient(debug_port=self._cdp_port)
        if await self._cdp.connect():
            self._login_mode = "cdp"
            self._page = self._cdp.page
            self._context = self._cdp.context
            self._browser = self._cdp._browser
            # 验证是否已登录抖音
            if await self._check_login_status(navigate=True):
                logger.info("CDP 模式: 抖音已登录，复用浏览器会话")
                return

            # 自动拉起的 Chrome 尚未登录，等待用户在可见窗口中扫码
            if self._cdp.launched_by_us:
                logger.info("Chrome 已自动拉起，请在浏览器窗口中登录抖音（120s 超时）...")
                try:
                    for _ in range(40):
                        await asyncio.sleep(3)
                        if await self._check_login_status(navigate=False):
                            logger.info("CDP 模式: 登录成功")
                            return
                except TimeoutError:
                    pass

            logger.warning("CDP 连接成功但抖音未登录，请在浏览器中手动登录后重试")
            await self._cdp.disconnect()
            self._page = None
            self._context = None
            self._browser = None

        # 方案2: 回退到 Playwright 自管浏览器
        logger.info("CDP 不可用，回退到 Playwright 自管浏览器模式")
        self._login_mode = "playwright"
        await self._login_playwright()

    # ------------------------------------------------------------------
    # 登录辅助
    # ------------------------------------------------------------------

    async def _login_playwright(self) -> None:
        """Playwright 自管浏览器登录（回退方案）"""
        pw = await async_playwright().start()
        self._browser = await pw.chromium.launch(headless=False)
        self._context = await self._browser.new_context(
            user_agent=_HEADERS["User-Agent"],
            viewport={"width": 1280, "height": 800},
        )
        self._page = await self._context.new_page()

        saved_cookies = self._load_cookies()
        if saved_cookies:
            await self._context.add_cookies(saved_cookies)
            if await self._check_login_status():
                logger.info("Cookie 有效，无需重新登录")
                return
            logger.info("Cookie 已过期，需要重新登录")

        logger.info("正在打开抖音首页: %s", _DOUYIN_BASE)
        await self._page.goto(_DOUYIN_BASE, wait_until="domcontentloaded")

        try:
            for _ in range(40):
                await asyncio.sleep(3)
                if await self._check_login_status(navigate=False):
                    break
            else:
                raise TimeoutError("登录超时（120 秒），请重试")
        except TimeoutError:
            raise

        cookies = await self._context.cookies()
        self._save_cookies(cookies)
        logger.info("抖音登录成功，Cookie 已保存")

    async def _is_url_fav_api(self, url: str) -> bool:
        """判断 URL 是否为收藏夹相关的 API 请求"""
        return any(kw in url for kw in _ALL_FAV_API_KEYWORDS)

    async def _scroll_until_complete(self, max_scrolls: int = 50,
                                      scroll_pause: float = 1.5) -> None:
        """滚动直到页面高度不再增长（更可靠的懒加载触发）"""
        if self._page is None:
            return
        prev_height = 0
        no_change_count = 0
        for i in range(max_scrolls):
            new_height = await self._page.evaluate("document.body.scrollHeight")
            await self._page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
            await asyncio.sleep(scroll_pause)
            if new_height == prev_height:
                no_change_count += 1
                if no_change_count >= 3:  # 连续3次无变化则停止
                    logger.debug("滚动停止: 页面高度不再增长 (scroll=%d, height=%d)", i + 1, new_height)
                    break
            else:
                no_change_count = 0
                prev_height = new_height
            logger.debug("第 %d 次滚动, 高度=%d", i + 1, new_height)

    async def get_favorites(self) -> list[FavoriteFolder]:
        """获取当前用户的所有收藏夹列表

        导航到收藏夹页面，拦截 API 请求获取收藏夹数据，
        循环滚动直到 has_more=false。
        """
        if self._page is None or self._context is None:
            raise RuntimeError("浏览器未初始化，请先调用 login()")

        logger.info("正在获取收藏夹列表")

        all_api_responses: list[tuple[str, dict]] = []
        folder_has_more = True
        folder_total = 0

        async def _on_response_all(response: Response) -> None:
            nonlocal folder_has_more, folder_total
            url = response.url
            if response.status == 200 and _FAV_FOLDER_API in url:
                try:
                    data = await response.json()
                    path = url.split("?")[0]
                    all_api_responses.append((path, data))
                    inner = data.get("data", data)
                    if isinstance(inner, dict) and "collects_list" in inner:
                        folder_has_more = bool(inner.get("has_more", False))
                        total = inner.get("total_number", 0)
                        if total:
                            folder_total = total
                        collected_count = sum(
                            len(d.get("data", d).get("collects_list", []))
                            for _, d in all_api_responses
                            if isinstance(d.get("data", d), dict) and "collects_list" in d.get("data", d)
                        )
                        logger.info("收藏夹列表: has_more=%s, 已捕获=%d/%d",
                                     folder_has_more, collected_count, folder_total)
                except Exception:
                    pass

        self._page.on("response", _on_response_all)

        try:
            # 导航到收藏夹页面
            favorites_url = (
                f"{_DOUYIN_BASE}/user/self"
                f"?from_tab_name=main"
                f"&showTab=favorite_collection"
                f"&showSubTab=favorite_folder"
            )
            await self._page.goto(favorites_url, wait_until="domcontentloaded")
            await asyncio.sleep(3)

            # 滚动加载直到数据完整
            prev_count = 0
            no_new_count = 0
            for i in range(50):
                await self._page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
                await asyncio.sleep(2)

                cur_count = len(all_api_responses)
                if cur_count > prev_count:
                    prev_count = cur_count
                    no_new_count = 0
                else:
                    no_new_count += 1

                if not folder_has_more and cur_count > 0 and no_new_count >= 1:
                    break
                if no_new_count >= 5:
                    break
        finally:
            self._page.remove_listener("response", _on_response_all)

        logger.info("共捕获 %d 个 API 响应", len(all_api_responses))

        # 从 API 数据中提取收藏夹
        folders = self._extract_folders(all_api_responses)
        logger.info("获取到 %d 个收藏夹", len(folders))
        return folders

    def _extract_folders(self, api_responses: list[tuple[str, dict]]) -> list[FavoriteFolder]:
        """从 API 响应中提取收藏夹列表"""
        folders: list[FavoriteFolder] = []

        for path, data in api_responses:
            inner = data.get("data", data)
            if not isinstance(inner, dict):
                continue

            collects_list = inner.get("collects_list", [])
            if isinstance(collects_list, list) and collects_list:
                first = collects_list[0] if collects_list else {}
                if isinstance(first, dict) and "collects_id" in first:
                    for item in collects_list:
                        fid = str(item.get("collects_id_str", item.get("collects_id", "")))
                        folders.append(FavoriteFolder(
                            folder_id=fid,
                            title=item.get("collects_name", "未命名收藏夹"),
                            video_count=item.get("total_number", 0),
                            url=f"{_DOUYIN_BASE}/user/self?from_tab_name=main"
                                f"&showTab=favorite_collection"
                                f"&showSubTab=favorite_folder&collects_id={fid}",
                        ))
                    continue

            # 兼容其他可能的格式
            for key, value in inner.items():
                if not isinstance(value, list) or not value:
                    continue
                first_item = value[0] if value else {}
                if not isinstance(first_item, dict):
                    continue
                has_folder = any(
                    k in first_item for k in
                    ["collects_id", "folder_id", "collection_id", "cover_count"]
                )
                if has_folder:
                    for item in value:
                        fid = str(item.get("collects_id_str", item.get("collects_id",
                                   item.get("folder_id", item.get("id", "")))))
                        folders.append(FavoriteFolder(
                            folder_id=fid,
                            title=item.get("collects_name", item.get("title",
                                   item.get("name", "未命名收藏夹"))),
                            video_count=item.get("total_number", item.get("cover_count",
                                        item.get("favorite_count", item.get("count", 0)))),
                            url=f"{_DOUYIN_BASE}/user/self?from_tab_name=main"
                                f"&showTab=favorite_collection"
                                f"&showSubTab=favorite_folder&collects_id={fid}",
                        ))

        return folders

    async def get_favorite_videos(self, folder_id: str) -> list[VideoInfo]:
        """获取指定收藏夹中的所有视频（自动处理分页）

        导航到收藏夹页面，拦截 API 请求获取视频数据，
        滚动直到 has_more=false。
        """
        if self._page is None or self._context is None:
            raise RuntimeError("浏览器未初始化，请先调用 login()")

        collected_data: list[dict] = []
        page_has_more = True
        page_cursor = 0
        total_video_count = 0

        async def _on_response(response: Response) -> None:
            nonlocal page_has_more, page_cursor, total_video_count
            url = response.url
            if response.status == 200 and _FAV_VIDEO_API in url:
                try:
                    data = await response.json()
                    collected_data.append(data)
                    inner = data.get("data", data)
                    if isinstance(inner, dict):
                        hm = inner.get("has_more", None)
                        if hm is not None:
                            page_has_more = bool(hm)
                        cursor = inner.get("cursor", None)
                        if cursor is not None:
                            page_cursor = cursor
                        tc = inner.get("total_number", 0)
                        if tc:
                            total_video_count = tc
                    logger.info("收藏夹视频 API: cursor=%d, has_more=%s, 累计=%d",
                                 page_cursor, page_has_more, len(collected_data))
                except Exception as e:
                    logger.warning("解析收藏夹视频响应失败: %s", e)

        self._page.on("response", _on_response)

        try:
            # 导航到指定收藏夹
            if folder_id == "default":
                fav_url = (
                    f"{_DOUYIN_BASE}/user/self"
                    f"?from_tab_name=main&showTab=favorite_collection"
                    f"&showSubTab=favorite_folder"
                )
            else:
                fav_url = (
                    f"{_DOUYIN_BASE}/user/self"
                    f"?from_tab_name=main&showTab=favorite_collection"
                    f"&showSubTab=favorite_folder"
                    f"&collects_id={folder_id}"
                )
            logger.info("导航到收藏夹: %s", fav_url)
            await self._page.goto(fav_url, wait_until="domcontentloaded")
            await asyncio.sleep(3)

            # 滚动加载
            prev_count = 0
            no_new_count = 0
            for i in range(50):
                await self._page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
                await asyncio.sleep(2)

                cur_count = len(collected_data)
                if cur_count > prev_count:
                    prev_count = cur_count
                    no_new_count = 0
                else:
                    no_new_count += 1

                if not page_has_more and cur_count > 0 and no_new_count >= 1:
                    logger.info("视频列表分页完毕 (has_more=false)")
                    break
                if no_new_count >= 5:
                    logger.info("连续 %d 次无新数据，停止", no_new_count)
                    break
        finally:
            self._page.remove_listener("response", _on_response)

        # 解析视频数据
        videos = self._extract_videos(collected_data)

        if total_video_count:
            logger.info("收藏夹 %s: 获取到 %d/%d 个视频", folder_id, len(videos), total_video_count)
        else:
            logger.info("收藏夹 %s: 获取到 %d 个视频", folder_id, len(videos))
        return videos

    def _extract_videos(self, collected_data: list[dict]) -> list[VideoInfo]:
        """从 API 响应中提取视频列表"""
        videos: list[VideoInfo] = []
        seen_ids: set[str] = set()

        for data in collected_data:
            inner = data.get("data", data)
            if not isinstance(inner, dict):
                continue
            aweme_list = inner.get("aweme_list")
            if not aweme_list:
                aweme_list = (
                    inner.get("video_list", [])
                    or inner.get("collection_list", [])
                )
            if not isinstance(aweme_list, list):
                continue
            for item in aweme_list:
                video_item = item.get("aweme_info", item)
                video = self._parse_video_info(video_item)
                if video and video.video_id not in seen_ids:
                    seen_ids.add(video.video_id)
                    videos.append(video)
        return videos

    def _parse_video_info(self, item: dict) -> Optional[VideoInfo]:
        """
        从抖音 API 返回的单条视频数据中解析 VideoInfo。

        Args:
            item: 抖音 aweme 对象

        Returns:
            VideoInfo 实例，解析失败返回 None
        """
        try:
            aweme_id = str(item.get("aweme_id", ""))
            if not aweme_id:
                return None

            desc = item.get("desc", "")
            # 视频时长（单位通常是秒或百分之一秒，需要兼容）
            duration = item.get("duration", 0)
            if duration and duration > 1000:
                duration = duration / 1000  # 毫秒转秒
            duration = float(duration) if duration else None

            # 作者信息
            author_info = item.get("author", {})
            author = author_info.get("nickname", "") if isinstance(author_info, dict) else ""

            # 封面图
            cover_data = item.get("video", {}).get("cover", {})
            cover_url = ""
            if isinstance(cover_data, dict):
                url_list = cover_data.get("url_list", [])
                cover_url = url_list[0] if url_list else ""

            # 视频播放地址
            video_url = f"{_DOUYIN_BASE}/video/{aweme_id}"

            # 标签
            text_extra = item.get("text_extra", [])
            tags = [t.get("hashtag_name", "") for t in text_extra if t.get("hashtag_name")]

            return VideoInfo(
                video_id=aweme_id,
                title=desc,
                url=video_url,
                duration=duration,
                author=author,
                description=desc,
                cover_url=cover_url,
                tags=tags,
                extra={"aweme_id": aweme_id},
            )
        except Exception as e:
            logger.warning("解析视频信息失败: %s", e)
            return None

    async def get_video_subtitle(self, video_id: str) -> list[SubtitleSegment] | None:
        """
        获取抖音视频字幕

        通过视频详情 API 获取字幕信息。
        抖音字幕数据可能在 aweme_detail.video.subtitle 或 aweme_detail.caption 中。
        部分视频自带字幕（创作者上传），如果没有字幕则返回 None。
        """
        if self._page is None or self._context is None:
            raise RuntimeError("浏览器未初始化，请先调用 login()")

        detail_url = f"{_DOUYIN_BASE}/aweme/v1/web/aweme/detail/?aweme_id={video_id}"
        logger.info("正在获取视频 %s 的字幕信息", video_id)

        # 通过拦截 API 响应获取视频详情
        detail_data: dict = {}

        async def _on_response(response: Response) -> None:
            if _VIDEO_DETAIL_API in response.url and response.status == 200:
                try:
                    data = await response.json()
                    detail_data.update(data)
                except Exception:
                    pass

        self._page.on("response", _on_response)
        try:
            # 导航到视频页面以触发 API 请求
            video_url = f"{_DOUYIN_BASE}/video/{video_id}"
            await self._page.goto(video_url, wait_until="domcontentloaded")
            await asyncio.sleep(5)
        finally:
            self._page.remove_listener("response", _on_response)

        # 从详情数据中提取字幕
        aweme_detail = detail_data.get("aweme_detail", {})
        if not aweme_detail:
            logger.warning("未获取到视频 %s 的详情数据", video_id)
            return None

        # 尝试从多个可能的路径获取字幕数据
        subtitle_list = None

        # 路径1: video.subtitle
        video_info = aweme_detail.get("video", {})
        if isinstance(video_info, dict):
            subtitle_list = video_info.get("subtitle", [])

        # 路径2: caption_infos
        if not subtitle_list:
            subtitle_list = aweme_detail.get("caption_infos", [])

        # 路径3: interaction_stickers
        if not subtitle_list:
            subtitle_list = aweme_detail.get("interaction_stickers", [])

        if not subtitle_list:
            logger.info("视频 %s 没有自带字幕", video_id)
            return None

        # 解析字幕数据
        segments: list[SubtitleSegment] = []
        for sub in subtitle_list:
            # 字幕 URL 可能在 subtitle_url / url 字段
            sub_url = sub.get("url", "") or sub.get("subtitle_url", "")
            if sub_url and sub_url.startswith("//"):
                sub_url = "https:" + sub_url

            # 如果字幕数据直接包含内容（某些格式）
            content = sub.get("content", "")
            if content:
                start = float(sub.get("start_time", 0))
                end = float(sub.get("end_time", 0))
                segments.append(SubtitleSegment(start=start, end=end, text=content))
                continue

            # 如果有字幕 URL，需要下载字幕文件
            if sub_url:
                try:
                    resp = await self._context.request.get(sub_url, headers=_HEADERS)
                    if resp.status == 200:
                        sub_data = await resp.json()
                        # 抖音字幕 JSON 格式可能是列表或包含 body 字段
                        body = sub_data if isinstance(sub_data, list) else sub_data.get("body", [])
                        for item in body:
                            start = float(item.get("from", item.get("start_time", 0)))
                            end = float(item.get("to", item.get("end_time", 0)))
                            text = item.get("content", item.get("text", ""))
                            if text:
                                segments.append(SubtitleSegment(start=start, end=end, text=text))
                except Exception as e:
                    logger.warning("下载字幕文件失败: %s", e)

        if segments:
            logger.info("视频 %s 获取到 %d 条字幕", video_id, len(segments))
            return segments

        logger.info("视频 %s 字幕解析结果为空", video_id)
        return None

    async def get_video_audio_url(self, video_id: str) -> str | None:
        """
        获取抖音视频的音频 URL

        通过视频详情 API 获取音频/视频播放地址。
        优先从 music.play_url 获取背景音乐，
        其次从 video.play_addr 获取视频自带音频。
        """
        if self._page is None or self._context is None:
            raise RuntimeError("浏览器未初始化，请先调用 login()")

        logger.info("正在获取视频 %s 的音频 URL", video_id)

        # 通过拦截 API 响应获取视频详情
        detail_data: dict = {}

        async def _on_response(response: Response) -> None:
            if _VIDEO_DETAIL_API in response.url and response.status == 200:
                try:
                    data = await response.json()
                    detail_data.update(data)
                except Exception:
                    pass

        self._page.on("response", _on_response)
        try:
            video_url = f"{_DOUYIN_BASE}/video/{video_id}"
            await self._page.goto(video_url, wait_until="domcontentloaded")
            await asyncio.sleep(5)
        finally:
            self._page.remove_listener("response", _on_response)

        aweme_detail = detail_data.get("aweme_detail", {})
        if not aweme_detail:
            logger.warning("未获取到视频 %s 的详情数据", video_id)
            return None

        # 方案1: 从 music 字段获取背景音乐
        music = aweme_detail.get("music", {})
        if isinstance(music, dict):
            play_url = music.get("play_url", {})
            if isinstance(play_url, dict):
                url_list = play_url.get("url_list", [])
                if url_list:
                    audio_url = url_list[0]
                    if audio_url and audio_url.startswith("//"):
                        audio_url = "https:" + audio_url
                    logger.info("视频 %s 获取到背景音乐 URL", video_id)
                    return audio_url
            # play_url 也可能是直接的字符串
            if isinstance(play_url, str) and play_url:
                if play_url.startswith("//"):
                    play_url = "https:" + play_url
                return play_url

        # 方案2: 从 video.play_addr 获取视频地址（包含音频）
        video_info = aweme_detail.get("video", {})
        if isinstance(video_info, dict):
            play_addr = video_info.get("play_addr", {})
            if isinstance(play_addr, dict):
                url_list = play_addr.get("url_list", [])
                if url_list:
                    audio_url = url_list[0]
                    if audio_url and audio_url.startswith("//"):
                        audio_url = "https:" + audio_url
                    logger.info("视频 %s 从 play_addr 获取到音频 URL", video_id)
                    return audio_url

            # 方案3: download_addr
            download_addr = video_info.get("download_addr", {})
            if isinstance(download_addr, dict):
                url_list = download_addr.get("url_list", [])
                if url_list:
                    audio_url = url_list[0]
                    if audio_url and audio_url.startswith("//"):
                        audio_url = "https:" + audio_url
                    logger.info("视频 %s 从 download_addr 获取到音频 URL", video_id)
                    return audio_url

        logger.warning("视频 %s 未能获取到音频 URL", video_id)
        return None

    async def download_audio_to_file(self, audio_url: str, output_path: str) -> bool:
        """
        通过 Playwright 浏览器上下文下载音频文件

        利用已登录的 BrowserContext（含完整 Cookie/Headers）下载音频，
        绕过 CDN 403 问题。

        Args:
            audio_url: 音频 URL
            output_path: 本地保存路径

        Returns:
            是否下载成功
        """
        if self._context is None:
            logger.warning("浏览器上下文不可用，无法通过 Playwright 下载音频")
            return False
        try:
            resp = await self._context.request.get(
                audio_url, headers={"Referer": _DOUYIN_BASE}
            )
            if resp.status != 200:
                logger.warning(
                    "Playwright 音频下载 HTTP %d: %s",
                    resp.status,
                    audio_url[:120],
                )
                return False
            body = await resp.body()
            with open(output_path, "wb") as f:
                f.write(body)
            logger.info("Playwright 音频下载成功: %s", output_path)
            return True
        except Exception as e:
            logger.warning("Playwright 音频下载异常: %s", e)
            return False

    async def get_audio_cookies(self) -> dict:
        """
        获取浏览器上下文中的 Cookie，用于 httpx 音频下载回退路径

        Returns:
            包含 Cookie 头的字典
        """
        if self._context is None:
            return {}
        cookies = await self._context.cookies()
        cookie_str = "; ".join(f"{c['name']}={c['value']}" for c in cookies)
        return {"Cookie": cookie_str} if cookie_str else {}

    async def download_video(self, video_id: str, output_path: str) -> bool:
        """下载无水印视频文件

        通过视频详情 API 获取播放地址，将 playwm 替换为 play 去除水印，
        使用浏览器上下文下载（携带完整 Cookie）。

        Args:
            video_id: 抖音视频 aweme_id
            output_path: 本地保存路径（.mp4）

        Returns:
            下载成功返回 True
        """
        if self._page is None or self._context is None:
            raise RuntimeError("浏览器未初始化，请先调用 login()")

        logger.info("正在获取视频 %s 的下载地址", video_id)

        detail_data: dict = {}

        async def _on_response(response: Response) -> None:
            if _VIDEO_DETAIL_API in response.url and response.status == 200:
                try:
                    data = await response.json()
                    detail_data.update(data)
                except Exception:
                    pass

        self._page.on("response", _on_response)
        try:
            video_url = f"{_DOUYIN_BASE}/video/{video_id}"
            await self._page.goto(video_url, wait_until="domcontentloaded")
            await asyncio.sleep(4)
        finally:
            self._page.remove_listener("response", _on_response)

        aweme_detail = detail_data.get("aweme_detail", {})
        video_info = aweme_detail.get("video", {})

        # 获取无 watermark 播放地址
        play_addr = None
        for key in ("play_addr_h264", "play_addr", "download_addr"):
            addr = video_info.get(key, {})
            if isinstance(addr, dict):
                url_list = addr.get("url_list", [])
                if url_list:
                    play_addr = url_list[0]
                    break
            elif isinstance(addr, str) and addr:
                play_addr = addr
                break

        if not play_addr:
            logger.warning("视频 %s 无法获取播放地址", video_id)
            return False

        # 去水印：将 playwm 替换为 play
        download_url = play_addr.replace("/playwm/", "/play/").replace("playwm", "play")
        if download_url.startswith("//"):
            download_url = "https:" + download_url

        logger.info("视频 %s 开始下载 (无 watermark)", video_id)

        try:
            resp = await self._context.request.get(
                download_url,
                headers={"Referer": _DOUYIN_BASE},
            )
            if resp.status != 200:
                logger.warning("视频下载 HTTP %d: %s", resp.status, video_id)
                return False
            body = await resp.body()
            with open(output_path, "wb") as f:
                f.write(body)
            logger.info("视频下载成功: %s (%d bytes)", output_path, len(body))
            return True
        except Exception as e:
            logger.warning("视频下载失败: %s", e)
            return False

    async def close(self) -> None:
        """清理资源

        CDP 模式：断开连接但不关闭用户浏览器
        Playwright 模式：关闭自管浏览器
        """
        if self._login_mode == "cdp" and self._cdp is not None:
            await self._cdp.disconnect()
            self._page = None
            self._context = None
            self._browser = None
            return

        # Playwright 自管浏览器模式：完全关闭
        if self._page is not None:
            try:
                await self._page.close()
            except Exception:
                pass
            self._page = None
        if self._context is not None:
            try:
                await self._context.close()
            except Exception:
                pass
            self._context = None
        if self._browser is not None:
            try:
                await self._browser.close()
            except Exception:
                pass
            self._browser = None
