# Work 3.2 Stage A — Five Resident True Orbital Motion Source & Coordinate Audit

- **Task ID:** `WATER_WORLD_V2_1_WORK3_2_FIVE_RESIDENT_TRUE_ORBITAL_MOTION_V2`
- **Base Main HEAD:** `2e8779d3afb7994c49569825ea9c85c2e3e5d37b`
- **Scope:** Five Residents (`G01, G02, A01, A02, C01`) across 13 frozen Render Segments (`8 Back + 5 Front`)
- **Companion Quantitative Audit JSON:** `01_AUDIT/five_resident_core_local_coordinate_audit.json`
- **Companion Orbital Profile JSON:** `02_PROFILE/five_resident_orbital_profile_v1.json`

---

## 1. Resident Mesh Coordinate System & Vertex Space

All 13 frozen Resident meshes (`releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1/02_SUPPLEMENTED_ASSETS/geometry/resident_*.json`) were audited against `five_resident_geometry_lock.json`:

- **Integrity:** All 13 mesh files match their V1 frozen SHA-256 checksums (`all13MeshesMatchV1FrozenSha256 = true`).
- **Vertex Attribute Layout (9 floats/vertex after `WaterWorldMesh.buildRibbonBuffersWithHalfWidths`):**
  - `layout(location=0) vec3 aPosition` — 1254 × 1254 canvas pixel coordinates `(x, y)` with `aPosition.z == 0.0` for every vertex across all 13 meshes.
  - `layout(location=1) vec3 aNormal` — unit ribbon cross-section outward normal in XY.
  - `layout(location=2) vec2 aUv` — `u ∈ [0, 1]` along segment, `v ∈ {0, 1}` across ribbon edges.
  - `layout(location=3) float aLocalHalfWidthPx` — exact local cross-section half-width in pixels.

## 2. `uModel` Matrix Structure (`draw_bindings.json`)

Every one of the 13 Resident draw bindings uses a **pure Z-translation column-major 4×4 matrix** (`all13ModelsArePureZTranslation = true`, identity 3×3 upper-left, `tx = 0`, `ty = 0`, `tz = modelZ`):

| Pass | Segment ID | Group | `modelZ` | Rotated 0..360° `Z` Range | Rotated 0..360° NDC `z` |
|---|---|---|---:|---|---|
| `02_RESIDENT_BACK` | `G01_back_arch` | `G01` | `+520.0` | `[+466.703, +565.675]` | `[+0.2279, +0.2762]` |
| `02_RESIDENT_BACK` | `G01_back_outer_veil` | `G01` | `+515.0` | `[+485.906, +564.631]` | `[+0.2373, +0.2757]` |
| `02_RESIDENT_BACK` | `G02_back_upper_arc` | `G02` | `+505.0` | `[+465.586, +550.178]` | `[+0.2273, +0.2686]` |
| `02_RESIDENT_BACK` | `G02_back_upper_hairline` | `G02` | `+502.0` | `[+462.595, +542.909]` | `[+0.2259, +0.2651]` |
| `02_RESIDENT_BACK` | `G02_back_right_rim` | `G02` | `+500.0` | `[+439.975, +512.183]` | `[+0.2148, +0.2501]` |
| `02_RESIDENT_BACK` | `A01_back_upper_left` | `A01` | `+495.0` | `[+495.000, +495.000]` | `[+0.2417, +0.2417]` |
| `02_RESIDENT_BACK` | `A02_back_lower_halo` | `A02` | `+490.0` | `[+457.820, +557.887]` | `[+0.2235, +0.2724]` |
| `02_RESIDENT_BACK` | `C01_back_lower_thread` | `C01` | `+485.0` | `[+428.735, +505.207]` | `[+0.2093, +0.2467]` |
| `10_RESIDENT_FRONT` | `G01_front_lower_sweep` | `G01` | `-520.0` | `[-561.300, -464.349]` | `[-0.2741, -0.2267]` |
| `10_RESIDENT_FRONT` | `G02_front_right_embrace` | `G02` | `-505.0` | `[-551.513, -472.922]` | `[-0.2693, -0.2309]` |
| `10_RESIDENT_FRONT` | `A01_front_left_embrace` | `A01` | `-510.0` | `[-510.000, -510.000]` | `[-0.2490, -0.2490]` |
| `10_RESIDENT_FRONT` | `A02_front_lower_strand` | `A02` | `-508.0` | `[-532.180, -447.318]` | `[-0.2599, -0.2184]` |
| `10_RESIDENT_FRONT` | `C01_front_lower_right` | `C01` | `-512.0` | `[-538.768, -456.191]` | `[-0.2631, -0.2227]` |

## 3. Unified Core Center & Core Radius Source

Read dynamically at runtime from `optical_config.json` (`WaterWorldSceneBindings.OpticalConfig`):
- `uCoreCenterPx = vec3(opt.sphereCenterPx[0], opt.sphereCenterPx[1], 0.0)` = `(626.4757504, 596.3441299, 0.0)`
- `uCoreRadiusPx = opt.sphereInnerRadiusPx` = `395.75` (exact radius used by `WaterWorldCoreMotionAdapter.buildAlignedVolumeCamera`; outer glass radius is `422.2986622`).

