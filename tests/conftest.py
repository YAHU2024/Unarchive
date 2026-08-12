"""
Pytest fixtures shared across test modules.
No real network/LLM/API calls — all external I/O is mocked.
"""
from __future__ import annotations

import pytest


@pytest.fixture
def sample_card() -> dict:
    """A minimal valid knowledge card for testing sync/analysis pipelines."""
    return {
        "video_id": "BV1234567890",
        "title": "Test Video Title",
        "author": "Test Author",
        "source_url": "https://www.bilibili.com/video/BV1234567890",
        "platform": "Bilibili",
        "transcript_source": "cc_subtitle",
        "transcript": "This is a test transcript.",
        "summary": "A test summary.",
        "keywords": ["test", "video"],
        "topics": ["testing"],
        "key_points": ["point 1"],
    }
