# AI Orb Core Asset Audit

## Read-only source identity

- Repository checkout: AI-Orb-Demo
- Branch: codex/ai-orb-v41-final-high-fidelity
- HEAD: 7e556babd2bdbad75ce22c1c57d19b386fd2a3c6
- Manifest: app/src/main/assets/orb_v3/manifest/ORB_V3_ASSET_MANIFEST.json
- Manifest schema: AI_ORB_V3_WEBP_VOLUME_MANIFEST_1_0
- Architecture declaration: WebP-First 3D Volume Hybrid Rendering (GLES 3.0)
- Total files: 35 WebP (32 volume slices + 3 2D maps); manifest disk bytes 1090048; declared GPU memory 9.0 MiB
- File-level SHA/dimension/mode audit: PASS

## Volume semantics

The 32 files are 256x256 spatial slices with Z indices 0 through 31, declared as a 256x256x32 GL_TEXTURE_3D volume. They are not temporal frames. RGB is declared sRGB and alpha is linear density; no premultiplied alpha. Manifest target is GL_SRGB8_ALPHA8.

All decoded slices are RGBA. Combined alpha distribution across 2,097,152 voxels: min 0, max 255, P10 0.00, P50 0.00, P75 14.00, P90 76.00, P95 109.00; fraction above 0.14 is 21.4994%. This is a byte-domain inventory statistic, not a ray-march visibility statistic or a visual pass.

| Z | Bytes | SHA256 | A min/max | A P10 | P50 | P75 | P90 | P95 | Fraction A > 0.14 |
|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 0 | 1016 | e484debced8ddf2b2dd974d0d533eda810e1d7432a17832ef3634a7e736a3c0c | 0/1 | 0 | 0 | 0 | 0 | 0 | 0.00% |
| 1 | 4360 | 0e74176fa474e9de9e7d01b2998cf7ed161fb0e2e00725efcc4db53e631c3795 | 0/14 | 0 | 0 | 0 | 0 | 3 | 0.00% |
| 2 | 8152 | a5fe6a01fd5fe0731b2c5917d3f154ffe301fdf89651cb5f36e71b9afa509dbe | 0/43 | 0 | 0 | 0 | 7 | 18 | 0.11% |
| 3 | 14780 | dc78e2020854b4c5b00d3926d3d43ef14c37da273402b57a565a4c13e0c7f2d7 | 0/103 | 0 | 0 | 1 | 20 | 31 | 2.25% |
| 4 | 20440 | ae2eafe37638f691dc1556ea4fd50853affe70cac06541f036dd8c7257fe0499 | 0/133 | 0 | 0 | 4 | 31 | 38 | 6.94% |
| 5 | 23888 | 27a8d63bd402c57a8f085a0db63ed91d18fa70ea081d2d25ab6b7b2161d13d68 | 0/155 | 0 | 0 | 7 | 40 | 47 | 13.01% |
| 6 | 26438 | 240e075c1b5bc0391d4ae693851b1756c023f2540486cd3a1ad43250301aafef | 0/187 | 0 | 0 | 8 | 51 | 60 | 15.12% |
| 7 | 27290 | ce08f2e791ddae88401e6e837f559c9f17bf4d24f05ac2172f84cae5be56e3ee | 0/214 | 0 | 0 | 8 | 62 | 76 | 17.01% |
| 8 | 26082 | e8798b0fb13692efc2fa171676c4dc57fe7b3803a0ad642c16329db9b69b0694 | 0/247 | 0 | 2 | 7 | 81 | 91 | 19.47% |
| 9 | 24480 | b1983f0cd691044d3286fd4b35ab27362341db948c1a98b5557ac1fc4a385741 | 0/255 | 0 | 3 | 7 | 92 | 104 | 21.95% |
| 10 | 23674 | 64b44ee259b1975a900aea78d5278bf853e0db5a09e0a9ced07486304cba459e | 0/255 | 0 | 4 | 8 | 99 | 110 | 23.48% |
| 11 | 25216 | e046fbbb5c1f664bbbab75d1ae577e781a14b7a3e84b13ab140e6d2314f0d62f | 0/255 | 0 | 5 | 34 | 102 | 114 | 24.96% |
| 12 | 27484 | 763124282eaf6e149054364b6ea3e0e510ff80619fab488ce62aeb6d4e222f8a | 0/255 | 0 | 5 | 40 | 104 | 121 | 26.41% |
| 13 | 29706 | 9b1f8ad537b8d39a025b460f96beb846aef7aaaf4d0cc4568c8f4fb0f8f8d9a7 | 0/255 | 0 | 5 | 45 | 113 | 162 | 27.87% |
| 14 | 31220 | 7dc29dd3f5b622bf9225c471dde56288edb7a82690712531f79ba67b922454c7 | 0/255 | 0 | 5 | 48 | 134 | 210 | 29.42% |
| 15 | 30858 | 9fa0df3c950415a74468cb1e01ca569dbd757bc18b4105b8d4c8ecac221030e9 | 0/255 | 0 | 5 | 57 | 141 | 236 | 30.84% |
| 16 | 31410 | 547447d73e40a48112b6900ed79c8c350fa332571bb8491280dd47dfc9132841 | 0/255 | 0 | 5 | 60 | 110 | 183 | 32.32% |
| 17 | 31562 | fb8874cb36b6dad2ec4ca8947a5524b37b0fd8ac28779efb969a793f6b0c759a | 0/255 | 0 | 5 | 61 | 104 | 135 | 33.75% |
| 18 | 33192 | d72c156fde525872d7c4adf1f64500bf16a178a53b3558bcb6a2050308d035fd | 0/255 | 0 | 5 | 63 | 104 | 130 | 35.18% |
| 19 | 35018 | 57d329fbd514fa8db5cd4f6242ce9942faa4d960ea7e334e2b572c5ac4127a72 | 0/255 | 0 | 5 | 65 | 106 | 166 | 36.73% |
| 20 | 36216 | 8549ca934945cf9dca8b471a2f0d36dce58ee665f4424366145adcc07f7489fb | 0/255 | 0 | 5 | 66 | 108 | 206 | 38.34% |
| 21 | 36682 | 7db20cc5c1713844c38a06781dc60a4cfc7e2ac4f3d4a82b7d3a81f75dc9c8e5 | 0/255 | 0 | 4 | 66 | 107 | 211 | 39.76% |
| 22 | 38250 | 0dc499a7f873e2648ad112440212361fb51108ef8e338c1ab37683edcbce6c26 | 0/255 | 0 | 4 | 64 | 105 | 178 | 40.19% |
| 23 | 38834 | 990f617ca4b17211b69226ca094d334f66da7ea02afd808c98486f114800d812 | 0/255 | 0 | 3 | 61 | 100 | 138 | 38.44% |
| 24 | 37046 | baf050809fbe56c4e2c31b03d8b3a2929c77d7a8e027a5403ddab2e5f041aefa | 0/255 | 0 | 1 | 59 | 97 | 123 | 35.18% |
| 25 | 32584 | c0963b2e848ab070ac75e8e6202ab29004acfc66e1710f01767fd45cde449052 | 0/255 | 0 | 0 | 52 | 87 | 113 | 31.90% |
| 26 | 28634 | c94a61b6da2df4e2d7e5f192b6a2a1bc2937fc873a70491d6018d0e86625c36f | 0/255 | 0 | 0 | 40 | 71 | 100 | 27.14% |
| 27 | 23196 | 95bfda3135fec3b3ed47e8ec3fdb99d79e539ddeea78f33087ca0c4fdd9c9aa8 | 0/255 | 0 | 0 | 20 | 62 | 78 | 20.55% |
| 28 | 18582 | 2d82e1fcf40502f139ac62d97f0b59f1961e6f0feeb8f4f4a59a4e8eae16c43c | 0/237 | 0 | 0 | 2 | 51 | 61 | 13.81% |
| 29 | 12748 | 92784cee073c09bce70863ede80a97056a5ad5cf201492fc56abd5be83a272e0 | 0/145 | 0 | 0 | 0 | 21 | 38 | 5.83% |
| 30 | 6482 | bc3d925a247036a67183ee846d876b57963da936219e0880d39f9a0a3de14bae | 0/40 | 0 | 0 | 0 | 1 | 9 | 0.04% |
| 31 | 1288 | 5a0e20c709bccd1c369ee3899c852469e62171845b14a8d0e7aa959c9a9aaa38 | 0/1 | 0 | 0 | 0 | 0 | 0 | 0.00% |

