"""Work 1.6 Round 1 scene structure & resource precision closure builder.

Reuses all 65 byte-frozen V2 archive assets (verified by SHA-256), never copies or crops
the frozen Master PNG into any texture, separates Ground Truth from Render Hypothesis,
splits the 5 Residents into true Back (Pass 02) and Front (Pass 10) mesh segments, and
writes the 11-stage Render Graph draw bindings and 5-effect optical configuration.
"""
import hashlib
import json
import math
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
V2_ZIP = ROOT / "releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip"
V21_ZIP = ROOT / "releases/water-world-v2-v21/delivery/WATER_WORLD_V2_1_ASSETS.zip"
MASTER_PNG = ROOT / "releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png"
DELIVERY = ROOT / "releases/water-world-v2-v21/delivery"
SCENE_OUT = ROOT / "releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1"
WORK16_OUT = ROOT / "releases/water-world-v2-v21/work1_6_structure_closure_android_gpu"

EXPECTED_V2_SHA = "6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095"
EXPECTED_V21_SHA = "28f8918c39f6e875602d3a7e90775e9d455aa6c4e42783247d1104c3b4509679"
EXPECTED_MASTER_SHA = "fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589"

CANVAS = 1254
SPHERE_CX = 626.4757504075296
SPHERE_CY = 596.3441299453135
SPHERE_R = 422.2986621604988
SPHERE_INNER_R = 395.75
IDENTITY = [1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0]


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def catmull_rom_chain(points, samples_per_seg=28):
    """Evaluate an open centripetal-like Catmull-Rom spline over (x, y, width_px) control points."""
    n = len(points)
    assert n >= 2
    ext = [points[0]] + list(points) + [points[-1]]
    out = []
    for i in range(n - 1):
        p0, p1, p2, p3 = ext[i], ext[i + 1], ext[i + 2], ext[i + 3]
        steps = samples_per_seg if i < n - 2 else samples_per_seg + 1
        for s in range(steps):
            t = s / samples_per_seg
            t2 = t * t
            t3 = t2 * t
            res = []
            for k in range(3):
                v = 0.5 * (
                    (2.0 * p1[k])
                    + (-p0[k] + p2[k]) * t
                    + (2.0 * p0[k] - 5.0 * p1[k] + 4.0 * p2[k] - p3[k]) * t2
                    + (-p0[k] + 3.0 * p1[k] - 3.0 * p2[k] + p3[k]) * t3
                )
                res.append(v)
            out.append(tuple(res))
    return out


def build_ribbon_mesh(control_pts_norm, z_depth=0.0, samples_per_seg=24):
    """Build an indexed triangle strip ribbon mesh in pixel coordinates (1254x1254)."""
    pts_px = [(x * CANVAS, y * CANVAS, w) for (x, y, w) in control_pts_norm]
    curve = catmull_rom_chain(pts_px, samples_per_seg=samples_per_seg)
    # Compute cumulative arc length for clean u in [0, 1]
    cum = [0.0]
    for i in range(1, len(curve)):
        dx = curve[i][0] - curve[i - 1][0]
        dy = curve[i][1] - curve[i - 1][1]
        cum.append(cum[-1] + math.hypot(dx, dy))
    total_len = max(cum[-1], 1e-4)

    vertices = []
    indices = []
    m = len(curve)
    for i, (cx, cy, half_w) in enumerate(curve):
        if i == 0:
            tx, ty = curve[1][0] - cx, curve[1][1] - cy
        elif i == m - 1:
            tx, ty = cx - curve[i - 1][0], cy - curve[i - 1][1]
        else:
            tx, ty = curve[i + 1][0] - curve[i - 1][0], curve[i + 1][1] - curve[i - 1][1]
        tl = max(math.hypot(tx, ty), 1e-5)
        nx, ny = -ty / tl, tx / tl
        u = cum[i] / total_len
        hw = max(2.0, half_w)
        # Left edge (v=0) and Right edge (v=1), normal points +Z with slight ribbon bevel
        vertices.append([round(cx - nx * hw, 3), round(cy - ny * hw, 3), z_depth, round(-nx * 0.25, 4), round(-ny * 0.25, 4), 0.9682, round(u, 5), 0.0])
        vertices.append([round(cx + nx * hw, 3), round(cy + ny * hw, 3), z_depth, round(nx * 0.25, 4), round(ny * 0.25, 4), 0.9682, round(u, 5), 1.0])
        if i > 0:
            b = (i - 1) * 2
            indices.extend([b, b + 1, b + 2, b + 2, b + 1, b + 3])
    return {"vertices": vertices, "indices": indices}


def build_rect_quad(x0, y0, x1, y1, z=0.0, rot_deg=0.0, flip_x=False, uv_rect=(0.0, 0.0, 1.0, 1.0)):
    cx = 0.5 * (x0 + x1)
    cy = 0.5 * (y0 + y1)
    hw = 0.5 * (x1 - x0)
    hh = 0.5 * (y1 - y0)
    rad = math.radians(rot_deg)
    ca, sa = math.cos(rad), math.sin(rad)
    u0, v0, u1, v1 = uv_rect
    if flip_x:
        u0, u1 = u1, u0
    corners = [(-hw, -hh, u0, v0), (hw, -hh, u1, v0), (-hw, hh, u0, v1), (hw, hh, u1, v1)]
    verts = []
    for lx, ly, u, v in corners:
        px = cx + lx * ca - ly * sa
        py = cy + lx * sa + ly * ca
        verts.append([round(px, 3), round(py, 3), z, 0.0, 0.0, 1.0, round(u, 5), round(v, 5)])
    return {"vertices": verts, "indices": [0, 1, 2, 2, 1, 3]}


def build_disc_mesh(cx, cy, radius, rings=1, segments=128):
    verts = [[round(cx, 3), round(cy, 3), 0.0, 0.0, 0.0, 1.0, round(cx / CANVAS, 6), round(cy / CANVAS, 6)]]
    for i in range(segments + 1):
        a = (i / segments) * 2.0 * math.pi
        x = cx + radius * math.cos(a)
        y = cy + radius * math.sin(a)
        verts.append([round(x, 3), round(y, 3), 0.0, 0.0, 0.0, 1.0, round(x / CANVAS, 6), round(y / CANVAS, 6)])
    indices = []
    for i in range(segments):
        indices.extend([0, i + 1, i + 2])
    return {"vertices": verts, "indices": indices}


def make_model(z_offset):
    m = IDENTITY.copy()
    m[14] = float(z_offset)
    return m


