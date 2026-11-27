# Water World V2.1 — Work 3.1.1 Root Cause & Surgical Hotfix Report

**Task ID:** `WATER_WORLD_V2_1_WORK3_1_1_WATER_UNIFORM_CONTRACT_HOTFIX_V1`  
**Incident:** `SHADER_FAILED (FINAL_SCENE): Missing scene uniform uWaterFlowPhase` on HUAWEI CMS-AL20 (Android 16 / API 36 / Maleoon 935)

---

## 1. Confirmed Root Cause

During Work 3.1, the fake 2D core swirl/filament block was correctly removed from `WATER_FRAGMENT` in `WaterWorldShaders.kt` so that internal Core fluid motion is exclusively rendered by `OrbFluidVolumeRenderer` (`sampler3D uFluidVolume`, 32 spatial Z-slices).

However, one orphaned uniform declaration and its matching upload call were left behind:

1. `app/src/main/java/com/example/waterworld/WaterWorldShaders.kt` (line 321):
   ```glsl
   uniform float uWaterFlowPhase;
   ```
   Declared in `WATER_FRAGMENT` with **zero executable reads** in `main()`.
2. `app/src/main/java/com/example/waterworld/WaterWorldStaticRenderer.kt` (line 810):
   ```kotlin
   GLES30.glUniform1f(s.water.uniform("uWaterFlowPhase"), wo.waterFlowPhaseRad)
   ```
   Queried `uWaterFlowPhase` on every `renderWaterPass()` frame.
3. `app/src/main/java/com/example/waterworld/WaterWorldProgram.kt` (lines 44–48):
   ```kotlin
   fun uniform(name: String): Int = uniformCache.getOrPut(name) {
       GLES30.glGetUniformLocation(id, name).also {
           check(it >= 0) { "Missing scene uniform $name" }
       }
   }
   ```
   Strict non-negative uniform check (preserved untouched).

Per OpenGL ES 3.00 Specification §2.12.6, the Maleoon 935 GLSL compiler optimizes out unused uniforms at link time so `glGetUniformLocation(program, "uWaterFlowPhase")` returns `-1`, triggering `IllegalStateException("Missing scene uniform uWaterFlowPhase")` and aborting `FINAL_SCENE`.

---

## 2. Full 12-Program Active-Uniform Audit

Before and after the fix, all 12 `WaterWorldShaders` programs (`opaque`, `transparent`, `residentRibbon`, `water`, `glass`, `caustics`, `bloomBright`, `bloomBlur`, `bloomComposite`, `particleBokeh`, `bokehOrb`, `present`) were audited against every `s.<prog>.uniform("...")` query in `WaterWorldStaticRenderer.kt`:

- **Before fix:** Only `[water]` had a dead required uniform (`uWaterFlowPhase`). All other 11 programs had 0 dead uniforms and 0 missing uniforms.
- **After fix:** **All 12 programs have 0 dead uniforms and 0 missing uniforms** (`water`: 21 declared = 21 actively read = 21 queried).

---

## 3. Surgical Production Changes (Strictly 2 Lines Deleted)

1. `app/src/main/java/com/example/waterworld/WaterWorldShaders.kt`: Deleted `uniform float uWaterFlowPhase;` from `WATER_FRAGMENT`. No dummy math and no 2D fake core swirl re-introduced.
2. `app/src/main/java/com/example/waterworld/WaterWorldStaticRenderer.kt`: Deleted `GLES30.glUniform1f(s.water.uniform("uWaterFlowPhase"), wo.waterFlowPhaseRad)` from `renderWaterPass()`.

---

## 4. Protected Scope Verification (Zero Untouched Boundaries Violated)

- `CoreFluidShaders.kt` 3D volume raymarching / 3D curl advection / 3D rotation (`uFluidPhase`, `uCoreRotMat`, `uFluidVolume`, `uDensityScale`, `uCausticsPhase`, `uCoreRenderMode`, `uCoreDiagMode`, `uCoreColorShift`): **Untouched**.
- 32 spatial Z-slices (`app/src/main/assets/orb_v3/volume/fluid_color_z00..z31.webp`): **Untouched**.
- `WaterWorldProgram.uniform()` strict `check(it >= 0)`: **Preserved untouched**.
- `WaterWorldMotionRuntime` & `WaterOpticsMotionState.waterFlowPhaseRad`: **Preserved untouched**.
- 11 Render Passes / 5 Optical Nodes / Core Volume Camera / EGL lifecycle: **Untouched**.
