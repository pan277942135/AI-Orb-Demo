# Water World V2.1 Work 1.6 — partial closure, not Work 2 ready

**Status: `WORK1_6_BLOCKED_MULTIPLE_GAPS`.** This delivery contains verified source reconciliation, three-target software GLES evidence and Android preview source. It does not meet the requested 11-stage optical pipeline, 80% visual acceptance or hardware GPU validation.

## Five-round record

| Round | Root cause / work | Result |
|---|---|---|
| 1 | Historical ground truth contained 12 uncertain objects; `behind`/`in_front_of` direction was ambiguous. Reconciled all 14 source hashes and preserved UNKNOWN segments. | Source PASS; geometry and occlusion BLOCKED. Commit `fae1e7fb87944b73d47f61a67c52fceadf11a429`. |
| 2 | One scene FBO and no final screen presentation. Added independent background, scene and final targets; executed 3 passes in software GLES. | 32 draws, 3 FBOs complete, GL error 0; final PNG byte-identical to prior baseline. Commit `930c7e2` plus source-path correction `50909c8`. 11-stage contract still incomplete. |
| 3 | Glass refraction, water distortion, caustics, Bloom and bokeh remain absent. | NOT RUN; no optical success claimed. |
| 4 | APK had no independent Android preview and no build-time source asset staging. Added debug GLSurfaceView entry, default framebuffer blit, three-pass capture, frozen resource packaging and duplicate texture reuse. | Kotlin-only compile and source plan PASS; full Android build and runtime evidence pending CI/device. Initial CI failures were diagnosed and repaired in follow-up commits. |
| 5 | Visual convergence requires the complete optical and occlusion pipeline. | NOT RUN; six-dimensional score not evaluated. |

## Verified diagnostic evidence

- Frozen master SHA256 `fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589`, 1254×1254 RGB.
- Original 65-member V2 ZIP SHA256 `6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095`.
- Three-target software GLES: Mesa llvmpipe ES 3.2, 32 draws, no GL error. It is **not** Android hardware GPU evidence.
- Final PNG SHA256 `798f825b237377d9a177a43a7d70ff5bc07d46cff1eb005b76b8125e3834ca40`, identical to Work 1.5. RGB MAE `0.1753496661`, luminance SSIM `0.4498302898`. Neither changed. Six-dimensional score remains null.
- `WORK2_STATIC_INPUT_READY.json` remains false.

## Unresolved work

1. Resolve 12 uncertain object masks and five Resident identities/widths from independent master evidence. Do not promote unclassified samples into confirmed occlusion.
2. Establish canonical front/back edges, then split Resident geometry into distinct confirmed front and back draws. Existing 10 subpath meshes are not a front/back split.
3. Implement and execute the remaining isolation and optical passes. Current three-target structure is a foundation, not the requested 11-pass renderer.
4. Obtain Android build/install/launch/capture evidence. Source compilation alone does not establish runtime success.
5. Obtain hardware GPU evidence and calibrate the frozen six-dimensional visual gate against the sole master. Do not assign an 80% score from SSIM/MAE alone.

The original V2 archive, master PNG, historical V2.1 ZIP and Work 1.5 diagnostic ZIP remain unchanged. PR #3 remains Draft; no Main merge is requested.
