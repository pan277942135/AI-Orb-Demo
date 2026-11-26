# Water World scene assembly diagnostic report

Status: PARTIAL_PROTOTYPE / VISUAL_ACCEPTANCE_NOT_MET. Work 2 Ready is false.

The 65 original V2 files retain their byte identity. Original fish meshes, RGBA ecology textures, immutable five-resident SVG paths, background, waterbed and bubble sprites are reused. Indexed sphere, ribbon, ecology, water-disc and bubble geometry supplement missing GPU inputs. Historical V2.1 packages and prior failure reports remain intact.

The Android source includes WaterWorldStaticRenderer, explicit scene binding reader, verified texture loader, indexed mesh uploader, shared GLSL program, depth FBO and EGL pbuffer capture entry. Kotlin compilation against Android 36.1 succeeds. There is no Android Activity/instrumentation invocation yet; this is not a complete Android product integration or full project build.

Actual software EGL/GLES diagnostics use the same production GLSL and JSON geometry/material bindings, but a separate Python GL harness and texture decoder. This proves shared-input software GLES behavior only. Mesa llvmpipe / OpenGL ES 3.2 executes 32 draws; framebuffer complete, GL error 0. It is not hardware GPU or device acceptance.

Three bounded diagnostic rounds:

1. View-dependent glass alpha: RGB MAE 0.175407659 -> 0.176573167, regression.
2. Original-width ribbon edge alpha and crest: 0.176573167 -> 0.172468524, improvement.
3. Separate glass hemispheres: 0.172468524 -> 0.175349666, regression. Stop further visual tuning.

Final luminance SSIM: 0.449830290. Six-axis scores and total remain null; a calibrated six-axis scoring rule and independent passing visual review were not established. These image metrics cannot be converted into an 80-percent score.

Largest differences: glass is still a basic transparent shell, not refractive crystal; resident paths/material/depth do not match the master optical field; ecology/background/water textures and placements remain visibly different from the master. Missing passes: glass refraction, water normal distortion, caustics, bloom, explicit bokeh, resident front/back split. Existing source boundaries and overlap remain uncertain.

Source lock, mesh indices/numerics, texture SHA and software FBO/readback checks pass. Exact-HEAD CI runs software checks and intentionally fails the final readiness assertion while incomplete. No APK or animation was produced. Physical FOV and camera distance remain unresolved; source image orthographic camera and T=0 are fixed. Motion binding interfaces are partial; no Water World temporal values are claimed.

Remaining work requires implementing the declared missing render passes and validated layer relationships, full Android invocation/build regression, hardware/device capture, six-axis review and passing readiness gate. The current ZIP is a reproducible diagnostic package, not accepted Work 2 input.