## Additional maps

- emerald_distribution.webp: 256x256 RGBA, linear data. R curtain, G grain, B meniscus ripple, A absorption modifier. No temporal dimension.
- caustics.webp: 256x256 RGBA, linear data. Channels encode two curved caustics, fine filaments and convergence nodes. This is an optical texture, not independent filament geometry.
- glass_environment.webp: 512x256 RGBA, sRGB environment map plus specular weight.

## Runtime path observed in source

- OrbWebpAssetLoader.loadAll decodes each manifest slice in declared order into one direct RGBA buffer, with Android BitmapFactory ARGB_8888, inScaled=false, inPremultiplied=false.
- OrbVolumeTexture3D.uploadDecoded uploads GL_TEXTURE_3D and checks GL_MAX_3D_TEXTURE_SIZE; four GL textures are required: volume, detail map, caustics map and environment map.
- OrbFluidVolumeRenderer binds textures and passes uCoreRotMat, uFluidPhase, uMeniscusPhase and uCausticsPhase; CoreFluidShaders samples sampler3D uFluidVolume.
- AIOrbScene runs a background and back-ribbon capture before the core pass, then draws front ribbons after the core. This is an existing AI Orb architecture audit only; no production code was modified or executed on GPU in this task.

## Motion asset findings

- CORE_VOLUME: available, true spatial 3D dataset.
- CORE_FLUID: available as color/density volume plus a detail map; future flow is shader/runtime driven, not stored as time frames.
- CORE_DENSITY and CORE_COLOR: available as channels in the volume.
- CORE_CAUSTICS: available as 2D map and shader parameters.
- CORE_GLASS: available as environment map plus runtime shader/model implementation.
- CORE_FILAMENT: no independently addressable 3D filament geometry/path asset found. A fine-filament channel exists only inside the caustics map.
- CORE_PARTICLES: the audited AIOrbParticles renderer is a scene-level far-depth droplet/mote pass, not a dedicated internal-core particle dataset. Marked unavailable as an independent core particle asset.

The current AI Orb motion implementation has a 26 deg/s base core rotation on normalized axis (0.14, 0.98, -0.14), with fluid phase advanced from the shared simulation clock. These values are source evidence for the current AI Orb branch only. They are not calibrated Water World animation values and are not copied as target motion values.
