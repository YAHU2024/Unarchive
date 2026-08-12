"""
Shared helpers for the per-(video, knowledge_base) ima sync state.

Both the Gradio GUI (app.py) and the CLI (cli.py) use this module so they
agree on:

  * Where the state file lives — derived from `AppConfig.data_dir`, not a
    hardcoded literal, so a custom `DATA_DIR` (or per-workspace `.env`)
    keeps the state file colocated with the knowledge cards and the rest
    of the app data. Before this module existed, GUI and CLI both wrote to
    a hardcoded `data/ima_sync_state.json` path, so a user who pointed
    `DATA_DIR` somewhere else would end up with their cards and their
    sync state in different directories — the next sync would then
    re-process every video as if it had never been synced.
  * How to encode the `(video, kb)` composite state key.
  * How to decide whether a re-sync is a no-op, a retry, or a cross-KB
    attempt.
  * How to load and atomically persist the on-disk JSON (with one-time
    legacy `vid`-only key migration in `load_ima_sync_state`).

This module has zero Gradio / async dependencies so it can be imported
from any context (CLI, GUI handler, test).
"""
from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)

# Separator used to encode (vid, kb_id) composite keys. The same separator
# must be used on both sides of the migration, so it lives here.
STATE_KEY_SEP = "::"

# Default state file name under the config-derived data dir. Kept as a
# constant so a test or downstream caller can override without hardcoding.
DEFAULT_STATE_FILENAME = "ima_sync_state.json"


def get_ima_state_path(config) -> Path:
    """Return the on-disk path for the ima sync state file.

    Always derived from ``config.data_dir`` so that custom ``DATA_DIR``
    (or multi-workspace ``.env``) keeps the state colocated with the
    knowledge cards. The parent directory is created if missing so a
    caller can `open(path, "w")` without first checking.
    """
    data_dir = Path(getattr(config, "data_dir", "./data") or "./data")
    path = data_dir / DEFAULT_STATE_FILENAME
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def state_key(video_id: str, knowledge_base_id: Optional[str]) -> str:
    """Build the ``(vid, kb_id)`` composite state key.

    An empty KB targets the "no KB" bucket for note-only syncs.
    """
    return f"{video_id}{STATE_KEY_SEP}{knowledge_base_id or ''}"


def parse_state_key(key: str) -> tuple[str, str]:
    """Inverse of :func:`state_key` for legacy migration.

    Tolerates old ``vid``-only keys by returning ``(vid, '')`` so the
    migration in :func:`load_ima_sync_state` can promote them safely.
    """
    if STATE_KEY_SEP in key:
        vid, _, kb = key.partition(STATE_KEY_SEP)
        return vid, kb
    return key, ""


def kb_state_decision(
    state: dict,
    vid: str,
    knowledge_base_id: str,
    resolved_folder_id: str,
) -> str:
    """Decide what to do when an ima note already exists for ``vid``.

    Returns one of:

    ``"skip"``
        Note present and KB-association for ``(vid, kb_id)`` matches the
        current target config; idempotent hit.
    ``"retry_kb"``
        Prior KB-association for ``(vid, kb_id)`` failed
        (``kb_added=False``); retry ``add_to_knowledge_base``.
    ``"retry_folder"``
        KB link exists for ``(vid, kb_id)`` but the requested folder
        differs from the one recorded in state; re-attempt so the new
        folder wins.
    ``"attempt_kb"``
        No state entry for ``(vid, kb_id)``. Either legacy (no prior
        state at all) or cross-KB (a different ``kb_id`` has an entry
        but this one does not); in both cases we should attempt
        ``add_to_knowledge_base`` for this KB instead of silently
        skipping.
    """
    if not knowledge_base_id:
        # No KB configured -> nothing to associate; always skip.
        return "skip"

    key = state_key(vid, knowledge_base_id)
    st = state.get(key)

    if not st:
        return "attempt_kb"

    prev_kb_added = bool(st.get("kb_added"))
    prev_folder = st.get("kb_folder_id", "") or ""

    if prev_kb_added and prev_folder == (resolved_folder_id or ""):
        return "skip"
    if prev_kb_added:
        return "retry_folder"
    return "retry_kb"


def load_ima_sync_state(state_file: Path) -> dict:
    """Load per-``(video, kb)`` ima sync state from disk.

    Returns ``{}`` if the file is missing/corrupt. Performs a one-time
    migration of legacy ``vid``-only keys (which lacked KB isolation) by
    promoting each old entry to ``f"{vid}::{kb_id_or_empty}"`` using the
    ``knowledge_base_id`` embedded in the legacy entry value. The
    migrated shape is persisted back to disk so the next load is a no-op.
    """
    raw: dict = {}
    try:
        if Path(state_file).exists():
            raw = json.loads(Path(state_file).read_text(encoding="utf-8"))
    except Exception as e:
        logger.warning("Failed to load ima sync state: %s", e)
        return {}

    migrated: dict = {}
    needs_resave = False
    for key, value in raw.items():
        if not isinstance(value, dict):
            # drop malformed entries silently
            needs_resave = True
            continue
        if STATE_KEY_SEP in key:
            migrated[key] = value
            continue
        # Legacy key - promote using embedded knowledge_base_id
        legacy_kb = value.get("knowledge_base_id", "") or ""
        new_key = state_key(key, legacy_kb)
        # Preserve folder id if already recorded under old schema
        if "kb_folder_id" in value and "kb_folder_id" not in migrated.get(new_key, {}):
            migrated.setdefault(new_key, value)
        else:
            migrated[new_key] = value
        needs_resave = True
        logger.info(
            "ima sync state: migrated legacy key %r -> %r", key, new_key
        )

    if needs_resave:
        try:
            save_ima_sync_state(state_file, migrated)
        except Exception as e:
            logger.warning("Failed to persist migrated ima sync state: %s", e)
    return migrated


def save_ima_sync_state(state_file: Path, state: dict) -> None:
    """Persist per-video ima sync state to disk (atomic write).

    Writes to ``<state_file>.tmp`` first and then ``os.replace``s it onto
    the target so a crash mid-write cannot leave a half-written JSON
    file (which would make the next load fall back to ``{}`` and force
    every video to re-sync).
    """
    state_file = Path(state_file)
    state_file.parent.mkdir(parents=True, exist_ok=True)
    tmp = state_file.with_suffix(state_file.suffix + ".tmp")
    tmp.write_text(
        json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    tmp.replace(state_file)
