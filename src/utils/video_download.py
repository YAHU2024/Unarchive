"""视频文件下载工具"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Optional, Callable, Awaitable

import httpx

logger = logging.getLogger(__name__)


async def download_with_progress(
    url: str,
    output_path: str,
    headers: dict | None = None,
    progress_callback: Optional[Callable[[int, int], Awaitable[None]]] = None,
    timeout: float = 300.0,
) -> bool:
    """流式下载文件，支持进度回调

    Args:
        url: 文件 URL
        output_path: 本地保存路径
        headers: 额外 HTTP 请求头
        progress_callback: 进度回调 (bytes_downloaded, total_bytes)
        timeout: 超时秒数

    Returns:
        下载成功返回 True
    """
    Path(output_path).parent.mkdir(parents=True, exist_ok=True)

    try:
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=True) as client:
            async with client.stream("GET", url, headers=headers or {}) as resp:
                if resp.status != 200:
                    logger.warning("下载失败 HTTP %d: %s", resp.status, url[:120])
                    return False

                total = int(resp.headers.get("content-length", 0))
                downloaded = 0

                with open(output_path, "wb") as f:
                    async for chunk in resp.aiter_bytes(chunk_size=8192):
                        f.write(chunk)
                        downloaded += len(chunk)
                        if progress_callback and total > 0:
                            await progress_callback(downloaded, total)

        logger.info("下载完成: %s (%d bytes)", output_path, downloaded)
        return True
    except Exception as e:
        logger.warning("下载异常: %s", e)
        return False
