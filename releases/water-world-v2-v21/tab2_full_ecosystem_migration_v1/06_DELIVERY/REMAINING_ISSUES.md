# REMAINING_ISSUES

**Task ID:** `TAB2_WATER_WORLD_FULL_ECOSYSTEM_MIGRATION_V1`

## 1. Hardware GPU & On-Device Runtime Verification (`NOT_RUN` in AI Studio Container)

- `GLES_SHADER_PASS = NOT_RUN` — AI Studio build container has no physical Android GPU or EGL/GLES 3.0 driver; shader link & runtime execution on HUAWEI Maleoon 935 (CMS-AL20 / Android 16 / API 36) must be verified using the Exact-HEAD GitHub CI Debug APK.
- `HARDWARE_GPU_VERIFIED = NOT_RUN` — Real-device visual acceptance on Tab 1 (`AI Orb`), Tab 2 (`照片水光` with new frozen underwater PNG + full dynamic Water World sphere + independent photo perimeter light), and Tab 3 (`水下世界`) remains to be performed by the user on physical hardware.

## 2. Pre-Existing Unrelated Unit Test Failure (Inherited from Base Main HEAD)

- `com.example.AIOrbV32ReferenceFittingTest.v32_04_stageACameraAndStageEOpticsHardGatesPassInVisualLoss` — Pre-existing failure already present on Base Main HEAD prior to Work 3.2 and `TAB2_WATER_WORLD_FULL_ECOSYSTEM_MIGRATION_V1`. Tab 1 V3 reference-fitting logic was intentionally not altered to preserve strict scope isolation.

## 3. Git / Remote Repository Boundaries (By Design)

- AI Studio workspace does not contain local `.git` metadata and per project governance AI Studio does NOT push to GitHub, modify `main`, or open PRs. The user / CI pipeline will commit these verified sources and artifacts, run Exact-HEAD GitHub Actions, and distribute the signed Debug APK for physical device verification.
