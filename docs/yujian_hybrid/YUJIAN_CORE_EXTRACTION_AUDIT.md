# YuJian Core Extraction Audit

## Frozen inputs

- Demo base: `a8e9249da74784fdf89cb08ec089ae9b676eb45a` (`codex/ai-orb-v32-final-closure-optical-fusion-v3`). Remote `main` remains `07ce54a6657edc57b2106d734b57ca3cec87545e`.
- YuJian source: `03db3692669847084d7e48fc3128f85cbf832af9`. The source remote `main` still points to this commit at audit time.
- Development branch: `codex/yujian-orb-third-tab-hybrid-v1`.

## What the pinned source actually renders

`AIOrbCore.kt` owns two Core routes. The image route draws `processing_water_core_v1.webp` and applies `OrbFluidRefractionProgramV1`/AGSL. The optional GL candidate is `OrbCrystalWakeGlOverlay.kt`; once its first frame is presented, that route hides the legacy image/AGSL group. The overlay creates a Compose `AndroidView`/`TextureView` with its own EGL host and compiles six programs for environment, ribbons, grass, sphere, droplets, and starbursts.

The extractable Core-only shader is `SPHERE_FS` (`OrbCrystalWakeGlOverlay.kt`, pinned source SHA-256 `91b7503522338c65a33d2390385808e3b1985733be925f1e7dfa2141d331ad17`; extracted shader SHA-256 `eff0af75967117b501be6409009dfa62d9fb336065e458464a19d3e5221bbc05`). It is a screen-space analytic sphere shader. It samples a completed background FBO and implements chromatic refraction, an animated curved meniscus, teal/aqua body, multi-octave caustics, directional gold entry light, and a double Fresnel rim. It does **not** sample the WebP, build a mesh, raymarch a 3D volume, or consume `OrbCoreRotationFieldV1` in that GL path.

The source WebP is still part of YuJian's image/AGSL Core route and is copied byte-for-byte into the Demo APK. Its active-use status in the extracted GL shader is `PACKAGED_NOT_SAMPLED_BY_SPHERE_FS`; this is recorded explicitly rather than pretending the source GL program consumes it.

The selected GL route has no rigid 3D self-rotation. The source's `OrbCoreRotationFieldV1.inverseSample` is a radial 2D image-coordinate field used by the image/AGSL path. Its frozen 7-second profile is adapted to the extracted shader's interior optical coordinates so Tab 3 can share the Demo scene clock. This produces source-derived interior angular flow; it is not represented as a 3D mesh rotation.

## Source-to-target mapping

See `YUJIAN_ORB_SOURCE_MANIFEST.json` for every audited file's source and target hashes, role, and migration status.

| Source | Target / status |
|---|---|
| `OrbCrystalWakeGlOverlay.kt` / `SPHERE_FS` | Extracted to `app/src/main/assets/yujian_orb_core/YuJianCoreFragment.glsl`; ESSL 3.0 interface adaptation; Core only |
| Overlay `QUAD_VS` contract | `YuJianCoreVertex.glsl`; adapted to project the source analytic sphere through Demo's shared model/view/projection |
| `OrbResidentSystemsV1.kt` / `OrbCoreRotationFieldV1.inverseSample` | Frozen 7-second radial interior rotation profile applied to shader optical coordinates; source file itself is not imported |
| `processing_water_core_v1.webp` | Exact binary copy at `app/src/main/assets/yujian_orb_core/processing_water_core_v1.webp`; decoded as 384×384 RGBA with alpha; not sampled by extracted `SPHERE_FS` |
| `AIOrbCore.kt`, `OrbFluidRefractionProgramV1.kt`, `orb_fluid_refraction_v1.agsl` | Audited as the parallel source image/AGSL route; not copied as a separate host/runtime |
| `WaterLightGlSupport.kt` and Compose `OrbCrystalWakeGlOverlay` host | Excluded: would create the prohibited second EGL/TextureView renderer |
| `OrbCausticsAdapter.kt`, `OrbDropletRenderer.kt`, `OrbFilamentRenderer.kt` | Excluded from Tab 3 Core; Demo's native outer passes remain the only outer renderer |
| Source environment, grass, ribbon, droplet, starburst and Bloom programs | Excluded; duplicate Demo outer systems are not migrated |

## Confirmed source constraints and remaining fidelity gap

The source asset is included and SHA-verified, but the extracted production GL shader does not consume it. The selected source GL Core is analytic 2D screen-space shading, not a mesh/3D volume, and the selected GL shader itself does not spin as a rigid 3D body. These are source-confirmed facts. The integration does not add a synthetic mesh, 3D volume, extra Core texture blend, or independent clock and does not label the Core as a true 3D spin.

The API36 GPU run and side-by-side image metrics decide runtime status. A software AVD is reported as software rendering, never hardware GPU evidence.
