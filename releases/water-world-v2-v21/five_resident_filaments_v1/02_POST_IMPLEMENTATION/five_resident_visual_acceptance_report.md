# Five Resident Filaments V1 — Visual Acceptance & Evidence Level Report

**Contract ID:** `WATER_WORLD_FIVE_RESIDENT_FILAMENTS_DESIGN_FREEZE_V1`  
**Overall Acceptance Claim:** **NOT YET `FIVE_RESIDENT_FILAMENTS_V1_ACCEPTED`** (Pending real Android GLES 3.0 GPU verification on user hardware `HUAWEI CMS-AL20 / Maleoon 935`)

---

## 1. Six-Tier Evidence Status Matrix (Contract Section 9 — Strictly Separated)

| Tier | Evidence Status Key | Result | Proof / Notes |
|---|---|---|---|
| **1** | `STATIC_GEOMETRY_LOCKED` | **PASS** | All 13 frozen Resident mesh JSONs (`8 Back + 5 Front`) verified byte-for-byte & SHA-256 unchanged against Work 2 baseline; Master PNG SHA-256 `fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589` intact. |
| **2** | `MOTION_DESIGN_V1_FROZEN` | **PASS** | All 5 pre-implementation frozen assets generated under `01_PRE_IMPLEMENTATION/` (`five_resident_geometry_lock.json`, `five_resident_segment_mapping.json`, `five_resident_motion_profile_v1.json`, `five_resident_motion_acceptance_matrix.md`, `five_resident_source_audit.md`), explicitly marked `V1_DESIGN_TARGET_NOT_GROUND_TRUTH_MEASURED`. |
| **3** | `MOTION_RUNTIME_IMPLEMENTED` | **PASS** | V1 profile, evaluator, canonical 13-segment mapping, per-vertex half-width mesh builder, `RESIDENT_RIBBON_VERTEX` + `RESIDENT_RIBBON_FRAGMENT`, and Resident renderer pass implemented; 14 WaterWorld test suites / 73 tests / 0 failures (`WaterWorldFiveResidentFilamentsV1Test` 6/6 PASS, `WaterWorldShaderUniformContractTest` 5/5 PASS), `compile_applet` PASS, Debug APK built cleanly. |
| **4** | `SOFTWARE_MOTION_VERIFIED` | **PASS** | `WaterWorldFiveResidentV1SoftwarePrecheck` rendered all 9 canonical timestamps (`T = 0, 1, 2, 4, 8, 12, 20, 40, 60 s`) at `1254 × 1254` + 45 per-Resident ROIs + contact sheet + metrics JSON (`FIVE_RESIDENT_DYNAMIC_CONTACT_SHEET.png` visually inspected). |
| **5** | `GLES_SHADER_VERIFIED` | **NOT_RUN** | Cloud build container lacks an Android EGL / GLES 3.0 GPU driver (`libEGL.so` / `libGLESv2.so`). Static GLSL ES 3.00 uniform & syntax contract checks passed (0 dead uniforms across all 12 programs), but actual GPU driver compilation/link occurs on target Android hardware. |
| **6** | `HARDWARE_GPU_VISUAL_VERIFIED` | **NOT_RUN** | Real Android hardware acceptance must be performed by installing `AI-Orb-Demo-WaterWorld-V2.1-FiveResidentV1-debug.apk` on target Android GLES 3.0 device (`HUAWEI CMS-AL20 / Maleoon 935`). |

---

## 2. Software Motion Precheck Quantitative Results (`T = 0..60 s`)

### 2.1 `T = 0 s` Static Parity vs Work 2 Reference (Hard Gate: `MAE <= 0.6 / 255`)

