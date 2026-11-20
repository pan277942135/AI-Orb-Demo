# AI-Orb-Demo V3 — Reference Master Lock

## 1. Sole Frozen High-Fidelity Visual Master

| Field | Verified Value |
| :--- | :--- |
| **Workspace Relative Path** | `public/assets/aistudio/____________910d1531.png` |
| **File Exists** | `true` |
| **File Size** | `2,004,711` bytes |
| **Image Format** | PNG (8-bit, non-interlaced) |
| **Resolution** | `1254 × 1254` px (1:1 aspect ratio) |
| **Color Type / Space** | `RGB` (untagged sRGB display space; `sRGB` chunk absent, `iCCP` chunk absent) |
| **Alpha Channel** | `false` (opaque RGB) |
| **SHA-256** | `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122` |
| **QA Mirror Check** | `app/src/main/res/drawable/ref_master_qa_only.png` (identical SHA-256 `910d1531...`) |

## 2. Observed Pixel Content Summary (Direct Inspection)

- **Composition**: Centered crystal-clear blown glass orb (`diameter = 646.8 px`, `51.58%` of `1254×1254` canvas) suspended in a deep dark emerald-teal underwater abyss (`corner mean sRGB = (0.020, 0.118, 0.114)`, luma `0.097`) with soft dark-teal and muted olive-gold bokeh.
- **Outer Glass Shell**: Transparent, thick double-walled refractive crystal sphere (`center = (620.4, 605.5)`, `R = 323.4 px`), crystal-clear dark upper cavity above a curved organic meniscus, razor-sharp golden sunlight entry flare at `10:45` radiating delicate golden shafts, luminous molten-gold double rim along `12:30–3:30`, and crisp crystal-cyan rim along `4:30–6:30`.
- **Inner Liquid Volume**: Asymmetric 3D swirling translucent fluid filling the lower ~80% of the glass cavity:
  - Lush translucent **emerald-green liquid curtains** (`38.9%` of interior pixels) sweeping vertically along the left flank (`7–10 o'clock`) and curving inward on the right-center (`2–4 o'clock`).
  - **Deep sapphire-teal water valleys** (`23.0%`) creating dramatic volumetric dark depth.
  - **Electric-cyan glowing branching underwater caustics** (`27.1%`) concentrated in the lower-center and lower-right optical belly (`5–7 o'clock`).
  - **Clear dark upper glass cavity** (`11.2%`) above the sculpted concave liquid meniscus with zero milky haze.
- **Outer 5 Resident Structures** (preserved via V2/Plan-B independent ribbon architecture):
  - `C01` ice-blue multi-walled liquid-glass band (`8:30` bend → front-diagonal → `5:30` lower-right under-arc)
  - `A02` / `A01` broad warm molten-amber & champagne-gold glass loops (`10:30` crown & `3:30` blazing right loop)
  - `G02` / `G01` crisp warm sunlit olive-emerald translucent kelp fronds (`7:30` folded lower-left leaf & `1:30` upper-right arch)
  - Pinpoint jewel-like optical flares and small ray-shaded crystal water droplets.

## 3. Binding Freeze & Provenance Rules

1. **Immutability**: `public/assets/aistudio/____________910d1531.png` MUST NEVER be modified, overwritten, resized, or replaced. Automated test `AIOrbV3ReferenceAndAssetTest` verifies its exact byte size, resolution (`1254×1254`), and SHA-256 on every test run.
2. **Master vs. Runtime Asset Separation**:
   - Master PNG is strictly the visual acceptance contract.
   - Production runtime raster assets MUST be Lossless WebP generated from zero inside `tools/orb-v3-generator/` and stored in `app/src/main/assets/orb_v3/`.
   - Master PNG MUST NEVER be directly converted into a runtime sphere texture or sliced into fake 3D volume frames.
3. **Zero Secondary Master / Zero YuJian Reuse**:
   - Historical comparison artifacts (e.g., `AI_Orb_reference_screenshot_video_comparison_a7b862a2.jpg`) are diagnostic history only and cannot override this lock.
   - Zero assets, textures, masks, shaders, or fish-recognition resources from YuJian may be reused.
