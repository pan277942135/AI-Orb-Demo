# AI-Orb-Demo V3.2 — Motion Time-Integration & 20-Second Regression Report

**Controller:** `AIOrbMotionController` (`FIXED_SUBSTEP_SEC = 1/120s`, `HITCH_SPIKE_THRESHOLD_SEC = 0.20s`, `HITCH_CLAMP_SEC = 0.05s`)  
**Frozen Core Self-Rotation Speed:** `26.0°/s` around frozen 3D axis `(0.14, 0.98, -0.14)` (`~13.8s` per full `360°` turn)

| Frame Rate Regime | Final `simTimeSec` | Core Angle @ `5 s` | Core Angle @ `10 s` | Core Angle @ `20 s` |
|---|---:|---:|---:|---:|
| 60 FPS steady | `20.0000 s` | `139.41°` | `273.53°` | `533.02°` |
| 30 FPS steady | `20.0000 s` | `139.41°` | `273.53°` | `533.02°` |
| 20 FPS steady | `20.0000 s` | `139.41°` | `273.53°` | `533.02°` |
| 15 FPS slow (0.0667s > 0.05s) | `20.0000 s` | `139.41°` | `273.53°` | `533.02°` |
| 10 FPS slow (0.1000s > 0.05s) | `20.0000 s` | `139.41°` | `273.53°` | `533.02°` |

- **Live Device 20-Second `FULL_RENDER` Hardware GPU Video Capture:** `BLOCKED_NEEDS_GPU_FRAME` / `PENDING_REAL_GPU_CAPTURE`.
