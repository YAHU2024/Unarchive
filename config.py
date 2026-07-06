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

    # Whisper 相关
    whisper_model: str = Field(default="small", description="Whisper 模型: tiny/base/small/medium/large")
    whisper_device: str = Field(default="auto", description="Whisper 运行设备: auto / cpu / cuda")
    whisper_compute_type: str = Field(default="auto", description="faster-whisper 计算精度: auto / float16 / int8_float16 / int8")

    # 路径配置
    data_dir: str = Field(default="./data", description="数据根目录")
    transcripts_dir: str = Field(default="./data/transcripts", description="字幕/转写文件目录")
    audio_cache_dir: str = Field(default="./data/audio_cache", description="音频缓存目录")
    knowledge_base_dir: str = Field(default="./data/knowledge_base", description="知识库目录")

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
            whisper_model=os.getenv("WHISPER_MODEL", "small"),
            whisper_device=os.getenv("WHISPER_DEVICE", "auto"),
            whisper_compute_type=os.getenv("WHISPER_COMPUTE_TYPE", "auto"),
            data_dir=os.getenv("DATA_DIR", "./data"),
            transcripts_dir=os.getenv("TRANSCRIPTS_DIR", "./data/transcripts"),
            audio_cache_dir=os.getenv("AUDIO_CACHE_DIR", "./data/audio_cache"),
            knowledge_base_dir=os.getenv("KNOWLEDGE_BASE_DIR", "./data/knowledge_base"),
            douyin_cdp_port=int(os.getenv("DOUYIN_CDP_PORT", "9222")),
            video_download_dir=os.getenv("VIDEO_DOWNLOAD_DIR", "./data/videos"),
        )

    def ensure_dirs(self) -> None:
        """确保所有数据目录存在"""
        for dir_path in [self.data_dir, self.transcripts_dir, self.audio_cache_dir, self.knowledge_base_dir]:
            Path(dir_path).mkdir(parents=True, exist_ok=True)


@lru_cache()
def get_config() -> AppConfig:
    """获取配置单例"""
    return AppConfig.from_env()
