"""
X-Bogus 签名生成器（骨架）

参考 DouK-Downloader (JoeanAmier/TikTokDownloader) 的签名实现。
TODO: 完整实现需要逆向抖音 X-Bogus 算法的 DNA 表和加密逻辑。

当前为骨架模块，不建议在生产中使用。
实际使用时优先走 CDP 模式（无需签名），此模块仅作为无浏览器环境的回退。
"""

from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


class DouyinSigner:
    """抖音 API 签名器（未实现）"""

    @staticmethod
    def sign(url_path: str, params: dict) -> dict:
        """对请求参数进行 X-Bogus 签名（未实现）

        Args:
            url_path: API 路径，如 "/aweme/v1/web/collects/list/"
            params: 原始查询参数字典

        Returns:
            添加了 X-Bogus 和 a_bogus 的完整参数字典
        """
        logger.warning("X-Bogus 签名未实现，请使用 CDP 模式")
        return params
