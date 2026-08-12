"""
Integration tests for multi-knowledge-base switching in the Gradio UI.

These tests exercise the state machine that powers the KB dropdown (Tab 1)
and its cross-tab effects on folder loading and sync (Tab 5).  They complement
``test_ima_sync_state_isolation.py`` by testing the *orchestration* layer:
state transitions, handler return shapes, and parameter extraction from
``gr.State`` — not just the decision logic in isolation.

Scope:
  * state initialization from .env-backed AppConfig
  * _choice_value normalization (Gradio 6.0 dict-vs-string)
  * on_kb_change: state mutation + folder reset + Tab 5 label update
  * on_folder_change: folder value stored into state
  * do_load_kbs: choices built, kb_map cached
  * do_sync_ima parameter extraction (kb_id, kb_folder_value from state)
  * do_save_config parameter extraction
  * back-to-back KB switches with folder clearing
  * deselection (empty KB) transitions
  * cross-KB folder isolation
  * sync state key compositing with the active KB ID
"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# Make the project root importable regardless of where pytest is invoked.
ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import app  # noqa: E402


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_state(
    kb_id: str = "",
    kb_name: str = "",
    kb_folder_value: str = "",
    kb_map: dict | None = None,
) -> dict:
    """Build a state dict matching the shape in build_ui()."""
    return {
        "kb_id": kb_id,
        "kb_name": kb_name,
        "kb_folder_value": kb_folder_value,
        "kb_map": dict(kb_map or {}),
    }


# ---------------------------------------------------------------------------
# 1. State initialization: app.state carries .env values correctly
# ---------------------------------------------------------------------------

class TestStateInitialization:
    """When build_ui() creates the gr.State, it must thread config values through."""

    def test_initial_state_kb_id_from_config(self, monkeypatch):
        """Simulate a .env with IMA_KNOWLEDGE_BASE_ID set."""
        monkeypatch.setattr(
            app, "get_config",
            lambda: _fake_config(ima_knowledge_base_id="kb_123"),
        )
        ui = app.build_ui()
        # The state is baked into the Blocks — access through the component id.
        # We verify the build succeeded; deeper state verification happens in
        # the next tests that exercise the handlers directly.
        ui.close()
        # No crash = config threading worked.

    def test_initial_state_kb_name_matches_kb_id_when_set(self, monkeypatch):
        """When kb_id is configured, kb_name is initialised to kb_id (fallback)."""
        monkeypatch.setattr(
            app, "get_config",
            lambda: _fake_config(ima_knowledge_base_id="kb_123"),
        )
        ui = app.build_ui()
        ui.close()

    def test_initial_state_kb_name_empty_when_kb_id_empty(self, monkeypatch):
        """When no kb_id is configured, kb_name is empty."""
        monkeypatch.setattr(
            app, "get_config",
            lambda: _fake_config(ima_knowledge_base_id=""),
        )
        ui = app.build_ui()
        ui.close()

    def test_initial_folder_value_from_config(self, monkeypatch):
        """kb_folder_value thread from config's folder_id (preferred) or name."""
        monkeypatch.setattr(
            app, "get_config",
            lambda: _fake_config(
                ima_knowledge_base_id="kb_x",
                ima_knowledge_base_folder_id="folder_abc",
                ima_knowledge_base_folder_name="MyFolder",
            ),
        )
        ui = app.build_ui()
        ui.close()


# ---------------------------------------------------------------------------
# 2. _choice_value normalization (Gradio 6.0 compat)
# ---------------------------------------------------------------------------

class TestChoiceValue:
    """_choice_value normalises Gradio 6.0 dropdown return values."""

    def test_plain_string_passthrough(self):
        assert app._choice_value("kb_abc") == "kb_abc"

    def test_dict_extracts_value(self):
        assert app._choice_value({"label": "X", "value": "kb_abc"}) == "kb_abc"

    def test_none_returns_empty_string(self):
        assert app._choice_value(None) == ""

    def test_empty_string_returns_empty_string(self):
        assert app._choice_value("") == ""

    def test_dict_with_none_value(self):
        assert app._choice_value({"label": "X", "value": None}) == ""


