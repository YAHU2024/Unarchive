"""
Tests for ima sync state isolation across knowledge bases.

Bug being guarded: the on-disk state was previously keyed by `video_id`
alone. Two consecutive syncs of the same video into two different KBs
would see stale `kb_added=True` from the first KB on the second run and
silently skip — leaving the second KB without the note.

These tests pin the (vid, kb) composite-key behavior of the decision
helper and the load-time legacy migration.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

# Make the project root importable regardless of where pytest is invoked.
ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import app  # noqa: E402
import cli as cli_module  # noqa: E402


# ---------------------------------------------------------------------------
# 1. Composite state-key derivation
# ---------------------------------------------------------------------------

class TestStateKey:
    """_state_key encodes the (vid, kb) pair so cross-KB entries don't collide."""

    def test_state_key_with_kb(self):
        assert app._state_key("BV123", "kb_aaa") == "BV123::kb_aaa"

    def test_state_key_no_kb(self):
        """Empty KB produces a `vid::` key (no-KB bucket)."""
        assert app._state_key("BV123", "") == "BV123::"

    def test_state_key_none_kb_treated_as_empty(self):
        assert app._state_key("BV123", None) == "BV123::"

    def test_parse_state_key_roundtrip(self):
        vid, kb = app._parse_state_key("BV123::kb_aaa")
        assert vid == "BV123"
        assert kb == "kb_aaa"

    def test_parse_state_key_legacy_returns_empty_kb(self):
        """Legacy `vid`-only keys parse as (vid, '') so migration is safe."""
        vid, kb = app._parse_state_key("BV123")
        assert vid == "BV123"
        assert kb == ""


# ---------------------------------------------------------------------------
# 2. KB-isolation decision helper — the actual bug fix in pure logic
# ---------------------------------------------------------------------------

class TestKbStateDecision:
    """Cover the four cases of _kb_state_decision."""

    def test_skip_when_no_kb_configured(self):
        """With knowledge_base_id empty, we never try to add → skip."""
        decision = app._kb_state_decision(
            state={}, vid="BV1", knowledge_base_id="", resolved_folder_id="folder_1"
        )
        assert decision == "skip"

    def test_attempt_kb_when_no_state_entry(self):
        """No (vid, kb) state → attempt (handles cross-KB + legacy)."""
        state = {"BV1::kb_other": {"kb_added": True, "kb_folder_id": "folder_x"}}
        decision = app._kb_state_decision(
            state=state, vid="BV1", knowledge_base_id="kb_new",
            resolved_folder_id="folder_a",
        )
        assert decision == "attempt_kb"

    def test_skip_when_same_kb_and_folder_match(self):
        """Idempotent hit when state shows this (vid, kb) was added AND folder matches."""
        state = {"BV1::kb_a": {"kb_added": True, "kb_folder_id": "folder_a"}}
        decision = app._kb_state_decision(
            state=state, vid="BV1", knowledge_base_id="kb_a",
            resolved_folder_id="folder_a",
        )
        assert decision == "skip"

    def test_skip_when_kb_added_and_both_root(self):
        """Both target folder and recorded folder empty → match → skip."""
        state = {"BV1::kb_a": {"kb_added": True, "kb_folder_id": ""}}
        decision = app._kb_state_decision(
            state=state, vid="BV1", knowledge_base_id="kb_a",
            resolved_folder_id="",
        )
        assert decision == "skip"

    def test_retry_folder_when_folder_differs(self):
        """KB link exists but new run asks for a different folder → re-attempt."""
        state = {"BV1::kb_a": {"kb_added": True, "kb_folder_id": "folder_a"}}
        decision = app._kb_state_decision(
            state=state, vid="BV1", knowledge_base_id="kb_a",
            resolved_folder_id="folder_b",
        )
        assert decision == "retry_folder"

    def test_retry_kb_when_prev_added_false(self):
        """Prior round failed (kb_added=False) → retry."""
        state = {"BV1::kb_a": {"kb_added": False, "kb_folder_id": "folder_a"}}
        decision = app._kb_state_decision(
            state=state, vid="BV1", knowledge_base_id="kb_a",
            resolved_folder_id="folder_a",
        )
        assert decision == "retry_kb"

    def test_cross_kb_isolation_does_not_skip(self):
        """The user's reported bug: KB-A then KB-B must NOT see KB-A's kb_added=True."""
        # State only knows about KB-A for this vid.
        state = {"BV1::kb_A": {"kb_added": True, "kb_folder_id": "folder_A"}}
        # Now we ask about KB-B.
        decision = app._kb_state_decision(
            state=state, vid="BV1", knowledge_base_id="kb_B",
            resolved_folder_id="folder_B",
        )
        assert decision == "attempt_kb", (
            "Cross-KB sync must not be blocked by stale kb_added from another KB"
        )


# ---------------------------------------------------------------------------
# 3. Load-time migration of legacy vid-only state
# ---------------------------------------------------------------------------

