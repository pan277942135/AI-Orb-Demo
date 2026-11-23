# AI Orb Demo — P0-3 Machine-Readable Acceptance Contract V1.0

**Status:** FROZEN
**Execution readiness:** PREFLIGHT_REQUIRED
**Baseline HEAD:** `e33cbc9c28d6a203e6b81c249eff6bf853f4cd8d`

## Contents

- `AI_ORB_P0_3_ACCEPTANCE_CONTRACT_V1.json` — authoritative machine-readable contract.
- `AI_ORB_P0_3_ACCEPTANCE_CONTRACT_V1.schema.json` — JSON Schema for structural validation.
- `AI_ORB_P0_3_ROI_ANNOTATION_TEMPLATE.json` — semantic region-mask worklist.
- `references/REF_A_CORE_CRYSTAL_RGBA.png` — exact uploaded Reference A.
- `references/REF_B_FULL_SCENE_RGB.png` — exact uploaded Reference B.
- `roi_masks/REF_A_CORE_SILHOUETTE_ALPHA_GE_008.png` — deterministic silhouette mask derived from Reference A alpha >= 8.

## Reference identity

- REF_A SHA-256: `2773e4628ff6132605314693742488542c3e70fd04b581d8825dd13cea0067ee`
- REF_B SHA-256: `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122`

The original PNG files are copied byte-for-byte. Do not modify or recompress them.

## P0-3 freeze meaning

The visual references, technical baseline, region taxonomy, capture rules, motion invariants, acceptance thresholds and three-round progress policy are frozen.

The contract is **not yet execution-ready**. CodeX must complete every `preflight_blocker` before Round 1:
1. Generate and hash semantic ROI masks.
2. Reconcile `raymarchStepsHigh=36` in `MASTER_FIDELITY_V32.json` with `48` in `AIOrbConfig.QualityTier.HIGH`.
3. Record the actual production Icosphere subdivision value.
4. Run the V4.2 baseline on an API 36 AVD and capture all required outputs.
5. Measure baseline repeatability noise using three identical captures.

## Important renderer distinction

SwiftShader output is valid for deterministic software-rendered comparisons only. It must never be reported as hardware-GPU verification. Record the actual GL vendor, renderer and version for every run.

## Iteration rule

Maximum three optimization rounds. Every round must show a target defect improvement beyond measured noise, without more than 2% regression in protected metrics or any new P0 defect. A no-progress round counts toward the three-round limit.

## No silent threshold changes

Thresholds and reference masks are immutable during optimization. Any proposed change requires a new contract version and explicit product-owner approval.
