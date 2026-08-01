"""
Tests for the shared ima sync state path resolution (config-derived).

These tests pin the behavior introduced when the state file stopped being
a hardcoded ``data/ima_sync_state.json`` literal and started being derived
from ``AppConfig.data_dir`` — so a custom ``DATA_DIR`` (or per-workspace
``.env``) keeps the state colocated with the knowledge cards, and so the
GUI and CLI both resolve to the same on-disk file.

The same on-disk format / file location is what makes a sync started in
the GUI respect (or correctly re-attempt) KB associations done earlier
in the CLI, and vice versa.
"""
from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

import pytest

# Make the project root importable regardless of where pytest is invoked.
ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import app  # noqa: E402
import cli as cli_module  # noqa: E402
from config import get_config  # noqa: E402
from src.sync import ima_state  # noqa: E402


# ---------------------------------------------------------------------------
# 1. Default path: derived from data_dir, not a hardcoded literal
# ---------------------------------------------------------------------------

class TestPathDerivedFromDataDir:
    """``get_ima_state_path`` returns ``<data_dir>/ima_sync_state.json``."""

    def test_default_data_dir_resolves_to_known_relative_path(self, monkeypatch):
        """The default data dir is ``./data`` so the default state file is
        ``data/ima_sync_state.json`` (preserves backward compatibility with
        pre-refactor installations that already have a state file there)."""
        # No chdir needed — we just check the relative path is built the
        # same way the pre-refactor code constructed it. Using a tmpdir
        # to keep the parent-create side effect off the user's real data/.
        get_config.cache_clear()
        try:
            cfg = get_config()
            # Sanity: default data_dir is ./data
            assert cfg.data_dir == "./data"
            path = ima_state.get_ima_state_path(cfg)
            assert path == Path("./data") / "ima_sync_state.json"
            assert path.name == "ima_sync_state.json"
        finally:
            get_config.cache_clear()

    def test_custom_data_dir_is_honored(self, monkeypatch):
        """A custom ``DATA_DIR`` makes the state file follow it."""
        with tempfile.TemporaryDirectory() as tmpdir:
            custom_data = Path(tmpdir) / "workspace_data"
            monkeypatch.setenv("DATA_DIR", str(custom_data))
            get_config.cache_clear()
            try:
                cfg = get_config()
                path = ima_state.get_ima_state_path(cfg)
                assert path == custom_data / "ima_sync_state.json"
                # Parent dir was auto-created (so callers can write directly)
                assert path.parent.exists()
            finally:
                get_config.cache_clear()

    def test_state_filename_constant(self):
        """The filename is exported as a constant so callers can override."""
        assert ima_state.DEFAULT_STATE_FILENAME == "ima_sync_state.json"
        assert ima_state.STATE_KEY_SEP == "::"


# ---------------------------------------------------------------------------
# 2. GUI and CLI resolve to the same path
# ---------------------------------------------------------------------------

class TestGuiAndCliSharePath:
    """GUI helper and CLI helper return the same Path for the same config."""

    def test_gui_and_cli_resolve_to_same_path(self, monkeypatch):
        """This is the actual fix for the P2 review: when the user configures
        ``DATA_DIR=...``, the GUI's ``_get_ima_state_file()`` and the CLI's
        ``get_ima_state_path(config)`` must both return the SAME on-disk
        Path, so a sync done in one entry point is visible to the other."""
        with tempfile.TemporaryDirectory() as tmpdir:
            custom_data = Path(tmpdir) / "shared"
            monkeypatch.setenv("DATA_DIR", str(custom_data))
            get_config.cache_clear()
            try:
                cfg = get_config()

                gui_path = app._get_ima_state_file()
                cli_path = ima_state.get_ima_state_path(cfg)

                assert gui_path == cli_path
            finally:
                get_config.cache_clear()

    def test_default_data_dir_no_test_override(self, monkeypatch):
        """Even with no module-level ``_IMA_SYNC_STATE_FILE`` override, the
        GUI returns the same path as the CLI helper."""
        # Clear any test override that earlier tests may have set, and use
        # a custom DATA_DIR so we don't touch the user's real ./data dir.
        monkeypatch.setattr(app, "_IMA_SYNC_STATE_FILE", None)
        with tempfile.TemporaryDirectory() as tmpdir:
            monkeypatch.setenv("DATA_DIR", tmpdir)
            get_config.cache_clear()
            try:
                cfg = get_config()
                gui_path = app._get_ima_state_file()
                cli_path = ima_state.get_ima_state_path(cfg)
                assert gui_path == cli_path
                assert gui_path == Path(tmpdir) / "ima_sync_state.json"
            finally:
                get_config.cache_clear()

    def test_gui_test_override_still_takes_precedence(self, monkeypatch):
        """``app._IMA_SYNC_STATE_FILE`` is the legacy monkeypatch target
        for tests. It must still win when set, so the existing test
        suite keeps working unchanged."""
        with tempfile.TemporaryDirectory() as tmpdir:
            override = Path(tmpdir) / "test_state.json"
            monkeypatch.setattr(app, "_IMA_SYNC_STATE_FILE", override)
            # Also point DATA_DIR somewhere else to prove the override wins
            monkeypatch.setenv("DATA_DIR", str(Path(tmpdir) / "ignored"))
            get_config.cache_clear()
            try:
                assert app._get_ima_state_file() == override
            finally:
                get_config.cache_clear()


