# WATER WORLD V2.1 Repair and Work 2 Static Input Readiness Report

**Status:** `BLOCKED_GROUND_TRUTH_UNCERTAIN`  
**Master:** `fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589` verified, 1254×1254 RGB, 2,256,234 bytes. **GPU:** NOT_RUN.

## Verified causes and repairs

The earlier fish color threshold omitted cool, translucent fins. A documented seeded GrabCut mask and deterministic rigid-transform fitting were applied; original V2 fish PNG bytes remain unchanged. The prior Resident scoring path measured a master-derived trace against itself. The independent composition now uses original V2 SVG curves, while direct source trace evidence is stored separately. Resident width cross-sections are contaminated by same-color crossings and optical highlights; width error is null and fails the gate. Plant/rock boundaries overlap reflection, glass, moss and contact shadows, so their resource defects remain hypotheses rather than confirmed causes.

Round 2 F03 optimizer objective: 0.866947 → 0.868489 (+0.001542), threshold 0.90; optimizer did not converge. Canonical CPU composition: 0.863860 → 0.863828 (-0.000032). The optimizer gain was not reproduced by the independent compositor; no third blind fit was applied to uncertain Ground Truth.

## Per-object root-cause matrix

| Object | Root cause class | Root cause / fix | Source SHA | Prior same metric | Current | Threshold | Status |
|---|---|---|---|---:|---:|---:|---|
| WW_FISH_01 | SOURCE_CONFIRMED | SOURCE_CONFIRMED: the prior box/transform did not match the full visible fins; rigid transform only was fitted. F03 remains below threshold and medium confidence. / Recalibrate only the source transform for fish; keep other original textures unchanged and wait for reliable masks. | `056b55e6f61fb353b8db2fa8631176a4caca330f2f1d5460eba9a7a72d8cc134` | 0.9091985320232036 | 0.9091985320232036 | 0.9 | PASS |
| WW_FISH_02 | SOURCE_CONFIRMED | SOURCE_CONFIRMED: the prior box/transform did not match the full visible fins; rigid transform only was fitted. F03 remains below threshold and medium confidence. / Recalibrate only the source transform for fish; keep other original textures unchanged and wait for reliable masks. | `565b78326a259484fb78a542cb7c2f0ce8cf831eb080c28b1bf93a469615bbd8` | 0.9051181102362205 | 0.9051181102362205 | 0.9 | PASS |
| WW_FISH_03 | SOURCE_CONFIRMED | SOURCE_CONFIRMED: the prior box/transform did not match the full visible fins; rigid transform only was fitted. F03 remains below threshold and medium confidence. / Recalibrate only the source transform for fish; keep other original textures unchanged and wait for reliable masks. | `c8def6b8d5e66463b903a2fa329f2e1f354067598931233b0e29c966d32ef6d1` | 0.863859649122807 | 0.8638277959756668 | 0.9 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| WW_FISH_04 | SOURCE_CONFIRMED | SOURCE_CONFIRMED: the prior box/transform did not match the full visible fins; rigid transform only was fitted. F03 remains below threshold and medium confidence. / Recalibrate only the source transform for fish; keep other original textures unchanged and wait for reliable masks. | `714a0afa95e6c3d802044bbb745334875bd79640960b35ee40bc4125a2d3b44d` | 0.9056915039868023 | 0.9056915039868023 | 0.9 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| G01 | SOURCE_CONFIRMED | SOURCE_CONFIRMED: prior composition reused master-traced geometry for scoring. Independent V2 SVG paths now drive composition; direct source coverage and identity/width ambiguity remain. / Recalibrate only the source transform for fish; keep other original textures unchanged and wait for reliable masks. | `ee8b1285b3dbcde4d4d06232779904c1e50d30ce92ee5d0ecfd7201983c5c73b` | 0.018048433734203187 | 0.018048433734203187 | 0.015 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| G02 | SOURCE_CONFIRMED | SOURCE_CONFIRMED: prior composition reused master-traced geometry for scoring. Independent V2 SVG paths now drive composition; direct source coverage and identity/width ambiguity remain. / Recalibrate only the source transform for fish; keep other original textures unchanged and wait for reliable masks. | `ee8b1285b3dbcde4d4d06232779904c1e50d30ce92ee5d0ecfd7201983c5c73b` | 0.05499748668699491 | 0.05499748668699491 | 0.015 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| A01 | SOURCE_CONFIRMED | SOURCE_CONFIRMED: prior composition reused master-traced geometry for scoring. Independent V2 SVG paths now drive composition; direct source coverage and identity/width ambiguity remain. / Recalibrate only the source transform for fish; keep other original textures unchanged and wait for reliable masks. | `ee8b1285b3dbcde4d4d06232779904c1e50d30ce92ee5d0ecfd7201983c5c73b` | 0.0038609780021807037 | 0.0038609780021807037 | 0.015 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| A02 | SOURCE_CONFIRMED | SOURCE_CONFIRMED: prior composition reused master-traced geometry for scoring. Independent V2 SVG paths now drive composition; direct source coverage and identity/width ambiguity remain. / Recalibrate only the source transform for fish; keep other original textures unchanged and wait for reliable masks. | `ee8b1285b3dbcde4d4d06232779904c1e50d30ce92ee5d0ecfd7201983c5c73b` | 0.014787653661809374 | 0.014787653661809374 | 0.015 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| C01 | SOURCE_CONFIRMED | SOURCE_CONFIRMED: prior composition reused master-traced geometry for scoring. Independent V2 SVG paths now drive composition; direct source coverage and identity/width ambiguity remain. / Recalibrate only the source transform for fish; keep other original textures unchanged and wait for reliable masks. | `ee8b1285b3dbcde4d4d06232779904c1e50d30ce92ee5d0ecfd7201983c5c73b` | 0.10278116483342134 | 0.10278116483342134 | 0.015 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| plant_left | HYPOTHESIS | HYPOTHESIS: V2 placement/shape mismatch is possible, but transparent leaf edges overlap glass and Resident light; source boundary not conclusive. / Recalibrate only the source transform for fish; keep other original textures unchanged and wait for reliable masks. | `bc958efc305204b0d35c2675645dd7ebac3d1da97ae93362c8532855e5076998` | 0.2284727171319679 | 0.2284727171319679 | 0.85 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| plant_right | HYPOTHESIS | HYPOTHESIS: V2 placement/shape mismatch is possible, but transparent leaf edges overlap glass and Resident light; source boundary not conclusive. / Recalibrate only the source transform for fish; keep other original textures unchanged and wait for reliable masks. | `f0437ca0643b5c8d6e1b25a9117390a66764bf80e8beea206e082b0c5732a172` | 0.2033919286952216 | 0.2033919286952216 | 0.85 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| rock_01 | HYPOTHESIS | HYPOTHESIS: V2 placement/shape mismatch is possible, but rock edges overlap moss, plants, highlights and contact shadows; source boundary not conclusive. / Recalibrate only the source transform for fish; keep other original textures unchanged and wait for reliable masks. | `0128e55605b0063450d46abbbac06d2b16976faa17dacb1657b53c8b538e1986` | 0.3006602831177058 | 0.3006602831177058 | 0.85 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| rock_02 | HYPOTHESIS | HYPOTHESIS: V2 placement/shape mismatch is possible, but rock edges overlap moss, plants, highlights and contact shadows; source boundary not conclusive. / Recalibrate only the source transform for fish; keep other original textures unchanged and wait for reliable masks. | `50238eb18b944dfdc2a1af7dbe577dde02f8a03f3220686d2cb5a43487a37315` | 0.13450238282515153 | 0.13450238282515153 | 0.85 | BLOCKED_GROUND_TRUTH_UNCERTAIN |
| rock_03 | HYPOTHESIS | HYPOTHESIS: V2 placement/shape mismatch is possible, but rock edges overlap moss, plants, highlights and contact shadows; source boundary not conclusive. / Recalibrate only the source transform for fish; keep other original textures unchanged and wait for reliable masks. | `5d6a78e23b1ee4e96c0acb8894e2876205b601aceb4d1ef22e3fac8458e94a99` | 0.20804153165394934 | 0.20804153165394934 | 0.85 | BLOCKED_GROUND_TRUTH_UNCERTAIN |

