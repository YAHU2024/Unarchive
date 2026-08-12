"""
LLM 内容分析模块

调用 LLM API（DeepSeek / 通义千问）对视频转写文本进行结构分析和内容总结。
使用 OpenAI 兼容接口格式，支持多种国产大模型。
"""

import asyncio
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
_LONG_TRANSCRIPT_LIMIT = 12000
_TRANSCRIPT_CHUNK_SIZE = 9000
_TRANSCRIPT_CHUNK_OVERLAP = 300
_CHUNK_SUMMARY_MAX_TOKENS = 768


class LLMQuotaExceededError(RuntimeError):
    """LLM API 配额/余额耗尽，不可重试，调用方应停止管道并持久化部分结果。"""


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
        self.enable_thinking = bool(self.config.llm_enable_thinking)
        self.thinking_budget = max(0, int(self.config.llm_thinking_budget))
        self.max_tokens = max(256, int(self.config.llm_max_tokens))

        # httpx 异步客户端，设置合理超时
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(connect=10.0, read=120.0, write=10.0, pool=10.0),
            trust_env=False,  # 禁用环境变量代理，避免 ALL_PROXY 等残留导致请求失败
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

        analysis_chunked = len(transcript) > _LONG_TRANSCRIPT_LIMIT
        analysis_transcript = transcript
        if analysis_chunked:
            analysis_transcript = await self._summarize_long_transcript(
                title, author, transcript
            )
            logger.info(
                "逐字稿分块分析完成: %d -> %d 字符，原文未截断",
                len(transcript),
                len(analysis_transcript),
            )

        # 一次请求同时生成摘要和结构化字段，减少重复输入、排队与重试。
        try:
            combined_result = await self._analyze_combined(
                title, author, analysis_transcript
            )
        except Exception as e:
            logger.error("LLM 分析调用失败: %s", e)
            raise

        # 合并两个结果
        merged = {
            "title": title,
            "author": author,
            "summary": combined_result.get("summary", ""),
            "keywords": combined_result.get("keywords", []),
            "one_line_summary": combined_result.get("one_line_summary", ""),
            "topics": combined_result.get("topics", []),
            "key_points": combined_result.get("key_points", []),
            "knowledge_tags": combined_result.get("knowledge_tags", []),
            "target_audience": combined_result.get("target_audience", ""),
            "action_items": combined_result.get("action_items", []),
            "analysis_chunked": analysis_chunked,
            "mindmap_structure": combined_result.get("mindmap_structure", {
                "center": title,
                "branches": [],
            }),
        }

        logger.info("视频分析完成: %s", title)
        return merged

    async def _analyze_combined(self, title: str, author: str, transcript: str) -> dict:
        prompt = self._load_prompt(
            "analyze_combined.txt",
            title=title,
            author=author,
            transcript=transcript,
        )
        response = await self._call_llm(
            prompt,
            "你是专业的视频知识整理助手。只依据输入内容，并始终返回合法 JSON。",
        )
        return self._parse_llm_json(response)

    @staticmethod
    def _split_transcript(transcript: str) -> list[str]:
        """Split with overlap so every source character reaches a chunk."""
        chunks: list[str] = []
        step = _TRANSCRIPT_CHUNK_SIZE - _TRANSCRIPT_CHUNK_OVERLAP
        start = 0
        while start < len(transcript):
            end = min(len(transcript), start + _TRANSCRIPT_CHUNK_SIZE)
            chunks.append(transcript[start:end])
            if end >= len(transcript):
                break
            start += step
        return chunks

    async def _summarize_long_transcript(
        self, title: str, author: str, transcript: str
    ) -> str:
        chunks = self._split_transcript(transcript)

        async def summarize(index: int, chunk: str) -> str:
            prompt = self._load_prompt(
                "chunk_summarize.txt",
                title=title,
                author=author,
                chunk_index=index + 1,
                chunk_total=len(chunks),
                transcript=chunk,
            )
            return await self._call_llm(
                prompt,
                "你是视频逐字稿事实提取器。只保留原文明确表达的事实、步骤、数字和结论，"
                "不要补充原文没有的信息。",
                max_tokens=_CHUNK_SUMMARY_MAX_TOKENS,
            )

        summaries = await asyncio.gather(
            *(summarize(index, chunk) for index, chunk in enumerate(chunks))
        )
        return "\n\n".join(
            f"[原文分段 {index + 1}/{len(summaries)}]\n{summary.strip()}"
            for index, summary in enumerate(summaries)
            if summary.strip()
        )

    @staticmethod
    def _chat_completions_url(base_url: str) -> str:
        """Build an OpenAI-compatible chat endpoint without duplicating ``/v1``."""
        normalized = base_url.rstrip("/")
        if normalized.endswith("/chat/completions"):
            return normalized
        if normalized.endswith("/v1"):
            return f"{normalized}/chat/completions"
        return f"{normalized}/v1/chat/completions"

    @staticmethod
    def _is_quota_error(status_code: int, body: str) -> bool:
        """Detect LLM quota/balance exhaustion from HTTP status + response body.

        Criteria (any match = quota exhausted, no retry):
        - HTTP 402 (Payment Required) — always quota
        - Error code in JSON body: insufficient_quota, insufficient_balance, quota_exceeded
        - Error message contains: quota, insufficient, balance, exceeded (case-insensitive)
        """
        if status_code == 402:
            return True
        body_lower = body.lower()
        quota_keywords = ["insufficient_quota", "quota_exceeded",
                          "insufficient_balance", "balance insufficient",
                          "quota exceeded", "exceeded your quota",
                          "exceeded your current quota"]
        if any(kw in body_lower for kw in quota_keywords):
            return True
        # Try parsing OpenAI-compatible error JSON
        try:
            error_data = json.loads(body)
            error_code = (error_data.get("error", {}).get("code", "") or "").lower()
            if error_code in ("insufficient_quota", "insufficient_balance", "quota_exceeded"):
                return True
            error_type = (error_data.get("error", {}).get("type", "") or "").lower()
            if error_type == "insufficient_quota":
                return True
        except (json.JSONDecodeError, AttributeError):
            pass
        return False

    async def _call_llm(
        self,
        prompt: str,
        system_prompt: str = None,
        *,
        max_tokens: int | None = None,
    ) -> str:
        """调用 LLM API（OpenAI 兼容接口格式）

        自动重试最多 _max_retries 次，支持 DeepSeek 和通义千问。
        检测到配额/余额耗尽时立即抛出 LLMQuotaExceededError，不重试。

        Args:
            prompt: 用户提示词
            system_prompt: 系统提示词，为 None 时使用默认值

        Returns:
            LLM 响应的文本内容

        Raises:
            LLMQuotaExceededError: 配额/余额耗尽，不可重试
            httpx.HTTPStatusError: API 返回非 200 状态码（非配额）
            RuntimeError: 所有重试均失败
        """
        url = self._chat_completions_url(self.base_url)

        messages = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.append({"role": "user", "content": prompt})

        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": 0.7,
            "max_tokens": max_tokens or self.max_tokens,
        }
        use_stream = "siliconflow" in self.base_url.lower()
        if use_stream:
            payload["stream"] = True
        if self._supports_thinking_controls():
            payload["enable_thinking"] = self.enable_thinking
            if self.enable_thinking and self.thinking_budget > 0:
                payload["thinking_budget"] = self.thinking_budget

        last_error = None
        last_response_body = None  # 用于记录 API 响应体，辅助诊断
        quota_exhausted = False   # 标记是否已检测到配额耗尽
        for attempt in range(1, self._max_retries + 1):
            try:
                logger.debug("LLM API 调用 (第 %d/%d 次), provider=%s, model=%s",
                             attempt, self._max_retries, self.provider, self.model)

                if use_stream:
                    response, content = await self._stream_completion(url, payload)
                else:
                    response = await self._client.post(url, json=payload)
                    content = None

                # 非 200 时主动读取响应体，检查是否配额耗尽
                if response.status_code != 200:
                    last_response_body = response.text[:500]
                    logger.warning(
                        "LLM API 返回异常状态 %d (第 %d 次): %s",
                        response.status_code, attempt, last_response_body,
                    )
                    if self._is_quota_error(response.status_code, last_response_body):
                        quota_exhausted = True
                        raise LLMQuotaExceededError(
                            f"LLM 配额/余额耗尽 (HTTP {response.status_code}): {last_response_body[:200]}"
                        )
                    response.raise_for_status()

                if content is None:
                    data = response.json()
                    content = data["choices"][0]["message"]["content"]
                logger.debug("LLM 响应长度: %d 字符", len(content))
                return content

            except LLMQuotaExceededError:
                # Propagate immediately — quota errors are never retried
                raise

            except httpx.HTTPStatusError as e:
                last_error = e
                body = last_response_body or ""
                logger.warning(
                    "LLM API HTTP 错误 (第 %d 次): status=%d, body=%s",
                    attempt, e.response.status_code, body,
                )
                if attempt < self._max_retries:
                    import asyncio
                    await asyncio.sleep(2 ** attempt)

            except KeyError as e:
                last_error = e
                # 记录实际收到的响应，便于排查格式问题
                try:
                    raw = json.dumps(data, ensure_ascii=False)[:500]
                except Exception:
                    raw = repr(data)[:500] if 'data' in dir() else "N/A"
                logger.warning(
                    "LLM 响应格式异常 (第 %d 次): 缺少字段 %r, 响应内容: %s",
                    attempt, e.args[0] if e.args else "?", raw,
                )
                if attempt < self._max_retries:
                    import asyncio
                    await asyncio.sleep(2 ** attempt)

            except httpx.RequestError as e:
                last_error = e
                logger.warning("LLM API 请求失败 (第 %d 次): %s", attempt, e)
                if attempt < self._max_retries:
                    import asyncio
                    await asyncio.sleep(2 ** attempt)

        # All retries exhausted — if it was a quota pattern, raise accordingly
        if quota_exhausted:
            body_snippet = (last_response_body or "")[:200]
            raise LLMQuotaExceededError(
                f"LLM API 调用在 {self._max_retries} 次重试后仍报告配额耗尽: {body_snippet}"
            )
        # 用 repr 兜底，确保错误信息不为空
        error_detail = str(last_error) or repr(last_error)
        raise RuntimeError(f"LLM API 调用在 {self._max_retries} 次重试后仍失败: {error_detail}")

    def _supports_thinking_controls(self) -> bool:
        """Only send SiliconFlow/Qwen3-specific fields to compatible endpoints."""
        return "siliconflow" in self.base_url.lower() and "qwen3" in self.model.lower()

    async def _stream_completion(self, url: str, payload: dict) -> tuple[httpx.Response, str]:
        """Read SiliconFlow SSE content so long outputs keep the connection active."""
        content_parts: list[str] = []
        async with self._client.stream("POST", url, json=payload) as response:
            if response.status_code != 200:
                await response.aread()
                return response, ""
            async for line in response.aiter_lines():
                if not line.startswith("data:"):
                    continue
                raw = line[5:].strip()
                if not raw or raw == "[DONE]":
                    continue
                chunk = json.loads(raw)
                choices = chunk.get("choices", [])
                if not choices:
                    continue
                part = choices[0].get("delta", {}).get("content", "")
                if part:
                    content_parts.append(part)
        if not content_parts:
            raise KeyError("stream content")
        return response, "".join(content_parts)

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