# ---------------------------------------------------------------------------
# 3. Save/load round-trip works through the resolved path
# ---------------------------------------------------------------------------

class TestRoundTripThroughConfigPath:
    """GUI and CLI both writing through the same path keeps the on-disk
    shape and the legacy migration in lockstep."""

    def test_gui_save_then_gui_load_round_trips(self, monkeypatch):
        with tempfile.TemporaryDirectory() as tmpdir:
            monkeypatch.setenv("DATA_DIR", tmpdir)
            get_config.cache_clear()
            try:
                app._save_ima_sync_state({"BV1::kb_a": {"note_id": "n1", "kb_added": True}})
                loaded = app._load_ima_sync_state()
                assert "BV1::kb_a" in loaded
                assert loaded["BV1::kb_a"]["note_id"] == "n1"
                # File is on disk under the config-derived path
                on_disk = Path(tmpdir) / "ima_sync_state.json"
                assert on_disk.exists()
            finally:
                get_config.cache_clear()

    def test_cli_loader_reads_what_gui_wrote(self, monkeypatch):
        """The CLI loader must accept the same file the GUI wrote."""
        with tempfile.TemporaryDirectory() as tmpdir:
            monkeypatch.setenv("DATA_DIR", tmpdir)
            get_config.cache_clear()
            try:
                state_file = ima_state.get_ima_state_path(get_config())
                app._save_ima_sync_state({
                    "BV1::kb_cli": {"note_id": "n1", "kb_added": False, "kb_error": "rate"},
                })
                loaded = cli_module._load_ima_state_for_cli(state_file)
                assert "BV1::kb_cli" in loaded
                assert loaded["BV1::kb_cli"]["kb_added"] is False
                assert loaded["BV1::kb_cli"]["kb_error"] == "rate"
            finally:
                get_config.cache_clear()

    def test_legacy_migration_persists_via_resolved_path(self, monkeypatch):
        """A legacy ``vid``-only file under the resolved data_dir is migrated
        on the next GUI load, and the migrated shape is persisted in place
        at the same path the CLI would use."""
        with tempfile.TemporaryDirectory() as tmpdir:
            monkeypatch.setenv("DATA_DIR", tmpdir)
            get_config.cache_clear()
            try:
                state_file = Path(tmpdir) / "ima_sync_state.json"
                state_file.write_text(
                    json.dumps({"BV9": {"knowledge_base_id": "kb_legacy",
                                         "kb_added": True, "note_id": "n_legacy"}}),
                    encoding="utf-8",
                )
                loaded = app._load_ima_sync_state()
                assert "BV9::kb_legacy" in loaded

                # Re-read the on-disk file (same path the CLI would use)
                on_disk = json.loads(state_file.read_text(encoding="utf-8"))
                assert "BV9::kb_legacy" in on_disk
                assert "BV9" not in on_disk
            finally:
                get_config.cache_clear()


# ---------------------------------------------------------------------------
# 4. CLI's _cmd_sync_ima uses the shared helper (not a hardcoded path)
# ---------------------------------------------------------------------------

class TestCliSyncUsesConfigPath:
    """Pinning that the CLI no longer references the literal
    ``data/ima_sync_state.json`` directly — it must go through
    ``get_ima_state_path`` so DATA_DIR works for CLI runs too."""

    def test_cli_module_does_not_hardcode_state_path(self):
        """Scanning cli.py source should not reveal the old literal path."""
        cli_src = (ROOT / "cli.py").read_text(encoding="utf-8")
        # The old literal was a relative 'data/ima_sync_state.json' string
        # in a Path() call. After the refactor, the only references should
        # be via the imported get_ima_state_path helper.
        assert 'Path("data/ima_sync_state.json")' not in cli_src, (
            "cli.py still hardcodes the state file path; "
            "use src.sync.ima_state.get_ima_state_path instead"
        )
        assert "get_ima_state_path" in cli_src

    def test_app_module_does_not_hardcode_state_path(self):
        """Same check for app.py: no literal Path() to the old location."""
        app_src = (ROOT / "app.py").read_text(encoding="utf-8")
        # The old module-level constant was _IMA_SYNC_STATE_FILE = Path(...)
        # After the refactor it should be Optional[Path] = None with
        # resolution delegated to get_ima_state_path.
        assert 'Path("data/ima_sync_state.json")' not in app_src, (
            "app.py still hardcodes the state file path; "
            "delegate to src.sync.ima_state.get_ima_state_path"
        )
        assert "get_ima_state_path" in app_src
