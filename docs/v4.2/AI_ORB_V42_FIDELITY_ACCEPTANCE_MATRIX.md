# AI Orb V4.2 Fidelity Acceptance Matrix

## Status summary

| State | Result | Evidence / next step |
|---|---|---|
| `SOURCE_FIX_COMPLETE` | **IN PROGRESS → implementation present; Android compile/CI pending** | Root-cause changes and tests are in the V4.2 worktree. See root-cause report. |
| `CI_PASS` | **PENDING** | Existing PR #1 workflow has been updated for the V4.2 exact-head APK and checks. Run after push. |
| `GPU_RENDER_VERIFIED` | **NOT_RUN** | Mesa GLES compiler/linker is software validation only. No hardware GPU or Android device is attached. |
| `VISUAL_FIDELITY_ACCEPTED` | **NOT_RUN** | Requires same-snapshot GPU images and human review against the frozen master. |
| `USER_DEVICE_QA_PENDING` | **PENDING USER** | Install the exact-head APK on HUAWEI CMS-AL20 / Android 16 API36, export the V4.2 evidence ZIP, and record a 20-second run. |

## Input and source gates

| Gate | Status | Result |
|---|---|---|
| Main baseline fetched / PR checked | PASS | Main `07ce54a6657edc57b2106d734b57ca3cec87545e`; PR #1 remains open on the requested branch at audited base HEAD `b6bf39bd7ffe348ceb2b1adf77b2623d330d97d4`. No new PR or merge. |
| Frozen master identity | PASS | SHA-256 `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122`; 1254×1254 RGB. |
| Master Orb bounds | PASS | Center `(616.0,574.5)`, radius `326 px`, from frozen region data. |
| `285147.mp4` source | BLOCKED | Not available in this execution workspace or attachment set; user-reported F01–F07 remain unverified observations. |
| YuJian reference | PASS | PR #344 HEAD `c609c3eda8f61b1948541959c7a71d1cebd518df` inspected. No YuJian source or assets copied. |
| Production code path | PASS (source audit) | Tab 1 reaches `OrbFluidVolumeRenderer.draw()` and `CORE_GLASS_FRAG`; no active legacy inner-fluid renderer found. |
| Full-render fallback gate | PASS (source change) | Formal Tab 1 frames wait for four successful uploads and `glIsTexture` validation; evidence capture reads back the production GL uniforms and rejects mismatches/errors. Explicit diagnostics retain fallback. Device runtime still pending. |

## Offline, asset, and shader checks

| Gate | Threshold / expected | Status | Actual |
|---|---:|---|---:|
| WebP assets | 35/35 lossless RGBA | PASS | 35/35 |
| 3D volume | 256×256×32 | PASS | 2,097,152 voxels |
| Canonical→decoded pixel closure | Exact RGBA bytes | PASS | 32/32 slices; max quantization error `0.001880` |
| 360° volume continuity | Existing gate | PASS | Max adjacent-Z RGB MAE `0.0596`; alpha MAE `0.0465`; corner alpha `0` |
| Offline visual gates | 17/17 | PASS | E03 area error `2.8%`; E05 `6.7%`; E06 `12.3%`; E07 `4.8%` |
| Negative CI cases | 10/10 strict rejects | PASS | 10/10 |
| Crystal Lens CPU geometry | 90 rays, finite and stable | PASS | Max phi residual `1.65e-14`; max normal error `6.47e-11` |
| GLES 3.0 shader compile/link | All production programs | PASS (software Mesa) | 16 sources compiled; 9 programs linked; 12 core fitted uniforms active |
| Kotlin/Robolectric tests | All existing + V4.2 tests | NOT_RUN locally | Android SDK is absent; CI pending |
| AndroidTest compile | Pass | NOT_RUN locally | CI pending |
| Lint | Pass | NOT_RUN locally | CI pending |
| Debug APK + asset SHA | Pass | NOT_RUN locally | CI pending |
| Exact-head CI artifact | Exact V4.2 commit | PENDING | Existing PR workflow will emit APK, SHA, fingerprint, and reports |