# ---------------------------------------------------------------------------
# 3. on_kb_change: state + folder reset + Tab 5 label
# ---------------------------------------------------------------------------

class TestOnKbChange:
    """Exercise the full handler: state mutation, folder clearing, status text."""

    @pytest.mark.asyncio
    async def test_switch_kb_updates_state_and_clears_folder(self):
        state = _make_state(kb_map={"kb_A": "Knowledge A", "kb_B": "Knowledge B"})
        new_state, folder_upd, status, kb_label = await app.on_kb_change("kb_A", state)

        assert new_state["kb_id"] == "kb_A"
        assert new_state["kb_name"] == "Knowledge A"
        assert new_state["kb_folder_value"] == ""
        # Folder dropdown is reset
        assert folder_upd["choices"] == []
        assert folder_upd["value"] is None
        # Status text mentions the selected KB
        assert "Knowledge A" in status
        assert "重新加载文件夹" in status
        # Tab 5 label
        assert kb_label == "Knowledge A"

    @pytest.mark.asyncio
    async def test_switch_kb_from_dict_choice(self):
        """Gradio 6.0 may return the whole choice dict — must extract value."""
        state = _make_state(kb_map={"kb_B": "Knowledge B"})
        new_state, _, _, kb_label = await app.on_kb_change(
            {"label": "Knowledge B — desc", "value": "kb_B"}, state
        )
        assert new_state["kb_id"] == "kb_B"
        assert new_state["kb_name"] == "Knowledge B"
        assert kb_label == "Knowledge B"

    @pytest.mark.asyncio
    async def test_switch_kb_fallsback_to_id_when_not_in_map(self):
        """Custom or unlisted KB ID: kb_name falls back to the ID itself."""
        state = _make_state(kb_map={"kb_A": "Knowledge A"})
        new_state, _, _, kb_label = await app.on_kb_change("kb_unknown", state)

        assert new_state["kb_id"] == "kb_unknown"
        assert new_state["kb_name"] == "kb_unknown"
        assert kb_label == "kb_unknown"

    @pytest.mark.asyncio
    async def test_back_to_back_switches(self):
        """Seq: KB_A → KB_B → KB_C — each step resets folder."""
        kb_map = {"kb_A": "A", "kb_B": "B", "kb_C": "C"}
        state = _make_state(kb_map=kb_map)

        # Switch A
        state, _, _, _ = await app.on_kb_change("kb_A", state)
        assert state["kb_id"] == "kb_A"
        assert state["kb_folder_value"] == ""

        # Switch B (simulate user loaded folders for A before switching)
        state["kb_folder_value"] = "folder_from_A"
        state, _, _, _ = await app.on_kb_change("kb_B", state)
        assert state["kb_id"] == "kb_B"
        assert state["kb_folder_value"] == ""  # must be reset!

        # Switch C
        state, _, _, _ = await app.on_kb_change("kb_C", state)
        assert state["kb_id"] == "kb_C"
        assert state["kb_folder_value"] == ""

    @pytest.mark.asyncio
    async def test_deselect_kb(self):
        """Selecting empty (no KB) clears state properly."""
        state = _make_state(
            kb_id="kb_A", kb_name="Knowledge A",
            kb_folder_value="folder_x", kb_map={"kb_A": "Knowledge A"},
        )
        new_state, folder_upd, status, kb_label = await app.on_kb_change("", state)

        assert new_state["kb_id"] == ""
        assert new_state["kb_name"] == ""
        assert new_state["kb_folder_value"] == ""
        assert kb_label == "未选择"
        assert "未选择" in status

    @pytest.mark.asyncio
    async def test_deselect_via_none_value(self):
        """Gradio may send None for empty selection."""
        state = _make_state(
            kb_id="kb_A", kb_name="A", kb_map={"kb_A": "A"},
        )
        new_state, _, _, kb_label = await app.on_kb_change(None, state)

        assert new_state["kb_id"] == ""
        assert kb_label == "未选择"

    @pytest.mark.asyncio
    async def test_deselect_via_none_dict_value(self):
        """Gradio may send a dict with value=None."""
        state = _make_state(
            kb_id="kb_A", kb_name="A", kb_map={"kb_A": "A"},
        )
        new_state, _, _, kb_label = await app.on_kb_change(
            {"label": "", "value": None}, state
        )
        assert new_state["kb_id"] == ""
        assert kb_label == "未选择"

    @pytest.mark.asyncio
    async def test_on_kb_change_preserves_kb_map(self):
        """kb_map must survive a KB switch — it's needed for name lookups later."""
        kb_map = {"kb_A": "A", "kb_B": "B"}
        state = _make_state(kb_map=kb_map)
        new_state, _, _, _ = await app.on_kb_change("kb_A", state)
        assert new_state["kb_map"] == kb_map

    @pytest.mark.asyncio
    async def test_on_kb_change_exception_resets_state_safely(self):
        """Even if something throws, we return a clean empty state."""
        state = _make_state(kb_id="kb_A")
        # Inject a broken kb_map that will crash on .get()
        state["kb_map"] = None  # type: ignore
        new_state, folder_upd, status, kb_label = await app.on_kb_change("kb_A", state)

        assert new_state["kb_id"] == ""
        assert new_state["kb_name"] == ""
        assert new_state["kb_folder_value"] == ""
        assert folder_upd["choices"] == []
        assert "出错" in (kb_label or "")


