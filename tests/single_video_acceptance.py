"""
Real IMA API single-video two-round acceptance test.

Phase 1: Load a real knowledge card, create note + add to KB, save sync state.
Phase 2: Re-run with same card — check_document_exists hits, state says "skip",
         verifying dedup + state-based decision is correct.

Usage:
  $env:PYTHONIOENCODING="utf-8"
  python tests/single_video_acceptance.py [--video-id BV1PS42197aM]

If no --video-id given, defaults to the first available card in data/knowledge_base/.
"""

import argparse
import asyncio
import json
import sys
import time
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from config import get_config
from src.sync.ima import ImaSync
from src.sync.ima_state import (
    get_ima_state_path,
    kb_state_decision,
    load_ima_sync_state,
    save_ima_sync_state,
    state_key,
)

_ok = 0
_fail = 0


def log(prefix, msg):
    global _ok, _fail
    if prefix == "PASS":
        _ok += 1
    elif prefix == "FAIL":
        _fail += 1
    print(f"[{prefix}] {msg}", flush=True)


def info(msg):
    print(f"      {msg}", flush=True)


def _pick_video(config):
    """Pick a real knowledge card to test."""
    kb_dir = Path(config.knowledge_base_dir)
    if not kb_dir.exists():
        return None

    cards = sorted(kb_dir.glob("*.json"))
    for c in cards:
        data = json.loads(c.read_text(encoding="utf-8"))
        vid = data.get("video_id", "")
        title = data.get("title", "")
        if vid and title:
            return vid, title, c.name
    return None


async def _round_1(ima, card, video_id, title, state_file, kb_id, kb_folder_id):
    """Create note + add to KB. Save state."""
    print("", flush=True)
    print("--- Round 1: create_document + add_to_knowledge_base ---", flush=True)

    # Step 1: check not already exists
    existing = await ima.check_document_exists(video_id)
    if existing:
        log("WARN", f"Note already exists ({existing}) — state may be stale. Will skip create_document.")
        note_id = existing
    else:
        try:
            note_id = await ima.create_document(
                title=f"[{video_id}] {title}",
                content=card,
            )
            if note_id:
                log("PASS", f"Note created: {note_id}")
            else:
                log("FAIL", "create_document returned empty note_id")
                return None, False
        except Exception as e:
            log("FAIL", f"create_document raised: {e}")
            return None, False

    # Save state (kb_added=False for now)
    state = load_ima_sync_state(state_file)
    key = state_key(video_id, kb_id)
    state[key] = {"note_id": note_id, "kb_added": False, "timestamp": _now_iso()}
    save_ima_sync_state(state_file, state)
    info(f"State saved: {key} -> note_id={note_id}, kb_added=False")

    # Step 2: add to KB
    kb_added = False
    if kb_id:
        try:
            await ima.add_to_knowledge_base(note_id, f"[{video_id}] {title}", kb_folder_id)
            log("PASS", "add_to_knowledge_base succeeded")
            kb_added = True
            # Update state
            state = load_ima_sync_state(state_file)
            state[key] = {"note_id": note_id, "kb_added": True, "timestamp": _now_iso()}
            save_ima_sync_state(state_file, state)
        except Exception as e:
            log("FAIL", f"add_to_knowledge_base raised: {e}")
    else:
        info("No KB configured — skipping KB association")

    return note_id, kb_added


async def _round_2(ima, video_id, title, state_file, kb_id, kb_folder_id):
    """Check document exists, evaluate state decision."""
    print("", flush=True)
    print("--- Round 2: check_document_exists + state decision ---", flush=True)

    # Step 1: check_document_exists
    try:
        found = await ima.check_document_exists(video_id)
        if found:
            log("PASS", f"check_document_exists: found {found}")
        else:
            log("FAIL", "check_document_exists returned None (should have found)")
            return False
    except Exception as e:
        log("FAIL", f"check_document_exists raised: {e}")
        return False

    # Step 2: evaluate state decision
    state = load_ima_sync_state(state_file)
    key = state_key(video_id, kb_id)
    decision = kb_state_decision(state, video_id, kb_id, kb_folder_id)
    info(f"State key: {key}")
    info(f"State data: {state.get(key, {})}")
    info(f"Decision: {decision}")

    if decision == "skip":
        log("PASS", "Correctly decided to skip (note exists + kb_added)")
        return True
    elif decision in ("attempt_kb", "retry_kb"):
        log("WARN", f"Decision is '{decision}' — attempting KB recovery")
        note_data = state.get(key, {})
        nid = note_data.get("note_id", found)
        try:
            await ima.add_to_knowledge_base(nid, f"[{video_id}] {title}", kb_folder_id)
            log("PASS", "KB recovery succeeded")
            state[key] = {"note_id": nid, "kb_added": True, "timestamp": _now_iso()}
            save_ima_sync_state(state_file, state)
            return True
        except Exception as e:
            log("FAIL", f"KB recovery failed: {e}")
            return False
    else:
        log("FAIL", f"Unexpected decision: {decision}")
        return False


