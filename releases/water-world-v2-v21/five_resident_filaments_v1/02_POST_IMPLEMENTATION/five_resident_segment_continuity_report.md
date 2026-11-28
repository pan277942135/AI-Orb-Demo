# Five Resident Filaments V1 — 13-Segment Continuity & Occlusion Report

**Contract ID:** `WATER_WORLD_FIVE_RESIDENT_FILAMENTS_DESIGN_FREEZE_V1`  
**Static Geometry Lock:** `STATIC_GEOMETRY_LOCKED` (13 / 13 frozen mesh JSON SHA-256 verified unchanged)

---

## 1. Canonical Arc-Length Parameterization (`s ∈ [0, 1]`)

Every one of the 13 frozen Resident Render Segments (`8 Back` in `02_RESIDENT_BACK` + `5 Front` in `10_RESIDENT_FRONT`) is mapped onto its parent Resident's unified canonical arc-length coordinate:

`vCanonicalS = mix(segmentArcLengthStart, segmentArcLengthEnd, localUvDirection > 0 ? aUv.x : 1.0 - aUv.x) + localFlowPhaseOffset`

This eliminates the Work 3 bug where every segment restarted flow, wave, and brightness phase independently at `aUv.x = 0`.

| Resident | Dir | Segment ID | Pass | `s` Range | `localUvDir` | Measured Arc | Width Range | Continuity & Occlusion Status |
|---|---:|---|---|---|---|---:|---|---|
| **G01** | `+1` | `G01_back_arch` | `02_RESIDENT_BACK` | `0.0000 .. 0.5600` | `+1` | `1344.00 px` | `20.0–85.1 px` | `HYPOTHESIS_DISJOINT_OCCLUDED_GAP_GROUND_TRUTH_UNKNOWN` |
| **G01** | `+1` | `G01_back_outer_veil` | `02_RESIDENT_BACK` | `0.2400 .. 0.5500` | `+1` | `744.87 px` | `20.0–59.0 px` | `PARALLEL_VEIL_CO_PHASED_GROUND_TRUTH_UNKNOWN` |
| **G01** | `+1` | `G01_front_lower_sweep` | `10_RESIDENT_FRONT` | `0.6000 .. 0.9600` | `-1` | `870.18 px` | `22.0–76.8 px` | `HYPOTHESIS_DISJOINT_OCCLUDED_GAP_GROUND_TRUTH_UNKNOWN` |
| **G02** | `-1` | `G02_back_upper_arc` | `02_RESIDENT_BACK` | `0.0000 .. 0.3400` | `+1` | `906.15 px` | `14.0–58.0 px` | `HYPOTHESIS_PARTIAL_GAP_GROUND_TRUTH_UNKNOWN` |
| **G02** | `-1` | `G02_back_upper_hairline` | `02_RESIDENT_BACK` | `0.0200 .. 0.3100` | `+1` | `779.87 px` | `14.0–38.4 px` | `PARALLEL_HAIRLINE_CO_PHASED_GROUND_TRUTH_UNKNOWN` |
| **G02** | `-1` | `G02_back_right_rim` | `02_RESIDENT_BACK` | `0.4300 .. 0.6400` | `+1` | `550.51 px` | `16.0–38.2 px` | `HYPOTHESIS_PARTIAL_GAP_GROUND_TRUTH_UNKNOWN` |
| **G02** | `-1` | `G02_front_right_embrace` | `10_RESIDENT_FRONT` | `0.6400 .. 1.0000` | `+1` | `944.29 px` | `22.0–60.7 px` | `HYPOTHESIS_PARTIAL_GAP_GROUND_TRUTH_UNKNOWN` |
| **A01** | `+1` | `A01_back_upper_left` | `02_RESIDENT_BACK` | `0.0000 .. 0.2863` | `+1` | `416.70 px` | `19.0–60.0 px` | `GEOMETRICALLY_CONTIGUOUS_SEAM_0PX` |
| **A01** | `+1` | `A01_front_left_embrace` | `10_RESIDENT_FRONT` | `0.2863 .. 1.0000` | `+1` | `1038.53 px` | `18.0–64.0 px` | `GEOMETRICALLY_CONTIGUOUS_SEAM_0PX` |
| **A02** | `-1` | `A02_front_lower_strand` | `10_RESIDENT_FRONT` | `0.0000 .. 0.7200` | `+1` | `1024.92 px` | `18.0–46.0 px` | `HYPOTHESIS_OVERLAPPING_LOWER_HALO_GROUND_TRUTH_UNKNOWN` |
| **A02** | `-1` | `A02_back_lower_halo` | `02_RESIDENT_BACK` | `0.4700 .. 1.0000` | `+1` | `753.03 px` | `21.0–56.1 px` | `HYPOTHESIS_OVERLAPPING_LOWER_HALO_GROUND_TRUTH_UNKNOWN` |
| **C01** | `+1` | `C01_front_lower_right` | `10_RESIDENT_FRONT` | `0.0000 .. 0.5388` | `+1` | `858.45 px` | `22.0–54.6 px` | `HYPOTHESIS_LOWER_BRIDGE_GAP_260PX_GROUND_TRUTH_UNKNOWN` |
| **C01** | `+1` | `C01_back_lower_thread` | `02_RESIDENT_BACK` | `0.5388 .. 1.0000` | `-1` | `734.88 px` | `14.0–31.3 px` | `HYPOTHESIS_LOWER_BRIDGE_GAP_260PX_GROUND_TRUTH_UNKNOWN` |

---

## 2. Key Continuity Findings

1. **`A01` Exact `0.0 px` Back-to-Front Seam:** `A01_back_upper_left` ends at `(48.91, 570.57)` (`s = 0.2863`) and `A01_front_left_embrace` starts at `(48.91, 570.57)` (`s = 0.2863`). Both flow phase (`uFlowOffsetU`) and canonical wave/brightness coordinates match identically across `s = 0.2863`, while `endAnchor` smoothly stabilizes lateral displacement at the sphere limb transition so zero tearing or gap can open.
2. **Co-Phased Parallel Secondary Strands:** `G01_back_outer_veil` (`s = 0.24..0.55`) and `G02_back_upper_hairline` (`s = 0.02..0.31`) share their parent Resident's canonical `s` range so their moving highlights travel in natural spatial unison with their primary arcs.
3. **Honest Occlusion & Ground-Truth Boundary:** No hidden bridge geometry was invented to fill occluded segments behind the crystal sphere. All non-contiguous back/front spans remain honestly tagged `GROUND_TRUTH_UNKNOWN` with original Work 2 Pass assignments (`02_RESIDENT_BACK` behind crystal sphere, `10_RESIDENT_FRONT` in front of crystal sphere) preserved unchanged.
