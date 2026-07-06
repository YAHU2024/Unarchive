"""
Chrome DevTools Protocol 工具

通过 CDP 连接用户日常使用的 Chrome 浏览器，
复用已有的登录态，避免重复登录和反爬检测。

连接失败时自动查找并拉起 Chrome（带调试端口），
使用持久化用户目录保存登录态。
"""

from __future__ import annotations

import asyncio
import logging
import os
import shutil
import socket
import subprocess
import sys
from pathlib import Path
from typing import Optional

from playwright.async_api import async_playwright, Browser, BrowserContext, Page

logger = logging.getLogger(__name__)


class CDPClient:
    """管理与 Chrome 实例的 CDP 连接

    核心原则：断开时绝不关闭用户浏览器，只释放 Playwright 连接。
    """

    def __init__(self, debug_port: int = 9222, timeout: float = 30.0,
                 chrome_profile_dir: str = "data/chrome_profile"):
        self._debug_port = debug_port
        self._timeout = timeout
        self._chrome_profile_dir = chrome_profile_dir
        self._playwright = None
        self._browser: Optional[Browser] = None
        self._context: Optional[BrowserContext] = None
        self._page: Optional[Page] = None
        self._connected = False
        self._chrome_process: Optional[subprocess.Popen] = None

    @property
    def connected(self) -> bool:
        return self._connected and self._browser is not None

    @property
    def page(self) -> Optional[Page]:
        return self._page

    @property
    def context(self) -> Optional[BrowserContext]:
        return self._context

    @property
    def launched_by_us(self) -> bool:
        """是否由我们自动拉起的 Chrome（而非连接到用户已有的浏览器）"""
        return self._chrome_process is not None

    async def connect(self, url: str = "https://www.douyin.com") -> bool:
        """连接到 Chrome 实例（优先复用已有，失败则自动拉起）

        1. 先尝试连接已在运行的 Chrome（端口 {debug_port}）
        2. 失败则自动查找并拉起 Chrome，然后重试连接
        3. 拉起时使用持久化用户目录，登录态跨次保留

        Args:
            url: 连接后导航到的初始 URL

        Returns:
            连接成功返回 True，失败返回 False
        """
        # 第一步：尝试连接已在运行的 Chrome
        if await self._try_connect(url):
            return True

        # 第二步：自动拉起 Chrome 并重试
        if await self._launch_chrome():
            for attempt in range(10):
                await asyncio.sleep(1)
                if await self._try_connect(url):
                    logger.info("Chrome 已拉起，CDP 连接成功 (port=%d)", self._debug_port)
                    return True
            logger.warning("Chrome 已启动但 CDP 连接超时 (port=%d)", self._debug_port)

        return False

    async def _try_connect(self, url: str = "https://www.douyin.com") -> bool:
        """单次 CDP 连接尝试（不触发自动拉起）"""
        try:
            self._playwright = await async_playwright().start()
            self._browser = await self._playwright.chromium.connect_over_cdp(
                f"http://127.0.0.1:{self._debug_port}"
            )
            contexts = self._browser.contexts
            if contexts:
                self._context = contexts[0]
            else:
                self._context = await self._browser.new_context()

            pages = self._context.pages
            if pages:
                self._page = pages[0]
            else:
                self._page = await self._context.new_page()

            self._connected = True
            logger.info("CDP 连接成功: port=%d, 已有 %d 个上下文, %d 个页面",
                         self._debug_port, len(contexts), len(pages))
            return True
        except Exception as e:
            logger.warning("CDP 连接失败 (port=%d): %s", self._debug_port, e)
            await self._cleanup_connection()
            return False

    async def new_page(self) -> Optional[Page]:
        """创建新的后台页面"""
        if self._context is None:
            return None
        page = await self._context.new_page()
        return page

    # ------------------------------------------------------------------
    # Chrome 自动拉起
    # ------------------------------------------------------------------

    @staticmethod
    def _find_chrome() -> Optional[str]:
        """查找系统中已安装的 Chrome / Chromium 可执行文件路径"""
        env_path = os.environ.get("CHROME_PATH", "")
        if env_path and Path(env_path).exists():
            return env_path

        if sys.platform == "win32":
            candidates = [
                r"C:\Program Files\Google\Chrome\Application\chrome.exe",
                r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
                os.path.expandvars(r"%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe"),
            ]
        elif sys.platform == "darwin":
            candidates = [
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                os.path.expanduser("~/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
            ]
        else:
            candidates = [
                "google-chrome", "google-chrome-stable",
                "chromium-browser", "chromium",
                "/usr/bin/google-chrome", "/usr/bin/chromium-browser",
            ]

        for path in candidates:
            try:
                if Path(path).exists():
                    return str(path)
            except (OSError, TypeError):
                continue

        # Linux: fallback to which
        if sys.platform != "win32":
            for name in ["google-chrome", "google-chrome-stable", "chromium-browser", "chromium"]:
                found = shutil.which(name)
                if found:
                    return found

        return None

    async def _launch_chrome(self) -> bool:
        """启动 Chrome 并开启调试端口

        使用持久化用户目录 (data/chrome_profile)，登录态跨次保留。
        如果端口已被占用则跳过（说明 Chrome 已在运行）。
        """
        chrome_path = self._find_chrome()
        if not chrome_path:
            logger.warning("未找到 Chrome 安装路径，设置 CHROME_PATH 环境变量可手动指定")
            return False

        # 检查端口是否已被占用
        if self._is_port_in_use(self._debug_port):
            logger.debug("端口 %d 已被占用，跳过拉起", self._debug_port)
            return False

        profile_dir = Path(self._chrome_profile_dir).absolute()
        profile_dir.mkdir(parents=True, exist_ok=True)

        cmd = [
            chrome_path,
            f"--remote-debugging-port={self._debug_port}",
            f"--user-data-dir={profile_dir}",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions",
        ]
        # 默认显示窗口（用户可能需要登录），设置 CHROME_HEADLESS=1 可启用无头模式
        if os.environ.get("CHROME_HEADLESS"):
            cmd.append("--headless=new")

        try:
            logger.info("正在启动 Chrome: %s (port=%d, profile=%s)",
                         chrome_path, self._debug_port, profile_dir)
            self._chrome_process = subprocess.Popen(
                cmd,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            return True
        except Exception as e:
            logger.warning("启动 Chrome 失败: %s", e)
            return False

    @staticmethod
    def _is_port_in_use(port: int) -> bool:
        """检查端口是否已被监听"""
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
                s.settimeout(0.5)
                return s.connect_ex(("127.0.0.1", port)) == 0
        except OSError:
            return False

    async def _cleanup_connection(self) -> None:
        """清理失败的连接尝试（不记录日志，不杀 Chrome 进程）"""
        self._connected = False

        if self._page is not None:
            try:
                await self._page.close()
            except Exception:
                pass
            self._page = None

        if self._browser is not None:
            try:
                await self._browser.close()
            except Exception:
                pass
            self._browser = None

        self._context = None

    async def disconnect(self) -> None:
        """断开 CDP 连接

        - 释放 Playwright 连接（不关闭用户原有的 Chrome）
        - 如果 Chrome 是我们拉起的，则终止进程
        """
        was_connected = self._connected
        self._connected = False

        if self._page is not None:
            try:
                await self._page.close()
            except Exception:
                pass
            self._page = None

        if self._browser is not None:
            try:
                # disconnect 而非 close，用户浏览器继续保持打开
                await self._browser.close()
            except Exception:
                pass
            self._browser = None

        self._context = None

        # 终止我们拉起的 Chrome 进程
        if self._chrome_process is not None:
            try:
                self._chrome_process.terminate()
                self._chrome_process.wait(timeout=5)
                logger.info("Chrome 进程已终止 (pid=%d)", self._chrome_process.pid)
            except Exception:
                try:
                    self._chrome_process.kill()
                except Exception:
                    pass
            self._chrome_process = None

        if was_connected:
            logger.info("CDP 连接已断开")

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        await self.disconnect()
