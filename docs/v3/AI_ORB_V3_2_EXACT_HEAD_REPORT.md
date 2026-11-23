# AI Orb V3.2 — Exact-HEAD & Artifact Provenance Report

- **Task ID:** `AI_ORB_V3_2_REFERENCE_TO_3D_FINAL_FIDELITY_P0`
- **Repository:** `https://github.com/pan277942135/AI-Orb-Demo`
- **Stated Baseline Main HEAD:** `f514cae56edae68eb9e29b99000bc338609f1e4a`
- **Delivery Status:** **`SOURCE_READY_GPU_PENDING`**

---

## 1. Environment & Git/CI Honesty Disclosure

- **Workspace `.git` metadata:** Not present in this AI Studio cloud build container (the platform exports/syncs source snapshots without a local `.git` directory).
- **GitHub Push & CI Execution:** This container has no GitHub credentials or remote push access; therefore `.github/workflows/ci-v32.yml` has been authored and verified locally against the exact Gradle/Python commands, but remote GitHub Actions CI has **not** been executed from inside this container.
- **Physical GPU / Emulator:** Not available inside this build container; therefore real GLES 3.0 GPU visual frames are **`PENDING_REAL_GPU_CAPTURE`** until the user runs the one-click **Export V3.2 Fidelity Evidence** flow on a real Android device.

---

## 2. Exact Verified Artifact Checksums (SHA-256)

| Artifact | Size (bytes) | SHA-256 |
|---|---:|---|
| `AI-Orb-Demo-V3.2-HighFidelity-debug.apk` | 22,153,987 | `bcdebc2379e70b1b3a28efe740f06e68daea7d296e73faa98588388a1b0ed27c` |
| `public/assets/aistudio/____________910d1531.png` | 2,004,711 | `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122` |
| `app/src/main/assets/orb_v3/manifest/MASTER_FIDELITY_V32.json` | 30,712 | `b508c23b815819010107b9f4db25dba13e22e0c8c6fda22be54ac1b1e1cf9460` |
| `app/src/main/assets/orb_v3/manifest/ORB_V3_ASSET_MANIFEST.json` | 8,810 | `ab10619d85d0194539a800adb022be6aa3fbadb797c9f76fc30008de9a5ac24c` |
| `app/src/main/assets/orb_v3/manifest/SHA256SUMS` | 3,288 | `04c7f8652e6ca58555aaa516b6d81e196136c3290bf3864200dd1eb7520aa5d0` |
| `docs/v3/AI_ORB_V3_2_VISUAL_LOSS.json` | 2,854 | `5d14d12d5f24e23b91799cea7e5fbde4bb7ccc1b2330553ab7429566eddf0390` |
| `AI_ORB_V3_REFERENCE_COMPARISON.png` *(OFFLINE_CPU_PROXY)* | 1,388,935 | `686e5df055e60861b54b938ed097504213172f37d91cc8a3b37f15b577de477d` |
| `docs/v3/REFERENCE_SQUARE_OFFLINE_PROXY.png` *(OFFLINE_CPU_PROXY)* | 180,692 | `1be01b0e271f36906497c758d3fd4a5a9a655c1682954e457e735a2e8eaa362a` |
| `docs/v3/PRODUCTION_PORTRAIT_OFFLINE_PROXY.png` *(OFFLINE_CPU_PROXY)* | 100,072 | `141e6a95ee45f84d414fe857db182a9ca957ca6d85b00c06efe8ab641442be93` |
| `app/src/main/java/com/example/orb/gl/CoreFluidShaders.kt` | 53,540 | `f9ff05d97509d249a8d5e21f93bc96e3126df43e1d05647c8c3338cff9061c84` |
| `app/src/main/java/com/example/orb/gl/RibbonOpticsShaders.kt` | 26,396 | `bf031d401bb6a45ee5077ac59b66b1f4185255508b87230da338daee066ba2b2` |
| `app/src/main/java/com/example/orb/v3/MasterFidelityV32.kt` | 23,223 | `d93b27d42dc4c5b61b291c9a87c0017586ac05b3924be65737ba0918f4f4278c` |
| `app/src/main/java/com/example/orb/v3/OrbGpuReferenceCapture.kt` | 13,641 | `d2c5ad06ab4dc53d9e1b4252a517e326dbdac8f9d49bcb9a103a8c22e7e5b91e` |
| `app/src/main/java/com/example/orb/scene/OrbFluidVolumeRenderer.kt` | 6,242 | `a16d742b4f31f408e850d2f4874ae00977c7cd68de83952ce50bd1738af5f72f` |
| `app/src/main/java/com/example/orb/scene/AIOrbOrbitRibbons.kt` | 34,824 | `8bc4eed0554ed45fd64fc73fde1b9a84b3b238e076bf3e3acebbd7e721b9931c` |
| `app/src/main/java/com/example/orb/scene/AIOrbScene.kt` | 37,837 | `7f9b6cbcd375b7d7ddb5a666ae9e7ddf88bb798992524fea88cdd7f11b22dada` |

---

## 3. Exact Build Binding Inside APK

- `AI-Orb-Demo-V3.2-HighFidelity-debug.apk` was copied byte-for-byte directly from `app/build/outputs/apk/debug/app-debug.apk` immediately after `gradle :app:testDebugUnitTest :app:assembleDebug --console=plain` exited `0` (77/77 tests PASS).
- `MASTER_FIDELITY_V32.json` and all 35 regenerated Lossless RGBA WebP assets are bundled directly inside `assets/orb_v3/` of the candidate APK and verified via Robolectric `ApplicationProvider.getApplicationContext().assets` in `AIOrbV32ReferenceFittingTest`.
