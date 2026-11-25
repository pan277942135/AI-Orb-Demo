# WATER WORLD V2 Asset Audit Report

## Source integrity and parser result

- Original archive: WATER_WORLD_V2_ASSETS.zip
- Bytes: 11678323
- SHA256: 6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095
- Historical expected SHA256: 6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095
- Archive entries: 65
- Internal SHA256 entries verified: 63/63
- Raster decode: 35 files; errors 0
- JSON parse: 17 files; SVG XML parse: 1 files
- Parse/decode/checksum errors: 0
- The original ZIP remains unchanged.

A complete member-by-member audit was first streamed from the original ZIP. After temporary cache cleanup, the original archive was also physically extracted to `/workspace/WATER_WORLD_WORK/V2_EXTRACTED/full` (65 files, about 12 MB). The original ZIP was retained unchanged. Inventory is in asset_audit_ledger.csv.

## Category inventory

- 00_MASTER: 1
- 01_BACKGROUND: 1
- 02_RESIDENT: 2
- 03_SPHERE: 6
- 04_WATER: 5
- 05_FISH: 9
- 06_PLANTS: 3
- 07_ROCKS: 9
- 08_LIGHTING: 1
- 09_BUBBLES: 2
- 10_DEPTH: 10
- 11_MANIFEST: 12
- 12_PREVIEW: 2
- ROOT: 2

## Source-package issues and quality limits

- Source inventory declares four fish, five Resident paths, two plants, three rocks and nine bubble placements. Existing asset locations/geometry are estimates.
- Fish textures are straight-alpha candidates, but premultiplication is not declared. Mesh JSON exists, but separate tail/fin animation rig was not identified.
- Resident paths are SVG plus JSON curves; source metadata says curves are estimated, not traced from master pixels.
- Plants are single RGBA cutouts; leaves are not separately rigged.
- Sphere/cavity/glass masks and placements exist but encode the prior reconstruction, not verified master truth.
- Water bed, normal, relative depth and caustics maps exist; no time sequence exists.
- Nine bubble placements and a provisional motion estimate exist. These numeric values have not been validated against a temporal reference and are excluded from frozen motion parameters.

## Master gate

The user confirmed the inline image is the only frozen master. This execution does not have its underlying raw PNG bytes or downloadable file ID. Therefore dimensions, SHA256, ICC, alpha, object geometry, calibration, masks, overlay, heatmap and visual measurements are blocked. No V2 asset is recalibrated, repaired or regenerated without evidence.

Current decision counts: KEEP unchanged 65; RECALIBRATE 0; REPAIR 0; REGENERATE 0. KEEP means source-preserved, not visually accepted.
