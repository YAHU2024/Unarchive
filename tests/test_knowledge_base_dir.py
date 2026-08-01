"""
Tests for unified knowledge_base_dir: GUI and CLI use the same directory.

The _kb_dir() helper in app.py must read from AppConfig, not hardcode.
"""
from __future__ import annotations

from pathlib import Path
from unittest.mock import patch
import json
import tempfile
import os

import pytest


class TestKbDirFromConfig:
    """GUI helper _kb_dir() reads from AppConfig.knowledge_base_dir."""

    def test_kb_dir_uses_config(self, monkeypatch):
        """_kb_dir() returns the path from AppConfig, not hardcoded value."""
        import app
        from config import get_config

        # Use a temp directory as knowledge_base_dir
        with tempfile.TemporaryDirectory() as tmpdir:
            kb_path = Path(tmpdir) / "custom_kb"
            monkeypatch.setenv("KNOWLEDGE_BASE_DIR", str(kb_path))
            # Clear lru_cache so config re-reads env
            get_config.cache_clear()
            try:
                result = app._kb_dir()
                assert result == kb_path
                assert kb_path.exists()  # dir was created
            finally:
                get_config.cache_clear()

    def test_save_and_load_use_same_dir(self, monkeypatch):
        """_save_knowledge_card and _load_knowledge_card read from same config dir."""
        import app
        from config import get_config

        with tempfile.TemporaryDirectory() as tmpdir:
            kb_path = Path(tmpdir) / "shared_kb"
            monkeypatch.setenv("KNOWLEDGE_BASE_DIR", str(kb_path))
            get_config.cache_clear()
            try:
                card = {"video_id": "BV001", "title": "Test", "summary": "test"}
                saved_path = app._save_knowledge_card("BV001", card)
                assert saved_path.parent == kb_path
                loaded = app._load_knowledge_card("BV001")
                assert loaded is not None
                assert loaded["video_id"] == "BV001"
                assert loaded["title"] == "Test"
            finally:
                get_config.cache_clear()

    def test_list_cards_from_config_dir(self, monkeypatch):
        """_list_knowledge_cards reads from config directory."""
        import app
        from config import get_config

        with tempfile.TemporaryDirectory() as tmpdir:
            kb_path = Path(tmpdir) / "list_kb"
            monkeypatch.setenv("KNOWLEDGE_BASE_DIR", str(kb_path))
            get_config.cache_clear()
            try:
                # Save two cards
                app._save_knowledge_card("BV001", {"video_id": "BV001", "title": "A"})
                app._save_knowledge_card("BV002", {"video_id": "BV002", "title": "B"})
                cards = app._list_knowledge_cards()
                assert len(cards) == 2
                titles = {c["title"] for c in cards}
                assert titles == {"A", "B"}
            finally:
                get_config.cache_clear()
