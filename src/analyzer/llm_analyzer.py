"""
LLM 内容分析模块

调用 LLM API（DeepSeek / 通义千问）对视频转写文本进行结构分析和内容总结。
使用 OpenAI 兼容接口格式，支持多种国产大模型。
"""

import json
import re
import logging
from pathlib import Path
from typing import Optional

import httpx

from config import AppConfig, get_config

logger = logging.getLogger(__name__)

# 项目根目录（src/analyzer -> 上两级）
_PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
_PROMPTS_DIR = _PROJECT_ROOT / "prompts"


class LLMAnalyzer:
    """LLM 视频内容分析器

    通过 OpenAI 兼容接口调用 DeepSeek / 通义千问等国产大模型，
    对视频逐字稿进行结构分析和内容总结。
    """

    def __init__(self, config: AppConfig = None):
        """初始化 LLM 分析器

        Args:
            config: 应用配置，为 None 时自动从环境变量加载
        """
        self.config = config or get_config()
        self.api_key = self.config.llm_api_key
        self.base_url = self.config.llm_base_url.rstrip("/")
        self.model = self.config.llm_model
        self.provider = self.config.llm_provider

        # httpx 异步客户端，设置合理超时
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(connect=10.0, read=120.0, write=10.0, pool=10.0),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
        )

        # 重试配置
        self._max_retries = 3

    async def analyze_video(self, title: str, author: str, transcript: str) -> dict:
        """对视频内容进行完整的结构分析和总结

        Args:
            title: 视频标题
            author: 视频作者
            transcript: 视频逐字稿文本

        Returns:
            结构化分析结果字典，包含 summary, keywords, topics, key_points 等字段
        """
        logger.info("开始分析视频: %s (作者: %s)", title, author)

        # 截断过长的逐字稿
        truncated = self._truncate_transcript(transcript)

        # 并行执行结构分析和内容总结
        try:
            import asyncio
            structure_result, summary_result = await asyncio.gather(
                self._analyze_structure(title, author, truncated),
                self._summarize(title, author, truncated),
            )
        except Exception as e:
            logger.error("LLM 分析调用失败: %s", e)
            raise

        # 合并两个结果
        merged = {
            "title": title,
            "author": author,
            # 来自 summary
            "summary": summary_result.get("summary", ""),
            "keywords": summary_result.get("keywords", []),
            "one_line_summary": summary_result.get("one_line_summary", ""),
            # 来自 structure
            "topics": structure_result.get("topics", []),
            "key_points": structure_result.get("key_points", []),
            "knowledge_tags": structure_result.get("knowledge_tags", []),
            "target_audience": structure_result.get("target_audience", ""),
            "action_items": structure_result.get("action_items", []),
            "mindmap_structure": structure_result.get("mindmap_structure", {
                "center": title,
                "branches": [],
            }),
        }

        logger.info("视频分析完成: %s", title)
        return merged

    async def _call_llm(self, prompt: str, system_prompt: str = None) -> str:
        """调用 LLM API（OpenAI 兼容接口格式）

        自动重试最多 _max_retries 次，支持 DeepSeek 和通义千问。

        Args:
            prompt: 用户提示词
            system_prompt: 系统提示词，为 None 时使用默认值

        Returns:
            LLM 响应的文本内容

        Raises:
            httpx.HTTPStatusError: API 返回非 200 状态码
            RuntimeError: 所有重试均失败
        """
        url = f"{self.base_url}/v1/chat/completions"

        messages = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.append({"role": "user", "content": prompt})

        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": 0.7,
            "max_tokens": 4096,
        }

        last_error = None
        for attempt in range(1, self._max_retries + 1):
            try:
                logger.debug("LLM API 调用 (第 %d/%d 次), provider=%s, model=%s",
                             attempt, self._max_retries, self.provider, self.model)

                response = await self._client.post(url, json=payload)
                response.raise_for_status()

                data = response.json()
                content = data["choices"][0]["message"]["content"]
                logger.debug("LLM 响应长度: %d 字符", len(content))
                return content

            except (httpx.HTTPStatusError, httpx.RequestError, KeyError) as e:
                last_error = e
                logger.warning("LLM API 调用失败 (第 %d 次): %s", attempt, e)
                if attempt < self._max_retries:
                    import asyncio
                    await asyncio.sleep(2 ** attempt)  # 指数退避

        raise RuntimeError(f"LLM API 调用在 {self._max_retries} 次重试后仍失败: {last_error}")

    async def _analyze_structure(self, title: str, author: str, transcript: str) -> dict:
        """结构分析 - 使用 prompts/analyze.txt 模板

        提取关键观点、主题分类、知识标签、思维导图结构等。

        Args:
            title: 视频标题
            author: 视频作者
            transcript: 截断后的逐字稿

        Returns:
            结构化分析结果字典
        """
        prompt = self._load_prompt(
            "analyze.txt",
            title=title,
            author=author,
            transcript=transcript,
        )
        system_prompt = "你是一个专业的视频内容分析师，擅长结构化信息提取。请始终返回合法的 JSON 格式。"

        response = await self._call_llm(prompt, system_prompt)
        return self._parse_llm_json(response)

    async def _summarize(self, title: str, author: str, transcript: str) -> dict:
        """内容总结 - 使用 prompts/summarize.txt 模板

        生成摘要、关键词、一句话总结。

        Args:
            title: 视频标题
            author: 视频作者
            transcript: 截断后的逐字稿

        Returns:
            摘要结果字典，包含 summary, keywords, one_line_summary
        """
        prompt = self._load_prompt(
            "summarize.txt",
            title=title,
            author=author,
            transcript=transcript,
        )
        system_prompt = "你是一个专业的内容摘要专家，擅长提炼核心观点。请始终返回合法的 JSON 格式。"

        response = await self._call_llm(prompt, system_prompt)
        return self._parse_llm_json(response)

    def _load_prompt(self, prompt_file: str, **kwargs) -> str:
        """加载 Prompt 模板并填充变量

        从 prompts/ 目录读取模板文件，使用 kwargs 填充占位符。

        Args:
            prompt_file: 模板文件名，如 "analyze.txt"
            **kwargs: 模板变量，如 title, author, transcript

        Returns:
            填充后的提示词文本

        Raises:
            FileNotFoundError: 模板文件不存在
        """
        prompt_path = _PROMPTS_DIR / prompt_file
        if not prompt_path.exists():
            raise FileNotFoundError(f"Prompt 模板文件不存在: {prompt_path}")

        template = prompt_path.read_text(encoding="utf-8")
        return template.format(**kwargs)

    def _truncate_transcript(self, transcript: str, max_chars: int = 12000) -> str:
        """截断过长的逐字稿

        如果超过 max_chars，保留开头和结尾部分，中间用省略标记替代。
        开头保留 60%，结尾保留 40%，确保上下文完整。

        Args:
            transcript: 原始逐字稿文本
            max_chars: 最大字符数，默认 12000

        Returns:
            截断后的逐字稿
        """
        if len(transcript) <= max_chars:
            return transcript

        # 开头保留 60%，结尾保留 40%
        head_chars = int(max_chars * 0.6)
        tail_chars = max_chars - head_chars - len("\n\n...[内容省略]...\n\n")

        head = transcript[:head_chars]
        tail = transcript[-tail_chars:]

        logger.info("逐字稿已截断: %d -> %d 字符", len(transcript), max_chars)
        return f"{head}\n\n...[内容省略]...\n\n{tail}"

    def _parse_llm_json(self, response: str) -> dict:
        """从 LLM 响应中提取 JSON

        处理以下情况：
        1. 纯 JSON 文本
        2. Markdown 代码块包裹的 JSON（```json ... ```）
        3. JSON 前后有多余文字说明

        Args:
            response: LLM 原始响应文本

        Returns:
            解析后的字典

        Raises:
            ValueError: 无法从响应中提取有效 JSON
        """
        text = response.strip()

        # 预处理：清除尾随逗号（LLM 常见输出格式问题，如 `"key": "val",}`）
        text = re.sub(r",\s*([}\]])", r"\1", text)

        # 尝试 1: 直接解析
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            pass

        # 尝试 2: 提取 markdown 代码块中的 JSON
        # 匹配 ```json ... ``` 或 ``` ... ```
        pattern = r"```(?:json)?\s*\n?(.*?)\n?\s*```"
        match = re.search(pattern, text, re.DOTALL)
        if match:
            try:
                return json.loads(match.group(1).strip())
            except json.JSONDecodeError:
                pass

        # 尝试 3: 查找第一个 { 到最后一个 } 之间的内容
        first_brace = text.find("{")
        last_brace = text.rfind("}")
        if first_brace != -1 and last_brace != -1 and last_brace > first_brace:
            try:
                return json.loads(text[first_brace:last_brace + 1])
            except json.JSONDecodeError:
                pass

        # 尝试 4: 查找第一个 [ 到最后一个 ] 之间的内容（数组格式）
        first_bracket = text.find("[")
        last_bracket = text.rfind("]")
        if first_bracket != -1 and last_bracket != -1 and last_bracket > first_bracket:
            try:
                result = json.loads(text[first_bracket:last_bracket + 1])
                # 如果是数组，包装成字典
                if isinstance(result, list):
                    return {"items": result}
                return result
            except json.JSONDecodeError:
                pass

        logger.error("无法从 LLM 响应中解析 JSON，原始响应:\n%s", text[:500])
        raise ValueError(f"无法从 LLM 响应中提取有效 JSON。响应前 200 字符: {text[:200]}")

    async def close(self):
        """关闭 httpx 客户端"""
        await self._client.aclose()

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        await self.close()
