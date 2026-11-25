# Work 1.5 Acceptance Matrix

| Gate | Status | Evidence |
|---|---|---|
| V2 archive SHA, ZIP CRC, and member count | PASS | 11,678,323 bytes; expected SHA; CRC valid; 65 entries |
| V2 embedded checksums and decoding | PASS | 63/63 hashes; 35 raster images decoded; JSON/SVG parse passed |
| Physical extraction | PASS | 65 files extracted; about 12 MB; original ZIP unchanged |
| Sole master identity | PASS | User confirmed the currently uploaded `奇幻水晶球中的水下生态世界.png` as the only master |
| Raw master PNG bytes/metadata | BLOCKED_MASTER_SOURCE | No readable PNG bytes or attachment handle is mounted for pixel processing |
| Validator regression suite | PASS | 21/21 unit tests pass |
| Validator Preflight | PASS | Real package run: `PREFLIGHT_PASS`, exit 0 |
| Validator full closure | BLOCKED_MASTER_SOURCE | Real package run exits 10; no master pixels or final visual artifacts are fabricated |
| Motion registry and graph | PASS | 47 unique IDs/nodes, 28 edges; node sets match; no duplicate/dangling/cyclic dependencies |
| Core input/output contract | PASS | Required dependency outputs match destination runtime input tokens |
| AI Orb 35 WebP source audit | PASS | All manifest hashes, dimensions and RGBA decoding pass |
| V2.1 visual calibration/recomposition | NOT_RUN_BLOCKED_MASTER_SOURCE | Requires source pixel bytes |
| GPU renderer/API36 evidence | NOT_RUN | This task segment performs no GPU rendering or Android runtime implementation |
| Final calibrated V2.1 ZIP | BLOCKED_MASTER_SOURCE | No calibrated asset package was created |
| Work 2 start | BLOCKED | Source-derived static reconstruction remains incomplete |
