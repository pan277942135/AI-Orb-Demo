# Tab 3 REF_B Outer Optical Field

## Runtime design

`OuterOpticalFieldRenderer` is initialized in the existing `AIOrbScene` GL context and is called only when `sceneMode == YUJIAN_HYBRID` and `coreMode == YUJIAN_CORE`.

The runtime manifest is generated from the frozen P0-A REF_B extraction and packaged at `assets/outer_optical_field/OUTER_OPTICAL_FIELD_RUNTIME_V1.json`. It contains 7 measured projected trace paths, their measured width and color summaries, 12 REF_B background-bokeh candidates, 51 local highlight candidates, 5 droplet candidates, and 10 particle candidates.

The renderer creates 7 translucent body strips and 14 parallel inner filaments. The 14 filament lanes are explicit geometry inferences from the measured strip widths; they are not claimed as a direct count of 3D filaments in a single RGB image. The five liquid drops and ten small particles are generated from the P0-A detected components. Counts are data-driven from the manifest and are not constrained by the prior five-resident / ten-ribbon architecture.

Each reference pixel is converted to a core-relative coordinate using REF_B's measured center and 652.00476-pixel diameter. At draw time that coordinate is projected using the current YuJian Core model, view, projection, and sampled projected sphere bounds. The front/back depth comes from the frozen resident semantic groups and a spherical surface intersection. That depth mapping remains `INFERRED` because REF_B is a single static image.

## Pass ordering

1. The existing deep teal background shader renders the same background field. Tab 3 supplies the measured REF_B bokeh uniforms; Tab 1 and Tab 2 supply no such uniforms and keep their original bokeh branch.
2. Tab 3 draws back surfaces, filaments, highlights, and particles.
3. The existing refraction FBO snapshots the completed background and back field.
4. The source-extracted YuJian `SPHERE_FS` draws in the same scene FBO and EGL context.
5. Tab 3 refreshes the refraction snapshot from the completed Core.
6. Tab 3 draws front surfaces, filaments, highlights, and droplets with depth testing and Core refraction.
7. The existing HDR post-processing runs once at the end.

No YuJian peripheral overlay is copied. The only YuJian source renderer used is the already pinned Core-only Pass 6. The packaged `processing_water_core_v1.webp` remains packaged but is not sampled by the source `SPHERE_FS`, as stated in the existing Core extraction audit.

## Material and motion

The cyan glass, gold surface, and emerald/gold rear group have independent base colors and material response. The strip shader combines a transmissive body, a measured-width inner filament, edge Fresnel, folded highlights, animated glints, and a refracted sample for front layers. The shared `AIOrbMotionSnapshot.simTimeSec` drives vertex ripple, along-path highlights, point drift, and pulse; per-trace phase and period values remain distinct.

The outer renderer reports its manifest SHA, combined shader-source SHA, linked program IDs, source structure counts, actual framebuffer and refraction texture IDs, read-back uniforms, draw vertex counts, and GL error/pass in `AIOrbTelemetry`.

## Isolation and verification

Tab 1 continues to call its native Ribbon, Highlight, and Particle renderers. Tab 2 continues to use its Task Energy and Perimeter WaterLight renderers. The new scene path only replaces Tab 3's outer passes. The `BACKGROUND_FRAG` addition selects the original 12 procedural bokeh entries whenever no Tab 3 reference-bokeh list is supplied.

Static validation: `validate_runtime_manifest.py` checks the exact REF_B SHA, P0-A status, extraction contract, measured fields, and packaged asset SHA. `validate_glsl_es.py` compiles and links the four new production shader sources in GLES 3.0 and verifies active uniforms. API36 instrumentation verifies linked programs, draw counts, texture/FBO state, GL errors, and repeated captures. The AVD uses SwiftShader and is software-render evidence only.
