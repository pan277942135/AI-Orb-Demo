# BACKGROUND_ASSET_AUDIT

**Task ID:** `TAB2_WATER_WORLD_FULL_ECOSYSTEM_MIGRATION_V1`  
**Phase:** Phase 1 — Background Integration  
**Lock Status:** `BACKGROUND_SOURCE_LOCK_VERIFIED`

## 1. Source-to-APK Asset Integrity Verification

| Property | Frozen Source (`public/assets/aistudio/...`) | Android Asset (`app/src/main/assets/tab2_background/...`) | Match |
|---|---|---|---|
| File Path | `public/assets/aistudio/ChatGPT 图像 2026年11月23日 10_33_32.png` | `app/src/main/assets/tab2_background/tab2_underwater_bg.png` | Exact copy |
| Size (Bytes) | `1,557,320` | `1,557,320` | EXACT |
| Resolution | `852 × 1846` px | `852 × 1846` px | EXACT |
| SHA-256 | `f3dc1549919f48f939df5a6cf5baf18fb6846164c6f857df8e20cfe247b495ba` | `f3dc1549919f48f939df5a6cf5baf18fb6846164c6f857df8e20cfe247b495ba` | EXACT |
| Git Blob SHA-1 | `92562ff0a60bc63f3064c98af1922f817fee22c9` | `92562ff0a60bc63f3064c98af1922f817fee22c9` | EXACT |
| AAPT2 Crunch Risk | N/A | Stored in `assets/` (never crunched or re-encoded by AAPT2) | ZERO |

## 2. Aspect-Preserving Framing Rules (`Tab2UnderwaterBackgroundRenderer.computeCoverUvRect`)

- Original aspect ratio: `852 / 1846 = 0.4615` (~`9:19.5`, matching modern Android portrait screens like `1080×2340` and `1080×2400`).
- Zero horizontal or vertical stretching on any aspect ratio (`16:9`, `19.5:9`, `20:9`, foldable, tablet).
- Preserves top water surface ripples, top-right sunlight rays, clear central cyan water, side floating bubbles, and bottom-left/bottom-right aquatic plants + caustic seabed.
- Zero post-process tinting, darkening, blurring, or re-encoding applied to the background PNG.
- Strictly scoped to Tab 2; Tab 1 (`AI Orb`) and Tab 3 (`水下世界`) backgrounds remain 100% untouched.
