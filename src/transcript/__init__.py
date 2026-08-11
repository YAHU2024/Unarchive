"""
字幕/转写模块

处理视频字幕获取和语音转文字（Whisper）。
"""

from src.transcript.subtitle import (
    SubtitleParser,
    get_transcript,
    prefetch_transcript_media,
)
from src.transcript.whisper_asr import WhisperTranscriber

__all__ = [
    "SubtitleParser",
    "WhisperTranscriber",
    "get_transcript",
    "prefetch_transcript_media",
]
