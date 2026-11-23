# AI-Orb-Demo V3.2 — Reference-to-3D Structural Parity Closeout Repair Report

**Task ID:** `AI_ORB_V3_2_REFERENCE_TO_3D_FINAL_FIDELITY_P0`  
**Sole Frozen Master:** `public/assets/aistudio/____________910d1531.png`  
**Master SHA-256:** `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122` (verified unmodified)  
**Visual Parity Verdict:** `SOURCE_READY_GPU_PENDING` (`BLOCKED_NEEDS_GPU_FRAME` / `PENDING_REAL_GPU_CAPTURE`)

---

## 1. Completed V3.2 Reference-to-3D Fixes (G0 -> G5)

| Gate | Key Artifacts | Structural Convergence | Verification Status |
|---|---|---|---|
| **G0 Master & Coordinate Lock** | `REFERENCE_FITTING_TARGETS.json`, `REFERENCE_VISUAL_REGIONS.json`, `AI_ORB_V3_2_MASTER_LOCK.md` | Resolved `(620.4, 605.5)` vs `(616.0, 574.5)` outer-glass rim center conflict; locked E01–E14 | **PASS (Offline)** |
| **G1 Reference-to-3D Fitting** | `tools/orb-v3-generator/fitting/*.py`, `MASTER_FIDELITY_V32.json` | Camera `diamErr=0.00%, IoU=0.9996`; Candidate B crystal lens winner (`E03=7.1%, 0.0068D`, `E04=0.0141R`); Gold Chamfer `0.0248R` (`<=0.025R`, non-oval); Haze `0.00%` | **PASS (Offline)** / **BLOCKED_NEEDS_GPU_FRAME** |
| **G2/G3 WebP 3D Volume + Runtime** | `generate_orb_volume.py`, 32 Lossless WebP slices, `MasterFidelityV32.kt`, `CoreFluidShaders.kt`, `AIOrbOrbitRibbons.kt` | Single-source `MASTER_FIDELITY_V32.json` drives both Python WebP synthesis and Android GLES3 shaders (Candidate B crystal lens + active-surface fold normals + fitted A01/A02) | **PASS (Code/Asset)** / **BLOCKED_NEEDS_GPU_FRAME** |
| **G4 Real GPU Capture Pipeline** | `OrbGpuReferenceCapture.kt`, `CAVITY_ONLY` mode, Tab 1 one-click evidence export | Pure GL-thread `glReadPixels` (Y-flipped, zero UI) exports all 6 diagnostic PNGs + JSON telemetry + build fingerprint | **READY FOR REAL DEVICE** (`BLOCKED_NEEDS_GPU_FRAME`) |

---

## 2. Same-Classifier Offline Proxy QA Metrics (`OFFLINE_CPU_PROXY` — NOT Real GPU!)

Generated comparison artifacts:
- `AI_ORB_V3_REFERENCE_COMPARISON.png` (`1536 × 512`: Master | V3.2 Offline CPU Proxy | 2× Diff)
- `docs/v3/REFERENCE_SQUARE_OFFLINE_PROXY.png` (`512 × 512`)
- `docs/v3/PRODUCTION_PORTRAIT_OFFLINE_PROXY.png` (`360 × 800`)

| Metric | Master (`____________910d1531.png`) | V3.2 Offline Proxy | Delta / Status |
|---|---|---|---|
| Outer Sphere Diameter Ratio | `0.5199` | `0.5209` | `relErr = 0.19%` |
| `E03` Upper Cavity Area Ratio | `0.06737` | `0.0916` | `relErr = 36.0%`, `Δc = 0.0257D` |
| `E05` Emerald Area Ratio | `0.32685` | `0.29576` | `relErr = 9.5%`, `Δc = 0.0339D` |
| `E06` Sapphire Area Ratio | `0.25527` | `0.24665` | `relErr = 3.4%`, `Δc = 0.0149D` |
| `E07` Aqua Area Ratio | `0.15496` | `0.32225` | `relErr = 108.0%`, `Δc = 0.0815D` |
| `E10` Gold Combined Chamfer (Stage D) | — | `0.0248 R` | `<= 0.025R`, `nonRegularOval = True` |
| Offline Full-Frame RGB MAE | — | `0.1585` | Offline CPU proxy only |
| Real Hardware GPU Frame Comparison | — | `BLOCKED_NEEDS_GPU_FRAME` | Run one-click Export V3.2 Fidelity Evidence on physical Android device |
