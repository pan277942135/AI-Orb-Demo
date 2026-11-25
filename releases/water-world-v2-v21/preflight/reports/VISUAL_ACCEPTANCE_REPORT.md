# WATER WORLD V2.1 Canonical Visual Acceptance

**Overall:** `BLOCKED_GROUND_TRUTH_UNCERTAIN`. CPU-only measurement; GPU acceptance is NOT_RUN.
Metric method: `canonical_metrics.py`; 1254×1254 source-pixel masks, binary threshold 128, full-precision comparisons, confidence/completeness gating.

| Gate | Frozen threshold | Canonical measurement | Result |
|---|---:|---:|---|
| sphere_iou | ≥ 0.98 | `0.994769406` | PASS |
| sphere_center_error_over_diameter | ≤ 0.005 | `0.001046399` | PASS |
| sphere_diameter_error | ≤ 0.01 | `0.001334252` | PASS |
| fish_iou | ≥ 0.9 | `{"WW_FISH_01":0.9091985320232036,"WW_FISH_02":0.9051181102362205,"WW_FISH_03":0.8638277959756668,"WW_FISH_04":0.9056915039868023}` | FAIL |
| fish_center_error_over_canvas | ≤ 0.01 | `{"WW_FISH_01":0.0009287859996366278,"WW_FISH_02":0.0016849964175349938,"WW_FISH_03":0.000792379320307834,"WW_FISH_04":0.0009527293336069867}` | PASS |
| resident_bidirectional_chamfer_over_radius | ≤ 0.015 | `{"G01":0.018048433734203187,"G02":0.05499748668699491,"A01":0.0038609780021807037,"A02":0.014787653661809374,"C01":0.10278116483342134}` | FAIL |
| resident_width_error | ≤ 0.05 | `{"G01":null,"G02":null,"A01":null,"A02":null,"C01":null}` | FAIL |
| plant_iou | ≥ 0.85 | `{"plant_left":0.2284727171319679,"plant_right":0.2033919286952216}` | FAIL |
| rock_iou | ≥ 0.85 | `{"rock_01":0.3006602831177058,"rock_02":0.13450238282515153,"rock_03":0.20804153165394934}` | FAIL |

## Object confidence gates

| Object | Confidence | Status | Evidence |
|---|---|---|---|
| WW_FISH_01 | MEDIUM_HIGH | PASS | `03_STATIC_RECOMPOSITION/object_comparisons/WW_FISH_01.png` |
| WW_FISH_02 | MEDIUM_HIGH | PASS | `03_STATIC_RECOMPOSITION/object_comparisons/WW_FISH_02.png` |
| WW_FISH_03 | MEDIUM | BLOCKED_GROUND_TRUTH_UNCERTAIN | `03_STATIC_RECOMPOSITION/object_comparisons/WW_FISH_03.png` |
| WW_FISH_04 | MEDIUM | BLOCKED_GROUND_TRUTH_UNCERTAIN | `03_STATIC_RECOMPOSITION/object_comparisons/WW_FISH_04.png` |
| G01 | MEDIUM | BLOCKED_GROUND_TRUTH_UNCERTAIN | `03_STATIC_RECOMPOSITION/object_comparisons/G01.png` |
| G02 | MEDIUM | BLOCKED_GROUND_TRUTH_UNCERTAIN | `03_STATIC_RECOMPOSITION/object_comparisons/G02.png` |
| A01 | MEDIUM | BLOCKED_GROUND_TRUTH_UNCERTAIN | `03_STATIC_RECOMPOSITION/object_comparisons/A01.png` |
| A02 | MEDIUM | BLOCKED_GROUND_TRUTH_UNCERTAIN | `03_STATIC_RECOMPOSITION/object_comparisons/A02.png` |
| C01 | LOW | BLOCKED_GROUND_TRUTH_UNCERTAIN | `03_STATIC_RECOMPOSITION/object_comparisons/C01.png` |
| plant_left | LOW | BLOCKED_GROUND_TRUTH_UNCERTAIN | `03_STATIC_RECOMPOSITION/object_comparisons/plant_left.png` |
| plant_right | MEDIUM_LOW | BLOCKED_GROUND_TRUTH_UNCERTAIN | `03_STATIC_RECOMPOSITION/object_comparisons/plant_right.png` |
| rock_01 | LOW | BLOCKED_GROUND_TRUTH_UNCERTAIN | `03_STATIC_RECOMPOSITION/object_comparisons/rock_01.png` |
| rock_02 | MEDIUM_LOW | BLOCKED_GROUND_TRUTH_UNCERTAIN | `03_STATIC_RECOMPOSITION/object_comparisons/rock_02.png` |
| rock_03 | LOW | BLOCKED_GROUND_TRUTH_UNCERTAIN | `03_STATIC_RECOMPOSITION/object_comparisons/rock_03.png` |

A numeric threshold PASS does not override incomplete or low-confidence Ground Truth. The master is comparison-only and was not used as a composition layer.
