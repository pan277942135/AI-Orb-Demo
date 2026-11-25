# Work 2 GPU Static Reconstruction Execution Contract

## Frozen inputs

- Sole master identity: 奇幻水晶球中的水下生态世界.png, confirmed by user as the inline upload. Raw bytes unavailable here, therefore SHA, dimensions, color profile and pixel masks remain BLOCKED_MASTER_SOURCE. Work 2 must obtain the raw file before pixel calibration or visual acceptance.
- V2 archive: releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip; 11,678,323 bytes; SHA256 6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095. Internal 63 checksums pass.
- V2.1 calibrated package: NOT BUILT. No assets were calibrated without the raw master.

## Inherited V2 mappings

Four fish use 05_FISH/fish_01.png through fish_04.png with masks and mesh JSON; placements are estimates. Five Residents G01/G02/A01/A02/C01 share residents.svg with independent curves in residents.json; curve confidence is estimated. Sphere/cavity/back/front glass masks are under 03_SPHERE. Water color, normal, relative depth and caustics are under 04_WATER. Plants are two RGBA cutouts, rocks three individual RGBA images, lighting has lighting_rig.json, and bubbles use bubble_sprite.png plus nine estimated placements. No item is declared visually calibrated.

## Execution order

1. Verify raw master source and freeze its hash, dimensions, ICC and alpha. 2. Extract regions and geometry using fixed thresholds. 3. Reuse V2 assets by KEEP/RECALIBRATE/REPAIR/REGENERATE in that order. 4. Fit scene graph and transforms with no master pixels embedded in production assets. 5. Generate CPU static preview from independent V2 assets only. 6. Compute requested overlays and metrics using the same normalization and fixed regions. 7. Freeze exact camera and GPU inputs. 8. Implement a separate WaterWorldStaticRenderer; do not alter AI Orb/photo-water production paths.

## Static acceptance targets

Initial targets from Work 1.5 are sphere IoU >=0.98, center error <=0.5% sphere diameter, diameter error <=1%, fish IoU >=0.90 and center error <=1% canvas, resident bidirectional Chamfer <=0.015R and width error <=5%, plant and rock IoU >=0.85. Targets are provisional until masks/regions are measured from raw master bytes. No target passes in this preflight.

## Rendering/evidence requirements

Static pass order and diagnostics are frozen in sibling contracts. Work 2 must use a fixed camera, T=0, same viewport, asset hashes and shader build. Capture FBO completeness, GL errors, vendor/renderer/version, dimensions and PNG SHA. CPU composites and hardware GPU captures must be distinguished. API36 device evidence, performance and APK checks are required before a runtime acceptance claim.
