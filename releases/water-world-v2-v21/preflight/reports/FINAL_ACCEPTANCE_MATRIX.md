# Work 1.5 Acceptance Matrix

| Gate | Status | Evidence |
|---|---|---|
| V2 archive source SHA and members | PASS | asset_audit_report.md and validation_manifest.json
| V2 internal checksums / decodes | PASS | 63/63 hashes; all image/JSON/SVG parsers passed
| Physical extraction to disk | BLOCKED_CAPACITY | free disk about 2 MiB vs 11.7 MB archive; streamed audit used
| Raw master source metadata/pixels | BLOCKED_MASTER_SOURCE | user confirmed inline image, but raw file bytes/attachment ID unavailable in execution workspace
| V2.1 calibration and recomposition | NOT_RUN_BLOCKED_MASTER_SOURCE | no source-derived calibration, preview, overlay or heatmap generated
| AI Orb 35 WebP asset audit | PASS | manifest hashes, dimensions and RGBA decoding inspected
| Motion schema/dependency validation | PASS_WITH_GAPS | identities/contracts ready; assets and temporal references partial
| GPU static/dynamic input contracts | PASS_PRELIMINARY | contracts authored; final measurements and device formats await master/Work 2
| Static Resource Readiness | FAIL / BLOCKED | gate requires raw master and visual acceptance
| Motion Readiness | PARTIAL | schema ready; motion assets/references/parameters not fully ready
| GPU Input Contract Readiness | PARTIAL | contract structure ready, but static gate is not passed
| Work 2 start | BLOCKED | do not auto-start; wait for raw source and user instruction