def run():
    # 1. Source lock verification
    assert sha256_file(V2_ZIP) == EXPECTED_V2_SHA, "V2 ZIP SHA mismatch"
    assert sha256_file(V21_ZIP) == EXPECTED_V21_SHA, "V2.1 ZIP SHA mismatch"
    assert sha256_file(MASTER_PNG) == EXPECTED_MASTER_SHA, "Master PNG SHA mismatch"

    geom_dir = SCENE_OUT / "02_SUPPLEMENTED_ASSETS/geometry"
    mat_dir = SCENE_OUT / "02_SUPPLEMENTED_ASSETS/materials"
    light_dir = SCENE_OUT / "02_SUPPLEMENTED_ASSETS/lighting"
    sg_dir = SCENE_OUT / "03_SCENE_GRAPH"
    for d in (geom_dir, mat_dir, light_dir, sg_dir):
        d.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(V2_ZIP) as zf:
        v2_shas = {}
        for name in zf.namelist():
            if name.startswith("WATER_WORLD_V2/") and not name.endswith("/"):
                rel = name.removeprefix("WATER_WORLD_V2/")
                v2_shas[rel] = sha256_bytes(zf.read(name))
        assert len(v2_shas) == 65
        bubble_cfg = json.loads(zf.read("WATER_WORLD_V2/09_BUBBLES/bubble_placements.json"))

    def tex_ref(rel):
        assert rel in v2_shas, f"Missing V2 texture: {rel}"
        return "01_REUSED_ASSETS/" + rel, v2_shas[rel]

    # Full-canvas quad & water interior disc (clipped precisely inside crystal shell)
    env_quad = build_rect_quad(0, 0, CANVAS, CANVAS)
    (geom_dir / "environment_quad.json").write_text(json.dumps(env_quad) + "\n")

    water_disc = build_disc_mesh(SPHERE_CX, SPHERE_CY, SPHERE_INNER_R + 12.0, segments=128)
    (geom_dir / "water_disc.json").write_text(json.dumps(water_disc) + "\n")

    # 2. Five Independent Residents — True Back (Pass 02) & Front (Pass 10) Segment Splits
    # Control points: (x_norm, y_norm, half_width_px)
    resident_specs = [
        # G01: Silky lush emerald-lime ribbon sweeping top -> right behind sphere, and lower-left front sweep
        {
            "draw_id": "G01_back_arch",
            "resident_id": "G01",
            "pass_id": "02_RESIDENT_BACK",
            "depth": 520.0,
            "color": [0.42, 0.84, 0.16, 0.94],
            "core_color": [0.84, 1.0, 0.36],
            "emission": 1.24,
            "ribbon_style": 0.0,
            "points": [
                (0.24, 0.165, 11.0),
                (0.41, 0.095, 25.0),
                (0.575, 0.072, 34.0),
                (0.715, 0.140, 44.0),
                (0.828, 0.258, 44.0),
                (0.905, 0.382, 34.0),
                (0.895, 0.520, 22.0),
                (0.835, 0.625, 10.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_UPPER_RIGHT_BACK_ARCH",
            "confidence": "MEDIUM_LOW",
        },
        {
            "draw_id": "G01_back_outer_veil",
            "resident_id": "G01",
            "pass_id": "02_RESIDENT_BACK",
            "depth": 515.0,
            "color": [0.28, 0.74, 0.20, 0.72],
            "core_color": [0.74, 0.98, 0.36],
            "emission": 1.02,
            "ribbon_style": 0.0,
            "points": [
                (0.65, 0.155, 14.0),
                (0.795, 0.220, 30.0),
                (0.920, 0.340, 30.0),
                (0.950, 0.468, 20.0),
                (0.892, 0.585, 11.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_RIGHT_SECONDARY_VEIL",
            "confidence": "LOW",
        },
        {
            "draw_id": "G01_front_lower_sweep",
            "resident_id": "G01",
            "pass_id": "10_RESIDENT_FRONT",
            "depth": -520.0,
            "color": [0.50, 0.86, 0.18, 0.92],
            "core_color": [0.92, 1.0, 0.40],
            "emission": 1.22,
            "ribbon_style": 0.0,
            "points": [
                (0.112, 0.505, 12.0),
                (0.132, 0.635, 32.0),
                (0.205, 0.755, 40.0),
                (0.332, 0.852, 38.0),
                (0.432, 0.932, 28.0),
                (0.562, 0.972, 12.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_LOWER_LEFT_FRONT_SWEEP",
            "confidence": "MEDIUM_LOW",
        },
        # G02: Fiery amber-orange-gold silk & filament arcing upper-left sun flare & outer right loop
        {
            "draw_id": "G02_back_upper_arc",
            "resident_id": "G02",
            "pass_id": "02_RESIDENT_BACK",
            "depth": 505.0,
            "color": [1.0, 0.56, 0.06, 0.96],
            "core_color": [1.0, 0.92, 0.48],
            "emission": 1.48,
            "ribbon_style": 1.0,
            "points": [
                (0.068, 0.410, 12.0),
                (0.125, 0.275, 26.0),
                (0.185, 0.182, 32.0),
                (0.280, 0.118, 28.0),
                (0.398, 0.112, 24.0),
                (0.520, 0.145, 16.0),
                (0.620, 0.185, 8.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_UPPER_LEFT_GOLD_BACK_ARC",
            "confidence": "MEDIUM_LOW",
        },
        {
            "draw_id": "G02_back_upper_hairline",
            "resident_id": "G02",
            "pass_id": "02_RESIDENT_BACK",
            "depth": 502.0,
            "color": [1.0, 0.62, 0.10, 0.88],
            "core_color": [1.0, 0.94, 0.56],
            "emission": 1.38,
            "ribbon_style": 1.0,
            "points": [
                (0.095, 0.360, 9.0),
                (0.155, 0.215, 22.0),
                (0.248, 0.082, 18.0),
                (0.395, 0.094, 14.0),
                (0.545, 0.132, 8.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_UPPER_LEFT_FINE_STRAND",
            "confidence": "LOW",
        },
        {
            "draw_id": "G02_back_right_rim",
            "resident_id": "G02",
            "pass_id": "02_RESIDENT_BACK",
            "depth": 500.0,
            "color": [1.0, 0.60, 0.08, 0.88],
            "core_color": [1.0, 0.92, 0.50],
            "emission": 1.35,
            "ribbon_style": 1.0,
            "points": [
                (0.835, 0.238, 8.0),
                (0.952, 0.358, 17.0),
                (0.972, 0.488, 19.0),
                (0.930, 0.622, 15.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_FAR_RIGHT_GOLD_LOOP",
            "confidence": "LOW",
        },
        {
            "draw_id": "G02_front_right_embrace",
            "resident_id": "G02",
            "pass_id": "10_RESIDENT_FRONT",
            "depth": -505.0,
            "color": [1.0, 0.56, 0.06, 0.96],
            "core_color": [1.0, 0.93, 0.50],
            "emission": 1.52,
            "ribbon_style": 1.0,
            "points": [
                (0.815, 0.432, 13.0),
                (0.926, 0.542, 30.0),
                (0.918, 0.675, 30.0),
                (0.798, 0.760, 26.0),
                (0.640, 0.776, 24.0),
                (0.485, 0.758, 11.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_RIGHT_LOWER_GOLD_FRONT",
            "confidence": "MEDIUM_LOW",
        },
        # A01: Vivid electric cyan-azure luminous water-light ribbon sweeping LEFT and LOWER-LEFT FRONT
        {
            "draw_id": "A01_back_upper_left",
            "resident_id": "A01",
            "pass_id": "02_RESIDENT_BACK",
            "depth": 495.0,
            "color": [0.06, 0.78, 1.0, 0.92],
            "core_color": [0.74, 0.98, 1.0],
            "emission": 1.36,
            "ribbon_style": 2.0,
            "points": [
                (0.222, 0.198, 10.0),
                (0.115, 0.282, 24.0),
                (0.048, 0.372, 30.0),
                (0.038, 0.455, 32.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_UPPER_LEFT_CYAN_ENTRY",
            "confidence": "MEDIUM_LOW",
        },
        {
            "draw_id": "A01_front_left_embrace",
            "resident_id": "A01",
            "pass_id": "10_RESIDENT_FRONT",
            "depth": -510.0,
            "color": [0.06, 0.82, 1.0, 0.96],
            "core_color": [0.78, 0.99, 1.0],
            "emission": 1.48,
            "ribbon_style": 2.0,
            "points": [
                (0.038, 0.455, 30.0),
                (0.092, 0.530, 34.0),
                (0.198, 0.612, 30.0),
                (0.328, 0.718, 30.0),
                (0.482, 0.782, 26.0),
                (0.635, 0.788, 17.0),
                (0.748, 0.765, 9.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_LEFT_LOWER_CYAN_FRONT",
            "confidence": "MEDIUM_LOW",
        },
        # A02: Secondary cyan-turquoise water-light strand & lower outer glowing loop
        {
            "draw_id": "A02_back_lower_halo",
            "resident_id": "A02",
            "pass_id": "02_RESIDENT_BACK",
            "depth": 490.0,
            "color": [0.06, 0.76, 0.98, 0.88],
            "core_color": [0.68, 0.97, 1.0],
            "emission": 1.28,
            "ribbon_style": 2.0,
            "points": [
                (0.335, 0.835, 11.0),
                (0.490, 0.908, 26.0),
                (0.655, 0.896, 30.0),
                (0.766, 0.828, 25.0),
                (0.838, 0.715, 15.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_BOTTOM_CYAN_OUTER_HALO",
            "confidence": "MEDIUM_LOW",
        },
        {
            "draw_id": "A02_front_lower_strand",
            "resident_id": "A02",
            "pass_id": "10_RESIDENT_FRONT",
            "depth": -508.0,
            "color": [0.08, 0.80, 1.0, 0.88],
            "core_color": [0.74, 0.98, 1.0],
            "emission": 1.32,
            "ribbon_style": 2.0,
            "points": [
                (0.068, 0.408, 13.0),
                (0.118, 0.545, 22.0),
                (0.232, 0.678, 24.0),
                (0.390, 0.766, 22.0),
                (0.562, 0.802, 17.0),
                (0.700, 0.785, 10.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_LEFT_SECONDARY_CYAN_STRAND",
            "confidence": "LOW",
        },
        # C01: Warm fiery-gold lower-right front loop & delicate lower-left warm thread
        {
            "draw_id": "C01_back_lower_thread",
            "resident_id": "C01",
            "pass_id": "02_RESIDENT_BACK",
            "depth": 485.0,
            "color": [1.0, 0.64, 0.14, 0.84],
            "core_color": [1.0, 0.92, 0.54],
            "emission": 1.32,
            "ribbon_style": 1.0,
            "points": [
                (0.128, 0.555, 8.0),
                (0.210, 0.750, 15.0),
                (0.372, 0.842, 15.0),
                (0.495, 0.902, 12.0),
                (0.515, 0.945, 7.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_LOWER_LEFT_GOLD_THREAD",
            "confidence": "LOW",
        },
        {
            "draw_id": "C01_front_lower_right",
            "resident_id": "C01",
            "pass_id": "10_RESIDENT_FRONT",
            "depth": -512.0,
            "color": [1.0, 0.62, 0.12, 0.95],
            "core_color": [1.0, 0.95, 0.58],
            "emission": 1.54,
            "ribbon_style": 1.0,
            "points": [
                (0.828, 0.485, 11.0),
                (0.905, 0.598, 24.0),
                (0.860, 0.715, 28.0),
                (0.738, 0.768, 28.0),
                (0.605, 0.775, 25.0),
                (0.455, 0.745, 13.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_LOWER_RIGHT_GOLD_FRONT",
            "confidence": "MEDIUM_LOW",
        },
    ]

    for spec in resident_specs:
        mesh = build_ribbon_mesh(spec["points"], z_depth=0.0, samples_per_seg=26)
        mesh["resident_id"] = spec["resident_id"]
        mesh["pass_id"] = spec["pass_id"]
        mesh["hypothesis"] = spec["hypothesis"]
        (geom_dir / f"{spec['draw_id']}_mesh.json").write_text(json.dumps(mesh) + "\n")

    # 2b. Full 16x6 grid connectivity for the 4 Ground-Truth fish meshes
    # Preserves 100% identical vertex coordinates, UVs, center_px, scale_xy, and rotation_radians
    # while eliminating the sparse alpha-culled stairstep cutouts from Work 1.5!
    for fid in ("WW_FISH_01", "WW_FISH_02", "WW_FISH_03", "WW_FISH_04"):
        orig = json.loads((geom_dir / f"{fid}_mesh.json").read_text())
        verts = orig["vertices"]
        # 17 cols x 7 rows = 119 vertices
        cols, rows = 17, 7
        assert len(verts) == cols * rows, f"Unexpected vertex count for {fid}: {len(verts)}"
        full_indices = []
        for r in range(rows - 1):
            for c in range(cols - 1):
                i0 = r * cols + c
                i1 = i0 + 1
                i2 = (r + 1) * cols + c
                i3 = i2 + 1
                full_indices.extend([i0, i2, i1, i1, i2, i3])
        upgraded = dict(orig)
        upgraded["indices"] = full_indices
        upgraded["method"] = "SOURCE_MESH_FULL_GRID_CONNECTIVITY_PRESERVING_GT_VERTICES"
        (geom_dir / f"{fid}_v16_mesh.json").write_text(json.dumps(upgraded) + "\n")

    # 3. Ecology Geometry — Reusing 100% Frozen V2 PNGs with Refined Placement & Depth Layering
    ecology_quads = [
        # Pass 05 FAR_ECOLOGY (deep underwater dark-teal background foliage & distant stones inside sphere)
        ("far_plant_left_bg", "05_FAR_ECOLOGY", "plant", "06_PLANTS/plant_left/plant_left.png",
         (300, 505, 520, 885), -8.0, False, 240.0, [0.42, 0.72, 0.56, 0.72], "RENDER_HYPOTHESIS_FAR_FLORA", "LOW"),
        ("far_plant_mid_bg", "05_FAR_ECOLOGY", "plant", "06_PLANTS/plant_right/plant_right.png",
         (480, 585, 645, 845), -10.0, False, 238.0, [0.36, 0.68, 0.54, 0.64], "RENDER_HYPOTHESIS_FAR_FLORA", "LOW"),
        ("far_plant_right_bg", "05_FAR_ECOLOGY", "plant", "06_PLANTS/plant_right/plant_right.png",
         (705, 535, 895, 855), 8.0, True, 236.0, [0.38, 0.70, 0.56, 0.68], "RENDER_HYPOTHESIS_FAR_FLORA", "LOW"),
        ("far_rock_left_bg", "05_FAR_ECOLOGY", "rock", "07_ROCKS/individual/rock_02.png",
         (355, 690, 565, 840), -6.0, False, 232.0, [0.48, 0.66, 0.72, 0.90], "RENDER_HYPOTHESIS_FAR_STONE", "LOW"),
        ("far_rock_right_bg", "05_FAR_ECOLOGY", "rock", "07_ROCKS/individual/rock_03.png",
         (740, 650, 920, 820), 5.0, True, 230.0, [0.46, 0.66, 0.68, 0.88], "RENDER_HYPOTHESIS_FAR_STONE", "LOW"),

        # Pass 08 NEAR_ECOLOGY (primary registered objects inside sphere)
        ("plant_mid_sprout", "08_NEAR_ECOLOGY", "plant", "06_PLANTS/plant_left/plant_left.png",
         (645, 650, 805, 870), -14.0, True, 128.0, [0.76, 0.96, 0.44, 0.95], "RENDER_HYPOTHESIS_MID_GRASS", "LOW"),
        ("plant_left", "08_NEAR_ECOLOGY", "plant", "06_PLANTS/plant_left/plant_left.png",
         (220, 385, 470, 890), -5.0, False, 122.0, [0.64, 0.92, 0.42, 1.0], "RENDER_HYPOTHESIS_LEFT_PLANT_REFINED", "MEDIUM_LOW"),
        ("plant_right", "08_NEAR_ECOLOGY", "plant", "06_PLANTS/plant_right/plant_right.png",
         (795, 495, 995, 868), 3.0, False, 120.0, [0.88, 1.02, 0.52, 1.0], "RENDER_HYPOTHESIS_RIGHT_PLANT_REFINED", "MEDIUM_LOW"),
        ("rock_02", "08_NEAR_ECOLOGY", "rock", "07_ROCKS/individual/rock_02.png",
         (712, 718, 902, 885), 6.0, False, 114.0, [0.80, 0.88, 0.82, 1.0], "RENDER_HYPOTHESIS_ROCK02_REFINED", "MEDIUM_LOW"),
        ("rock_01", "08_NEAR_ECOLOGY", "rock", "07_ROCKS/individual/rock_01.png",
         (385, 762, 695, 958), -3.0, False, 110.0, [0.76, 0.86, 0.92, 1.0], "RENDER_HYPOTHESIS_ROCK01_REFINED", "MEDIUM_LOW"),
        ("rock_03", "08_NEAR_ECOLOGY", "rock", "07_ROCKS/individual/rock_03.png",
         (585, 812, 835, 966), 4.0, False, 106.0, [1.04, 1.12, 0.42, 1.0], "RENDER_HYPOTHESIS_ROCK03_MOSS", "MEDIUM_LOW"),
    ]

    for (did, _, _, _, (x0, y0, x1, y1), rot, flip_x, _, _, _, _) in ecology_quads:
        q = build_rect_quad(x0, y0, x1, y1, z=0.0, rot_deg=rot, flip_x=flip_x)
        (geom_dir / f"{did}_v16_mesh.json").write_text(json.dumps(q) + "\n")

    # Inner micro-bubbles inside the water sphere (Pass 08 NEAR_ECOLOGY)
    inner_bubbles = [
        ("IB01", 0.398, 0.395, 0.0045), ("IB02", 0.405, 0.415, 0.0060),
        ("IB03", 0.412, 0.440, 0.0038), ("IB04", 0.394, 0.460, 0.0052),
        ("IB05", 0.408, 0.485, 0.0040), ("IB06", 0.418, 0.510, 0.0062),
        ("IB07", 0.365, 0.560, 0.0042), ("IB08", 0.378, 0.590, 0.0035),
        ("IB09", 0.530, 0.595, 0.0045), ("IB10", 0.542, 0.625, 0.0036),
        ("IB11", 0.720, 0.465, 0.0042), ("IB12", 0.735, 0.505, 0.0034),
        ("IB13", 0.340, 0.340, 0.0048), ("IB14", 0.352, 0.365, 0.0038),
    ]
    for bid, nx, ny, nr in inner_bubbles:
        cx, cy, r = nx * CANVAS, ny * CANVAS, nr * CANVAS
        q = build_rect_quad(cx - r, cy - r, cx + r, cy + r)
        (geom_dir / f"{bid}_quad.json").write_text(json.dumps(q) + "\n")

    # Outer glossy water droplets (reusing B01..B09 placements from V2 + refined crystal droplet shading)
    for b in bubble_cfg["bubbles"]:
        cx, cy = b["center"][0] * CANVAS, b["center"][1] * CANVAS
        r = b["radius_d"] * CANVAS * 0.92
        q = build_rect_quad(cx - r, cy - r, cx + r, cy + r)
        (geom_dir / f"{b['id']}_quad.json").write_text(json.dumps(q) + "\n")

    # 4. Assemble explicit 11-Stage Draw Bindings
    draws = []

    def add_draw(draw_id, pass_id, shader_program, mesh_path, material, texture_rel=None,
                 depth=0.0, depth_write=False, depth_test=True, blend_mode="ALPHA",
                 alpha_cutoff=0.0, ambient=0.45, shell_side=0.0, tint=None,
                 sphere_clip=False, ribbon_style=0.0, core_color=None, emission_override=None,
                 hypothesis_status="GROUND_TRUTH_VERIFIED", confidence="HIGH"):
        t_path, t_sha = tex_ref(texture_rel) if texture_rel else (None, None)
        entry = {
            "draw_id": draw_id,
            "pass_id": pass_id,
            "shader_program": shader_program,
            "mesh_path": mesh_path,
            "material": material,
            "texture_path": t_path,
            "texture_sha256": t_sha,
            "model": make_model(depth),
            "depth_write": depth_write,
            "depth_test": depth_test,
            "blend_mode": blend_mode,
            "alpha_cutoff": alpha_cutoff,
            "ambient": ambient,
            "shell_side": shell_side,
            "sphere_clip": sphere_clip,
            "ribbon_style": ribbon_style,
            "hypothesis_status": hypothesis_status,
            "confidence": confidence,
        }
        if tint is not None:
            entry["tint"] = tint
        if core_color is not None:
            entry["core_color"] = core_color
        if emission_override is not None:
            entry["emission_override"] = emission_override
        draws.append(entry)

    # Pass 01: Background (OPAQUE, depth_write=true)
    add_draw(
        "background", "01_BACKGROUND", "OPAQUE",
        "02_SUPPLEMENTED_ASSETS/geometry/environment_quad.json", "background",
        "01_BACKGROUND/background.png", depth=1900.0, depth_write=True, depth_test=True,
        blend_mode="NONE", ambient=1.0, hypothesis_status="SOURCE_ASSET_VERIFIED", confidence="HIGH"
    )

    # Pass 02: Resident Back (RESIDENT_RIBBON, depth_write=false, depth_test=true)
    for spec in resident_specs:
        if spec["pass_id"] == "02_RESIDENT_BACK":
            add_draw(
                spec["draw_id"], "02_RESIDENT_BACK", "RESIDENT_RIBBON",
                f"02_SUPPLEMENTED_ASSETS/geometry/{spec['draw_id']}_mesh.json", "resident",
                None, depth=spec["depth"], depth_write=False, depth_test=True,
                blend_mode="ALPHA", ambient=0.35, tint=spec["color"],
                ribbon_style=spec["ribbon_style"], core_color=spec["core_color"],
                emission_override=spec["emission"],
                hypothesis_status=spec["hypothesis"], confidence=spec["confidence"]
            )

    # Pass 03: Sphere Back Glass (GLASS, optical refraction back shell)
    add_draw(
        "sphere_back", "03_SPHERE_BACK", "GLASS",
        "02_SUPPLEMENTED_ASSETS/geometry/sphere_mesh.json", "glass",
        "03_SPHERE/glass_back_arc_mask.png", depth=450.0, depth_write=False, depth_test=True,
        blend_mode="ALPHA", ambient=0.45, shell_side=1.0,
        hypothesis_status="GROUND_TRUTH_SPHERE_CIRCLE", confidence="HIGH"
    )

    # Pass 04: Water Interior (WATER, optical normal distortion + meniscus + depth absorption)
    add_draw(
        "water", "04_WATER_INTERIOR", "WATER",
        "02_SUPPLEMENTED_ASSETS/geometry/water_disc.json", "water",
        "04_WATER/waterbed.png", depth=320.0, depth_write=False, depth_test=True,
        blend_mode="ALPHA", ambient=0.55, sphere_clip=True,
        hypothesis_status="RENDER_HYPOTHESIS_MENISCUS_AND_VOLUME", confidence="MEDIUM_LOW"
    )

    # Pass 05: Far Ecology (TRANSPARENT alpha-cutout, depth_write=true)
    for (did, pid, mat, tex, _, _, _, depth, tint, hyp, conf) in ecology_quads:
        if pid == "05_FAR_ECOLOGY":
            add_draw(
                did, pid, "TRANSPARENT",
                f"02_SUPPLEMENTED_ASSETS/geometry/{did}_v16_mesh.json", mat,
                tex, depth=depth, depth_write=True, depth_test=True,
                blend_mode="ALPHA", alpha_cutoff=0.22, ambient=0.58, tint=tint,
                sphere_clip=True, hypothesis_status=hyp, confidence=conf
            )

    # Pass 06: Lighting & Caustics (CAUSTICS + Volumetric Sunbeams, depth_write=false, additive)
    add_draw(
        "underwater_caustics_lighting", "06_LIGHTING", "CAUSTICS",
        "02_SUPPLEMENTED_ASSETS/geometry/water_disc.json", "water",
        "04_WATER/caustics/caustics_height.png", depth=200.0, depth_write=False, depth_test=True,
        blend_mode="ADDITIVE", ambient=1.0, sphere_clip=True,
        hypothesis_status="RENDER_HYPOTHESIS_VOLUMETRIC_CAUSTICS", confidence="MEDIUM_LOW"
    )

    # Pass 07: Four Fish (TRANSPARENT alpha-cutout, depth_write=true, Ground Truth transforms)
    fish_entries = [
        ("WW_FISH_01", "05_FISH/fish_01.png", 154.0, "GROUND_TRUTH_VERIFIED", "MEDIUM_HIGH"),
        ("WW_FISH_02", "05_FISH/fish_02.png", 152.0, "GROUND_TRUTH_VERIFIED", "MEDIUM_HIGH"),
        ("WW_FISH_03", "05_FISH/fish_03.png", 150.0, "RENDER_HYPOTHESIS_GROUND_TRUTH_MEDIUM", "MEDIUM"),
        ("WW_FISH_04", "05_FISH/fish_04.png", 148.0, "RENDER_HYPOTHESIS_GROUND_TRUTH_MEDIUM", "MEDIUM"),
    ]
    for fid, ftex, fdepth, fhyp, fconf in fish_entries:
        add_draw(
            fid, "07_FISH", "TRANSPARENT",
            f"02_SUPPLEMENTED_ASSETS/geometry/{fid}_v16_mesh.json", "fish",
            ftex, depth=fdepth, depth_write=True, depth_test=True,
            blend_mode="ALPHA", alpha_cutoff=0.14, ambient=0.68,
            tint=[1.05, 1.02, 0.92, 1.0], sphere_clip=True,
            hypothesis_status=fhyp, confidence=fconf
        )

    # Pass 08: Near Ecology + Inner Bubbles
    for (did, pid, mat, tex, _, _, _, depth, tint, hyp, conf) in ecology_quads:
        if pid == "08_NEAR_ECOLOGY":
            add_draw(
                did, pid, "TRANSPARENT",
                f"02_SUPPLEMENTED_ASSETS/geometry/{did}_v16_mesh.json", mat,
                tex, depth=depth, depth_write=True, depth_test=True,
                blend_mode="ALPHA", alpha_cutoff=0.18, ambient=0.52, tint=tint,
                sphere_clip=True, hypothesis_status=hyp, confidence=conf
            )
    for idx, (bid, _, _, _) in enumerate(inner_bubbles):
        add_draw(
            bid, "08_NEAR_ECOLOGY", "PARTICLE_BOKEH",
            f"02_SUPPLEMENTED_ASSETS/geometry/{bid}_quad.json", "bubble",
            "09_BUBBLES/bubble_sprite.png", depth=95.0 - idx * 0.2, depth_write=False, depth_test=True,
            blend_mode="ALPHA", ambient=1.0, tint=[0.85, 0.98, 1.0, 0.78],
            sphere_clip=True, hypothesis_status="RENDER_HYPOTHESIS_INNER_BUBBLES", confidence="LOW"
        )

    # Pass 09: Sphere Front Glass (GLASS, optical screen-space refraction + multi-ring Fresnel + sun flare)
    add_draw(
        "sphere_front", "09_SPHERE_FRONT", "GLASS",
        "02_SUPPLEMENTED_ASSETS/geometry/sphere_mesh.json", "glass",
        "03_SPHERE/glass_front_arc_mask.png", depth=-450.0, depth_write=False, depth_test=True,
        blend_mode="ALPHA", ambient=0.50, shell_side=-1.0,
        hypothesis_status="GROUND_TRUTH_SPHERE_CIRCLE", confidence="HIGH"
    )

    # Pass 10: Resident Front (RESIDENT_RIBBON, depth_write=false, depth_test=true)
    for spec in resident_specs:
        if spec["pass_id"] == "10_RESIDENT_FRONT":
            add_draw(
                spec["draw_id"], "10_RESIDENT_FRONT", "RESIDENT_RIBBON",
                f"02_SUPPLEMENTED_ASSETS/geometry/{spec['draw_id']}_mesh.json", "resident",
                None, depth=spec["depth"], depth_write=False, depth_test=True,
                blend_mode="ALPHA", ambient=0.35, tint=spec["color"],
                ribbon_style=spec["ribbon_style"], core_color=spec["core_color"],
                emission_override=spec["emission"],
                hypothesis_status=spec["hypothesis"], confidence=spec["confidence"]
            )

    # Pass 11: Final Composite — Outer Glossy Water Droplets (Bokeh + Bloom handled by optical nodes in Pass 11)
    for idx, b in enumerate(bubble_cfg["bubbles"]):
        add_draw(
            b["id"], "11_FINAL_COMPOSITE", "PARTICLE_BOKEH",
            f"02_SUPPLEMENTED_ASSETS/geometry/{b['id']}_quad.json", "bubble",
            "09_BUBBLES/bubble_sprite.png", depth=-600.0 - idx, depth_write=False, depth_test=True,
            blend_mode="ALPHA", ambient=1.0, tint=[0.90, 0.98, 1.0, 0.88],
            hypothesis_status="RENDER_HYPOTHESIS_OUTER_DROPLETS", confidence="LOW"
        )

    # 5. Write draw_bindings.json
    bindings_json = {
        "schema_version": "water-world-draw-bindings/2.0",
        "canvas_size": CANVAS,
        "time_seconds": 0,
        "pass_order": [
            "01_BACKGROUND",
            "02_RESIDENT_BACK",
            "03_SPHERE_BACK",
            "04_WATER_INTERIOR",
            "05_FAR_ECOLOGY",
            "06_LIGHTING",
            "07_FISH",
            "08_NEAR_ECOLOGY",
            "09_SPHERE_FRONT",
            "10_RESIDENT_FRONT",
            "11_FINAL_COMPOSITE",
        ],
        "optical_nodes": [
            "glass_refraction",
            "water_normal_distortion",
            "caustics",
            "bloom",
            "bokeh",
        ],
        "draws": draws,
        "status": "WORK1_6_ELEVEN_STAGE_RENDER_GRAPH",
        "missing_passes": [],
        "gpu_validation": "PENDING_DEVICE_HARDWARE_GPU",
    }
    (sg_dir / "draw_bindings.json").write_text(json.dumps(bindings_json, indent=2) + "\n")

    # 6. Write optical_config.json (shared 1:1 by GLSL ES 3.0 & Java Software Reference)
    t_water_norm, sha_water_norm = tex_ref("04_WATER/water_normal_tangent.png")
    t_water_depth, sha_water_depth = tex_ref("04_WATER/water_relative_depth_16bit.png")
    t_caustics, sha_caustics = tex_ref("04_WATER/caustics/caustics_height.png")
    t_glass_shell, sha_glass_shell = tex_ref("03_SPHERE/glass_shell_mask.png")
    t_cavity, sha_cavity = tex_ref("03_SPHERE/cavity_alpha.png")
    t_outer, sha_outer = tex_ref("03_SPHERE/sphere_outer_mask.png")

    optical_config = {
        "schema_version": "water-world-optics/1.0",
        "canvas_size": CANVAS,
        "sphere": {
            "center_px": [SPHERE_CX, SPHERE_CY],
            "outer_radius_px": SPHERE_R,
            "inner_radius_px": SPHERE_INNER_R,
        },
        "auxiliary_textures": {
            "water_normal": {"path": t_water_norm, "sha256": sha_water_norm},
            "water_depth": {"path": t_water_depth, "sha256": sha_water_depth},
            "caustics_height": {"path": t_caustics, "sha256": sha_caustics},
            "glass_shell_mask": {"path": t_glass_shell, "sha256": sha_glass_shell},
            "cavity_alpha": {"path": t_cavity, "sha256": sha_cavity},
            "sphere_outer_mask": {"path": t_outer, "sha256": sha_outer},
        },
        "background_atmosphere": {
            "top_glare_suppression": 0.90,
            "vignette_strength": 0.68,
            "deep_tint": [0.015, 0.105, 0.118],
            "top_center_halo_center": [0.56, 0.035],
            "top_center_halo_radius": 0.22,
            "top_center_halo_color": [0.14, 0.44, 0.48],
            "top_center_halo_intensity": 0.36,
        },
        "glass_refraction": {
            "enabled": True,
            "back_refraction_strength": 0.015,
            "front_refraction_strength": 0.028,
            "chromatic_dispersion": 0.0012,
            "fresnel_power": 4.0,
            "rim_color_cool": [0.54, 0.92, 1.0],
            "rim_color_warm": [1.0, 0.82, 0.36],
            "absorption_color": [0.02, 0.22, 0.30],
            "top_left_flare_norm": [0.336, 0.205],
            "top_left_flare_radius": 0.095,
            "top_left_flare_color": [1.0, 0.88, 0.46],
            "top_left_flare_intensity": 1.32,
            "bottom_right_flare_norm": [0.625, 0.772],
            "bottom_right_flare_radius": 0.088,
            "bottom_right_flare_color": [1.0, 0.86, 0.42],
            "bottom_right_flare_intensity": 1.26,
            "right_rim_arc_norm": [0.768, 0.445],
            "right_rim_arc_intensity": 0.76,
        },
        "water_normal_distortion": {
            "enabled": True,
            "normal_distortion_strength": 0.011,
            "meniscus_base_y_norm": 0.244,
            "meniscus_wave_amp_norm": 0.016,
            "meniscus_thickness_norm": 0.021,
            "air_dome_top_color": [0.10, 0.38, 0.52],
            "air_dome_warm_color": [1.0, 0.84, 0.42],
            "water_shallow_color": [0.05, 0.42, 0.52],
            "water_mid_color": [0.018, 0.29, 0.38],
            "water_deep_color": [0.010, 0.14, 0.18],
            "waterbed_blend": 0.16,
        },
        "caustics": {
            "enabled": True,
            "sun_origin_norm": [0.338, 0.212],
            "god_ray_intensity": 0.48,
            "god_ray_color": [1.0, 0.88, 0.46],
            "god_ray_beams": [
                {"angle_deg": 42.5, "width_deg": 2.4, "strength": 0.70},
                {"angle_deg": 51.0, "width_deg": 2.8, "strength": 0.96},
                {"angle_deg": 60.0, "width_deg": 3.0, "strength": 1.00},
                {"angle_deg": 69.5, "width_deg": 2.7, "strength": 0.90},
                {"angle_deg": 79.0, "width_deg": 2.3, "strength": 0.72},
                {"angle_deg": 88.5, "width_deg": 2.0, "strength": 0.52},
            ],
            "surface_caustic_strength": 0.62,
            "bed_caustic_strength": 0.54,
            "bed_caustic_color": [0.88, 0.95, 0.64],
        },
        "bloom": {
            "enabled": True,
            "threshold": 0.72,
            "soft_knee": 0.24,
            "downsample_scales": [2, 4],
            "blur_radius_px": 14,
            "strength": 0.44,
            "max_contribution": 0.56,
        },
        "bokeh": {
            "enabled": True,
            "orbs": [
                {"x": 0.052, "y": 0.598, "r": 0.026, "color": [0.95, 0.78, 0.28], "alpha": 0.46, "softness": 0.55, "layer": "BG"},
                {"x": 0.115, "y": 0.768, "r": 0.032, "color": [0.58, 0.86, 0.28], "alpha": 0.28, "softness": 0.65, "layer": "BG"},
                {"x": 0.105, "y": 0.092, "r": 0.028, "color": [0.22, 0.58, 0.52], "alpha": 0.24, "softness": 0.70, "layer": "BG"},
                {"x": 0.735, "y": 0.042, "r": 0.024, "color": [0.58, 0.76, 0.34], "alpha": 0.26, "softness": 0.65, "layer": "BG"},
                {"x": 0.915, "y": 0.165, "r": 0.026, "color": [0.16, 0.60, 0.64], "alpha": 0.28, "softness": 0.65, "layer": "BG"},
                {"x": 0.972, "y": 0.772, "r": 0.028, "color": [0.92, 0.74, 0.26], "alpha": 0.42, "softness": 0.52, "layer": "FG"},
                {"x": 0.895, "y": 0.865, "r": 0.030, "color": [0.22, 0.68, 0.62], "alpha": 0.24, "softness": 0.68, "layer": "BG"},
                {"x": 0.920, "y": 0.920, "r": 0.022, "color": [0.44, 0.72, 0.34], "alpha": 0.22, "softness": 0.65, "layer": "BG"},
                {"x": 0.180, "y": 0.925, "r": 0.034, "color": [0.18, 0.56, 0.54], "alpha": 0.22, "softness": 0.72, "layer": "BG"},
                {"x": 0.855, "y": 0.905, "r": 0.018, "color": [0.20, 0.66, 0.72], "alpha": 0.24, "softness": 0.62, "layer": "BG"},
                # Pinpoint glowing sparkles along ribbon intersections
                {"x": 0.182, "y": 0.242, "r": 0.032, "color": [1.0, 0.92, 0.60], "alpha": 0.72, "softness": 0.85, "layer": "FG"},
                {"x": 0.382, "y": 0.114, "r": 0.022, "color": [1.0, 0.90, 0.58], "alpha": 0.62, "softness": 0.85, "layer": "FG"},
                {"x": 0.972, "y": 0.415, "r": 0.022, "color": [1.0, 0.86, 0.48], "alpha": 0.68, "softness": 0.82, "layer": "FG"},
                {"x": 0.926, "y": 0.528, "r": 0.028, "color": [1.0, 0.90, 0.56], "alpha": 0.76, "softness": 0.82, "layer": "FG"},
                {"x": 0.686, "y": 0.892, "r": 0.024, "color": [0.58, 0.98, 1.0], "alpha": 0.72, "softness": 0.84, "layer": "FG"},
                {"x": 0.414, "y": 0.905, "r": 0.020, "color": [0.96, 1.0, 0.64], "alpha": 0.62, "softness": 0.84, "layer": "FG"},
                {"x": 0.502, "y": 0.892, "r": 0.014, "color": [1.0, 0.88, 0.52], "alpha": 0.64, "softness": 0.82, "layer": "FG"},
            ],
        },
    }
    (mat_dir / "optical_config.json").write_text(json.dumps(optical_config, indent=2) + "\n")

    # 7. Update Work 1.6 Ground Truth + Render Hypothesis + Render Plan + Occlusion Graph
    gt_old = json.loads((WORK16_OUT / "02_GROUND_TRUTH/GROUND_TRUTH_MANIFEST.json").read_text())
    hyp_map = {
        "WW_FISH_01": ("GROUND_TRUTH_VERIFIED", "MEDIUM_HIGH", "07_FISH"),
        "WW_FISH_02": ("GROUND_TRUTH_VERIFIED", "MEDIUM_HIGH", "07_FISH"),
        "WW_FISH_03": ("RENDER_HYPOTHESIS_RETAINING_UNKNOWN_GT", "MEDIUM", "07_FISH"),
        "WW_FISH_04": ("RENDER_HYPOTHESIS_RETAINING_UNKNOWN_GT", "MEDIUM", "07_FISH"),
        "G01": ("RENDER_HYPOTHESIS_BACK_AND_FRONT_SPLIT", "MEDIUM_LOW", "02_RESIDENT_BACK+10_RESIDENT_FRONT"),
        "G02": ("RENDER_HYPOTHESIS_BACK_AND_FRONT_SPLIT", "MEDIUM_LOW", "02_RESIDENT_BACK+10_RESIDENT_FRONT"),
        "A01": ("RENDER_HYPOTHESIS_BACK_AND_FRONT_SPLIT", "MEDIUM_LOW", "02_RESIDENT_BACK+10_RESIDENT_FRONT"),
        "A02": ("RENDER_HYPOTHESIS_BACK_AND_FRONT_SPLIT", "MEDIUM_LOW", "02_RESIDENT_BACK+10_RESIDENT_FRONT"),
        "C01": ("RENDER_HYPOTHESIS_BACK_AND_FRONT_SPLIT", "MEDIUM_LOW", "02_RESIDENT_BACK+10_RESIDENT_FRONT"),
        "plant_left": ("RENDER_HYPOTHESIS_INNER_LEFT_CURVE", "MEDIUM_LOW", "08_NEAR_ECOLOGY"),
        "plant_right": ("RENDER_HYPOTHESIS_INNER_RIGHT_CURVE", "MEDIUM_LOW", "08_NEAR_ECOLOGY"),
        "rock_01": ("RENDER_HYPOTHESIS_LOWER_LEFT_BOULDER", "MEDIUM_LOW", "08_NEAR_ECOLOGY"),
        "rock_02": ("RENDER_HYPOTHESIS_LOWER_RIGHT_STONE", "MEDIUM_LOW", "08_NEAR_ECOLOGY"),
        "rock_03": ("RENDER_HYPOTHESIS_FRONT_MOSS_STONE", "MEDIUM_LOW", "08_NEAR_ECOLOGY"),
    }
    gt_and_hyp = {
        "schema": "water-world-ground-truth-and-hypothesis/1.0",
        "master_sha256": EXPECTED_MASTER_SHA,
        "v2_zip_sha256": EXPECTED_V2_SHA,
        "v21_zip_sha256": EXPECTED_V21_SHA,
        "policy": "Render Hypotheses never overwrite Ground Truth UNKNOWN statuses.",
        "sphere": {
            "object_id": "WW_SPHERE",
            "ground_truth_status": "PASS_OUTER_CIRCLE_HIGH_CONFIDENCE",
            "center_px": [SPHERE_CX, SPHERE_CY],
            "outer_radius_px": SPHERE_R,
            "inner_radius_px": SPHERE_INNER_R,
            "source_asset": "01_REUSED_ASSETS/03_SPHERE/sphere_outer_mask.png",
            "source_sha256": v2_shas["03_SPHERE/sphere_outer_mask.png"],
        },
        "objects": [],
    }
    for obj in gt_old["objects"]:
        oid = obj["object_id"]
        h_status, h_conf, h_pass = hyp_map[oid]
        gt_and_hyp["objects"].append({
            "object_id": oid,
            "source_asset": obj["source_asset"],
            "source_sha256": obj["source_sha256"],
            "ground_truth_metric_status": obj["metric_status"],
            "ground_truth_confidence": obj["geometry_confidence"],
            "render_hypothesis": {
                "status": h_status,
                "confidence": h_conf,
                "assigned_pass": h_pass,
                "source": "analytical_master_visual_layout_inference",
            },
        })
    (WORK16_OUT / "02_GROUND_TRUTH/GROUND_TRUTH_AND_HYPOTHESIS.json").write_text(
        json.dumps(gt_and_hyp, indent=2, ensure_ascii=False) + "\n"
    )

    # Update OCCLUSION_GRAPH_CANONICAL.json: preserve UNKNOWN GT + add separate render_hypothesis_occlusion
    occ = json.loads((WORK16_OUT / "03_SCENE_GRAPH/OCCLUSION_GRAPH_CANONICAL.json").read_text())
    occ["render_hypothesis_occlusion"] = {
        "note": "Explicit Render Hypothesis for 11-stage front/back sorting; NOT promoted to Ground Truth.",
        "confidence": "MEDIUM_LOW",
        "source": "analytical_master_crossing_inference",
        "edges": [
            {"front": "10_RESIDENT_FRONT", "back": "09_SPHERE_FRONT"},
            {"front": "09_SPHERE_FRONT", "back": "08_NEAR_ECOLOGY"},
            {"front": "08_NEAR_ECOLOGY", "back": "07_FISH"},
            {"front": "07_FISH", "back": "06_LIGHTING"},
            {"front": "06_LIGHTING", "back": "05_FAR_ECOLOGY"},
            {"front": "05_FAR_ECOLOGY", "back": "04_WATER_INTERIOR"},
            {"front": "04_WATER_INTERIOR", "back": "03_SPHERE_BACK"},
            {"front": "03_SPHERE_BACK", "back": "02_RESIDENT_BACK"},
            {"front": "02_RESIDENT_BACK", "back": "01_BACKGROUND"},
        ],
        "resident_split_meshes": {
            rid: {
                "back_segments": [s["draw_id"] for s in resident_specs if s["resident_id"] == rid and s["pass_id"] == "02_RESIDENT_BACK"],
                "front_segments": [s["draw_id"] for s in resident_specs if s["resident_id"] == rid and s["pass_id"] == "10_RESIDENT_FRONT"],
                "ground_truth_status": "UNKNOWN_FRONT_BACK_NO_CONFIRMED_OCCLUSION",
                "hypothesis_status": "RENDER_HYPOTHESIS_SPLIT_MESH",
            }
            for rid in ("G01", "G02", "A01", "A02", "C01")
        },
    }
    (WORK16_OUT / "03_SCENE_GRAPH/OCCLUSION_GRAPH_CANONICAL.json").write_text(
        json.dumps(occ, indent=2, ensure_ascii=False) + "\n"
    )

    # Update RENDER_PLAN.json with true 11 composition stages + 5 optical nodes
    pass_counts = {}
    for d in draws:
        pass_counts[d["pass_id"]] = pass_counts.get(d["pass_id"], 0) + 1
    render_plan = {
        "schema": "water-world-render-plan/2.0",
        "canvas": [CANVAS, CANVAS],
        "time_seconds": 0,
        "composition_stages": [
            {"order": 1, "pass_id": "01_BACKGROUND", "shader": "OPAQUE", "draws": pass_counts.get("01_BACKGROUND", 0), "depth_test": True, "depth_write": True, "blend": "NONE"},
            {"order": 2, "pass_id": "02_RESIDENT_BACK", "shader": "RESIDENT_RIBBON", "draws": pass_counts.get("02_RESIDENT_BACK", 0), "depth_test": True, "depth_write": False, "blend": "ALPHA"},
            {"order": 3, "pass_id": "03_SPHERE_BACK", "shader": "GLASS", "draws": pass_counts.get("03_SPHERE_BACK", 0), "depth_test": True, "depth_write": False, "blend": "ALPHA"},
            {"order": 4, "pass_id": "04_WATER_INTERIOR", "shader": "WATER", "draws": pass_counts.get("04_WATER_INTERIOR", 0), "depth_test": True, "depth_write": False, "blend": "ALPHA"},
            {"order": 5, "pass_id": "05_FAR_ECOLOGY", "shader": "TRANSPARENT", "draws": pass_counts.get("05_FAR_ECOLOGY", 0), "depth_test": True, "depth_write": True, "blend": "ALPHA_CUTOUT"},
            {"order": 6, "pass_id": "06_LIGHTING", "shader": "CAUSTICS", "draws": pass_counts.get("06_LIGHTING", 0), "depth_test": True, "depth_write": False, "blend": "ADDITIVE"},
            {"order": 7, "pass_id": "07_FISH", "shader": "TRANSPARENT", "draws": pass_counts.get("07_FISH", 0), "depth_test": True, "depth_write": True, "blend": "ALPHA_CUTOUT"},
            {"order": 8, "pass_id": "08_NEAR_ECOLOGY", "shader": "TRANSPARENT+PARTICLE_BOKEH", "draws": pass_counts.get("08_NEAR_ECOLOGY", 0), "depth_test": True, "depth_write": "PER_MATERIAL", "blend": "ALPHA_CUTOUT_AND_ALPHA"},
            {"order": 9, "pass_id": "09_SPHERE_FRONT", "shader": "GLASS", "draws": pass_counts.get("09_SPHERE_FRONT", 0), "depth_test": True, "depth_write": False, "blend": "ALPHA"},
            {"order": 10, "pass_id": "10_RESIDENT_FRONT", "shader": "RESIDENT_RIBBON", "draws": pass_counts.get("10_RESIDENT_FRONT", 0), "depth_test": True, "depth_write": False, "blend": "ALPHA"},
            {"order": 11, "pass_id": "11_FINAL_COMPOSITE", "shader": "BLOOM+PARTICLE_BOKEH", "draws": pass_counts.get("11_FINAL_COMPOSITE", 0), "depth_test": True, "depth_write": False, "blend": "COMPOSITE"},
        ],
        "optical_nodes": [
            {"id": "glass_refraction", "attached_stages": ["03_SPHERE_BACK", "09_SPHERE_FRONT"], "shader": "GLASS", "status": "IMPLEMENTED"},
            {"id": "water_normal_distortion", "attached_stages": ["04_WATER_INTERIOR"], "shader": "WATER", "status": "IMPLEMENTED"},
            {"id": "caustics", "attached_stages": ["06_LIGHTING", "08_NEAR_ECOLOGY"], "shader": "CAUSTICS", "status": "IMPLEMENTED"},
            {"id": "bloom", "attached_stages": ["11_FINAL_COMPOSITE"], "shader": "BLOOM", "subpasses": ["bright_pass", "downsample_2x", "downsample_4x", "blur_h", "blur_v", "composite"], "status": "IMPLEMENTED"},
            {"id": "bokeh", "attached_stages": ["01_BACKGROUND", "08_NEAR_ECOLOGY", "11_FINAL_COMPOSITE"], "shader": "PARTICLE_BOKEH", "status": "IMPLEMENTED"},
        ],
        "total_draws": len(draws),
        "stage_count": 11,
        "optical_node_count": 5,
        "hardware_gpu": "NOT_RUN",
        "status": "ELEVEN_STAGE_PLUS_FIVE_OPTICS_IMPLEMENTED",
    }
    (WORK16_OUT / "04_RENDER_PLAN/RENDER_PLAN.json").write_text(json.dumps(render_plan, indent=2) + "\n")

    print(f"BUILD_WORK16_SCENE_OK: stages=11 optical_nodes=5 total_draws={len(draws)}")


if __name__ == "__main__":
    run()
