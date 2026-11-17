---
name: ui-reference-acceptance
description: "Compare Unarchive UI with design drafts or user reference images, including static details and interaction states, and produce a screenshot-backed difference report per UI slice. Use for UI development/review/acceptance in this repository; do not redesign a confirmed reference or treat automated tests as visual approval."
---

# UI reference acceptance

Apply the user's selected visual direction. Verify fidelity and observable interaction; do not introduce a new design system or require a particular design tool.

## Before changing UI

- Find current task/references through the internal index/handoff. Record exact revision/frame/image and latest user-confirmed overrides. Preserve temporary user images in the ignored design directory when needed. Do not publish private images/decisions in public documentation.
- Extract a short contract into the brief: arrangement, colors, type, icon geometry, touch targets, safe areas, states and gestures. Distinguish confirmed behavior, implementation parameters and unresolved choices. Ask only when ambiguity materially changes the product; otherwise state an assumption and proceed.
- Compare matching content/state. Record logical viewport, density, font scale, theme and keyboard state. A cropped reference proves local shape/proportions, not unseen position or absolute dp. Do not directly compare CSS physical pixels with Android pixels or suppress system font scaling to match images.

## During each UI slice

1. Mount the actual production page/flow; isolated previews are supplemental. Capture synthetic-content screenshots and recordings/observed key states for scrolling and transitions.
2. Check reference → actual → difference → disposition in [visual-diff.md](assets/visual-diff.md). Inspect layout/clearance, icon paths/optical size, colors/states, type/wrapping, metadata, borders/shadows, keyboard/safe-area geometry and touch bounds. Same icon family/component name is not proof of the same drawing.
3. Exercise relevant states: reading/editing, expanded/collapsed, pressed/disabled/selected, loading/error/recovery, finger-down/release/settled, gesture cancel/commit. Do not infer motion from static pictures, bitmap display from decode success, or TalkBack speech/order from semantics alone.
4. Fix deviations within confirmed scope before continuing. Separate approved differences from unexplained ones. Reopen defects shown in screenshots even when automation passed. Do not rewrite a baseline to hide a difference; baseline changes need an explicit product decision.

New references replace only the intended part of an older design. Preserve unchanged requirements. A side control should not reserve a full-width bottom row or leave hidden hit targets, when relevant; this is no permission to alter unrelated pages.

## Acceptance evidence

- Deliver a compact side-by-side/annotated artifact and difference table per slice, using existing tools. Overlays/pixel diffs locate differences but do not replace review of fonts, interaction or accessibility. Do not add a framework merely for one comparison.
- Cover light/dark, large text and reduced motion by impact. A tiny icon edit needs focused review, not every screen. Missing device/reference means not run, with a concrete reason; emulator images are not physical-device evidence.
- Report functional tests, agent visual comparison and user's visual/writing/TalkBack acceptance independently. Comparison can finish while human acceptance is pending. Silence or tool success is not human approval.
- Store reports/screenshots under the ignored task directory, update its current-status link and contribute comparison status to the test summary. Final report states material differences and limitations; no exact-fidelity claim without a matching comparison.