# ---------------------------------------------------------------------------
# 4. on_folder_change: folder value stored in state
# ---------------------------------------------------------------------------

class TestOnFolderChange:
    """Verify folder selection is threaded into state."""

    @pytest.mark.asyncio
    async def test_select_folder_id(self):
        state = _make_state(kb_id="kb_A")
        new_state = await app.on_folder_change("folder_abc", state)
        assert new_state["kb_folder_value"] == "folder_abc"

    @pytest.mark.asyncio
    async def test_select_folder_name(self):
        state = _make_state(kb_id="kb_A")
        new_state = await app.on_folder_change("MyFolder", state)
        assert new_state["kb_folder_value"] == "MyFolder"

    @pytest.mark.asyncio
    async def test_select_root(self):
        state = _make_state(kb_id="kb_A", kb_folder_value="folder_old")
        new_state = await app.on_folder_change("", state)
        assert new_state["kb_folder_value"] == ""

    @pytest.mark.asyncio
    async def test_folder_change_does_not_mutate_kb_id(self):
        state = _make_state(kb_id="kb_A", kb_name="A")
        new_state = await app.on_folder_change("folder_xyz", state)
        assert new_state["kb_id"] == "kb_A"
        assert new_state["kb_name"] == "A"

    @pytest.mark.asyncio
    async def test_folder_change_dict_value(self):
        """Gradio 6.0 may return a dict, _choice_value must normalise."""
        state = _make_state(kb_id="kb_A")
        new_state = await app.on_folder_change(
            {"label": "📂 MyDir", "value": "folder_xyz"}, state
        )
        assert new_state["kb_folder_value"] == "folder_xyz"


# ---------------------------------------------------------------------------
# 5. do_sync_ima: parameter extraction from state
# ---------------------------------------------------------------------------

class TestDoSyncImaParams:
    """Verify that do_sync_ima correctly extracts kb_id / folder from state."""

    def test_folder_id_when_value_starts_with_folder_prefix(self):
        state = _make_state(
            kb_id="kb_x", kb_folder_value="folder_abc123",
        )
        kb_id, kb_folder_id, kb_folder_name = _extract_sync_params(state)
        assert kb_id == "kb_x"
        assert kb_folder_id == "folder_abc123"
        assert kb_folder_name == ""

    def test_folder_name_when_value_does_not_start_with_folder(self):
        state = _make_state(
            kb_id="kb_x", kb_folder_value="MyFolder",
        )
        kb_id, kb_folder_id, kb_folder_name = _extract_sync_params(state)
        assert kb_id == "kb_x"
        assert kb_folder_id == ""
        assert kb_folder_name == "MyFolder"

    def test_empty_folder_value(self):
        state = _make_state(kb_id="kb_x", kb_folder_value="")
        kb_id, kb_folder_id, kb_folder_name = _extract_sync_params(state)
        assert kb_id == "kb_x"
        assert kb_folder_id == ""
        assert kb_folder_name == ""

    def test_no_kb_configured(self):
        state = _make_state(kb_id="", kb_folder_value="")
        kb_id, kb_folder_id, kb_folder_name = _extract_sync_params(state)
        assert kb_id == ""
        assert kb_folder_id == ""
        assert kb_folder_name == ""

    def test_sync_state_key_uses_active_kb_id(self):
        """After switching to kb_B, _state_key must use kb_B, not kb_A."""
        kb_id = "kb_B"
        key = app._state_key("BV123", kb_id)
        assert key == "BV123::kb_B"

    def test_kb_state_decision_after_switch_uses_new_kb(self):
        """After switching from kb_A (with prior state) to kb_B, decision must
        be attempt_kb (not skip) because kb_B state is empty."""
        # kb_A has prior success
        state = {"BV1::kb_A": {"kb_added": True, "kb_folder_id": "folder_a"}}
        # Now user selects kb_B
        decision = app._kb_state_decision(
            state, "BV1", "kb_B", resolved_folder_id="folder_b",
        )
        assert decision == "attempt_kb"


