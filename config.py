"""
配置管理模块

基于 python-dotenv 加载环境变量，使用 pydantic BaseModel 管理配置。
"""

import os
from pathlib import Path
from functools import lru_cache

from dotenv import load_dotenv
from pydantic import BaseModel, Field


# 加载 .env 文件
load_dotenv()


class AppConfig(BaseModel):
    """应用配置"""

    # LLM 相关
    llm_provider: str = Field(default="deepseek", description="LLM 提供商: deepseek / qwen")
    llm_api_key: str = Field(default="", description="LLM API Key")
    llm_base_url: str = Field(default="https://api.deepseek.com", description="LLM API Base URL")
    llm_model: str = Field(default="deepseek-chat", description="LLM 模型名称")

    # 飞书相关
    feishu_app_id: str = Field(default="", description="飞书 App ID")
    feishu_app_secret: str = Field(default="", description="飞书 App Secret")

    # ima 相关（腾讯 ima OpenAPI）
    ima_client_id: str = Field(default="", description="ima OpenAPI Client ID")
    ima_api_key: str = Field(default="", description="ima OpenAPI API Key")
    ima_knowledge_base_id: str = Field(default="", description="ima 知识库 ID（可选，留空仅建笔记）")
    ima_knowledge_base_folder_id: str = Field(default="", description="ima 知识库目标文件夹 ID（可选，留空则根目录）")
    ima_knowledge_base_folder_name: str = Field(default="", description="ima 知识库目标文件夹名称（按名解析，可选）")

    # Whisper 相关
    whisper_model: str = Field(default="small", description="Whisper 模型: tiny/base/small/medium/large")
    whisper_device: str = Field(default="auto", description="Whisper 运行设备: auto / cpu / cuda")
    whisper_compute_type: str = Field(default="auto", description="faster-whisper 计算精度: auto / float16 / int8_float16 / int8")
    audio_cache_max_age_days: int = Field(default=7, description="媒体缓存最长保留天数")
    audio_cache_max_bytes: int = Field(default=2 * 1024 * 1024 * 1024, description="媒体缓存最大字节数")

    # 路径配置
    data_dir: str = Field(default="./data", description="数据根目录")
    transcripts_dir: str = Field(default="./data/transcripts", description="字幕/转写文件目录")
    audio_cache_dir: str = Field(default="./data/audio_cache", description="音频缓存目录")
    knowledge_base_dir: str = Field(default="./data/knowledge_base", description="知识库目录")
    cookies_dir: str = Field(default="./data/cookies", description="平台 Cookie 持久化目录")
    logs_dir: str = Field(default="./data/logs", description="应用日志目录")
    chrome_profile_dir: str = Field(default="./data/chrome_profile", description="Chrome CDP 用户数据目录")

    # CDP 配置
    douyin_cdp_port: int = Field(default=9222, description="Chrome 远程调试端口")
    video_download_dir: str = Field(default="./data/videos", description="视频下载目录")

    @classmethod
    def from_env(cls) -> "AppConfig":
        """从环境变量构建配置"""
        return cls(
            llm_provider=os.getenv("LLM_PROVIDER", "deepseek"),
            llm_api_key=os.getenv("LLM_API_KEY", ""),
            llm_base_url=os.getenv("LLM_BASE_URL", "https://api.deepseek.com"),
            llm_model=os.getenv("LLM_MODEL", "deepseek-chat"),
            feishu_app_id=os.getenv("FEISHU_APP_ID", ""),
            feishu_app_secret=os.getenv("FEISHU_APP_SECRET", ""),
            ima_client_id=os.getenv("IMA_CLIENT_ID", ""),
            ima_api_key=os.getenv("IMA_API_KEY", ""),
            ima_knowledge_base_id=os.getenv("IMA_KNOWLEDGE_BASE_ID", ""),
            ima_knowledge_base_folder_id=os.getenv("IMA_KNOWLEDGE_BASE_FOLDER_ID", ""),
            ima_knowledge_base_folder_name=os.getenv("IMA_KNOWLEDGE_BASE_FOLDER_NAME", ""),
            whisper_model=os.getenv("WHISPER_MODEL", "small"),
            whisper_device=os.getenv("WHISPER_DEVICE", "auto"),
            whisper_compute_type=os.getenv("WHISPER_COMPUTE_TYPE", "auto"),
            audio_cache_max_age_days=int(os.getenv("AUDIO_CACHE_MAX_AGE_DAYS", "7")),
            audio_cache_max_bytes=int(os.getenv("AUDIO_CACHE_MAX_BYTES", str(2 * 1024 * 1024 * 1024))),
            data_dir=os.getenv("DATA_DIR", "./data"),
            transcripts_dir=os.getenv("TRANSCRIPTS_DIR", "./data/transcripts"),
            audio_cache_dir=os.getenv("AUDIO_CACHE_DIR", "./data/audio_cache"),
            knowledge_base_dir=os.getenv("KNOWLEDGE_BASE_DIR", "./data/knowledge_base"),
            cookies_dir=os.getenv("COOKIES_DIR", "./data/cookies"),
            logs_dir=os.getenv("LOGS_DIR", "./data/logs"),
            chrome_profile_dir=os.getenv("CHROME_PROFILE_DIR", "./data/chrome_profile"),
            douyin_cdp_port=int(os.getenv("DOUYIN_CDP_PORT", "9222")),
            video_download_dir=os.getenv("VIDEO_DOWNLOAD_DIR", "./data/videos"),
        )

    def ensure_dirs(self) -> None:
        """确保所有数据目录存在"""
        for dir_path in [
            self.data_dir, self.transcripts_dir,
            self.audio_cache_dir, self.knowledge_base_dir,
            self.cookies_dir, self.logs_dir,
            self.chrome_profile_dir, self.video_download_dir,
        ]:
            Path(dir_path).mkdir(parents=True, exist_ok=True)


@lru_cache()
def get_config() -> AppConfig:
    """获取配置单例"""
    return AppConfig.from_env()
