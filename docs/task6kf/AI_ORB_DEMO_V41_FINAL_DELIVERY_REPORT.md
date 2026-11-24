# AI Orb Demo V4.1 Final Delivery Report

**Task:** `AI_ORB_DEMO_V41_FINAL_HIGH_FIDELITY_CONVERGENCE_P0`
**Report date:** 2026-11-23
**Status:** Source changes and local software checks are prepared; final delivery is blocked on the original background PNG being available as a file. Hardware GPU capture and the 20-second physical-device review remain pending.

## 1. Baseline and delivery state

| Item | State |
|---|---|
| Remote Main baseline | `07ce54a6657edc57b2106d734b57ca3cec87545e` |
| V3.2 parent baseline | `b6bf39bd7ffe348ceb2b1adf77b2623d330d97d4` |
| Local work branch | `codex/ai-orb-v41-final-high-fidelity` |
| Local HEAD | `b6bf39bd7ffe348ceb2b1adf77b2623d330d97d4` with uncommitted V4.1 changes |
| PR #1 | Open and unmerged at last remote check |
| V4.1 commit / PR / CI run | Not created; changes are not committed and V4.1 CI has not run |
| Exact-HEAD V4.1 APK | Not built or delivered |
| V4.1 visual lock SHA256 | `cef6e7035b73bbebd615d1c3bc04933550d4bcf37ccf2f511e1f2f891a17e477` |

No Main or V3.2 branch changes were pushed. The existing V3.2 APK and checksum artifacts were left untouched.

## 2. Implementation completed locally

| Area | Changes |
|---|---|
| Visual lock | Added runtime and documentation copies of `TASK_6KF_VISUAL_LOCK_V41.json` and the Kotlin model/loader. It centralizes the frozen layout, four stream paths, perimeter parameters, keyframe timings, optics and capture settings. |
| Shared layout | Updated Photo frame, Orb viewport, Compose screen and `TaskSceneLayoutSnapshot` to use the frozen normalized geometry through one UI-to-GL mapping. |
| Task Energy | Kept four independent stream IDs and meshes, routed their values through the visual lock, and anchored the main stream endpoints to the frozen Orb/photo geometry. |
| Perimeter | Routed L1/L2/L3 width and alpha ranges through the visual lock and added independent Task/Perimeter diagnostic isolation. |
| Background renderer | Added asynchronous asset decode and GL-thread texture upload, texture sampling, context recreation handling, status telemetry and independent caustics. The source image itself is absent from the workspace, so the base texture cannot yet be packaged or visually accepted. |
| Evidence capture | Added snapshot locking, GL capture/readback checks, PNG validation and hashes, evidence ZIP packaging, and Android `PixelCopy` for paired full-window captures. Capture code is prepared; no real device evidence was generated. |
| CI | Added `.github/workflows/ci-v41.yml` with frozen V3.2 asset regression, Task shader compile/link, unit tests, Android test compilation, lint, APK asset checks and exact-HEAD fingerprint steps. Background SHA comparison is intentionally omitted per the user's instruction; the workflow still requires a readable PNG file and checks that it is PNG data. |
| Historical screenshots | Moved the old offline proxy images to `docs/task6kf/offline_cpu_proxy/` and labeled them as CPU proxy artifacts, not real GPU evidence. |

## 3. Local verification

| Check | Result |
|---|---|
| `git diff --check` | PASS |
| CI YAML parsing | PASS |
| Kotlin main and unit-test compilation | PASS |
| `:app:testDebugUnitTest` | PASS, 109 tests |
| `:app:compileDebugAndroidTestKotlin` | PASS |
| `:app:lintDebug` | PASS |
| GLES shader validation | PASS: 26 shader sources compiled and 14 programs linked under Mesa; this is software shader validation, not physical GPU acceptance |
| `validate_webp_assets.py` | PASS, 35/35 V3.2 assets |
| `validate_volume_continuity.py` | PASS, 32 slices |
| `fitting/validate_fit.py` | PASS, 17/17 gates; decoded WebP checks passed |
| `test_v32_closure_gates.py` | PASS, full 2,097,152-voxel lossless round-trip, lens ray checks, baseline positive case and 10/10 negative cases |
| V4.1 hardware GPU captures / 20-second run | PENDING_REAL_GPU_CAPTURE / REAL_DEVICE_QA_PENDING |

The Android device bridge (`adb`) is unavailable in this environment, so no physical device was connected for capture or the 20-second run.

The Mesa result must not be described as a hardware GPU pass. The six-frame visual acceptance record is in `V41_SIX_FRAME_VISUAL_ACCEPTANCE.md` and correctly remains pending.

## 4. Background input blocker

The image is visible in the chat, and the user instructed that it be used directly without SHA verification. The original PNG bytes have not appeared as a file in the Codex workspace or repository, so they cannot be copied into the Android assets directory. No replacement image was generated or substituted.

Required file path:

`app/src/main/assets/task_demo/background_lake_glass_v41.png`

Once the original PNG is attached as a file or placed at that path, the remaining source-asset packaging, APK build and exact-head validation can proceed. Per the user's instruction, no background SHA comparison is required.

## 5. Delivery states

| Gate | State |
|---|---|
| SOURCE_FIXED | Partial: V4.1 code and workflow changes are present locally; original background file is unavailable |
| UNIT_TEST_PASS | PASS: 109 JVM/Robolectric tests |
| TASK_SHADER_COMPILE_LINK_PASS | PASS under Mesa/software rendering |
| V3.2_REGRESSION_PASS | PASS for the listed Python asset, continuity, fit and closure gates |
| BACKGROUND_ASSET_PASS | PENDING: source PNG file missing |
| EXACT_HEAD_CI_PASS | PENDING: no V4.1 commit or workflow run |
| DEBUG_APK_BUILD | PENDING: no final APK was built without the required background asset |
| GPU_CAPTURE_PASS | PENDING_REAL_GPU_CAPTURE |
| REAL_DEVICE_VISUAL_ACCEPTED | REAL_DEVICE_QA_PENDING |
