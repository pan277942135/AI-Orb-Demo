# V2.1 Validator Test Report

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
