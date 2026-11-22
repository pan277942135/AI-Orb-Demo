# AI-Orb-Demo V3 — WebP Asset Manifest & Validation Report

- **Architecture:** WebP-First 3D Volume Hybrid Rendering (OpenGL ES 3.0 `GL_TEXTURE_3D` + 2D Optical Maps)
- **Frozen Visual Master:** `public/assets/aistudio/____________910d1531.png`
- **Frozen Master SHA-256:** `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122` (verified unmodified)
- **Deterministic Generator Seed:** `20261120`
- **Total Production WebP Files:** `35` (`32` 3D volume slices + `3` 2D optical/detail maps)
- **Total Compressed Asset Footprint on Disk:** `1481.8 KiB` (`1,517,414 bytes`)
- **Total Uncompressed GPU VRAM Footprint:** `9.00 MiB` (`8.00 MiB` 3D Volume + `1.00 MiB` 2D Maps)

## 1. 3D Fluid Volume Texture (`uFluidVolume`, Texture Unit 1)

| Property | Value |
| :--- | :--- |
| Asset Path Pattern | `app/src/main/assets/orb_v3/volume/fluid_color_z00.webp` .. `fluid_color_z31.webp` |
| Dimensions | `256 × 256 × 32` (`2,097,152` voxels) |
| Storage Encoding | Lossless RGBA WebP (`VP8L`, un-premultiplied) |
| Color Space | `RGB = sRGB`, `A = Linear Volumetric Density` |
| GPU Upload Format | `GLES30.GL_TEXTURE_3D` · `GL_SRGB8_ALPHA8` · `GL_LINEAR` · `GL_CLAMP_TO_EDGE` |
| GPU Memory | `8.00 MiB` (`8,388,608 bytes`) |
| Y-Axis Convention | Row 0 = `+Y` (human-inspectable right-side-up); `CORE_GLASS_FRAG` samples `uv.y = 0.5 - pos.y * 0.5` |

## 2. 2D Optical & Fluid Detail Maps

| Asset | Dimensions | Encoding | Color Space | GPU Format | Unit / Uniform | Disk Size |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `maps/emerald_distribution.webp` | `256 × 256` | Lossless RGBA WebP (`VP8L`) | Linear Data | `GL_RGBA8` | Unit 2 · `uFluidDetailMap` | `153.5 KiB` |
| `optics/caustics.webp` | `256 × 256` | Lossless RGBA WebP (`VP8L`) | Linear Data | `GL_RGBA8` | Unit 3 · `uCausticsMap` | `126.3 KiB` |
| `optics/glass_environment.webp` | `512 × 256` | Lossless RGBA WebP (`VP8L`) | sRGB Equirectangular | `GL_SRGB8_ALPHA8` | Unit 4 · `uGlassEnvMap` | `16.3 KiB` |

## 3. Integrity & Continuity Guarantees

- Every asset is encoded with `libwebp -lossless 1` (`VP8L` chunk verified).
- Master PNG is never packaged or used as a runtime sphere texture.
- Zero YuJian assets or external masks referenced anywhere in the pipeline.
- Exact SHA-256 digests recorded in `app/src/main/assets/orb_v3/manifest/SHA256SUMS` and `ORB_V3_ASSET_MANIFEST.json`, verified by JVM unit tests (`AIOrbV3ReferenceAndAssetTest`).
