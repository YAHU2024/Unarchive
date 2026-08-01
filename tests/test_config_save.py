"""
Tests for .env config save: preserves unknown keys, safe serialization.

All tests work with a temp .env file — no real .env is modified.
"""
from __future__ import annotations

import os
import tempfile
from pathlib import Path
from unittest.mock import patch, MagicMock


def _make_temp_env(lines: list[str]) -> str:
    """Write lines to a temp .env file and return its path."""
    tmp = tempfile.NamedTemporaryFile(
        mode="w", suffix=".env", delete=False, encoding="utf-8"
    )
    tmp.write("\n".join(lines) + "\n")
    tmp.close()
    return tmp.name


# The do_save_config function lives in app.py; we import and test it
# with a mocked .env path via monkeypatching "open" and os.path.exists.


class TestEnvSavePreservesUnknownKeys:
    """Saving config should keep fields not present in the form."""

    def test_preserves_unedited_fields(self, monkeypatch):
        """Fields like LLM_PROVIDER, WHISPER_MODEL, DATA_DIR survive save."""
        from app import do_save_config

        original_lines = [
            "# comment line",
            "LLM_PROVIDER=deepseek",
            "LLM_API_KEY=old_key",
            "LLM_BASE_URL=https://old.example.com",
            "LLM_MODEL=old-model",
            "WHISPER_MODEL=small",
            "WHISPER_DEVICE=cpu",
            "DATA_DIR=./my_data",
            "FEISHU_APP_ID=old_feishu",
            "FEISHU_APP_SECRET=old_secret",
            "IMA_CLIENT_ID=old_ima",
            "IMA_API_KEY=old_ima_key",
        ]
        env_path = _make_temp_env(original_lines)

        # Mock os.path.exists and open to use our temp file
        orig_exists = os.path.exists
        orig_open = open

        def mock_exists(path):
            if path == ".env":
                return True
            return orig_exists(path)

        monkeypatch.setattr(os.path, "exists", mock_exists)

        # Replace builtins.open only for ".env" reads/writes
        import builtins

        orig_builtin_open = builtins.open

        def mock_open(file, mode="r", encoding=None, **kwargs):
            if file == ".env":
                if "w" in mode:
                    return orig_builtin_open(env_path, mode, encoding=encoding, **kwargs)
                else:
                    return orig_builtin_open(env_path, mode, encoding=encoding, **kwargs)
            return orig_builtin_open(file, mode, encoding=encoding, **kwargs)

        monkeypatch.setattr(builtins, "open", mock_open)

        # Call do_save_config with new values for edited fields
        state = {"kb_id": "new_kb_id", "kb_folder_value": ""}
        result = do_save_config(
            llm_api_key="new_api_key",
            llm_base_url="https://new.example.com",
            llm_model="new-model",
            feishu_app_id="new_feishu",
            feishu_app_secret="new_secret",
            ima_client_id="new_ima",
            ima_api_key="new_ima_key",
            ima_knowledge_base_id="new_kb_id",
            state=state,
        )
        assert "配置已保存" in result

        # Read back the saved file
        with open(env_path, "r", encoding="utf-8") as f:
            saved = f.read()

        # Edited fields should be updated
        assert "LLM_API_KEY=new_api_key" in saved
        assert "LLM_BASE_URL=https://new.example.com" in saved
        assert "LLM_MODEL=new-model" in saved

        # Unedited fields should be preserved
        assert "LLM_PROVIDER=deepseek" in saved
        assert "WHISPER_MODEL=small" in saved
        assert "WHISPER_DEVICE=cpu" in saved
        assert "DATA_DIR=./my_data" in saved

        # Comments preserved
        assert "# comment line" in saved

        # Cleanup
        os.unlink(env_path)

    def test_safe_serialization_no_newlines_in_values(self, monkeypatch):
        """Values with newlines should be stripped to avoid broken .env."""
        from app import do_save_config

        original_lines = ["LLM_API_KEY=old"]
        env_path = _make_temp_env(original_lines)

        import builtins

        orig_builtin_open = builtins.open

        def mock_open(file, mode="r", encoding=None, **kwargs):
            if file == ".env":
                return orig_builtin_open(env_path, mode, encoding=encoding, **kwargs)
            return orig_builtin_open(file, mode, encoding=encoding, **kwargs)

        monkeypatch.setattr(os.path, "exists", lambda p: p == ".env" and True)
        monkeypatch.setattr(builtins, "open", mock_open)

        state = {"kb_id": "", "kb_folder_value": ""}
        result = do_save_config(
            llm_api_key="key\nwith\nnewlines",
            llm_base_url="https://ok.example.com",
            llm_model="model",
            feishu_app_id="",
            feishu_app_secret="",
            ima_client_id="",
            ima_api_key="",
            ima_knowledge_base_id="",
            state=state,
        )
        assert "配置已保存" in result

        with open(env_path, "r", encoding="utf-8") as f:
            saved = f.read()

        # No raw newlines inside values
        for line in saved.splitlines():
            if line.startswith("LLM_API_KEY="):
                # After stripping, the value should be on a single line
                assert "\n" not in line
                assert "keywithnewlines" in line

        os.unlink(env_path)

    def test_new_keys_appended_when_missing(self, monkeypatch):
        """When .env lacks IMA_KNOWLEDGE_BASE_FOLDER_ID, it is appended."""
        from app import do_save_config

        original_lines = [
            "LLM_API_KEY=test_key",
            "LLM_BASE_URL=https://test.example.com",
            "LLM_MODEL=test-model",
            "FEISHU_APP_ID=",
            "FEISHU_APP_SECRET=",
            "IMA_CLIENT_ID=",
            "IMA_API_KEY=",
            "IMA_KNOWLEDGE_BASE_ID=",
        ]
        env_path = _make_temp_env(original_lines)

        import builtins

        orig_builtin_open = builtins.open

        def mock_open(file, mode="r", encoding=None, **kwargs):
            if file == ".env":
                return orig_builtin_open(env_path, mode, encoding=encoding, **kwargs)
            return orig_builtin_open(file, mode, encoding=encoding, **kwargs)

        monkeypatch.setattr(os.path, "exists", lambda p: p == ".env" and True)
        monkeypatch.setattr(builtins, "open", mock_open)

        state = {"kb_id": "kb123", "kb_folder_value": "folder_abc"}
        result = do_save_config(
            llm_api_key="test_key",
            llm_base_url="https://test.example.com",
            llm_model="test-model",
            feishu_app_id="",
            feishu_app_secret="",
            ima_client_id="",
            ima_api_key="",
            ima_knowledge_base_id="kb123",
            state=state,
        )
        assert "配置已保存" in result

        with open(env_path, "r", encoding="utf-8") as f:
            saved = f.read()

        # These keys should be appended
        assert "IMA_KNOWLEDGE_BASE_FOLDER_ID=folder_abc" in saved
        assert "IMA_KNOWLEDGE_BASE_FOLDER_NAME=" in saved

        os.unlink(env_path)
