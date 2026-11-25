# WATER WORLD V2.1 — Static Calibration Package Report

**Status:** `BLOCKED_GROUND_TRUTH_UNCERTAIN` / `WORK1_5_VALIDATION_FAILED`. The frozen source, static-input contracts and package integrity pass; frozen per-object visual gates do not. `WORK2_STATIC_INPUT_READY` is not claimed.

## Frozen inputs

- Master PNG: 2,256,234 bytes, 1254×1254 RGB, SHA-256 `fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589`.
- Original V2 archive: SHA-256 `6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095`; 65 source files remain byte-identical.
- V2.1 ZIP: 65,417,819 bytes; SHA-256 `28f8918c39f6e875602d3a7e90775e9d455aa6c4e42783247d1104c3b4509679`; 384 members, 382 internal SHA entries, CRC PASS.

## Three quantified rounds

1. Fin-aware fish masks and transform fitting raised F01/F02/F04 numeric IoU above threshold; F03 reached 0.863859649. Resident identity/width and plant/rock boundary confidence remained limited.
2. F03 optimizer objective rose 0.866946978 → 0.868488671, but canonical composition changed 0.863859649 → 0.863827796 (-0.000031853).
3. Differential evolution (seed 2026, maxiter 180, 3,681 evaluations) converged to the prior transform and objective; canonical F03 remained 0.863827796 (change 0). The source asset was unchanged. No further fitting was performed after three rounds.

## Frozen visual results

- Sphere: IoU 0.994769406, center/D 0.001046400, diameter error 0.001334252: PASS.
- Fish IoU: F01 0.909198532 PASS; F02 0.905118110 PASS; F03 0.863827796 FAIL; F04 0.905691504 numeric PASS but confidence-blocked.
- Resident Chamfer/R: G01 0.018048434, G02 0.054997487, A01 0.003860978, A02 0.014787654, C01 0.102781165 (threshold ≤0.015); width error is unmeasured for all five.
- Plant IoU: 0.228472717 / 0.203391929; rock IoU: 0.300660283 / 0.134502383 / 0.208041532. Source boundaries remain uncertain; no defect is inferred from low-confidence masks.

## Verification

- Local suites: preflight 22/22 PASS; closure 21/21 PASS; Python compile PASS (43 unit tests total).
- V2 source archive: CRC PASS, 63/63 embedded SHA PASS; copied source files 65/65 unchanged.
- Motion Graph: 47 nodes / 28 dependencies PASS. CPU recomposition is reproducible across six output SHA values.
- Closure validator: source, scene/object contracts, masks, source hashes, Motion Graph, stale-contract check, canonical consistency, and final ZIP integrity PASS; frozen visual gates FAIL (exit 20).
- Static preflight: PASS. Exact-HEAD GitHub CI is triggered by the delivery commit and reported on PR #3.
- GPU, dynamic animation and Work 2 readiness: NOT_RUN / NOT_READY.
