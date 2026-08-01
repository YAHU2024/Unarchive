"""
Real IMA API diagnostic: verify the two-round "first-time failure, retry recovery" loop.

This script talks to the live IMA OpenAPI (credentials from .env) and MUST NOT be
replaced by mock tests. It is the authoritative acceptance gate for the kb-association
recovery flow.

Test flow (fresh run):
  Phase 1: create_document (import_doc) with NO knowledge_base_id
           -> note created, no KB association (simulating kb_added failure)
  Phase 2: create a NEW ImaSync WITH knowledge_base_id
           check_document_exists finds the note
           retry add_to_knowledge_base (recovery step)
           verify success

Usage:
  $env:PYTHONIOENCODING="utf-8"
  python tests/diag_ima_recovery.py
"""

import asyncio
import sys
import time
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from config import get_config
from src.sync.ima import ImaSync, CreateDocumentResult

_TS = int(time.time())
TEST_VIDEO_ID = f"IMA_RECOVERY_{_TS}"
TEST_TITLE = f"[{TEST_VIDEO_ID}] ima recovery test"
TEST_CONTENT = {
    "title": "ima recovery diagnostic",
    "author": "diag_script",
    "one_line_summary": "Live API: Phase 1 creates note without KB, Phase 2 retries add_to_knowledge_base.",
    "key_points": [
        {"point": "Phase 1: import_doc succeeds, no KB (simulated failure)", "detail": ""},
        {"point": "Phase 2: check_document_exists + add_to_knowledge_base (recovery)", "detail": ""},
    ],
    "transcript": "",
}

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


async def main():
    config = get_config()
    client_id = config.ima_client_id
    api_key = config.ima_api_key
    kb_id = config.ima_knowledge_base_id or ""

    if not client_id or not api_key:
        log("FAIL", "IMA_CLIENT_ID / IMA_API_KEY not set in .env")
        return 1

    print(f"=== IMA Two-Round Recovery Diagnostic ({_now_iso()}) ===", flush=True)
    print(f"KB ID:     {kb_id or '(none)'}", flush=True)
    print(f"Test ID:   {TEST_VIDEO_ID}", flush=True)
    print("", flush=True)

    # ================================================================
    # [1] Connect (no KB id) and verify credentials
    # ================================================================
    ima_no_kb = ImaSync(client_id=client_id, api_key=api_key, knowledge_base_id="")
    try:
        connected = await ima_no_kb.connect()
        if connected:
            log("PASS", "IMA connected (note-only mode)")
        else:
            log("FAIL", "IMA connection failed")
            await ima_no_kb.close()
            return 1
    except Exception as e:
        log("FAIL", f"IMA connection error: {e}")
        await ima_no_kb.close()
        return 1

    # ================================================================
    # Phase 1: create_document WITHOUT knowledge_base_id
    #          -> KB association is NOT attempted (simulated failure)
    # ================================================================
    print("", flush=True)
    print("--- Phase 1: create_document (no KB) ---", flush=True)
    info("Simulating kb_added failure by omitting knowledge_base_id")

    note_id = None
    try:
        result = await ima_no_kb.create_document(
            title=TEST_TITLE, content=TEST_CONTENT
        )
        note_id = result.note_id
        if note_id:
            log("PASS", f"Note created: {note_id} (no KB)")
        else:
            log("FAIL", "create_document returned empty note_id")
            await ima_no_kb.close()
            return 1
    except Exception as e:
        log("FAIL", f"create_document raised: {e}")
        await ima_no_kb.close()
        return 1

    await ima_no_kb.close()

    if not kb_id:
        log("PASS", "No KB configured -- note-only mode, recovery N/A")
        _print_summary(note_id, True, "")
        return 0 if _fail == 0 else 1

    # ================================================================
    # Phase 2: Recovery WITH knowledge_base_id
    #          New ImaSync instance (fresh client, kb_id set)
    #          check_document_exists -> add_to_knowledge_base
    # ================================================================
    print("", flush=True)
    print("--- Phase 2: Recovery (fresh ImaSync with KB) ---", flush=True)
    info("This simulates the second sync run that retries KB association")

    ima_kb = ImaSync(client_id=client_id, api_key=api_key, knowledge_base_id=kb_id)
    try:
        rec_connected = await ima_kb.connect()
        if not rec_connected:
            log("FAIL", "Recovery IMA connection failed")
            await ima_kb.close()
            return 1
    except Exception as e:
        log("FAIL", f"Recovery IMA connection error: {e}")
        await ima_kb.close()
        return 1

    # Step A: check_document_exists
    try:
        found = await ima_kb.check_document_exists(TEST_VIDEO_ID)
        if found == note_id:
            log("PASS", f"check_document_exists: found {found}")
        else:
            log("FAIL", f"check_document_exists: expected {note_id}, got {found!r}")
            await ima_kb.close()
            return 1
    except Exception as e:
        log("FAIL", f"check_document_exists raised: {e}")
        await ima_kb.close()
        return 1

    # Step B: add_to_knowledge_base (the recovery step)
    try:
        await ima_kb.add_to_knowledge_base(note_id, TEST_TITLE, "")
        log("PASS", "add_to_knowledge_base succeeded (recovery)!")
        kb_added = True
    except Exception as e:
        log("FAIL", f"add_to_knowledge_base raised: {e}")
        await ima_kb.close()
        return 1

    await ima_kb.close()

    _print_summary(note_id, kb_added, "")

    if _fail == 0:
        print("\n[PASS] Two-round recovery flow verified against live IMA API.", flush=True)
        return 0
    else:
        print(f"\n[WARN] {_fail} failure(s) -- see above.", flush=True)
        return 1


def _print_summary(note_id, kb_added, kb_error):
    print("", flush=True)
    print("=== Summary ===", flush=True)
    print(f"  Note ID:     {note_id}", flush=True)
    print(f"  KB added:    {kb_added}", flush=True)
    if kb_error:
        print(f"  KB error:    {kb_error}", flush=True)
    print(f"  Passes:      {_ok}", flush=True)
    print(f"  Failures:    {_fail}", flush=True)
    print(f"  End:         {_now_iso()}", flush=True)


def _now_iso():
    return datetime.now().isoformat()


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
