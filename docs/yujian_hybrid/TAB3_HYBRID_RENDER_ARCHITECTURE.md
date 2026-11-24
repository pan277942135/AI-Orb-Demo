# Tab 3 Hybrid Render Architecture

## Render path

```text
AIOrbCanvas (one GLSurfaceView / GLES 3.0 context)
  -> AIOrbScene.onDrawFrame
  -> shared Tab 1 camera, motion clock and post-processing
  -> Pass 1 existing deep teal background + REF_B-derived bokeh (Tab 3 only)
  -> Pass 2 REF_B rear surfaces, inferred filaments, highlights and particles
  -> Pass 3 refraction snapshot FBO
  -> Pass 4 YuJian Core-only SPHERE_FS extraction (Tab 3 only)
  -> Pass 5 refraction snapshot of the completed Core
  -> Pass 6 REF_B front surfaces, inferred filaments, highlights and droplets
  -> Pass 7 front-of-core optical detail and local refraction
  -> Pass 8 single final Demo HDR, Bloom, ACES and sRGB composite
```

Tab 1 continues to use its original `AIOrbCore` V3.2 renderer and native outer passes. Tab 2 continues through its existing Task/Photo/Perimeter pipeline. Tab 3 is `OrbSceneMode.YUJIAN_HYBRID`: it uses a REF_B-driven `OuterOpticalFieldRenderer` around the source-extracted `YuJianCoreRenderer`. Its centerline geometry, widths, material colors, bokeh, droplets, particles and highlight locations come from the frozen P0-A extraction, not the prior five-resident renderer. The two internal filament lanes per detected body and their spherical depth offsets are explicitly marked inferences.

## Core and refraction adapters

The first `captureRefractionSnapshot()` blits the background and rear optical field to a distinct refraction FBO. YuJian `SPHERE_FS` samples that texture, never the target FBO. It draws a projected, padded quad around the Core, discards fragments outside the antialiased sphere, and writes analytic sphere depth. After the Core draw, Tab 3 refreshes the snapshot and the front field samples it. This keeps FBO reads and writes separate and the final composite runs once.

Both Core and outer field derive projected center/radii from the existing `outerGlassModel`, `viewMatrix`, and `projMatrix`; neither creates a new camera. The source Core and Tab 3 field use `AIOrbMotionSnapshot.simTimeSec`. The same Demo motion controller owns time and pause state. Each trace retains its own phase and period.

## Resource and lifecycle ownership

The REF_B runtime manifest, strip meshes and two Tab 3 programs are initialized lazily on the shared GL thread the first time Tab 3 renders, then rebuilt after EGL context loss. Tab 1 and Tab 2 do not allocate or compile the added field.

- The single `AIOrbScene` owns both Core renderers, all outer renderers, the refraction snapshot, and final post-processing.
- `YuJianCoreRenderer.onSurfaceCreated()` compiles once per EGL context. Context recreation invalidates names and reloads the program/VBO. Tab switching does not recompile.
- Explicit teardown deletes the program and VBO while the GLSurfaceView context is current.
- Shader failure is `ERROR` telemetry; there is no procedural or static fallback in Tab 3.
- `processing_water_core_v1.webp` is packaged bit-identically and its Android decoder integrity is tested. It is not bound into the selected source `SPHERE_FS` route because that source shader does not sample it.

## Frozen outer boundary

Tab 1 and Tab 2 output paths are frozen. The only Tab 3 background difference is the optional REF_B bokeh uniform branch; the original procedural bokeh path remains the default for both existing tabs. Tab 3 does not use the Demo five-resident/Ribbon mesh as its outer architecture. No YuJian grass, Resident, ribbon, droplet, starburst, environment or Bloom renderer is used.

## Runtime evidence fields

`AIOrbTelemetry` records the selected Core mode and YuJian program; the REF_B manifest SHA; combined Tab 3 shader SHA and program IDs; surface/filament/highlight/drop/particle counts; per-layer draw vertex counts; active framebuffer and refraction texture; uniform read-back and GL errors. The source WebP SHA is recorded and verified in the APK but is not claimed to be sampled by the active `SPHERE_FS` shader.