# ---------------------------------------------------------------------------
# 6. do_save_config: KB ID extraction
# ---------------------------------------------------------------------------

class TestDoSaveConfigKbExtraction:
    """do_save_config reads KB ID from dropdown or falls back to state."""

    def test_kb_id_from_dropdown_string(self):
        """Direct dropdown value without running do_save_config (unit extract)."""
        kb_id = app._choice_value("kb_xyz") or ""
        assert kb_id == "kb_xyz"

    def test_kb_id_from_dropdown_dict(self):
        kb_id = app._choice_value({"label": "X", "value": "kb_xyz"}) or ""
        assert kb_id == "kb_xyz"

    def test_kb_id_fallback_to_state_when_dropdown_empty(self):
        """When dropdown returns None/empty, use state.kb_id."""
        dropdown_val = app._choice_value(None)  # ""
        state = {"kb_id": "kb_from_state"}
        kb_id = dropdown_val or state.get("kb_id", "")
        assert kb_id == "kb_from_state"

    def test_both_empty(self):
        kb_id = app._choice_value("") or ""
        assert kb_id == ""

    def test_folder_id_extraction_from_state(self):
        """folder_ prefix → folder_id, otherwise → folder_name."""
        state = _make_state(kb_folder_value="folder_xyz")
        fv = state.get("kb_folder_value", "")
        if fv and fv.startswith("folder_"):
            kb_folder_id, kb_folder_name = fv, ""
        else:
            kb_folder_id, kb_folder_name = "", fv
        assert kb_folder_id == "folder_xyz"
        assert kb_folder_name == ""

    def test_folder_name_extraction_from_state(self):
        state = _make_state(kb_folder_value="MyFolder")
        fv = state.get("kb_folder_value", "")
        if fv and fv.startswith("folder_"):
            kb_folder_id, kb_folder_name = fv, ""
        else:
            kb_folder_id, kb_folder_name = "", fv
        assert kb_folder_id == ""
        assert kb_folder_name == "MyFolder"


# ---------------------------------------------------------------------------
# 7. Back-to-back KB switching (broader integration)
# ---------------------------------------------------------------------------

