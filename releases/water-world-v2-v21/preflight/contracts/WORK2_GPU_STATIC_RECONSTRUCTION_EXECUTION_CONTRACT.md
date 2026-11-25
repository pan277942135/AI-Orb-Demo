# Work 2 GPU Static Reconstruction Execution Contract

## Source and readiness

- Frozen master `fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589` at immutable source commit `1662bbf9de671b0bb1fa5ce1891a1df3e49703b6`; 1254×1254 RGB PNG, no alpha/ICC.
- Original V2 archive `6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095`; 65 source files are preserved byte-for-byte.
- Current status `BLOCKED_GROUND_TRUTH_UNCERTAIN`. Ground Truth and object metrics block Work 2 readiness. GPU is `NOT_RUN`.

## Coordinates and camera

Canvas 1254×1254; top-left pixel-center origin; +X right; +Y down; UV=(x/1254,y/1254). Static projection is source-image-space orthographic. Sphere center/radius are in `01_ANALYSIS/master_sphere_geometry.json`. Physical FOV and camera distance are unresolved from one static image.

## Scene inputs

Read `03_SCENE_GRAPH/scene_graph_v2_1.json` for 14 unique objects, source SHA, master region, candidate transform/partial path, depth/material binding, confidence and evidence. Read `01_ANALYSIS/master_occlusion_graph.json` for known relations. Resident width error is unresolved; unmatched paths remain unclassified and are not presumed occluded. Master-derived cutouts are reference-only, never production albedo.

## Static render snapshot

Use source camera, 1254×1254 viewport, T=0, fixed asset hashes and no animation clock. Reuse one snapshot for all passes. Recommended layer order is background, back Residents, back glass, water, plants, rocks, fish, front Residents, front glass and bubbles; per-object uncertainty may require review. Report FBO completeness, GL error, GPU vendor/renderer/version and PNG SHA.

CPU recomposition and overlays are not GPU evidence. Do not claim GPU PASS. Frozen thresholds are unchanged. Do not begin production Work 2 geometry until Ground Truth and resource-fidelity blockers are resolved.