## 4. Projection & Depth Convention

`RESIDENT_RIBBON_VERTEX` projects scene-space `p` onto 1254 × 1254 orthographic NDC:

```glsl
gl_Position = vec4(p.x / uCanvas.x * 2.0 - 1.0, 1.0 - p.y / uCanvas.y * 2.0, p.z / 2048.0, 1.0);
```

- Depth test uses `GL_LEQUAL` (`z = -1.0` NEAR / front, `z = +1.0` FAR / back).
- Reference scene layers:
  - `01_BACKGROUND`: `Z = +1900` (writes depth buffer)
  - `02_RESIDENT_BACK`: `Z ∈ [+485, +520]` (depth-tested against background, depthWrite = false; composited *before* `03_SPHERE_BACK` and `04_WATER_INTERIOR` Core 3D volume)
  - `03_SPHERE_BACK`: `Z = +450`
  - `04_WATER_INTERIOR` & Core 3D Volume: `Z ∈ [0, +320]`
  - `05_FAR_ECOLOGY` / `07_FISH` / `08_NEAR_ECOLOGY`: `Z ∈ [+106, +240]`
  - `09_SPHERE_FRONT`: `Z = -450`
  - `10_RESIDENT_FRONT`: `Z ∈ [-520, -505]` (composited *after* `09_SPHERE_FRONT`, depth-tested against interior writes `Z >= +106`, depthWrite = false)
  - `11_FINAL_COMPOSITE` outer bubbles: `Z ∈ [-608, -600]`

## 5. Depth Safety Under Full 0..360° Orbital Rotation

Because all orbital axis tilts are strictly bounded to `<= 8°` from Core Z `(0, 0, 1)`:
- All 8 Back segments remain strictly within `Z ∈ [+428.7, +565.7]` (`< +1900` background, `> 0` Core center) across 0..360°. They are **never clipped by background depth** and **never flip in front of the Core**.
- All 5 Front segments remain strictly within `Z ∈ [-561.3, -447.3]` (`< +106` near ecology, `> -2048` near clip plane) across 0..360°. They **never clip behind the interior or near plane**.

## 6. `A01` Back/Front Seam Audit (`0.0 px` Exact Invariance)

- `A01_back_upper_left` end centerline (`u = 1`): `(48.906, 570.570, +495.0)`
- `A01_front_left_embrace` start centerline (`u = 0`): `(48.906, 570.570, -510.0)`
- Because `A01`'s orbital axis is pure Core Z `(0, 0, 1)` (tilt `0.000°`), both points share identical XY coordinates at `T=0` and rotate identically in XY at every angle `0..360°`.
- **Measured Max Screen-Space XY Seam Gap (0..360°):** **`0.000000 px`** (`<= 1.0 px` PASS).

## 7. Vertex Transform Order Audit

1. Evaluate V1 local ribbon wave and relative cross-section width breathing in mesh local space (`uDeformMode == 3`).
2. Apply static `uModel` (`vec4 p = uModel * vec4(localPos, 1.0)` and `worldNormal`).
3. If `uResidentOrbitEnabled == 1`:
   - Convert `p.xyz` to Core-local normalized coordinates: `coreLocalNorm = (p.xyz - uCoreCenterPx) / max(uCoreRadiusPx, 1.0)`
   - Apply precomputed 3×3 Rodrigues rotation: `rotatedNorm = uResidentOrbitMat * coreLocalNorm`
   - Convert back to scene space: `p.xyz = uCoreCenterPx + rotatedNorm * max(uCoreRadiusPx, 1.0)`
   - Rotate `worldNormal = normalize(uResidentOrbitMat * worldNormal)`
4. If `uResidentOrbitEnabled == 0` (`staticReferenceMode == true` or `T == 0`): bypass step 3 completely for exact bit-for-bit Work 2 / V1 `T=0` static identity.
5. Apply existing orthographic canvas projection and pass `vUv`, `vScreenUv`, `vNormal`, `vCanonicalS` to untouched `RESIDENT_RIBBON_FRAGMENT`.

## 8. GLES 3.0 Active Uniform Contract Compliance

Every added uniform in `RESIDENT_RIBBON_VERTEX` (`uResidentOrbitMat`, `uCoreCenterPx`, `uCoreRadiusPx`, `uResidentOrbitEnabled`) is actively read in the shader body, guaranteeing `glGetUniformLocation >= 0` on strict mobile GLES 3.0 drivers (including HUAWEI Maleoon 935) and passing `WaterWorldShaderUniformContractTest`.

## 9. Scope Isolation Confirmation

Zero changes are required to: AI Orb Core 32 Z-slices, `CoreFluidShaders.kt`, `OrbFluidVolumeRenderer.kt`, 4 fish, 2 plants, 3 rocks, 9 outer / 14 inner bubbles, water, glass, caustics, bloom, bokeh, 11 Render Passes, 5 Optical Nodes, or any frozen mesh/texture asset.