| Region | `T = 0 s` MAE (`/ 255`) | Target | Result |
|---|---:|---:|---|
| Full Scene (`1254 × 1254`) | `0.2578` | `<= 0.6000` | **PASS** |
| `G01` ROI | `0.290` | `<= 0.600` | **PASS** |
| `G02` ROI | `0.340` | `<= 0.600` | **PASS** |
| `A01` ROI | `0.346` | `<= 0.600` | **PASS** |
| `A02` ROI | `0.275` | `<= 0.600` | **PASS** |
| `C01` ROI | `0.264` | `<= 0.600` | **PASS** |

### 2.2 Sustained Dynamic Motion Progression (`ROI MAE vs T = 0 s`)

| Timestamp | Full Scene MAE | `G01` (`+1, 16s`) | `G02` (`-1, 18s`) | `A01` (`+1, 20s`) | `A02` (`-1, 14s`) | `C01` (`+1, 22s`) |
|---|---:|---:|---:|---:|---:|---:|
| `T = 1 s` | `4.9393` | `5.362` | `5.522` | `3.943` | `5.197` | `6.257` |
| `T = 2 s` | `4.7616` | `5.090` | `5.066` | `3.698` | `5.116` | `6.302` |
| `T = 4 s` | `5.1541` | `5.586` | `5.788` | `4.249` | `5.210` | `6.267` |
| `T = 8 s` | `5.5228` | `5.967` | `6.018` | `4.149` | `5.705` | `7.031` |
| `T = 12 s` | `5.2656` | `5.679` | `6.005` | `3.904` | `5.342` | `7.302` |
| `T = 20 s` | `5.0924` | `5.508` | `5.658` | `3.820` | `5.145` | `6.160` |
| `T = 40 s` | `5.0421` | `5.512` | `5.402` | `3.906` | `5.553` | `6.866` |
| `T = 60 s` | `5.1934` | `5.645` | `5.937` | `3.556` | `4.530` | `5.965` |

Visual inspection of `03_DYNAMIC_EVIDENCE/FIVE_RESIDENT_DYNAMIC_CONTACT_SHEET.png` confirmed:
- Clear moving luminous highlight bands with soft leading edges and smooth fading tails along every Resident's frozen path.
- Smooth relative width breathing across both thin hairlines (`14 px`) and broad arches (`85 px`) with zero distortion.
- Seamless highlight progression across `A01`'s `0.0 px` back-to-front limb seam (`s = 0.2863`) and clean occlusion behind/in-front of the crystal sphere.
- Zero drift, runaway bloom, clipping, or neon border artifacts across `T = 0..60 s`.

---

## 3. Delivered Artifacts & Hardware Acceptance Checklist

- **Debug APK:** `AI-Orb-Demo-WaterWorld-V2.1-FiveResidentV1-debug.apk`  
  - Size: `26,997,691 bytes`  
  - SHA-256: `8911590ec47ca40ba1fd9e23c49326e2ea7b51b1221073de72168526483b1cc6`
- **Dynamic Evidence ZIP:** `five_resident_dynamic_evidence.zip` (`70 files`)  
  - Size: `100,263,061 bytes`  
  - SHA-256: `1c11603566c7a9b314f3eb7c7d50da576bc22095b723af6ca83bae9b93a43baa`

**On-Device Hardware Verification Steps (Required before claiming `FIVE_RESIDENT_FILAMENTS_V1_ACCEPTED`):**
1. Install `AI-Orb-Demo-WaterWorld-V2.1-FiveResidentV1-debug.apk` on target Android GLES 3.0 device (`HUAWEI CMS-AL20`).
2. Open Tab 3 (`Water World`) and confirm `FINAL_SCENE` compiles/links with `0` GL errors (verifying `GLES_SHADER_VERIFIED`).
3. Observe all 5 Resident Filaments (`G01/G02/A01/A02/C01`) for 60 seconds: confirm independent opposing flow directions (`+1/-1/+1/-1/+1`), readable moving highlight bands, subtle lateral waves, relative width/brightness breathing, and intact Core 3D / fish / ecology.
4. Test Pause, Resume, Reset (`T = 0` matches Work 2 static frame), and speed changes (`0.5x / 1.0x / 1.5x`).