## Work 2 static inputs

The scene graph carries 14 IDs, V2 asset paths and SHA values, master regions, candidate transforms or partial paths, confidence, depth/material and mask references. Resident centerlines, width profiles, identity and occlusion uncertainty are separate files. Camera is 1254×1254 image-space orthographic; physical FOV/distance remain unresolved. Motion graph remains 47 nodes / 28 edges and all Water World temporal numeric parameters are null. The 32 WebP slices are spatial Z samples, not time frames.

Independent CPU recomposition, overlay, difference heatmap, region comparison and 14 per-object comparisons are included. They are not GPU evidence. `WORK2_STATIC_INPUT_READY` is not satisfied.

## Remaining blockers

- Fish F03 IoU remains below 0.90; F03/F04 Ground Truth confidence is MEDIUM.
- Resident G01/G02/A02/C01 direct coverage is below 90%; A01 identity remains ambiguous. Width gates are unresolved for all five.
- Plant/rock boundaries cannot yet be separated confidently from optical effects and overlaps.
- GPU renderer, animation, API36 and APK are outside this work and remain NOT_RUN.

## Current status

`BLOCKED_GROUND_TRUTH_UNCERTAIN`. Source available and verified; object fidelity/measurement is still blocked. No GPU PASS or Work 2 readiness is claimed.


## Automated validation

Local automated checks: **PASS**  
Exact-HEAD GitHub CI: **PENDING_EXACT_HEAD_CI**  
Visual acceptance: **BLOCKED_GROUND_TRUTH_UNCERTAIN**

| Check | Result | Count / detail |
|---|---|---|
| legacy_preflight_unit_tests | PASS | 22 tests; exit 0 |
| closure_unit_tests | PASS | 21 tests; exit 0 |
| python_compile | PASS | None tests; exit 0 |
| Master source | PASS | `fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589`; 2256234 bytes |
| Original V2 ZIP | PASS | `6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095`; 65 files; CRC PASS |
| V2 source copy | PASS | 63/63 embedded SHA entries; 65 files |
| Motion Graph | PASS | 47 nodes / 28 dependencies |
| CPU recomposition reproducibility | PASS | 6 SHA-256 outputs compared between clean runs |

These are local source/validator checks. They do not establish GPU or visual acceptance. The frozen visual gates remain independently evaluated by `visual_metrics.json`.
