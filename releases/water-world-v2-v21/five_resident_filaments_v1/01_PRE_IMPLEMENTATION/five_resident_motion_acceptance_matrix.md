# Five Resident Filaments V1 — Motion Acceptance Matrix

**Contract ID:** `WATER_WORLD_FIVE_RESIDENT_FILAMENTS_DESIGN_FREEZE_V1`  
**Status:** `MOTION_DESIGN_V1_FROZEN`  
**Static Baseline HEAD:** `120aeb2c1f716771351bf951826539350bedb512`  
**Master Canvas:** `1254 × 1254 px` (`SHA-256: fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589`)

---

## 1. Frozen Per-Resident Motion Parameters (V1 Design Target — Not Ground-Truth Measured)

| Resident | Color | Back Segments (`02_RESIDENT_BACK`) | Front Segments (`10_RESIDENT_FRONT`) | Dir | Period | Speed (`s/s`) | Init Phase | Wave Freq (`3/T`) | Width Amp / Hz | Brightness Amp / Hz | Max Lateral Wave | `T=2s` Flow | `T=5s` Flow |
|---|---|---|---|---:|---:|---:|---:|---:|---|---|---:|---:|---:|
| **G01** | `#9FE226` | `G01_back_arch`, `G01_back_outer_veil` | `G01_front_lower_sweep` | `+1` | `16.0 s` | `+0.06250` | `0.00 rad` | `0.1875 Hz` | `±6.0%` / `0.19 Hz` | `±9.0%` / `0.19 Hz` | `±8.0 px` | `12.50%` | `31.25%` |
| **G02** | `#FFC247` | `G02_back_upper_arc`, `G02_back_upper_hairline`, `G02_back_right_rim` | `G02_front_right_embrace` | `-1` | `18.0 s` | `-0.05556` | `1.24 rad` | `0.1667 Hz` | `±5.0%` / `0.18 Hz` | `±8.0%` / `0.16 Hz` | `±7.0 px` | `11.11%` | `27.78%` |
| **A01** | `#37E7EF` | `A01_back_upper_left` | `A01_front_left_embrace` | `+1` | `20.0 s` | `+0.05000` | `2.41 rad` | `0.1500 Hz` | `±7.0%` / `0.21 Hz` | `±10.0%` / `0.22 Hz` | `±9.0 px` | `10.00%` | `25.00%` |
| **A02** | `#15D8ED` | `A02_back_lower_halo` | `A02_front_lower_strand` | `-1` | `14.0 s` | `-0.07143` | `3.85 rad` | `0.2143 Hz` | `±6.0%` / `0.25 Hz` | `±9.0%` / `0.21 Hz` | `±8.0 px` | `14.29%` | `35.71%` |
| **C01** | `#F6BB48` | `C01_back_lower_thread` | `C01_front_lower_right` | `+1` | `22.0 s` | `+0.04545` | `5.02 rad` | `0.1364 Hz` | `±4.5%` / `0.17 Hz` | `±7.0%` / `0.15 Hz` | `±6.5 px` | `9.09%` | `22.73%` |

---

## 2. Quantitative & Visual Acceptance Gates

| Gate ID | Category | Acceptance Requirement | Verification Level |
|---|---|---|---|
| **G1** | Static Geometry Lock | All 13 frozen Resident mesh JSON files retain exact Work 2 SHA-256, vertex/index counts, Pass IDs (`8 Back + 5 Front`), tints, core colors, and `0` added/removed segments. | `STATIC_GEOMETRY_LOCKED` |
| **G2** | `T = 0` Pixel Identity | At `T = 0` (`staticReferenceMode = true` or `t == 0`), lateral wave `= 0`, width delta `= 0`, brightness scale `= 1.0`, moving highlight inactive; software precheck `T = 0` ROI & full-scene MAE vs Work 2 static `<= 0.6 / 255`. | `SOFTWARE_MOTION_VERIFIED` |
| **G3** | Readable Internal Flow | Every Resident visibly advances internal energy highlight band along canonical `s ∈ [0, 1]`: `>= 9%` path traversal by `T = 2s` (`min = 9.09%` on C01) and `>= 22%` by `T = 5s` (`min = 22.73%` on C01). | `MOTION_RUNTIME_IMPLEMENTED` + `SOFTWARE_MOTION_VERIFIED` |
| **G4** | Moving Highlight Quality | At least one clear moving highlight band per Resident with soft leading edge and smooth fading tail, cyclic wrap over `s ∈ [0, 1]`, zero blinking/strobing, zero neon outline halo, original colors preserved. | `SOFTWARE_MOTION_VERIFIED` + Pending `HARDWARE_GPU_VISUAL_VERIFIED` |
| **G5** | Relative Width Breathing | Zero fixed `18 px` width displacement. Every vertex scales symmetrically along its local cross-normal proportional to its exact per-vertex frozen half-width `aLocalHalfWidthPx`, spatially phased along `vCanonicalS`. | `MOTION_RUNTIME_IMPLEMENTED` + `SOFTWARE_MOTION_VERIFIED` |
| **G6** | Bounded Lateral Wave | Local lateral sway along cross-normal strictly bounded to per-Resident `maxLateralWavePx` (`±6.5..9.0 px` on `1254 px`), endpoint-stabilized (`endAnchor`), continuous spatial wave `waveFreqHz = 3 / period` across `vCanonicalS`. | `MOTION_RUNTIME_IMPLEMENTED` + `SOFTWARE_MOTION_VERIFIED` |
| **G7** | Anti-Synchronization | 5 distinct traversal periods (`16/18/20/14/22s`), alternating directions (`+1/-1/+1/-1/+1`), 5 distinct initial phases (`0.00/1.24/2.41/3.85/5.02 rad`), distinct width & brightness frequencies; across `T = 0..60s` all 5 never peak simultaneously. | `MOTION_RUNTIME_IMPLEMENTED` + Unit Test |
| **G8** | Back / Front Continuity | All 13 segments mapped onto canonical `s ∈ [0, 1]`. `A01` crosses its `0.0 px` seam at `s = 0.2863` continuously; non-contiguous Work 2 back/front bridges explicitly documented as `GROUND_TRUTH_UNKNOWN` without fabricated geometry. | `STATIC_GEOMETRY_LOCKED` + `SOFTWARE_MOTION_VERIFIED` |
| **G9** | Long-Run & Playback Control | Continuous playback `T = 0..60s` has zero NaN/Inf, zero wrap discontinuity, deterministic `evaluate(t)`, working pause/resume/reset (`T = 0`), and `0.5x / 1.0x / 1.5x` speed scaling. | `MOTION_RUNTIME_IMPLEMENTED` + Unit Test |
| **G10** | Strict Scope & Shader Contract | Zero changes to Core 3D (`32 Z-slices`), 4 fish, plants, rocks, bubbles, water, glass, caustics, background, 11 Passes, or 5 Optical Nodes; 0 dead uniforms across all 12 GLSL ES 3.00 programs. | `MOTION_RUNTIME_IMPLEMENTED` + Shader Contract Test |
| **G11** | Real Hardware Acceptance | User installs updated Debug APK on target Android GLES 3.0 GPU (`HUAWEI CMS-AL20 / Maleoon 935`) and confirms visual quality in Tab 3. | `HARDWARE_GPU_VISUAL_VERIFIED` (Pending User Device) |
