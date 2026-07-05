"""
抖音视频抓取器

通过 Playwright 模拟浏览器操作，抓取抖音收藏视频信息。
抖音 Web API 需要复杂的签名（X-Bogus / a_bogus），因此采用
Playwright 拦截网络请求的方式获取 API 数据，而非直接构造请求。
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
_FAV_FOLDER_API = "/aweme/v1/web/favorite/folder"       # 收藏夹列表
_FAV_VIDEO_API = "/aweme/v1/web/aweme/favorite"          # 收藏夹内视频
_VIDEO_DETAIL_API = "/aweme/v1/web/aweme/detail"         # 视频详情


class DouyinScraper(ScraperBase):
    """抖音抓取器，基于 Playwright 异步 API 实现"""

    def __init__(self, cookie_path: str = "data/cookies/douyin.json") -> None:
        self._cookie_path = Path(cookie_path)
        self._browser: Optional[Browser] = None
        self._context: Optional[BrowserContext] = None
        self._page: Optional[Page] = None

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

    async def _check_login_status(self) -> bool:
        """
        通过访问抖音首页检查当前 Cookie 是否仍有效。
        检测页面是否存在用户头像/登录标识元素。
        """
        try:
            if self._page is None:
                return False
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

    async def _wait_for_api_response(
        self, url_keyword: str, timeout: float = 15_000
    ) -> Optional[dict]:
        """
        等待并拦截包含指定关键字的 API 响应，返回解析后的 JSON。

        Args:
            url_keyword: API URL 中包含的关键字
            timeout: 等待超时（毫秒）

        Returns:
            解析后的 JSON dict，超时则返回 None
        """
        if self._page is None:
            raise RuntimeError("浏览器未初始化，请先调用 login()")

        result_event = asyncio.Event()
        captured_data: dict = {}

        async def _on_response(response: Response) -> None:
            if url_keyword in response.url and response.status == 200:
                try:
                    data = await response.json()
                    captured_data.update(data)
                    result_event.set()
                except Exception:
                    pass

        self._page.on("response", _on_response)
        try:
            await asyncio.wait_for(result_event.wait(), timeout=timeout / 1000)
            return captured_data if captured_data else None
        except asyncio.TimeoutError:
            logger.warning("等待 API 响应超时: %s", url_keyword)
            return None
        finally:
            self._page.remove_listener("response", _on_response)

    async def _collect_api_responses(
        self, url_keyword: str, action, timeout: float = 15_000
    ) -> list[dict]:
        """
        在执行某个页面操作（action）期间，收集所有匹配关键字的 API 响应。

        Args:
            url_keyword: API URL 关键字
            action: 异步回调函数，执行后触发 API 请求
            timeout: 等待超时（毫秒）

        Returns:
            匹配到的所有 API 响应 JSON 列表
        """
        if self._page is None:
            raise RuntimeError("浏览器未初始化，请先调用 login()")

        collected: list[dict] = []

        async def _on_response(response: Response) -> None:
            if url_keyword in response.url and response.status == 200:
                try:
                    data = await response.json()
                    collected.append(data)
                except Exception:
                    pass

        self._page.on("response", _on_response)
        try:
            await action()
            # 等待一段时间让所有请求完成
            await asyncio.sleep(timeout / 1000)
        finally:
            self._page.remove_listener("response", _on_response)

        return collected

    async def _scroll_to_load_more(self, max_scrolls: int = 20, scroll_pause: float = 1.5) -> None:
        """模拟滚动页面以触发加载更多"""
        if self._page is None:
            return
        for i in range(max_scrolls):
            await self._page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
            await asyncio.sleep(scroll_pause)
            logger.debug("第 %d 次滚动", i + 1)

    # ------------------------------------------------------------------
    # 抽象方法实现
    # ------------------------------------------------------------------

    async def login(self) -> None:
        """
        登录抖音

        流程：
        1. 启动 Playwright Chromium 浏览器
        2. 如果本地有 Cookie 文件，先加载并验证有效性
        3. 若 Cookie 无效或不存在，打开抖音首页等待用户手动扫码登录
        4. 登录成功后保存 Cookie 到本地
        """
        pw = await async_playwright().start()
        self._browser = await pw.chromium.launch(headless=False)
        self._context = await self._browser.new_context(
            user_agent=_HEADERS["User-Agent"],
            extra_http_headers=_HEADERS,
            viewport={"width": 1280, "height": 800},
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

        # 打开抖音首页，等待用户手动扫码/登录
        logger.info("正在打开抖音首页: %s", _DOUYIN_BASE)
        await self._page.goto(_DOUYIN_BASE, wait_until="domcontentloaded")

        # 等待登录成功（最长 120 秒）
        # 检测方式：等待页面出现用户相关元素，或 URL 变化
        try:
            # 等待最多 120 秒，每隔 3 秒检查一次登录状态
            for _ in range(40):
                await asyncio.sleep(3)
                if await self._check_login_status():
                    break
            else:
                raise TimeoutError("登录超时（120 秒），请重试")
        except TimeoutError:
            raise

        # 登录成功，保存 Cookie
        cookies = await self._context.cookies()
        self._save_cookies(cookies)
        logger.info("抖音登录成功，Cookie 已保存")

    async def get_favorites(self) -> list[FavoriteFolder]:
        """
        获取当前用户的所有收藏夹列表

        通过 Playwright 导航到收藏页面，拦截网络请求获取收藏夹数据。
        抖音收藏页面: https://www.douyin.com/user/self?showTab=favorite_collection
        """
        if self._page is None or self._context is None:
            raise RuntimeError("浏览器未初始化，请先调用 login()")

        favorites_url = f"{_DOUYIN_BASE}/user/self?showTab=favorite_collection"
        logger.info("正在导航到收藏页面: %s", favorites_url)

        # 设置响应拦截器，收集收藏夹 API 数据
        folder_data_list: list[dict] = []

        async def _on_response(response: Response) -> None:
            """拦截收藏夹列表 API 响应"""
            url = response.url
            if _FAV_FOLDER_API in url and response.status == 200:
                try:
                    data = await response.json()
                    folder_data_list.append(data)
                    logger.info("拦截到收藏夹列表 API 响应")
                except Exception as e:
                    logger.warning("解析收藏夹 API 响应失败: %s", e)

        self._page.on("response", _on_response)

        try:
            await self._page.goto(favorites_url, wait_until="domcontentloaded")
            # 等待页面加载并触发 API 请求
            await asyncio.sleep(5)

            # 尝试点击"收藏"标签页以触发请求
            try:
                fav_tab = await self._page.query_selector(
                    '[data-e2e="favorite-tab"], [class*="favorite"]'
                )
                if fav_tab:
                    await fav_tab.click()
                    await asyncio.sleep(3)
            except Exception:
                pass

            # 滚动页面以加载更多收藏夹
            await self._scroll_to_load_more(max_scrolls=5, scroll_pause=1.0)
        finally:
            self._page.remove_listener("response", _on_response)

        # 解析收藏夹数据
        folders: list[FavoriteFolder] = []
        for data in folder_data_list:
            folder_items = (
                data.get("data", {}).get("favorite_folder_list", [])
                or data.get("favorite_folder_list", [])
            )
            for item in folder_items:
                folder = FavoriteFolder(
                    folder_id=str(item.get("id", item.get("folder_id", ""))),
                    title=item.get("title", item.get("name", "未命名收藏夹")),
                    video_count=item.get("cover_count", item.get("video_count", 0)),
                    url=f"{_DOUYIN_BASE}/user/self?showTab=favorite_collection&folder_id={item.get('id', '')}",
                )
                folders.append(folder)

        # 如果 API 拦截没有获取到数据，尝试从页面 DOM 解析
        if not folders:
            logger.info("API 拦截未获取到数据，尝试从 DOM 解析收藏夹列表")
            folders = await self._parse_folders_from_dom()

        logger.info("获取到 %d 个收藏夹", len(folders))
        return folders

    async def _parse_folders_from_dom(self) -> list[FavoriteFolder]:
        """从页面 DOM 中解析收藏夹列表（兜底方案）"""
        if self._page is None:
            return []

        folders: list[FavoriteFolder] = []
        try:
            # 尝试查找收藏夹列表元素
            folder_elements = await self._page.query_selector_all(
                '[class*="folder-item"], [class*="collect-item"], [data-e2e="favorite-folder"]'
            )
            for elem in folder_elements:
                title_el = await elem.query_selector(
                    '[class*="title"], [class*="name"], span'
                )
                count_el = await elem.query_selector(
                    '[class*="count"], [class*="num"]'
                )
                title = await title_el.inner_text() if title_el else "未命名收藏夹"
                count_text = await count_el.inner_text() if count_el else "0"
                # 提取数字
                count = 0
                for ch in count_text:
                    if ch.isdigit():
                        count = count * 10 + int(ch)

                folder_id = str(len(folders))  # DOM 方式无法获取真实 ID，用索引代替
                folders.append(
                    FavoriteFolder(
                        folder_id=folder_id,
                        title=title.strip(),
                        video_count=count,
                    )
                )
        except Exception as e:
            logger.warning("DOM 解析收藏夹失败: %s", e)

        return folders

    async def get_favorite_videos(self, folder_id: str) -> list[VideoInfo]:
        """
        获取指定收藏夹中的所有视频（自动处理分页）

        通过 Playwright 导航到对应收藏夹页面，拦截 API 请求获取视频数据。
        """
        if self._page is None or self._context is None:
            raise RuntimeError("浏览器未初始化，请先调用 login()")

        videos: list[VideoInfo] = []
        collected_data: list[dict] = []

        async def _on_response(response: Response) -> None:
            """拦截收藏夹视频列表 API 响应"""
            url = response.url
            if _FAV_VIDEO_API in url and response.status == 200:
                try:
                    data = await response.json()
                    collected_data.append(data)
                    logger.info("拦截到收藏夹视频 API 响应")
                except Exception as e:
                    logger.warning("解析收藏夹视频响应失败: %s", e)

        self._page.on("response", _on_response)

        try:
            # 导航到收藏页面并指定收藏夹
            fav_url = (
                f"{_DOUYIN_BASE}/user/self"
                f"?showTab=favorite_collection&folder_id={folder_id}"
            )
            logger.info("正在导航到收藏夹视频页面: %s", fav_url)
            await self._page.goto(fav_url, wait_until="domcontentloaded")
            await asyncio.sleep(5)

            # 滚动加载更多视频
            prev_count = 0
            no_new_data_count = 0
            for scroll_idx in range(30):
                await self._page.evaluate(
                    "window.scrollTo(0, document.body.scrollHeight)"
                )
                await asyncio.sleep(1.5)

                # 检查是否有新数据
                if len(collected_data) > prev_count:
                    prev_count = len(collected_data)
                    no_new_data_count = 0
                else:
                    no_new_data_count += 1
                    if no_new_data_count >= 3:
                        logger.info("连续 %d 次滚动无新数据，停止加载", no_new_data_count)
                        break

                logger.debug("第 %d 次滚动，已拦截 %d 个响应", scroll_idx + 1, len(collected_data))
        finally:
            self._page.remove_listener("response", _on_response)

        # 解析视频数据
        seen_ids: set[str] = set()
        for data in collected_data:
            aweme_list = (
                data.get("data", {}).get("aweme_list", [])
                or data.get("aweme_list", [])
            )
            for item in aweme_list:
                video = self._parse_video_info(item)
                if video and video.video_id not in seen_ids:
                    seen_ids.add(video.video_id)
                    videos.append(video)

        # 如果 API 拦截未获取到数据，尝试 DOM 解析
        if not videos:
            logger.info("API 拦截未获取到视频数据，尝试从 DOM 解析")
            videos = await self._parse_videos_from_dom()

        logger.info("收藏夹 %s 共获取到 %d 个视频", folder_id, len(videos))
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

    async def _parse_videos_from_dom(self) -> list[VideoInfo]:
        """从页面 DOM 中解析视频列表（兜底方案）"""
        if self._page is None:
            return []

        videos: list[VideoInfo] = []
        try:
            video_elements = await self._page.query_selector_all(
                '[class*="video-card"], [data-e2e="favorite-video"], [class*="aweme-item"]'
            )
            for elem in video_elements:
                # 尝试提取标题
                title_el = await elem.query_selector(
                    '[class*="title"], [class*="desc"], a[title]'
                )
                title = ""
                if title_el:
                    title = await title_el.inner_text()
                    title = title.strip()

                # 尝试提取链接中的视频 ID
                link_el = await elem.query_selector("a[href*='/video/']")
                href = ""
                if link_el:
                    href = await link_el.get_attribute("href") or ""

                aweme_id = ""
                if "/video/" in href:
                    aweme_id = href.split("/video/")[-1].split("?")[0]

                if aweme_id:
                    videos.append(
                        VideoInfo(
                            video_id=aweme_id,
                            title=title or f"视频_{aweme_id}",
                            url=f"{_DOUYIN_BASE}/video/{aweme_id}",
                        )
                    )
        except Exception as e:
            logger.warning("DOM 解析视频列表失败: %s", e)

        return videos

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