## GPU and visual gates

The following values are **not** computed from the offline fit output. They need same-camera, same-snapshot production GPU frames. Until then they are `NOT_COMPARABLE`.

| GPU gate | Target | Status | Evidence |
|---|---:|---|---|
| Orb Silhouette IoU | ≥0.95 | NOT_COMPARABLE | No hardware GPU frame |
| Orb center error | ≤1% canvas width | NOT_COMPARABLE | No hardware GPU frame |
| E05/E06/E07 area error | each ≤15% | NOT_COMPARABLE | No hardware GPU frame |
| E05/E06/E07 centroid distance | each ≤0.04D | NOT_COMPARABLE | No hardware GPU frame |
| Upper dome grey/white haze | ≤1% | NOT_COMPARABLE | No hardware GPU frame |
| `L_cavity` | ≤0.15 | NOT_COMPARABLE | GPU loss not calculated |
| `L_volume` | ≤0.10 | NOT_COMPARABLE | GPU loss not calculated |
| `L_total` | ≤0.08 | NOT_COMPARABLE | GPU loss not calculated |
| Bloom OFF readability | Emerald fold, Sapphire valleys, glass rim, caustics visible | NOT_RUN | `BLOOM_OFF_GPU.png` is included in the device capture sequence |
| Lens integration | No independent eye-like outline | NOT_RUN | `CAVITY_ONLY_GPU.png`, `GLASS_ONLY_GPU.png`, and lens diagnostics pending |
| Resident occlusion | Core remains readable with five frozen residents | NOT_RUN | `RIBBONS_ONLY_GPU.png` and same-snapshot full frame pending |
| 20-second dynamic run | ≥30 FPS average, P95 ≤33.3ms, no >500ms freeze | NOT_RUN | Target device is unavailable |
| Real GPU telemetry | GLES vendor/renderer, IDs, uniforms, errors | NOT_RUN | Export implementation is ready; no device capture exists |

## Capture and package contents

The one-click Tab 1 capture now uses one frozen camera/model/motion snapshot and waits for each file to decode and hash before advancing. It emits:

- `REFERENCE_LOCKED_FRAME_GPU.png`
- `CAVITY_ONLY_GPU.png`
- `FLUID_VOLUME_ONLY_GPU.png`
- `RIBBONS_ONLY_GPU.png`
- `GLASS_ONLY_GPU.png`
- `FULL_RENDER_GPU.png`
- `CAUSTICS_ONLY_GPU.png`
- `BLOOM_OFF_GPU.png`
- `FOLD_NORMAL_GPU.png` (linear activation is sRGB-encoded in red; two channels show fold-normal orientation; measured ratio is recorded in telemetry)
- `VOLUME_DEPTH_GPU.png`
- `PRE_TONEMAP_GPU.png`
- `POST_TONEMAP_GPU.png`
- `RUNTIME_RENDER_CONFIG.json`, `GPU_TELEMETRY.json`, `BUILD_FINGERPRINT.json`, `SHA256SUMS.txt`
- `AI_ORB_V42_GPU_FIDELITY_EVIDENCE.zip`

If capture runs on SwiftShader, llvmpipe, or another software GLES backend, the telemetry and progress explicitly label it software diagnostics. It cannot satisfy the hardware GPU gate.

## Exact-head delivery

- Final source HEAD: **pending commit**.
- Existing PR: #1; no new PR requested or created.
- Workflow: `.github/workflows/ci-v32.yml` now names and packages V4.2 outputs. Exact CI run URL, APK download, size, APK SHA-256, and artifact ID will be filled from the completed exact-head run.
- This worktree has no Android SDK, `adb`, emulator, or hardware GPU device. No APK or GPU PNG is fabricated in place of those outputs.