class TestLegacyMigration:
    """Old state files keyed by `vid` get promoted to `vid::kb_id`."""

    def test_legacy_vid_only_with_embedded_kb_gets_promoted(self, tmp_path, monkeypatch):
        legacy = {
            "BV123": {
                "note_id": "note_x",
                "knowledge_base_id": "kb_old",
                "kb_added": True,
                "kb_error": "",
                "synced_at": "2025-01-01T00:00:00",
            }
        }
        state_file = tmp_path / "ima_sync_state.json"
        state_file.write_text(json.dumps(legacy), encoding="utf-8")
        monkeypatch.setattr(app, "_IMA_SYNC_STATE_FILE", state_file)

        loaded = app._load_ima_sync_state()

        assert "BV123::kb_old" in loaded
        # Old key must NOT remain (would otherwise conflict with the new one)
        assert "BV123" not in loaded
        # Embedded fields survive migration
        assert loaded["BV123::kb_old"]["note_id"] == "note_x"
        assert loaded["BV123::kb_old"]["kb_added"] is True

        # Migration should also persist the new shape in place.
        on_disk = json.loads(state_file.read_text(encoding="utf-8"))
        assert "BV123::kb_old" in on_disk

    def test_legacy_vid_only_without_kb_goes_to_root_bucket(self, tmp_path, monkeypatch):
        legacy = {"BV123": {"note_id": "note_x", "kb_added": True}}
        state_file = tmp_path / "ima_sync_state.json"
        state_file.write_text(json.dumps(legacy), encoding="utf-8")
        monkeypatch.setattr(app, "_IMA_SYNC_STATE_FILE", state_file)

        loaded = app._load_ima_sync_state()
        assert "BV123::" in loaded
        assert "BV123" not in loaded

    def test_mixed_keys_get_each_promoted_independently(self, tmp_path, monkeypatch):
        """A file with both new-shape `vid::kb` keys and old `vid` keys
        should leave the new ones alone and promote the old ones."""
        legacy = {
            "BV1::kb_A": {"note_id": "n1", "kb_added": True, "kb_folder_id": ""},
            "BV2": {"note_id": "n2", "knowledge_base_id": "kb_B", "kb_added": True},
        }
        state_file = tmp_path / "ima_sync_state.json"
        state_file.write_text(json.dumps(legacy), encoding="utf-8")
        monkeypatch.setattr(app, "_IMA_SYNC_STATE_FILE", state_file)

        loaded = app._load_ima_sync_state()
        assert "BV1::kb_A" in loaded
        assert "BV2::kb_B" in loaded
        assert "BV2" not in loaded

    def test_corrupt_state_returns_empty_without_raising(self, tmp_path, monkeypatch):
        state_file = tmp_path / "ima_sync_state.json"
        state_file.write_text("{ this is not json", encoding="utf-8")
        monkeypatch.setattr(app, "_IMA_SYNC_STATE_FILE", state_file)

        loaded = app._load_ima_sync_state()
        assert loaded == {}

    def test_missing_state_file_returns_empty(self, tmp_path, monkeypatch):
        state_file = tmp_path / "ima_sync_state.json"
        assert not state_file.exists()
        monkeypatch.setattr(app, "_IMA_SYNC_STATE_FILE", state_file)

        loaded = app._load_ima_sync_state()
        assert loaded == {}


# ---------------------------------------------------------------------------
# 4. CLI loader shares the same shape (single source of on-disk truth)
# ---------------------------------------------------------------------------

class TestCliStateLoader:
    """cli.py must produce the same migrated shape as app.py."""

    def test_cli_loader_promotes_legacy_keys(self, tmp_path):
        legacy = {"BV9": {"knowledge_base_id": "kb_cli", "kb_added": True}}
        state_file = tmp_path / "state.json"
        state_file.write_text(json.dumps(legacy), encoding="utf-8")

        loaded = cli_module._load_ima_state_for_cli(state_file)
        assert "BV9::kb_cli" in loaded
        on_disk = json.loads(state_file.read_text(encoding="utf-8"))
        assert "BV9::kb_cli" in on_disk

    def test_cli_loader_keeps_new_keys_untouched(self, tmp_path):
        state_file = tmp_path / "state.json"
        state_file.write_text(json.dumps({"BV9::kb_x": {"kb_added": True}}), encoding="utf-8")
        loaded = cli_module._load_ima_state_for_cli(state_file)
        assert "BV9::kb_x" in loaded


# ---------------------------------------------------------------------------
# 5. End-to-end: cross-KB round trip against the decision helper
# ---------------------------------------------------------------------------

class TestCrossKbRoundTrip:
    """Two consecutive syncs of the same video into two different KBs."""
# class is marked as skip if marked as expected failure
    pass

    @pytest.mark.parametrize(
        "round1_kb,round2_kb,round1_folder,round2_folder",
        [
            ("kb_A", "kb_B", "folder_A", "folder_B"),
            ("kb_A", "kb_B", "", ""),  # both root
        ],
    )
    def test_round2_decision_never_skips_after_round1_success(
        self, round1_kb, round2_kb, round1_folder, round2_folder
    ):
        """The user's reported bug scenario: KB-A succeeded → next sync
        to KB-B must not see KB-A's kb_added=True as a skip signal."""
        state: dict = {}

        # Round 1: sync to KB-A — success recorded
        decision1 = app._kb_state_decision(
            state, "BV_X", round1_kb, round1_folder
        )
        assert decision1 == "attempt_kb"
        state[app._state_key("BV_X", round1_kb)] = {
            "note_id": "note_x",
            "knowledge_base_id": round1_kb,
            "kb_added": True,
            "kb_folder_id": round1_folder,
        }

        # Round 2: sync to KB-B — must re-attempt, NOT skip
        decision2 = app._kb_state_decision(
            state, "BV_X", round2_kb, round2_folder
        )
        assert decision2 == "attempt_kb", (
            f"Round 2 to {round2_kb} must attempt association; got {decision2}"
        )

        # After round 2 records success, both KBs should be queryable independently
        state[app._state_key("BV_X", round2_kb)] = {
            "note_id": "note_x",
            "knowledge_base_id": round2_kb,
            "kb_added": True,
            "kb_folder_id": round2_folder,
        }
        decision_a = app._kb_state_decision(state, "BV_X", round1_kb, round1_folder)
        decision_b = app._kb_state_decision(state, "BV_X", round2_kb, round2_folder)
        assert decision_a == "skip"
        assert decision_b == "skip"
