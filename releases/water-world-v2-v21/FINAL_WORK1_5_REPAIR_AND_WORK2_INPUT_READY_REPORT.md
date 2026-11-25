# WATER WORLD V2.1 — Work 1.5 Repair and Work 2 Input Status

**Final status: `BLOCKED_GROUND_TRUTH_UNCERTAIN` / `WORK1_5_VALIDATION_FAILED`.** Master lock, static resources, contracts and package integrity are verified. Frozen visual acceptance failed; `WORK2_STATIC_INPUT_READY` is not claimed.

## Source lock and package

- Master: `releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png` at immutable source commit `1662bbf9de671b0bb1fa5ce1891a1df3e49703b6`.
- Master SHA-256: `fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589`; 2,256,234 bytes; 1254×1254 RGB; no alpha/ICC.
- Original V2 archive SHA-256: `6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095`; 65 source files preserved and 63 embedded hashes verified.
- Final package: `WATER_WORLD_V2_1_ASSETS.zip`; 65,409,370 bytes; SHA-256 `b5bf75bc7f8b9b0b6d377272887238a4fc142e8dc5acb7278da74e336ad1a466`; 384 archive members; CRC and 382 internal SHA entries verified.
- The master remains comparison-only; CPU composition uses independent V2 source assets.

## Confirmed causes and repairs

- Fish measurement: a warm-color-only mask excluded cool, translucent fins. Ground Truth now records seeded GrabCut masks and confidence, and fish transforms were fitted without changing the 65 original V2 files. F03 is still below threshold.
- Resident measurement: the former scoring path reused master-derived traced curves in its own composition. The revised CPU compositor uses the original V2 Resident SVG paths. Direct-source coverage is still below 90% for four residents, identities cross at overlaps, and width error is left null rather than inferred.
- Plant/rock boundaries overlap glass, highlights, moss, foliage and contact shadows. Their asset defects remain hypotheses; those original assets are kept unchanged pending reliable segmentation.
- F03 optimization round 2 optimizer objective: `0.866947 → `0.868489` (+0.001542). Canonical compositor IoU: `0.863860 → `0.863828` (-0.000032). The optimizer improvement did not reproduce in the canonical renderer; no third blind fit was applied.

## Frozen visual gates

| Object / gate | Measurement | Frozen target | Result |
|---|---:|---:|---|
| Sphere IoU | 0.994769406 | ≥ 0.98 | PASS |
| Sphere center / diameter | 0.001046399 | ≤ 0.005 | PASS |
| Sphere diameter error | 0.001334252 | ≤ 0.01 | PASS |
| WW_FISH_01 IoU / center error | 0.909198532 / 0.000928786 | IoU ≥0.90 / center ≤0.01 | PASS |
| WW_FISH_02 IoU / center error | 0.905118110 / 0.001684996 | IoU ≥0.90 / center ≤0.01 | PASS |
| WW_FISH_03 IoU / center error | 0.863827796 / 0.000792379 | IoU ≥0.90 / center ≤0.01 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| WW_FISH_04 IoU / center error | 0.905691504 / 0.000952729 | IoU ≥0.90 / center ≤0.01 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| Resident G01 Chamfer / width error / direct coverage | 0.018048434 / None / 0.759091 | ≤0.015 / ≤0.05 / direct coverage ≥0.90 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| Resident G02 Chamfer / width error / direct coverage | 0.054997487 / None / 0.618182 | ≤0.015 / ≤0.05 / direct coverage ≥0.90 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| Resident A01 Chamfer / width error / direct coverage | 0.003860978 / None / 0.927273 | ≤0.015 / ≤0.05 / direct coverage ≥0.90 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| Resident A02 Chamfer / width error / direct coverage | 0.014787654 / None / 0.700000 | ≤0.015 / ≤0.05 / direct coverage ≥0.90 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| Resident C01 Chamfer / width error / direct coverage | 0.102781165 / None / 0.300000 | ≤0.015 / ≤0.05 / direct coverage ≥0.90 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| plant_left IoU | 0.228472717 | ≥0.85 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| plant_right IoU | 0.203391929 | ≥0.85 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| rock_01 IoU | 0.300660283 | ≥0.85 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| rock_02 IoU | 0.134502383 | ≥0.85 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| rock_03 IoU | 0.208041532 | ≥0.85 | BLOCKED_GROUND_TRUTH_UNCERTAIN |

All metrics use `canonical_metrics.py`, full precision, common 1254×1254 coordinates, and alpha-mask threshold 128. Low/incomplete Ground Truth cannot pass based on a numeric score alone.

## Static evidence and automation

- CPU recomposition reproducibility: **PASS**, 6 output SHA comparisons; all matched.
- Historical preflight suite: **22/22 PASS** (21 original checks retained plus one ZIP-manifest path regression).
- New closure suite: **21/21 PASS**; Python compile PASS.
- Motion Graph: **47 nodes / 28 dependencies PASS**, temporal values remain null.
- Original V2 archive and source copy SHA: **PASS**; source copy 65 files / 63 embedded SHA entries.
- New strict closure validator: package integrity **PASS**, visual acceptance **FAIL**, exit 20.
- Legacy strict closure validator: master **MASTER_SOURCE_READY**, final ZIP integrity PASS, visual metrics FAIL, exit 20.
- GitHub Exact-HEAD CI is triggered by the final delivery commit; its run and conclusion will be recorded in PR #3. No GPU, animation or device run is included.

## Evidence files

- `static_recomposition.png`, `master_reference.png`, `master_overlay.png`, `difference_heatmap.png`, `region_comparison.png`
- `object_comparisons/` contains 14 object-level crops.
- `visual_metrics.json`, `validation_manifest.json`, `legacy_closure_validation.json`, `validator_test_results.json`, `recomposition_reproducibility.json`.
- Full reproducible contracts, Ground Truth masks, source copies and scripts are in the ZIP.

## Remaining blockers

- F03 fish Ground Truth/IoU remains under the 0.90 gate; F04 has unresolved source confidence despite numeric IoU over threshold.
- Resident direct-source coverage/identity and all five width measurements remain unresolved; width remains null.
- Plant and rock complete/visible boundaries remain low confidence.
- Therefore `WORK2_STATIC_INPUT_READY` and CLOSED statuses are not valid. GPU static-input execution, dynamics and APK are outside this task.

## GitHub

- Branch: `assets/water-world-v2-v21-calibration`; PR #3 remains Open/Draft.
- Package download (branch URL): [Download WATER_WORLD_V2_1_ASSETS.zip](https://raw.githubusercontent.com/pan277942135/AI-Orb-Demo/assets/water-world-v2-v21-calibration/releases/water-world-v2-v21/delivery/WATER_WORLD_V2_1_ASSETS.zip).
- PR: https://github.com/pan277942135/AI-Orb-Demo/pull/3. Exact final commit SHA and CI URL are recorded in the updated PR description.
