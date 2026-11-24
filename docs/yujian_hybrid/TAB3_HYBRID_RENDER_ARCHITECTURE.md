# Tab 3 Hybrid Render Architecture

## Render path

```text
AIOrbCanvas (one GLSurfaceView / GLES 3.0 context)
  -> AIOrbScene.onDrawFrame
  -> shared Tab 1 camera, motion clock and post-processing
  -> Pass 1 background
  -> Pass 2 rear particles
  -> Pass 3 rear Demo Residents
  -> Pass 4 rear Demo Energy Highlights
  -> Pass 5 refraction snapshot FBO
  -> Pass 6 YuJian Core-only SPHERE_FS extraction (Tab 3 only)
  -> Pass 7 front Demo Residents
  -> Pass 8 front Demo highlights and particles
  -> Pass 9 Demo HDR, Bloom, ACES and sRGB composite
```

Tab 1 continues to use its original `AIOrbCore` V3.2 renderer. Tab 2 continues through its existing task pipeline. Tab 3 is `OrbSceneMode.YUJIAN_HYBRID`, mapping only Pass 6 to `YuJianCoreRenderer`. `activeRenderPassesV3` remains unchanged; `activeRenderPassesYuJianHybrid` changes the Pass 6 manifest entry only.

## Pass 5 → Pass 6 adapter

`AIOrbPostProcessing.captureRefractionSnapshot()` blits the finished scene FBO to a distinct refraction FBO and rebinds the scene target. The Core shader samples only this completed texture; it never samples the target FBO. The shader draws a projected, padded quad around the Core, discards fragments outside the antialiased sphere, and writes the analytic sphere's projected depth so Pass 7/8 retain front/back depth ordering. UV sampling uses the FBO's lower-left GLES origin without a Y flip. RGB remains linear in the existing HDR scene target; `hdrScale` is applied to the extracted source Core's RGB in the RGBA8 fallback mode. No second Bloom or tone map is run.

The projection adapter derives center and silhouette radii from the existing `outerGlassModel`, `viewMatrix`, and `projMatrix`; it does not create a new camera. The source Core's `uTime` uses `AIOrbMotionSnapshot.simTimeSec`. The same Demo motion controller owns time and pause state. Outer residents continue to use their existing independent motion.

## Resource and lifecycle ownership

- The single `AIOrbScene` owns both Core renderers, all outer renderers, the refraction snapshot, and final post-processing.
- `YuJianCoreRenderer.onSurfaceCreated()` compiles once per EGL context. Context recreation invalidates names and reloads the program/VBO. Tab switching does not recompile.
- Explicit teardown deletes the program and VBO while the GLSurfaceView context is current.
- Shader failure is `ERROR` telemetry; there is no procedural or static fallback in Tab 3.
- `processing_water_core_v1.webp` is packaged bit-identically and its Android decoder integrity is tested. It is not bound into the selected source `SPHERE_FS` route because that source shader does not sample it.

## Frozen outer boundary

The background, five Demo Residents, rear/front order, Energy Highlights, particles, and Pass 9 are unchanged. No YuJian grass, Resident, ribbon, droplet, starburst, environment or Bloom renderer is used in Tab 3.

## Runtime evidence fields

`AIOrbTelemetry` records the selected `coreRendererMode`, YuJian program ID, adapted fragment SHA-256, shader status, render-target dimensions, frame count, context rebuild count, projected Core bounds, and GL error/pass. The source WebP's SHA-256 is recorded in the source manifest and APK verification. The active GL shader does not claim the image is sampled.
