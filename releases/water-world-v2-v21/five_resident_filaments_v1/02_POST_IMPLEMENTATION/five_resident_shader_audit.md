# Five Resident Filaments V1 — GPU Shader Audit

**Contract ID:** `WATER_WORLD_FIVE_RESIDENT_FILAMENTS_DESIGN_FREEZE_V1`  
**Shader Contract Static Verification:** `PASS` (0 dead uniforms across all 12 programs via `WaterWorldShaderUniformContractTest`)  
**Runtime GLES Driver Compile/Link (`GLES_SHADER_VERIFIED`):** `NOT_RUN` (no Android EGL/GLES 3.0 driver in cloud container)

---

## 1. Dedicated Vertex Shader Architecture (`RESIDENT_RIBBON_VERTEX`)

To guarantee zero side effects on `OPAQUE`, `TRANSPARENT`, and `PARTICLE_BOKEH` (which continue sharing `MESH_VERTEX`), `s.residentRibbon` now links a dedicated `RESIDENT_RIBBON_VERTEX` shader:

1. **Vertex Attributes:**
   - `location=0`: `aPosition` (frozen Work 2 vertex XYZ)
   - `location=1`: `aNormal` (verified 100% outward cross-section normal across all 1,600+ strip pairs of all 13 meshes)
   - `location=2`: `aUv` (`u ∈ [0, 1]` along segment, `v ∈ {0, 1}` across ribbon edges)
   - `location=3`: `aLocalHalfWidthPx` (exact load-time computed `0.5 * hypot(v1.xy - v0.xy)` from frozen strip pairs; zero changes to frozen JSON files)
2. **Elimination of Hardcoded `18.0 px` Width Displacement (Contract Section 2.1.C):**
   - Pre-V1 `MESH_VERTEX` (`uDeformMode == 3`) applied `widthPulse * acrossSigned * 18.0 * endAnchor`, which distorted narrow `14 px` hairlines by `>128%`.
   - `RESIDENT_RIBBON_VERTEX` replaces this with exact relative width breathing: `localPos.xy += outwardDir * (aLocalHalfWidthPx * widthAmp * widthWave * endAnchor)`, scaling symmetrically around the centerline strictly proportional to local ribbon thickness (`±4.5%..7.0%`) and spatially phased along `vCanonicalS`.
3. **Bounded Canonical Lateral Wave:**
   - `ribbonNormal = outwardDir * ((aUv.y < 0.5) ? 1.0 : -1.0)` shifts both edges in the same perpendicular direction so the ribbon bends smoothly without pinching.
   - `lateralWave = sin(wavePhase - canonicalS * 4π) * 0.68 + cos(wavePhase * 0.73 + canonicalS * 2π) * 0.32` (`|lateralWave| <= 1.0`), strictly bounded to per-Resident `maxLateralWavePx` (`±6.5..9.0 px`) with endpoint stabilization (`endAnchor`).
4. **Exact `T = 0` Identity:** When `uDeformMode == 0`, `localPos == aPosition` bit-for-bit.

---

## 2. Upgraded Fragment Shader (`RESIDENT_RIBBON_FRAGMENT`)

1. **Canonical Flow Coordinate:** Consumes `in float vCanonicalS` mapped continuously across Back & Front segments of the same Resident.
2. **Moving Energy Highlight Band (Contract Section 2.1.A):**
   - Primary + staggered secondary asymmetric energy envelopes (`phaseHead1 = fract((vCanonicalS - uFlowOffsetU) * flowDir)`) combine a narrow soft leading edge (`exp(-(d/0.068)^2)`) with a longer smooth fading tail (`exp(-((1-d)/0.20)^2)`), wrapping seamlessly across `[0, 1)` with zero discontinuity or flashing.
   - Luminance boost concentrates along the filament's inner luminous core (`coreFocus = exp(-7.5 * across^2)`) using each segment's frozen `uTint` and `uCoreColor`, avoiding harsh neon outline halos and preserving exact Resident color identity.
3. **Non-Uniform Spatially-Phased Brightness Breathing (Contract Section 2.1.D):**
   - Modulates brightness along `vCanonicalS` (`brightWave`) blended with `uBrightnessScale`, strictly bounded to `[1 - brightnessAmp, 1 + brightnessAmp]` (`±7..10%`) with zero uniform whole-line strobing.
4. **Exact `T = 0` Identity:** When `uResidentFlowParams.x == 0.0` (`flowActive == 0`), `uFlow = u`, `energyBand = 0.0`, and `finalBrightness = 1.0`, reducing mathematically identically to Work 2 `T = 0`.

---

## 3. Active Uniform Parity Audit (`residentRibbon`)

Every uniform declared in `RESIDENT_RIBBON_VERTEX` + `RESIDENT_RIBBON_FRAGMENT` is actively read in shader executable code AND uploaded by `WaterWorldStaticRenderer.kt`:

| Uniform | Stage | Renderer Upload (`PassId.RESIDENT_BACK/FRONT`) | Active Shader Reads | Dead? |
|---|---|---|---|---|
| `uModel` | Vertex | `glUniformMatrix4fv(..., item.binding.model)` | 2 | No |
| `uCanvas` | Vertex | `glUniform2f(..., canvasW, canvasH)` | 3 | No |
| `uDeformMode` | Vertex | `glUniform1i(..., deformMode)` | 1 | No |
| `uDeformParams` | Vertex | `glUniform4f(..., widthAmp, lateralWavePx, wavePhaseRad, widthTimePhaseRad)` | 4 | No |
| `uSegmentArcRange` | Vertex | `glUniform4f(..., arcStart, arcEnd, uvDir, phaseOffset)` | 4 | No |
| `uTint` | Fragment | `glUniform4fv(..., item.binding.tint)` | 3 | No |
| `uCoreColor` | Fragment | `glUniform3fv(..., item.binding.coreColor)` | 2 | No |
| `uEmission` | Fragment | `glUniform1f(..., item.binding.emission)` | 1 | No |
| `uRibbonStyle` | Fragment | `glUniform1f(..., item.binding.ribbonStyle)` | 3 | No |
| `uFlowOffsetU` | Fragment | `glUniform1f(..., rState.flowOffsetU)` | 2 | No |
| `uBrightnessScale` | Fragment | `glUniform1f(..., rState.brightnessScale)` | 1 | No |
| `uResidentFlowParams` | Fragment | `glUniform4f(..., flowActive, brightAmp, brightTimePhaseRad, flowDir)` | 4 | No |

All 12 Water World programs passed `WaterWorldShaderUniformContractTest.t04_allTwelveWaterWorldProgramsHaveExactActiveUniformParityWithRenderer` with **0 dead uniforms** and **0 missing uniform queries**.
