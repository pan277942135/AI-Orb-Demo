# Final acceptance matrix

| Gate | State | Evidence / limit |
|---|---|---|
| User source lock | PASS | Inline uploaded image is the only visual reference; source checksum was not requested or computed. |
| 4 independent fish | PASS | RGBA cutouts, alpha masks, UV meshes, and estimated placements are included. |
| 5 Resident curves | PASS | Separate G01/G02/A01/A02/C01 IDs in SVG and JSON; path positions are estimates. |
| Background and water | PASS | Generated full-bleed background, sphere interior water plate, caustic, normal, and relative depth maps. |
| Sphere / cavity / glass layers | PASS | Reconstructed ellipse masks and front/back arc masks; not pixel-traced. |
| 2 plants / 3 rocks | PASS | Independent generated cutouts, alpha masks, and estimated placements. |
| Lighting / bubbles | PASS | Relative lighting rig, bubble sprite, placement and motion estimates. |
| Scene Graph and manifest | PASS | JSON graph, inventory, generated-asset checksums. |
| Asset file validation | PASS | Raster decode, JSON parse, alpha, counts, map depth, and mesh-index checks passed. |
| Source pixel comparison | NOT_RUN | User image was inline only; no local source bytes for overlays/difference metrics. |
| GPU / Android / APK | NOT_IN_SCOPE | This work produces assets only. |
| Final visual acceptance | NOT_ACCEPTED | Static reconstruction preview is an approximate layout review, not fidelity acceptance. |

`PASS` here means the asset package itself passed file and structure checks. It does not imply visual equivalence to the master.