async def main():
    global _ok, _fail

    parser = argparse.ArgumentParser(description="Single-video IMA two-round acceptance")
    parser.add_argument("--video-id", default="", help="Video ID to test (default: first available card)")
    args = parser.parse_args()

    config = get_config()
    client_id = config.ima_client_id
    api_key = config.ima_api_key
    kb_id = config.ima_knowledge_base_id or ""
    kb_folder_id = config.ima_knowledge_base_folder_id or ""

    if not client_id or not api_key:
        log("FAIL", "IMA_CLIENT_ID / IMA_API_KEY not set in .env")
        return 1

    # Pick video
    if args.video_id:
        video_id = args.video_id
        card_path = Path(config.knowledge_base_dir) / f"{video_id}.json"
        if not card_path.exists():
            log("FAIL", f"Card not found: {card_path}")
            return 1
        card = json.loads(card_path.read_text(encoding="utf-8"))
        title = card.get("title", video_id)
    else:
        picked = _pick_video(config)
        if not picked:
            log("FAIL", "No knowledge cards found in data/knowledge_base/")
            return 1
        video_id, title, filename = picked
        card_path = Path(config.knowledge_base_dir) / filename
        card = json.loads(card_path.read_text(encoding="utf-8"))
        info(f"Using card: {filename}")

    state_file = get_ima_state_path(config)

    print(f"=== Single-Video IMA Two-Round Acceptance ({_now_iso()}) ===", flush=True)
    print(f"Video ID:  {video_id}", flush=True)
    print(f"Title:     {title}", flush=True)
    print(f"KB ID:     {kb_id or '(none)'}", flush=True)
    print(f"Folder ID: {kb_folder_id or '(none)'}", flush=True)
    print(f"State:     {state_file}", flush=True)

    # Round 1
    ima = ImaSync(client_id=client_id, api_key=api_key, knowledge_base_id=kb_id)
    try:
        connected = await ima.connect()
        if not connected:
            log("FAIL", "IMA connection failed")
            return 1
        log("PASS", "IMA connected")
    except Exception as e:
        log("FAIL", f"IMA connection error: {e}")
        return 1

    note_id, kb_added = await _round_1(ima, card, video_id, title, state_file, kb_id, kb_folder_id)
    await ima.close()

    if note_id is None:
        log("FAIL", "Round 1 failed — aborting")
        return 1

    # Round 2 (fresh ImaSync instance)
    ima2 = ImaSync(client_id=client_id, api_key=api_key, knowledge_base_id=kb_id)
    try:
        connected = await ima2.connect()
        if not connected:
            log("FAIL", "Round 2: IMA connection failed")
            return 1
    except Exception as e:
        log("FAIL", f"Round 2: IMA connection error: {e}")
        return 1

    round2_ok = await _round_2(ima2, video_id, title, state_file, kb_id, kb_folder_id)
    await ima2.close()

    # Summary
    print("", flush=True)
    print("=== Summary ===", flush=True)
    print(f"  Video ID:    {video_id}", flush=True)
    print(f"  Note ID:     {note_id}", flush=True)
    print(f"  KB added:    {kb_added}", flush=True)
    print(f"  Round 2 ok:  {round2_ok}", flush=True)
    print(f"  Passes:      {_ok}", flush=True)
    print(f"  Failures:    {_fail}", flush=True)
    print(f"  End:         {_now_iso()}", flush=True)

    if _fail == 0 and round2_ok:
        print("\n[PASS] Single-video two-round acceptance verified against live IMA API.", flush=True)
        return 0
    else:
        print(f"\n[WARN] {_fail} failure(s) — see above.", flush=True)
        return 1


def _now_iso():
    return datetime.now().isoformat()


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
