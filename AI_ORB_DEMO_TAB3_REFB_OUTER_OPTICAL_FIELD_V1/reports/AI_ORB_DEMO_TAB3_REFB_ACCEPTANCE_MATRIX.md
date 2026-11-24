# AI Orb Demo Tab 3 REF_B Acceptance Matrix

Statuses describe evidence for the exact built HEAD. Source completion, software AVD output, hardware GPU validation, and visual acceptance remain separate gates.

| Gate | Status | Evidence |
|---|---|---|
| REF_B source SHA | PASS | `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122` |
| P0-A extraction and frozen masks | PASS | `ref_b_extraction/P0_A_PREFLIGHT_PASS.json`; frozen 13-region / 12-mask contract verified |
| Runtime manifest | PASS | `OUTER_OPTICAL_FIELD_RUNTIME_V1.json`; source-derived digest checked by CI |
| Tab 3 source implementation | PENDING_EXACT_HEAD_CI | API36 instrumentation is the linked-program and runtime-state gate |
| Tab 1 / Tab 2 isolation | PENDING_API36_REGRESSION | Compare the frozen Tab 1/2 tests and AVD evidence on the final HEAD |
| GLES 3.0 compile/link | PENDING_EXACT_HEAD_CI | Production shader validator includes both new Tab 3 shader programs |
| API36 AVD render | PENDING_API36_AVD | The CI emulator is software GLES (SwiftShader), not a hardware GPU |
| Round 1 measurable improvement | PENDING_API36_CAPTURE | Must satisfy ≥5% improvement, >3× repeat noise, and ≤2% per-region regression |
| Round 2 | NOT_RUN | Eligible only after a source-backed Round 1 correction |
| Round 3 | NOT_RUN | Eligible only after a source-backed Round 2 correction |
| Hardware GPU render | NOT_RUN | API36 AVD software rendering cannot satisfy this gate |
| Visual fidelity accepted | NOT_ACCEPTED | Requires the frozen ROI review and human visual review; build success alone is insufficient |
| Exact-HEAD APK | PENDING_EXACT_HEAD_CI | APK fingerprint and digest are emitted by the CI workflow |
| User device QA | PENDING_USER_DEVICE_QA | HUAWEI CMS-AL20 / Android 16 final installation and review |
