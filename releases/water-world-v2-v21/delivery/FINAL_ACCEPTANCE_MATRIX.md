# V2.1 Work 1.5 Acceptance Matrix

| Gate | Status | Evidence |
|---|---|---|
| Exact master bytes/metadata | PASS | `00_MASTER_METADATA/master_source_validation.json` |
| Prior V2 source ZIP integrity | CARRIED_FORWARD_PASS | unchanged SHA; 65 files; 63/63 embedded checksums |
| Prior Validator regression suite | CARRIED_FORWARD_PASS | 21/21 PASS, not rerun per request |
| Prior Motion Graph | CARRIED_FORWARD_PASS | 47 nodes / 28 dependencies, not rerun per request |
| Source-derived geometry | PASS_WITH_CONFIDENCE_NOTES | `01_ANALYSIS/master_*_geometry.json` |
| Independent static recomposition | PASS | CPU V2 asset composition; not GPU |
| Sphere hard gates | PASS | `03_STATIC_RECOMPOSITION/visual_metrics.json` |
| Fish silhouette IoU | FAIL | measured values below 0.90 |
| Fish center | PASS | measured values at or below 0.01 |
| Resident Chamfer | FAIL | C01 exceeds 0.015R |
| Resident width error | PASS | all at or below 0.05 |
| Plant IoU | FAIL | both below 0.85 |
| Rock IoU | FAIL | all below 0.85 |
| Full static visual acceptance | FAIL | no thresholds changed |
| Motion fidelity / GPU renderer | NOT_RUN | static work only; temporal/GPU evidence not claimed |
| V2.1 complete ZIP | PASS | `08_MANIFEST/sha256_manifest.txt`; ZIP CRC and all manifest members verified |
