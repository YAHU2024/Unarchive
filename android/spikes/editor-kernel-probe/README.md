# Editor kernel model probe

This standalone JVM probe characterizes Compose Rich Editor 1.2.0 without adding
it to the Android app. It checks synthetic Markdown round trips, model-level
cross-paragraph replacement, undo/redo, and large-document processing costs.

Run from `android/` with the existing wrapper:

```powershell
.\gradlew.bat -p spikes/editor-kernel-probe run --console=plain
```

Results are written to `build/reports/kernel-probe/report.md` and `report.json`
inside this standalone project. Only fixture names, booleans, counts and timing
are reported. The fixtures contain no user notes or credentials.
Fixture `sourceCharacters` counts UTF-16 code units; the large-document report
also includes Unicode code points and UTF-8 bytes explicitly.

The probe deliberately reports raw Markdown preservation separately from model
editing. A successful process means the evidence was collected; it does not mean
the candidate passed every requirement. Model-level operations and desktop JVM
timing do not establish Android IME, selection handles, rendering, frame rate,
memory use, or physical-device acceptance. No production data is read or written.

Upstream: <https://github.com/MohamedRejeb/Compose-Rich-Editor>, tag `v1.2.0`,
commit `34e06d6bb8906789522f4eae1ec8bca1b324f1c1`, Apache-2.0.

The bounded source-range bridge can be verified separately:

```powershell
.\gradlew.bat -p spikes/editor-kernel-probe sourceAdapterProbe additionalAdapterProbe --console=plain
```

It consumes actual candidate document changes and keeps original Markdown as the
writeback authority. Unsupported nodes are protected source-mode placeholders;
this is a fidelity prototype, not a complete production editor. The Android
probe compiles the same adapter file and includes its processing in measurements.

Incremental source mapping and bounded inverse-operation history have separate
gates, including full-parser oracles, explicit source-panel context fallbacks,
Unicode/pre-edit selection checks, and long-document history stress:

```powershell
.\gradlew.bat -p spikes/editor-kernel-probe sourceAdapterProbe additionalAdapterProbe incrementalAdapterProbe boundedHistoryProbe --console=plain
```

The native projection API avoids materializing a whole candidate state. Dirty
block parsing is local for supported ordinary text edits, while source strings,
diffs and suffix-coordinate updates still have linear work. History limits
include undo and redo together; reported accounting bytes are not measured heap.
Reports are written to `build/reports/e2/`. These gates do not prove device frame
budgets or completeness of native rich-format rendering.
