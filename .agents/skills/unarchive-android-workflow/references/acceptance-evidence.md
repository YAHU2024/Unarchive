# Acceptance evidence and project invariants

Read applicable parts; this is not a requirement to run every gate.

## Note-editor invariants

- Markdown source is authoritative. Preserve unchanged ranges and existing migrations; no third-party editor-tree export over the whole document.
- Keep the owned session and continuous Native Editable for ordinary read/edit/input. A navigation remount may create a host; validate retained session/selection, not an old detached View.
- Use local transactions and one bounded history; composition, source scratch, assets and AI candidates retain their specified recovery semantics.
- Formal save checks revision/source identity. Quiet draft protection differs from formal save/delivery; external actions use the chosen formal snapshot. Empty formal content is valid, not a fallback request.
- Source, selection and pixels are separate assertions. Distinguish input accepted, observed draw and physical presentation.

Current product choices come from the internal index/brief; do not make a transient device finding a global requirement.

## Evidence boundaries

| Evidence | Can establish | Cannot establish alone |
|---|---|---|
| JVM/oracle | Mapping/history/persistence/concurrency | Native IME or actual screen |
| Production-route AndroidTest | Wired actions/source/selection/tested geometry | All workflows or visual approval |
| Actual IME/touch | Observed device candidate/hold/drag sequence | Other devices or human writing |
| Real process restart | Persistence across process death | Pending Picker identity unless tested |
| Decode/software draw | Asset and one drawing path | Hardware screen actually displays it |
| OS chooser | Intent/export bytes/return state | Receiver rendering or delivery |
| True service | Authorized request/response/state | Unread remote content or quality |
| Release metrics | Timing/frame/heap/GC in that protocol | User acceptance/subjective fluency |
| Reference comparison | Differences under recorded conditions | TalkBack speech or human approval |

Use passed/failed/incomplete/not_run/blocked/error with reasons. Skips retain counts and incomplete status. Build success does not imply tests ran. Do not add overlapping suites/runs or mix probes with production. Unknown source/APK provenance remains unknown.

## Reusable summary

`scripts/summarize_acceptance.py` uses Python's standard library. It does not build, install, test, call services, modify raw reports or assign human approval.

Copy `../assets/evidence-manifest.json` into a private task folder. Set task/requirement IDs and checks; remove irrelevant gates. Paths are relative to the manifest. Each check has unique `id`, supported `kind`, `required`, `path`, optional expected `runId` and optional `provenance` path.

Inputs: `junit` XML/directory, `build` Gradle log, `device` note redesign result JSON, `process` process recovery JSON, `release` release result JSON, `live` sanitized result JSON, `visual`/`manual` explicit structured outcomes. Missing input is not run; inconsistent/malformed input is error. Output is per-check, never a cumulative total or all-AC acceptance claim.

```powershell
# Repository root; output must be a new directory.
python .agents/skills/unarchive-android-workflow/scripts/summarize_acceptance.py `
  --manifest docs/internal/android/current-task/evidence-manifest.json `
  --output-dir docs/internal/android/current-task/generated-summary
```

For a new run, optionally capture context **before running**, after source is stable:

```powershell
python .agents/skills/unarchive-android-workflow/scripts/summarize_acceptance.py `
  --capture-context docs/internal/android/current-task/run-context.json `
  --repo-root . `
  --app-apk android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk `
  --test-apk android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Context stores HEAD, a fingerprint of git-listed nonignored Android files and optional APK digests. This is **not proof those APKs were installed**; runner-side verification is separate. Check source stability after running; do not bind changed source to earlier results or retroactively capture history.

Visual/manual files contain status, evidence paths and a short nonprivate reason. Preserve the actual human decision in the original record. The helper validates structure, not authorship. Use the Markdown summary template for unsupported details. No raw private logs/errors/credentials in manifests.
