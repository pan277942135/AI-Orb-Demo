# AI Orb V4.2 P0-3 Preflight Audit

## Scope and freeze

This audit follows the supplemental P0-3 correction for existing PR #1 on `codex/ai-orb-v32-final-closure-optical-fusion-v3`. Audited remote HEAD before these changes: `e33cbc9c28d6a203e6b81c249eff6bf853f4cd8d`. The frozen REF_A / REF_B images and contract thresholds are unchanged. The 3D WebP volume was not regenerated and visual fitting was not rerun.

## Gate status before API36 CI evidence

| Gate | Status | Evidence and remaining check |
|---|---|---|
| PF01 ROI freeze | PASS | 13 contract regions, 12 masks, alpha>=8 core silhouette, overlap-preserving independent semantic masks, per-mask source overlays, area/bounds, reference and mask SHA-256 validated by `validate_semantic_roi_masks.py`. |
| PF02 raymarch source | PASS (static) | Master and Kotlin HIGH value are 48; software renderer cap is `min(configured, 20)`. Production `OrbFluidVolumeRenderer.draw()` reads linked-program `uRaymarchSteps` using `GlShaderProgram.getIntUniform()` / `GLES30.glGetUniformiv()`. API36 runtime value is pending. |
| PF03 geometry source | PASS (static) | HIGH uses subdivision 4; source forwards the same value at init and quality changes. `GlMesh` reports measured counts and a vertex-buffer SHA-256. API36 measured geometry is pending. |
| PF04 API36 AVD | PENDING | Workflow creates and records an API36 x86_64 AVD, verifies the exact-head base APK after installation, waits for the production WebP volume, and captures all nine frozen modes from the production renderer. This runner requests SwiftShader, so its output is software-render evidence only. |
| PF05 repeatability | PENDING | Instrumentation performs three separate app cold starts, each exporting nine modes and runtime state. Analyzer checks exact deterministic runtime fields and reports screenshot and metric variance without adding a new tolerance. |
| Real hardware GPU | PENDING | No attached device or hardware GPU is available in this workspace. A successful software AVD run cannot satisfy this gate. |
| Visual fidelity acceptance | PENDING | Same-condition measurements and visual review follow only after the P0-3 baseline is complete; no optimization round has started. |

## Implementation evidence

- `generate_semantic_roi_masks.py` emits `ROI_MASK_FREEZE_MANIFEST.json` and individual original-image overlays. The validator records actual semantic intersections and does not clip masks to force exclusivity.
- `OrbFluidVolumeRenderer.draw()` reports actual uniform readback, shader identity, backend, texture state, and Icosphere metadata.
- `OrbGpuReferenceCapture` emits the nine contract modes first, with per-frame mode configuration, real production uniform readback, FBO/GL status, PNG hashes, shared camera/motion snapshot hashes, and mesh state.
- `P0_3Api36EvidenceTest` waits for `WEBP3D_OK` before requesting capture. It copies each run to an independently named baseline directory.
- `analyze_avd_baseline.py` verifies the installed artifact SHA, all nine captures per run, API 36, actual uniform and mesh evidence, the 20-second video, and repeated-run consistency. It produces PF02/PF03/PF05 JSON reports and both evidence ZIPs. Visual loss results are reported separately and do not convert software rendering into hardware validation.

## Current entry decision

`preflightReadyForRound1 = false` until PF04 and PF05 have passed on the exact pushed HEAD. Once PF01–PF05 are all marked PASS by the API36 job, the software AVD baseline may allow Round 1 to begin; hardware GPU verification and real-device visual acceptance remain separate statuses.
