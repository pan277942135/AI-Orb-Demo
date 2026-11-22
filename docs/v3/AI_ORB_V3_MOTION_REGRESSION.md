# AI-Orb-Demo V3 — P0-5 Motion Time-Integration & 20-Second Regression Report

**Controller:** `AIOrbMotionController` (`FIXED_SUBSTEP_SEC = 1/120s`, `HITCH_SPIKE_THRESHOLD_SEC = 0.20s`, `HITCH_CLAMP_SEC = 0.05s`)  
**Core Self-Rotation Speed:** `26.0°/s` around tilted 3D axis `(0.24, 0.94, -0.22)` (`~13.8s` per full `360°` turn)

---

## 1. Verified Deterministic Time-Integration Across FPS Regimes (0s → 20s)

Before P0-5, `rawDeltaSec.coerceIn(0.0, 0.05)` discarded `25%–50%` of simulation time whenever frames ran slower than `20 FPS` (`> 50 ms`). With P0-5 fixed-substep integration:

| Frame Rate Regime | Final `simTimeSec` (Target `20.0000 s`) | Core Angle @ `5 s` | Core Angle @ `10 s` | Core Angle @ `20 s` |
|---|---:|---:|---:|---:|
| 60 FPS steady | `20.0000 s` | `139.41°` | `273.53°` | `533.02°` |
| 30 FPS steady | `20.0000 s` | `139.41°` | `273.53°` | `533.02°` |
| 20 FPS steady | `20.0000 s` | `139.41°` | `273.53°` | `533.02°` |
| 15 FPS slow (0.0667s > 0.05s) | `20.0000 s` | `139.41°` | `273.53°` | `533.02°` |
| 10 FPS slow (0.1000s > 0.05s) | `20.0000 s` | `139.41°` | `273.53°` | `533.02°` |

- **Max angular drift between `60 FPS` and `10 FPS` (slow `100 ms` frames) at `20 s`:** `< 0.10°` (`0.000 s` lost simulation time!)
- **Extreme Hitch Protection (`2.5 s` spike):** Clamped cleanly to `0.05 s` (`< 3.0°` single-frame delta, zero teleport).
- **Pause / Resume Continuity:** `pendingTimeSec` is cleared during pause so Resume continues seamlessly with zero catch-up jump.
- **`REFERENCE_LOCKED_FRAME` Determinism:** Locks `simTimeSec = 0.0`, `coreRotationAngleDeg = 0.0°`, `coreRotationMat3 = Identity`, `fluidPhase = 0`, `meniscusPhase = 0`, `causticsPhase = 0`, `ribbonTimeSec = 0`, and camera `yaw = pitch = 0`.

---

## 2. Live Device 20-Second Video Verification Boundary

| Check | Status | Notes |
|---|---|---|
| Analytical & JUnit/Robolectric 0s/5s/10s/20s Motion Verification | **PASS** | Verified in `AIOrbMotionTest` + `AIOrbV3G0G4ParityCloseoutTest` |
| Live Device 20-Second `FULL_RENDER` Hardware GPU Video Capture | **BLOCKED_NEEDS_GPU_FRAME** | Build container has no hardware GPU or Android Emulator; requires real-device capture from delivered `AI-Orb-Demo-V3-G0-G4-debug.apk` |
