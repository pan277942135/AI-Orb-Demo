# AI Orb V3.1 — Unified Coordinate & Transform Contract

**Task ID:** `AI_ORB_V3_1_FINAL_STRUCTURAL_PARITY_P0`
**Master Reference:** `public/assets/aistudio/____________910d1531.png`
**Master SHA-256:** `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122`

---

## 1. Frozen Spatial Composition Constants

| Parameter | Value | Owner | Status |
|---|---|---|---|
| `coreShellScale` | `0.80f` | `AIOrbScene.kt` | FROZEN |
| `ribbonHugScale` | `0.87f` | `AIOrbOrbitRibbons.kt` | FROZEN |
| `visualBoundRadius` | `1.36f` | `AIOrbScene.kt` | FROZEN |
| `BASE_CORE_ROTATION_DEG_PER_SEC` | `26.0` | `AIOrbMotionController.kt` | FROZEN |
| `CORE_ROTATION_AXIS` | `Vec3(0.14f, 0.98f, -0.14f).normalized()` | `AIOrbMotionController.kt` | FROZEN |
| `densityScale` | `1.65f` (clamped `[1.20, 1.95]`) | `AIOrbConfig.kt` / `OrbFluidVolumeRenderer.kt` | V3.1 UNIFIED |

---

## 2. Authoritative Coordinate Frame Chain

```text
World Space (Camera looking along -Z from (0, 0, camZ))
  │
  ├──► Glass Local Space:  p_local = (uInvModel * vec4(p_world, 1.0)).xyz
  │      • Outer glass sphere radius = 1.0 (scaled to 0.80 in World Space)
  │      • Refracted ray enters at rayOrg = vLocalPos * 0.945 along rayDir = normalize(mat3(uInvModel) * refract(-V, safeN, 1/1.42))
  │      • Outer glass Fresnel, double-wall rim rings, and 10:30 curved golden refraction stay strictly in View/Glass Local Frame (independent of core spin!)
  │
  ├──► Rotated Core Frame: p_core = invCoreRot * p_local,  where invCoreRot = transpose(uCoreRotMat)
  │      • Shared as the SINGLE authoritative frame for ALL internal fluid geometry:
  │        1. Crystal Cavity SDF:       cavityPhi(p_core, uMeniscusPhase)  (via uReferenceMeniscusY[32])
  │        2. Cavity Surface Normal:    cavityNormal(p_core, uMeniscusPhase, slope) -> world/local via uCoreRotMat * mNCore
  │        3. 3D WebP Volume Sampling:  sampleFluidVolume3D(p_core, uFluidPhase) -> volUv = (x*0.5+0.5, 0.5-y*0.5, z*0.5+0.5)
  │        4. Underwater Caustics:      floorCorePos = toCoreFrame(floorPos, invCoreRot)
  │        5. Core Key Lighting:        keyDirCore = normalize(invCoreRot * normalize(uKeyLightDir))
  │      • Because uCoreRotMat is orthonormal, transpose(uCoreRotMat) == inverse(uCoreRotMat) with zero shear/drift.
  │
  └──► Resident Orbit Frame (5 Independent Residents: G01, G02, A01, A02, C01)
         • Control splines authored in unit-orb coordinates, scaled by ribbonHugScale = 0.87f.
         • Driven by frozen R06 periods (G01=6100ms, G02=5300ms, A01=3700ms, A02=2900ms, C01=4400ms).
         • Never locked to uCoreRotMat; orbits smoothly around the stationary crystal glass shell.
```

---

## 3. WebP 3D Volume Axis Convention

- `volume/fluid_color_z00.webp` .. `fluid_color_z31.webp` (`256 × 256 × 32`, Lossless RGBA WebP `VP8L`):
  - **X axis (Column `0..255`):** `-1.0` (left) → `+1.0` (right) → `volUv.x = samplePos.x * 0.5 + 0.5`
  - **Y axis (Row `0..255`):** Row `0` = `+1.0` (top), Row `255` = `-1.0` (bottom) → `volUv.y = 0.5 - samplePos.y * 0.5`
  - **Z axis (Slice `z00..z31`):** `-1.0` (rear) → `+1.0` (front) → `volUv.z = samplePos.z * 0.5 + 0.5`
- **Single-Source Meniscus Guarantee:** The 32 WebP slices contain **NO** baked meniscus clipping (upper sphere mean alpha `> 0.04`). The curved crystal cavity boundary is evaluated exclusively in `CORE_GLASS_FRAG` via `cavityPhi(coreSp, uMeniscusPhase)` using `uReferenceMeniscusY[32]`, 5-step binary bisection, and front-wall top-entry contact gating.

---

## 4. Compositing & Color Space Contract

- **Core Glass Pass (`OrbFluidVolumeRenderer.kt`):** `GLES30.glDisable(GLES30.GL_BLEND)` is enforced. `CORE_GLASS_FRAG` reads `originalBg` and `refractedBack` from `uRefractionTex`, integrates Beer-Lambert transmittance over `cavityPhi < 0`, composites `mix(originalBg, col, edgeAlpha)`, and outputs `alpha = 1.0`.
- **Per-Texture Color Space Uniforms:**
  - `uFluidVolumeIsSrgb` (`GL_SRGB8_ALPHA8` preferred; shader `pow(2.2)` fallback if `0`)
  - `uGlassEnvIsSrgb` (`GL_SRGB8_ALPHA8` preferred; shader `pow(2.2)` fallback if `0`)
  - `uCausticsIsSrgb` (strictly linear `GL_RGBA8`, `0`)
  - `uFluidDetailIsSrgb` (strictly linear `GL_RGBA8`, `0`)