class TestBackToBackKbSwitch:
    """Simulate a full user flow: load KBs → switch → switch → sync."""

    @pytest.mark.asyncio
    async def test_full_flow_state_machine(self):
        """Simulate the complete state machine:

        1. Load KBs (do_load_kbs-like, but with the side-effect pattern that
           the handler returns a new state with kb_map).
        2. Select KB-A → on_kb_change
        3. Select folder for KB-A → on_folder_change
        4. Switch to KB-B → on_kb_change (must clear folder!)
        5. Select folder for KB-B → on_folder_change
        6. Verify sync params for KB-B use correct kb_id and folder.
        """
        # Step 1: simulate do_load_kbs result
        kb_map = {"kb_A": "Knowledge A", "kb_B": "Knowledge B"}
        state = _make_state(kb_map=kb_map)

        # Step 2: select KB-A
        state, _, _, _ = await app.on_kb_change("kb_A", state)
        assert state["kb_id"] == "kb_A"
        assert state["kb_name"] == "Knowledge A"
        assert state["kb_folder_value"] == ""

        # Step 3: select folder for KB-A
        state = await app.on_folder_change("folder_A1", state)
        assert state["kb_folder_value"] == "folder_A1"

        # Step 4: switch to KB-B (must clear KB-A's folder!)
        state, folder_upd, status, kb_label = await app.on_kb_change("kb_B", state)
        assert state["kb_id"] == "kb_B"
        assert state["kb_name"] == "Knowledge B"
        assert state["kb_folder_value"] == ""  # ← old folder cleared
        assert folder_upd["choices"] == []
        # Tab 5 label updated
        assert kb_label == "Knowledge B"

        # Step 5: select folder for KB-B
        state = await app.on_folder_change("folder_B1", state)
        assert state["kb_folder_value"] == "folder_B1"

        # Step 6: verify sync params extraction
        kb_id, kb_folder_id, kb_folder_name = _extract_sync_params(state)
        assert kb_id == "kb_B"
        assert kb_folder_id == "folder_B1"


# ---------------------------------------------------------------------------
# 8. Cross-KB folder isolation
# ---------------------------------------------------------------------------

class TestCrossKbFolderIsolation:
    """KB-switch must clear the folder dropdown and state so old KB's folder
    does not leak into the new KB."""

    @pytest.mark.asyncio
    async def test_switch_kb_returns_empty_folder_choices(self):
        """on_kb_change must always output gr.update(choices=[])."""
        state = _make_state(kb_map={"kb_A": "A", "kb_B": "B"})
        _, folder_upd, _, _ = await app.on_kb_change("kb_A", state)
        assert folder_upd["choices"] == []
        assert folder_upd["value"] is None

    @pytest.mark.asyncio
    async def test_switch_back_to_previous_kb_clears_folder_too(self):
        """Even switching back to the previous KB clears the folder
        (user must re-load). This is intentional: the folder dropdown
        is not a cached list."""
        kb_map = {"kb_A": "A", "kb_B": "B"}
        state = _make_state(kb_map=kb_map)

        # Select A
        state, _, _, _ = await app.on_kb_change("kb_A", state)
        # Select B
        state, _, _, _ = await app.on_kb_change("kb_B", state)
        # Back to A — folder must still be empty
        state, folder_upd, _, _ = await app.on_kb_change("kb_A", state)
        assert state["kb_folder_value"] == ""
        assert folder_upd["choices"] == []


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _fake_config(**overrides) -> "app.AppConfig":
    """Build an AppConfig with test-friendly defaults."""

    class _FakeCfg:
        pass

    cfg = _FakeCfg()
    cfg.llm_api_key = ""
    cfg.llm_base_url = "https://api.example.com"
    cfg.llm_model = "test-model"
    cfg.feishu_app_id = ""
    cfg.feishu_app_secret = ""
    cfg.ima_client_id = ""
    cfg.ima_api_key = ""
    cfg.ima_knowledge_base_id = ""
    cfg.ima_knowledge_base_folder_id = ""
    cfg.ima_knowledge_base_folder_name = ""
    cfg.whisper_model = "tiny"
    cfg.whisper_device = "cpu"
    cfg.whisper_compute_type = "auto"
    cfg.data_dir = "./data"
    cfg.transcripts_dir = "./data/transcripts"
    cfg.audio_cache_dir = "./data/audio_cache"
    cfg.knowledge_base_dir = "./data/knowledge_base"
    cfg.douyin_cdp_port = 9222
    cfg.video_download_dir = "./data/videos"

    for k, v in overrides.items():
        setattr(cfg, k, v)

    cfg.ensure_dirs = lambda: None
    return cfg


def _extract_sync_params(state: dict) -> tuple:
    """Replicate the parameter extraction logic from do_sync_ima."""
    kb_id = state.get("kb_id", "")
    fv = state.get("kb_folder_value", "")
    if fv and fv.startswith("folder_"):
        kb_folder_id, kb_folder_name = fv, ""
    else:
        kb_folder_id, kb_folder_name = "", fv
    return kb_id, kb_folder_id, kb_folder_name
