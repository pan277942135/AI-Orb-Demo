"""Water World V2.1 Work 2 — 5-Round Evidence-Driven Static Fidelity Scene Builder.

Preserves 100% of frozen V2 archive assets (65 members verified by SHA-256), never references
or binds Master PNG as a texture, keeps Work 1.6 evidence directory immutable, preserves all
Ground Truth UNKNOWN statuses (never promoting Render Hypotheses to Ground Truth), and applies
reproducible round-by-round static geometry, glass, ecology, Resident, and optical refinements
(--round 1..5) directly into the production Android scene JSONs & meshes.
"""
import argparse
import hashlib
import json
import math
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
V2_ZIP = ROOT / "releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip"
V21_ZIP = ROOT / "releases/water-world-v2-v21/delivery/WATER_WORLD_V2_1_ASSETS.zip"
MASTER_PNG = ROOT / "releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png"
SCENE_OUT = ROOT / "releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1"
WORK2_OUT = ROOT / "releases/water-world-v2-v21/work2_gpu_static_fidelity"

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


def build_ribbon_mesh(control_pts_norm, z_depth=0.0, samples_per_seg=26):
    pts_px = [(x * CANVAS, y * CANVAS, w) for (x, y, w) in control_pts_norm]
    curve = catmull_rom_chain(pts_px, samples_per_seg=samples_per_seg)
    cum = [0.0]
    for i in range(1, len(curve)):
        cum.append(cum[-1] + math.hypot(curve[i][0] - curve[i - 1][0], curve[i][1] - curve[i - 1][1]))
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


def build_disc_mesh(cx, cy, radius, segments=128):
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


def get_resident_specs(round_num: int):
    # Rounds 1..3 keep Work 1.6 resident splines so Round 4 delta is 100% isolated and measurable
    if round_num < 4:
        return [
            {
                "draw_id": "G01_back_arch", "resident_id": "G01", "pass_id": "02_RESIDENT_BACK", "depth": 520.0,
                "color": [0.42, 0.84, 0.16, 0.94], "core_color": [0.84, 1.0, 0.36], "emission": 1.24, "ribbon_style": 0.0,
                "points": [(0.24, 0.165, 11.0), (0.41, 0.095, 25.0), (0.575, 0.072, 34.0), (0.715, 0.140, 44.0),
                           (0.828, 0.258, 44.0), (0.905, 0.382, 34.0), (0.895, 0.520, 22.0), (0.835, 0.625, 10.0)],
                "hypothesis": "RENDER_HYPOTHESIS_UPPER_RIGHT_BACK_ARCH", "confidence": "MEDIUM_LOW",
            },
            {
                "draw_id": "G01_back_outer_veil", "resident_id": "G01", "pass_id": "02_RESIDENT_BACK", "depth": 515.0,
                "color": [0.28, 0.74, 0.20, 0.72], "core_color": [0.74, 0.98, 0.36], "emission": 1.02, "ribbon_style": 0.0,
                "points": [(0.65, 0.155, 14.0), (0.795, 0.220, 30.0), (0.920, 0.340, 30.0), (0.950, 0.468, 20.0), (0.892, 0.585, 11.0)],
                "hypothesis": "RENDER_HYPOTHESIS_RIGHT_SECONDARY_VEIL", "confidence": "LOW",
            },
            {
                "draw_id": "G01_front_lower_sweep", "resident_id": "G01", "pass_id": "10_RESIDENT_FRONT", "depth": -520.0,
                "color": [0.50, 0.86, 0.18, 0.92], "core_color": [0.92, 1.0, 0.40], "emission": 1.22, "ribbon_style": 0.0,
                "points": [(0.112, 0.505, 12.0), (0.132, 0.635, 32.0), (0.205, 0.755, 40.0), (0.332, 0.852, 38.0),
                           (0.432, 0.932, 28.0), (0.562, 0.972, 12.0)],
                "hypothesis": "RENDER_HYPOTHESIS_LOWER_LEFT_FRONT_SWEEP", "confidence": "MEDIUM_LOW",
            },
            {
                "draw_id": "G02_back_upper_arc", "resident_id": "G02", "pass_id": "02_RESIDENT_BACK", "depth": 505.0,
                "color": [1.0, 0.56, 0.06, 0.96], "core_color": [1.0, 0.92, 0.48], "emission": 1.48, "ribbon_style": 1.0,
                "points": [(0.068, 0.410, 12.0), (0.125, 0.275, 26.0), (0.185, 0.182, 32.0), (0.280, 0.118, 28.0),
                           (0.398, 0.112, 24.0), (0.520, 0.145, 16.0), (0.620, 0.185, 8.0)],
                "hypothesis": "RENDER_HYPOTHESIS_UPPER_LEFT_GOLD_BACK_ARC", "confidence": "MEDIUM_LOW",
            },
            {
                "draw_id": "G02_back_upper_hairline", "resident_id": "G02", "pass_id": "02_RESIDENT_BACK", "depth": 502.0,
                "color": [1.0, 0.62, 0.10, 0.88], "core_color": [1.0, 0.94, 0.56], "emission": 1.38, "ribbon_style": 1.0,
                "points": [(0.095, 0.360, 9.0), (0.155, 0.215, 22.0), (0.248, 0.082, 18.0), (0.395, 0.094, 14.0), (0.545, 0.132, 8.0)],
                "hypothesis": "RENDER_HYPOTHESIS_UPPER_LEFT_FINE_STRAND", "confidence": "LOW",
            },
            {
                "draw_id": "G02_back_right_rim", "resident_id": "G02", "pass_id": "02_RESIDENT_BACK", "depth": 500.0,
                "color": [1.0, 0.60, 0.08, 0.88], "core_color": [1.0, 0.92, 0.50], "emission": 1.35, "ribbon_style": 1.0,
                "points": [(0.835, 0.238, 8.0), (0.952, 0.358, 17.0), (0.972, 0.488, 19.0), (0.930, 0.622, 15.0)],
                "hypothesis": "RENDER_HYPOTHESIS_FAR_RIGHT_GOLD_LOOP", "confidence": "LOW",
            },
            {
                "draw_id": "G02_front_right_embrace", "resident_id": "G02", "pass_id": "10_RESIDENT_FRONT", "depth": -505.0,
                "color": [1.0, 0.56, 0.06, 0.96], "core_color": [1.0, 0.93, 0.50], "emission": 1.52, "ribbon_style": 1.0,
                "points": [(0.815, 0.432, 13.0), (0.926, 0.542, 30.0), (0.918, 0.675, 30.0), (0.798, 0.760, 26.0),
                           (0.640, 0.776, 24.0), (0.485, 0.758, 11.0)],
                "hypothesis": "RENDER_HYPOTHESIS_RIGHT_LOWER_GOLD_FRONT", "confidence": "MEDIUM_LOW",
            },
            {
                "draw_id": "A01_back_upper_left", "resident_id": "A01", "pass_id": "02_RESIDENT_BACK", "depth": 495.0,
                "color": [0.06, 0.78, 1.0, 0.92], "core_color": [0.74, 0.98, 1.0], "emission": 1.36, "ribbon_style": 2.0,
                "points": [(0.222, 0.198, 10.0), (0.115, 0.282, 24.0), (0.048, 0.372, 30.0), (0.038, 0.455, 32.0)],
                "hypothesis": "RENDER_HYPOTHESIS_UPPER_LEFT_CYAN_ENTRY", "confidence": "MEDIUM_LOW",
            },
            {
                "draw_id": "A01_front_left_embrace", "resident_id": "A01", "pass_id": "10_RESIDENT_FRONT", "depth": -510.0,
                "color": [0.06, 0.82, 1.0, 0.96], "core_color": [0.78, 0.99, 1.0], "emission": 1.48, "ribbon_style": 2.0,
                "points": [(0.038, 0.455, 30.0), (0.092, 0.530, 34.0), (0.198, 0.612, 30.0), (0.328, 0.718, 30.0),
                           (0.482, 0.782, 26.0), (0.635, 0.788, 17.0), (0.748, 0.765, 9.0)],
                "hypothesis": "RENDER_HYPOTHESIS_LEFT_LOWER_CYAN_FRONT", "confidence": "MEDIUM_LOW",
            },
            {
                "draw_id": "A02_back_lower_halo", "resident_id": "A02", "pass_id": "02_RESIDENT_BACK", "depth": 490.0,
                "color": [0.06, 0.76, 0.98, 0.88], "core_color": [0.68, 0.97, 1.0], "emission": 1.28, "ribbon_style": 2.0,
                "points": [(0.335, 0.835, 11.0), (0.490, 0.908, 26.0), (0.655, 0.896, 30.0), (0.766, 0.828, 25.0), (0.838, 0.715, 15.0)],
                "hypothesis": "RENDER_HYPOTHESIS_BOTTOM_CYAN_OUTER_HALO", "confidence": "MEDIUM_LOW",
            },
            {
                "draw_id": "A02_front_lower_strand", "resident_id": "A02", "pass_id": "10_RESIDENT_FRONT", "depth": -508.0,
                "color": [0.08, 0.80, 1.0, 0.88], "core_color": [0.74, 0.98, 1.0], "emission": 1.32, "ribbon_style": 2.0,
                "points": [(0.068, 0.408, 13.0), (0.118, 0.545, 22.0), (0.232, 0.678, 24.0), (0.390, 0.766, 22.0),
                           (0.562, 0.802, 17.0), (0.700, 0.785, 10.0)],
                "hypothesis": "RENDER_HYPOTHESIS_LEFT_SECONDARY_CYAN_STRAND", "confidence": "LOW",
            },
            {
                "draw_id": "C01_back_lower_thread", "resident_id": "C01", "pass_id": "02_RESIDENT_BACK", "depth": 485.0,
                "color": [1.0, 0.64, 0.14, 0.84], "core_color": [1.0, 0.92, 0.54], "emission": 1.32, "ribbon_style": 1.0,
                "points": [(0.128, 0.555, 8.0), (0.210, 0.750, 15.0), (0.372, 0.842, 15.0), (0.495, 0.902, 12.0), (0.515, 0.945, 7.0)],
                "hypothesis": "RENDER_HYPOTHESIS_LOWER_LEFT_GOLD_THREAD", "confidence": "LOW",
            },
            {
                "draw_id": "C01_front_lower_right", "resident_id": "C01", "pass_id": "10_RESIDENT_FRONT", "depth": -512.0,
                "color": [1.0, 0.62, 0.12, 0.95], "core_color": [1.0, 0.95, 0.58], "emission": 1.54, "ribbon_style": 1.0,
                "points": [(0.828, 0.485, 11.0), (0.905, 0.598, 24.0), (0.860, 0.715, 28.0), (0.738, 0.768, 28.0),
                           (0.605, 0.775, 25.0), (0.455, 0.745, 13.0)],
                "hypothesis": "RENDER_HYPOTHESIS_LOWER_RIGHT_GOLD_FRONT", "confidence": "MEDIUM_LOW",
            },
        ]

    # Round 4+ Work 2 Refined 5-Resident Splines:
    # Sleeker silk-filament width modulation, controlled top-arch luminance matching Master top-strip (0.212),
    # warmer lower-right gold (G02/C01) & balanced aqua-cyan (A01/A02). All occlusion remains RENDER_HYPOTHESIS.
    return [
        # G01: Sunlit emerald-chartreuse silk sweeping upper-right back & dramatic lower-left front arc
        {
            "draw_id": "G01_back_arch", "resident_id": "G01", "pass_id": "02_RESIDENT_BACK", "depth": 520.0,
            "color": [0.48, 0.85, 0.15, 0.92], "core_color": [0.88, 1.0, 0.40], "emission": 1.16, "ribbon_style": 0.0,
            "points": [
                (0.242, 0.165, 10.0), (0.412, 0.098, 22.0), (0.576, 0.076, 30.0), (0.715, 0.142, 40.0),
                (0.826, 0.258, 42.0), (0.902, 0.382, 32.0), (0.892, 0.520, 21.0), (0.834, 0.624, 10.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_WORK2_G01_UPPER_RIGHT_BACK", "confidence": "MEDIUM_LOW",
        },
        {
            "draw_id": "G01_back_outer_veil", "resident_id": "G01", "pass_id": "02_RESIDENT_BACK", "depth": 515.0,
            "color": [0.34, 0.76, 0.18, 0.70], "core_color": [0.78, 0.98, 0.38], "emission": 0.98, "ribbon_style": 0.0,
            "points": [
                (0.650, 0.155, 13.0), (0.794, 0.220, 28.0), (0.916, 0.340, 28.0), (0.946, 0.468, 19.0), (0.890, 0.584, 10.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_WORK2_G01_SECONDARY_VEIL", "confidence": "LOW",
        },
        {
            "draw_id": "G01_front_lower_sweep", "resident_id": "G01", "pass_id": "10_RESIDENT_FRONT", "depth": -520.0,
            "color": [0.54, 0.87, 0.16, 0.92], "core_color": [0.94, 1.0, 0.42], "emission": 1.20, "ribbon_style": 0.0,
            "points": [
                (0.112, 0.506, 11.0), (0.134, 0.636, 30.0), (0.206, 0.755, 38.0), (0.334, 0.852, 36.0),
                (0.434, 0.930, 26.0), (0.562, 0.968, 11.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_WORK2_G01_LOWER_LEFT_FRONT", "confidence": "MEDIUM_LOW",
        },
        # G02: Fiery amber-gold filament framing upper-left glass rim & lower-right golden embrace
        {
            "draw_id": "G02_back_upper_arc", "resident_id": "G02", "pass_id": "02_RESIDENT_BACK", "depth": 505.0,
            "color": [1.0, 0.58, 0.06, 0.94], "core_color": [1.0, 0.93, 0.50], "emission": 1.36, "ribbon_style": 1.0,
            "points": [
                (0.068, 0.410, 11.0), (0.125, 0.275, 24.0), (0.186, 0.184, 29.0), (0.280, 0.120, 25.0),
                (0.398, 0.114, 21.0), (0.520, 0.146, 14.0), (0.618, 0.186, 7.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_WORK2_G02_UPPER_LEFT_BACK", "confidence": "MEDIUM_LOW",
        },
        {
            "draw_id": "G02_back_upper_hairline", "resident_id": "G02", "pass_id": "02_RESIDENT_BACK", "depth": 502.0,
            "color": [1.0, 0.62, 0.10, 0.84], "core_color": [1.0, 0.94, 0.56], "emission": 1.24, "ribbon_style": 1.0,
            "points": [
                (0.095, 0.360, 8.0), (0.155, 0.216, 19.0), (0.248, 0.086, 15.0), (0.395, 0.096, 12.0), (0.544, 0.134, 7.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_WORK2_G02_UPPER_HAIRLINE", "confidence": "LOW",
        },
        {
            "draw_id": "G02_back_right_rim", "resident_id": "G02", "pass_id": "02_RESIDENT_BACK", "depth": 500.0,
            "color": [1.0, 0.60, 0.08, 0.88], "core_color": [1.0, 0.93, 0.52], "emission": 1.38, "ribbon_style": 1.0,
            "points": [
                (0.834, 0.240, 8.0), (0.948, 0.358, 17.0), (0.968, 0.488, 19.0), (0.926, 0.622, 15.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_WORK2_G02_RIGHT_RIM_BACK", "confidence": "LOW",
        },
        {
            "draw_id": "G02_front_right_embrace", "resident_id": "G02", "pass_id": "10_RESIDENT_FRONT", "depth": -505.0,
            "color": [1.0, 0.58, 0.06, 0.96], "core_color": [1.0, 0.94, 0.54], "emission": 1.54, "ribbon_style": 1.0,
            "points": [
                (0.816, 0.434, 13.0), (0.924, 0.542, 29.0), (0.916, 0.674, 29.0), (0.796, 0.758, 25.0),
                (0.640, 0.775, 23.0), (0.485, 0.758, 11.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_WORK2_G02_RIGHT_LOWER_FRONT", "confidence": "MEDIUM_LOW",
        },
        # A01: Electric aqua-cyan water-light sweeping left equator & lower-left front glass rim
        {
            "draw_id": "A01_back_upper_left", "resident_id": "A01", "pass_id": "02_RESIDENT_BACK", "depth": 495.0,
            "color": [0.06, 0.78, 0.94, 0.90], "core_color": [0.74, 0.98, 0.99], "emission": 1.28, "ribbon_style": 2.0,
            "points": [
                (0.222, 0.198, 9.5), (0.116, 0.282, 22.0), (0.049, 0.372, 28.0), (0.039, 0.455, 30.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_WORK2_A01_UPPER_LEFT_BACK", "confidence": "MEDIUM_LOW",
        },
        {
            "draw_id": "A01_front_left_embrace", "resident_id": "A01", "pass_id": "10_RESIDENT_FRONT", "depth": -510.0,
            "color": [0.06, 0.82, 0.96, 0.94], "core_color": [0.78, 0.99, 1.0], "emission": 1.42, "ribbon_style": 2.0,
            "points": [
                (0.039, 0.455, 28.0), (0.094, 0.530, 32.0), (0.199, 0.612, 28.0), (0.329, 0.718, 28.0),
                (0.482, 0.782, 25.0), (0.635, 0.788, 16.0), (0.746, 0.765, 9.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_WORK2_A01_LOWER_LEFT_FRONT", "confidence": "MEDIUM_LOW",
        },
        # A02: Luminous lower outer aqua-cyan arc underneath crystal sphere & secondary left strand
        {
            "draw_id": "A02_back_lower_halo", "resident_id": "A02", "pass_id": "02_RESIDENT_BACK", "depth": 490.0,
            "color": [0.06, 0.76, 0.94, 0.86], "core_color": [0.68, 0.97, 0.99], "emission": 1.24, "ribbon_style": 2.0,
            "points": [
                (0.336, 0.835, 10.5), (0.490, 0.906, 25.0), (0.654, 0.895, 28.0), (0.765, 0.827, 24.0), (0.836, 0.715, 14.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_WORK2_A02_BOTTOM_HALO_BACK", "confidence": "MEDIUM_LOW",
        },
        {
            "draw_id": "A02_front_lower_strand", "resident_id": "A02", "pass_id": "10_RESIDENT_FRONT", "depth": -508.0,
            "color": [0.08, 0.80, 0.95, 0.86], "core_color": [0.74, 0.98, 0.99], "emission": 1.28, "ribbon_style": 2.0,
            "points": [
                (0.069, 0.408, 12.0), (0.119, 0.545, 21.0), (0.233, 0.678, 23.0), (0.390, 0.766, 21.0),
                (0.562, 0.801, 16.0), (0.699, 0.785, 9.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_WORK2_A02_LOWER_STRAND_FRONT", "confidence": "LOW",
        },
        # C01: Warm fiery-gold lower-right front loop & subtle lower-left warm thread
        {
            "draw_id": "C01_back_lower_thread", "resident_id": "C01", "pass_id": "02_RESIDENT_BACK", "depth": 485.0,
            "color": [1.0, 0.64, 0.14, 0.86], "core_color": [1.0, 0.93, 0.56], "emission": 1.38, "ribbon_style": 1.0,
            "points": [
                (0.128, 0.555, 8.0), (0.211, 0.750, 15.0), (0.372, 0.842, 15.0), (0.495, 0.901, 12.0), (0.515, 0.944, 7.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_WORK2_C01_LOWER_THREAD_BACK", "confidence": "LOW",
        },
        {
            "draw_id": "C01_front_lower_right", "resident_id": "C01", "pass_id": "10_RESIDENT_FRONT", "depth": -512.0,
            "color": [1.0, 0.62, 0.12, 0.96], "core_color": [1.0, 0.96, 0.60], "emission": 1.62, "ribbon_style": 1.0,
            "points": [
                (0.827, 0.485, 11.0), (0.903, 0.598, 24.0), (0.859, 0.714, 27.0), (0.737, 0.767, 27.0),
                (0.604, 0.774, 25.0), (0.456, 0.745, 13.0),
            ],
            "hypothesis": "RENDER_HYPOTHESIS_WORK2_C01_LOWER_RIGHT_FRONT", "confidence": "MEDIUM_LOW",
        },
    ]


def get_ecology_quads(round_num: int):
    # Round 1: heatmap-guided geometry refinement for plants & rocks
    # Round 3: natural sunlit emerald-gold foliage & mossy rock shading tints
    if round_num >= 3:
        plant_left_tint = [0.68, 0.94, 0.42, 1.0]
        plant_right_tint = [0.88, 1.02, 0.50, 1.0]
        plant_mid_tint = [0.78, 0.98, 0.44, 0.96]
        rock_01_tint = [0.78, 0.90, 0.94, 1.0]
        rock_02_tint = [0.82, 0.90, 0.82, 1.0]
        rock_03_tint = [0.86, 0.96, 0.38, 1.0]  # Natural sunlit olive-moss stone matching Master
    else:
        plant_left_tint = [0.64, 0.92, 0.42, 1.0]
        plant_right_tint = [0.88, 1.02, 0.52, 1.0]
        plant_mid_tint = [0.76, 0.96, 0.44, 0.95]
        rock_01_tint = [0.76, 0.86, 0.92, 1.0]
        rock_02_tint = [0.80, 0.88, 0.82, 1.0]
        rock_03_tint = [1.04, 1.12, 0.42, 1.0]

    return [
        # Pass 05 FAR_ECOLOGY (deep background flora & distant seabed stones)
        ("far_plant_left_bg", "05_FAR_ECOLOGY", "plant", "06_PLANTS/plant_left/plant_left.png",
         (302, 504, 522, 884), -7.5, False, 240.0, [0.42, 0.72, 0.56, 0.72], "RENDER_HYPOTHESIS_WORK2_FAR_FLORA", "LOW"),
        ("far_plant_mid_bg", "05_FAR_ECOLOGY", "plant", "06_PLANTS/plant_right/plant_right.png",
         (480, 582, 646, 845), -9.5, False, 238.0, [0.36, 0.68, 0.54, 0.65], "RENDER_HYPOTHESIS_WORK2_FAR_FLORA", "LOW"),
        ("far_plant_right_bg", "05_FAR_ECOLOGY", "plant", "06_PLANTS/plant_right/plant_right.png",
         (704, 534, 894, 855), 7.5, True, 236.0, [0.38, 0.70, 0.56, 0.68], "RENDER_HYPOTHESIS_WORK2_FAR_FLORA", "LOW"),
        ("far_rock_left_bg", "05_FAR_ECOLOGY", "rock", "07_ROCKS/individual/rock_02.png",
         (356, 690, 564, 840), -6.0, False, 232.0, [0.48, 0.66, 0.72, 0.90], "RENDER_HYPOTHESIS_WORK2_FAR_STONE", "LOW"),
        ("far_rock_right_bg", "05_FAR_ECOLOGY", "rock", "07_ROCKS/individual/rock_03.png",
         (738, 650, 920, 820), 5.0, True, 230.0, [0.46, 0.66, 0.68, 0.88], "RENDER_HYPOTHESIS_WORK2_FAR_STONE", "LOW"),

        # Pass 08 NEAR_ECOLOGY (Round 1 heatmap-guided spatial geometry anchored to inner glass curve)
        ("plant_mid_sprout", "08_NEAR_ECOLOGY", "plant", "06_PLANTS/plant_left/plant_left.png",
         (642, 648, 804, 868), -13.5, True, 128.0, plant_mid_tint, "RENDER_HYPOTHESIS_WORK2_MID_GRASS", "LOW"),
        ("plant_left", "08_NEAR_ECOLOGY", "plant", "06_PLANTS/plant_left/plant_left.png",
         (216, 380, 470, 892), -5.5, False, 122.0, plant_left_tint, "RENDER_HYPOTHESIS_WORK2_LEFT_PLANT", "MEDIUM_LOW"),
        ("plant_right", "08_NEAR_ECOLOGY", "plant", "06_PLANTS/plant_right/plant_right.png",
         (794, 496, 996, 868), 3.0, False, 120.0, plant_right_tint, "RENDER_HYPOTHESIS_WORK2_RIGHT_PLANT", "MEDIUM_LOW"),
        ("rock_02", "08_NEAR_ECOLOGY", "rock", "07_ROCKS/individual/rock_02.png",
         (710, 718, 902, 886), 5.8, False, 114.0, rock_02_tint, "RENDER_HYPOTHESIS_WORK2_ROCK02", "MEDIUM_LOW"),
        ("rock_01", "08_NEAR_ECOLOGY", "rock", "07_ROCKS/individual/rock_01.png",
         (384, 762, 696, 958), -3.0, False, 110.0, rock_01_tint, "RENDER_HYPOTHESIS_WORK2_ROCK01", "MEDIUM_LOW"),
        ("rock_03", "08_NEAR_ECOLOGY", "rock", "07_ROCKS/individual/rock_03.png",
         (584, 814, 834, 965), 3.8, False, 106.0, rock_03_tint, "RENDER_HYPOTHESIS_WORK2_ROCK03_MOSS", "MEDIUM_LOW"),
    ]


def run(round_num: int):
    assert 1 <= round_num <= 5
    assert sha256_file(V2_ZIP) == EXPECTED_V2_SHA, "V2 ZIP SHA mismatch"
    assert sha256_file(V21_ZIP) == EXPECTED_V21_SHA, "V2.1 ZIP SHA mismatch"
    assert sha256_file(MASTER_PNG) == EXPECTED_MASTER_SHA, "Master PNG SHA mismatch"

    geom_dir = SCENE_OUT / "02_SUPPLEMENTED_ASSETS/geometry"
    mat_dir = SCENE_OUT / "02_SUPPLEMENTED_ASSETS/materials"
    sg_dir = SCENE_OUT / "03_SCENE_GRAPH"
    for d in (geom_dir, mat_dir, sg_dir):
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

    # Full-canvas quad & water disc
    (geom_dir / "environment_quad.json").write_text(json.dumps(build_rect_quad(0, 0, CANVAS, CANVAS)) + "\n")
    (geom_dir / "water_disc.json").write_text(json.dumps(build_disc_mesh(SPHERE_CX, SPHERE_CY, SPHERE_INNER_R + 12.0, segments=128)) + "\n")

    resident_specs = get_resident_specs(round_num)
    for spec in resident_specs:
        mesh = build_ribbon_mesh(spec["points"], z_depth=0.0, samples_per_seg=26)
        mesh["resident_id"] = spec["resident_id"]
        mesh["pass_id"] = spec["pass_id"]
        mesh["hypothesis"] = spec["hypothesis"]
        (geom_dir / f"{spec['draw_id']}_mesh.json").write_text(json.dumps(mesh) + "\n")

    # Preserve exact Ground Truth vertex coordinates for the 4 fish meshes
    for fid in ("WW_FISH_01", "WW_FISH_02", "WW_FISH_03", "WW_FISH_04"):
        orig = json.loads((geom_dir / f"{fid}_mesh.json").read_text())
        verts = orig["vertices"]
        cols, rows = 17, 7
        assert len(verts) == cols * rows
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

    ecology_quads = get_ecology_quads(round_num)
    for (did, _, _, _, (x0, y0, x1, y1), rot, flip_x, _, _, _, _) in ecology_quads:
        q = build_rect_quad(x0, y0, x1, y1, z=0.0, rot_deg=rot, flip_x=flip_x)
        (geom_dir / f"{did}_v16_mesh.json").write_text(json.dumps(q) + "\n")

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
        (geom_dir / f"{bid}_quad.json").write_text(json.dumps(build_rect_quad(cx - r, cy - r, cx + r, cy + r)) + "\n")

    # Round 1: refine outer droplet sizing (0.84x vs 0.92x) preserving rim contact while reducing bloat
    droplet_scale = 0.84
    droplet_alpha = 0.82 if round_num >= 5 else 0.85
    for b in bubble_cfg["bubbles"]:
        cx, cy = b["center"][0] * CANVAS, b["center"][1] * CANVAS
        r = b["radius_d"] * CANVAS * droplet_scale
        (geom_dir / f"{b['id']}_quad.json").write_text(json.dumps(build_rect_quad(cx - r, cy - r, cx + r, cy + r)) + "\n")

    draws = []

    def add_draw(draw_id, pass_id, shader_program, mesh_path, material, texture_rel=None,
                 depth=0.0, depth_write=False, depth_test=True, blend_mode="ALPHA",
                 alpha_cutoff=0.0, ambient=0.45, shell_side=0.0, tint=None,
                 sphere_clip=False, ribbon_style=0.0, core_color=None, emission_override=None,
                 hypothesis_status="GROUND_TRUTH_VERIFIED", confidence="HIGH"):
        t_path, t_sha = tex_ref(texture_rel) if texture_rel else (None, None)
        entry = {
            "draw_id": draw_id, "pass_id": pass_id, "shader_program": shader_program,
            "mesh_path": mesh_path, "material": material, "texture_path": t_path, "texture_sha256": t_sha,
            "model": make_model(depth), "depth_write": depth_write, "depth_test": depth_test,
            "blend_mode": blend_mode, "alpha_cutoff": alpha_cutoff, "ambient": ambient,
            "shell_side": shell_side, "sphere_clip": sphere_clip, "ribbon_style": ribbon_style,
            "hypothesis_status": hypothesis_status, "confidence": confidence,
        }
        if tint is not None:
            entry["tint"] = tint
        if core_color is not None:
            entry["core_color"] = core_color
        if emission_override is not None:
            entry["emission_override"] = emission_override
        draws.append(entry)

    add_draw("background", "01_BACKGROUND", "OPAQUE",
             "02_SUPPLEMENTED_ASSETS/geometry/environment_quad.json", "background",
             "01_BACKGROUND/background.png", depth=1900.0, depth_write=True, depth_test=True,
             blend_mode="NONE", ambient=1.0, hypothesis_status="SOURCE_ASSET_VERIFIED", confidence="HIGH")

    for spec in resident_specs:
        if spec["pass_id"] == "02_RESIDENT_BACK":
            add_draw(spec["draw_id"], "02_RESIDENT_BACK", "RESIDENT_RIBBON",
                     f"02_SUPPLEMENTED_ASSETS/geometry/{spec['draw_id']}_mesh.json", "resident",
                     None, depth=spec["depth"], depth_write=False, depth_test=True,
                     blend_mode="ALPHA", ambient=0.35, tint=spec["color"],
                     ribbon_style=spec["ribbon_style"], core_color=spec["core_color"],
                     emission_override=spec["emission"],
                     hypothesis_status=spec["hypothesis"], confidence=spec["confidence"])

    add_draw("sphere_back", "03_SPHERE_BACK", "GLASS",
             "02_SUPPLEMENTED_ASSETS/geometry/sphere_mesh.json", "glass",
             "03_SPHERE/glass_back_arc_mask.png", depth=450.0, depth_write=False, depth_test=True,
             blend_mode="ALPHA", ambient=0.45, shell_side=1.0,
             hypothesis_status="GROUND_TRUTH_SPHERE_CIRCLE", confidence="HIGH")

    add_draw("water", "04_WATER_INTERIOR", "WATER",
             "02_SUPPLEMENTED_ASSETS/geometry/water_disc.json", "water",
             "04_WATER/waterbed.png", depth=320.0, depth_write=False, depth_test=True,
             blend_mode="ALPHA", ambient=0.55, sphere_clip=True,
             hypothesis_status="RENDER_HYPOTHESIS_WORK2_MENISCUS_AND_VOLUME", confidence="MEDIUM_LOW")

    for (did, pid, mat, tex, _, _, _, depth, tint, hyp, conf) in ecology_quads:
        if pid == "05_FAR_ECOLOGY":
            add_draw(did, pid, "TRANSPARENT", f"02_SUPPLEMENTED_ASSETS/geometry/{did}_v16_mesh.json", mat,
                     tex, depth=depth, depth_write=True, depth_test=True, blend_mode="ALPHA",
                     alpha_cutoff=0.22, ambient=0.56, tint=tint, sphere_clip=True,
                     hypothesis_status=hyp, confidence=conf)

    add_draw("underwater_caustics_lighting", "06_LIGHTING", "CAUSTICS",
             "02_SUPPLEMENTED_ASSETS/geometry/water_disc.json", "water",
             "04_WATER/caustics/caustics_height.png", depth=200.0, depth_write=False, depth_test=True,
             blend_mode="ADDITIVE", ambient=1.0, sphere_clip=True,
             hypothesis_status="RENDER_HYPOTHESIS_WORK2_VOLUMETRIC_CAUSTICS", confidence="MEDIUM_LOW")

    # Round 3: crisp sunlit dorsal contrast on the 4 fish (preserving GT vertex positions)
    fish_tint = [1.02, 1.03, 0.94, 1.0] if round_num >= 3 else [1.05, 1.02, 0.92, 1.0]
    fish_ambient = 0.66 if round_num >= 3 else 0.68
    fish_entries = [
        ("WW_FISH_01", "05_FISH/fish_01.png", 154.0, "GROUND_TRUTH_VERIFIED", "MEDIUM_HIGH"),
        ("WW_FISH_02", "05_FISH/fish_02.png", 152.0, "GROUND_TRUTH_VERIFIED", "MEDIUM_HIGH"),
        ("WW_FISH_03", "05_FISH/fish_03.png", 150.0, "RENDER_HYPOTHESIS_RETAINING_UNKNOWN_GT", "MEDIUM"),
        ("WW_FISH_04", "05_FISH/fish_04.png", 148.0, "RENDER_HYPOTHESIS_RETAINING_UNKNOWN_GT", "MEDIUM"),
    ]
    for fid, ftex, fdepth, fhyp, fconf in fish_entries:
        add_draw(fid, "07_FISH", "TRANSPARENT", f"02_SUPPLEMENTED_ASSETS/geometry/{fid}_v16_mesh.json", "fish",
                 ftex, depth=fdepth, depth_write=True, depth_test=True, blend_mode="ALPHA",
                 alpha_cutoff=0.14, ambient=fish_ambient, tint=fish_tint, sphere_clip=True,
                 hypothesis_status=fhyp, confidence=fconf)

    for (did, pid, mat, tex, _, _, _, depth, tint, hyp, conf) in ecology_quads:
        if pid == "08_NEAR_ECOLOGY":
            add_draw(did, pid, "TRANSPARENT", f"02_SUPPLEMENTED_ASSETS/geometry/{did}_v16_mesh.json", mat,
                     tex, depth=depth, depth_write=True, depth_test=True, blend_mode="ALPHA",
                     alpha_cutoff=0.18, ambient=0.52, tint=tint,
                     sphere_clip=True, hypothesis_status=hyp, confidence=conf)

    for idx, (bid, _, _, _) in enumerate(inner_bubbles):
        add_draw(bid, "08_NEAR_ECOLOGY", "PARTICLE_BOKEH", f"02_SUPPLEMENTED_ASSETS/geometry/{bid}_quad.json", "bubble",
                 "09_BUBBLES/bubble_sprite.png", depth=95.0 - idx * 0.2, depth_write=False, depth_test=True,
                 blend_mode="ALPHA", ambient=1.0, tint=[0.85, 0.98, 1.0, 0.72],
                 sphere_clip=True, hypothesis_status="RENDER_HYPOTHESIS_INNER_BUBBLES", confidence="LOW")

    add_draw("sphere_front", "09_SPHERE_FRONT", "GLASS",
             "02_SUPPLEMENTED_ASSETS/geometry/sphere_mesh.json", "glass",
             "03_SPHERE/glass_front_arc_mask.png", depth=-450.0, depth_write=False, depth_test=True,
             blend_mode="ALPHA", ambient=0.50, shell_side=-1.0,
             hypothesis_status="GROUND_TRUTH_SPHERE_CIRCLE", confidence="HIGH")

    for spec in resident_specs:
        if spec["pass_id"] == "10_RESIDENT_FRONT":
            add_draw(spec["draw_id"], "10_RESIDENT_FRONT", "RESIDENT_RIBBON",
                     f"02_SUPPLEMENTED_ASSETS/geometry/{spec['draw_id']}_mesh.json", "resident",
                     None, depth=spec["depth"], depth_write=False, depth_test=True,
                     blend_mode="ALPHA", ambient=0.35, tint=spec["color"],
                     ribbon_style=spec["ribbon_style"], core_color=spec["core_color"],
                     emission_override=spec["emission"],
                     hypothesis_status=spec["hypothesis"], confidence=spec["confidence"])

    for idx, b in enumerate(bubble_cfg["bubbles"]):
        add_draw(b["id"], "11_FINAL_COMPOSITE", "PARTICLE_BOKEH", f"02_SUPPLEMENTED_ASSETS/geometry/{b['id']}_quad.json", "bubble",
                 "09_BUBBLES/bubble_sprite.png", depth=-600.0 - idx, depth_write=False, depth_test=True,
                 blend_mode="ALPHA", ambient=1.0, tint=[0.88, 0.96, 1.0, droplet_alpha],
                 hypothesis_status="RENDER_HYPOTHESIS_WORK2_OUTER_DROPLETS", confidence="LOW")

    bindings_json = {
        "schema_version": "water-world-draw-bindings/2.1-work2",
        "work2_round": round_num,
        "canvas_size": CANVAS,
        "time_seconds": 0,
        "pass_order": [
            "01_BACKGROUND", "02_RESIDENT_BACK", "03_SPHERE_BACK", "04_WATER_INTERIOR",
            "05_FAR_ECOLOGY", "06_LIGHTING", "07_FISH", "08_NEAR_ECOLOGY",
            "09_SPHERE_FRONT", "10_RESIDENT_FRONT", "11_FINAL_COMPOSITE",
        ],
        "optical_nodes": ["glass_refraction", "water_normal_distortion", "caustics", "bloom", "bokeh"],
        "draws": draws,
        "status": f"WORK2_ROUND_{round_num}_ELEVEN_STAGE_RENDER_GRAPH",
        "missing_passes": [],
        "gpu_validation": "PENDING_DEVICE_HARDWARE_GPU",
    }
    (sg_dir / "draw_bindings.json").write_text(json.dumps(bindings_json, indent=2) + "\n")

    # Optical configuration tuned progressively across Rounds 2 (glass) & 5 (water + 5 optics)
    t_water_norm, sha_water_norm = tex_ref("04_WATER/water_normal_tangent.png")
    t_water_depth, sha_water_depth = tex_ref("04_WATER/water_relative_depth_16bit.png")
    t_caustics, sha_caustics = tex_ref("04_WATER/caustics/caustics_height.png")
    t_glass_shell, sha_glass_shell = tex_ref("03_SPHERE/glass_shell_mask.png")
    t_cavity, sha_cavity = tex_ref("03_SPHERE/cavity_alpha.png")
    t_outer, sha_outer = tex_ref("03_SPHERE/sphere_outer_mask.png")

    # Round 2 glass parameters (crystal wall transmission, balanced dispersion & Master-aligned flares)
    glass_cfg = {
        "enabled": True,
        "back_refraction_strength": 0.016 if round_num >= 2 else 0.015,
        "front_refraction_strength": 0.030 if round_num >= 2 else 0.028,
        "chromatic_dispersion": 0.0014 if round_num >= 2 else 0.0012,
        "fresnel_power": 3.8 if round_num >= 2 else 4.0,
        "rim_color_cool": [0.56, 0.96, 1.0] if round_num >= 2 else [0.54, 0.92, 1.0],
        "rim_color_warm": [1.0, 0.88, 0.48] if round_num >= 2 else [1.0, 0.82, 0.36],
        "absorption_color": [0.018, 0.22, 0.28] if round_num >= 2 else [0.02, 0.22, 0.30],
        "top_left_flare_norm": [0.332, 0.204] if round_num >= 2 else [0.336, 0.205],
        "top_left_flare_radius": 0.102 if round_num >= 2 else 0.095,
        "top_left_flare_color": [1.0, 0.92, 0.56] if round_num >= 2 else [1.0, 0.88, 0.46],
        "top_left_flare_intensity": 1.68 if round_num >= 2 else 1.32,
        "bottom_right_flare_norm": [0.630, 0.772] if round_num >= 2 else [0.625, 0.772],
        "bottom_right_flare_radius": 0.095 if round_num >= 2 else 0.088,
        "bottom_right_flare_color": [1.0, 0.90, 0.52] if round_num >= 2 else [1.0, 0.86, 0.42],
        "bottom_right_flare_intensity": 1.56 if round_num >= 2 else 1.26,
        "right_rim_arc_norm": [0.780, 0.535] if round_num >= 2 else [0.768, 0.445],
        "right_rim_arc_intensity": 1.02 if round_num >= 2 else 0.76,
    }

    # Round 5 water + caustics + bloom + background convergence
    water_cfg = {
        "enabled": True,
        "normal_distortion_strength": 0.012 if round_num >= 5 else 0.011,
        "meniscus_base_y_norm": 0.242 if round_num >= 5 else 0.244,
        "meniscus_wave_amp_norm": 0.017 if round_num >= 5 else 0.016,
        "meniscus_thickness_norm": 0.020 if round_num >= 5 else 0.021,
        "air_dome_top_color": [0.10, 0.40, 0.54] if round_num >= 5 else [0.10, 0.38, 0.52],
        "air_dome_warm_color": [1.0, 0.90, 0.52] if round_num >= 5 else [1.0, 0.84, 0.42],
        "water_shallow_color": [0.05, 0.42, 0.52] if round_num >= 5 else [0.05, 0.42, 0.52],
        "water_mid_color": [0.018, 0.29, 0.38] if round_num >= 5 else [0.018, 0.29, 0.38],
        "water_deep_color": [0.010, 0.14, 0.18] if round_num >= 5 else [0.010, 0.14, 0.18],
        "waterbed_blend": 0.16 if round_num >= 5 else 0.16,
    }

    caustics_cfg = {
        "enabled": True,
        "sun_origin_norm": [0.336, 0.210] if round_num >= 5 else [0.338, 0.212],
        "god_ray_intensity": 0.49 if round_num >= 5 else 0.48,
        "god_ray_color": [1.0, 0.90, 0.48] if round_num >= 5 else [1.0, 0.88, 0.46],
        "god_ray_beams": [
            {"angle_deg": 42.3, "width_deg": 2.4, "strength": 0.72},
            {"angle_deg": 50.9, "width_deg": 2.8, "strength": 0.98},
            {"angle_deg": 59.9, "width_deg": 3.0, "strength": 1.02},
            {"angle_deg": 69.3, "width_deg": 2.7, "strength": 0.92},
            {"angle_deg": 78.9, "width_deg": 2.3, "strength": 0.74},
            {"angle_deg": 88.3, "width_deg": 2.0, "strength": 0.52},
        ] if round_num >= 5 else [
            {"angle_deg": 42.5, "width_deg": 2.4, "strength": 0.70},
            {"angle_deg": 51.0, "width_deg": 2.8, "strength": 0.96},
            {"angle_deg": 60.0, "width_deg": 3.0, "strength": 1.00},
            {"angle_deg": 69.5, "width_deg": 2.7, "strength": 0.90},
            {"angle_deg": 79.0, "width_deg": 2.3, "strength": 0.72},
            {"angle_deg": 88.5, "width_deg": 2.0, "strength": 0.52},
        ],
        "surface_caustic_strength": 0.68 if round_num >= 5 else 0.62,
        "bed_caustic_strength": 0.60 if round_num >= 5 else 0.54,
        "bed_caustic_color": [0.92, 0.97, 0.66] if round_num >= 5 else [0.88, 0.95, 0.64],
    }

    bloom_cfg = {
        "enabled": True,
        "threshold": 0.71 if round_num >= 5 else 0.72,
        "soft_knee": 0.24 if round_num >= 5 else 0.24,
        "downsample_scales": [2, 4],
        "blur_radius_px": 14,
        "strength": 0.46 if round_num >= 5 else 0.44,
        "max_contribution": 0.58 if round_num >= 5 else 0.56,
    }

    bg_atm_cfg = {
        "top_glare_suppression": 0.92 if round_num >= 5 else 0.90,
        "vignette_strength": 0.66 if round_num >= 5 else 0.68,
        "deep_tint": [0.012, 0.102, 0.118] if round_num >= 5 else [0.015, 0.105, 0.118],
        "top_center_halo_center": [0.56, 0.035],
        "top_center_halo_radius": 0.22,
        "top_center_halo_color": [0.14, 0.44, 0.48],
        "top_center_halo_intensity": 0.34 if round_num >= 5 else 0.36,
    }

    bokeh_orbs = [
        {"x": 0.052, "y": 0.598, "r": 0.025, "color": [0.95, 0.78, 0.28], "alpha": 0.44, "softness": 0.55, "layer": "BG"},
        {"x": 0.115, "y": 0.768, "r": 0.030, "color": [0.58, 0.86, 0.28], "alpha": 0.26, "softness": 0.65, "layer": "BG"},
        {"x": 0.105, "y": 0.092, "r": 0.026, "color": [0.22, 0.58, 0.52], "alpha": 0.22, "softness": 0.70, "layer": "BG"},
        {"x": 0.735, "y": 0.042, "r": 0.022, "color": [0.58, 0.76, 0.34], "alpha": 0.24, "softness": 0.65, "layer": "BG"},
        {"x": 0.915, "y": 0.165, "r": 0.024, "color": [0.16, 0.60, 0.64], "alpha": 0.26, "softness": 0.65, "layer": "BG"},
        {"x": 0.972, "y": 0.772, "r": 0.026, "color": [0.92, 0.74, 0.26], "alpha": 0.38, "softness": 0.52, "layer": "FG"},
        {"x": 0.895, "y": 0.865, "r": 0.028, "color": [0.22, 0.68, 0.62], "alpha": 0.22, "softness": 0.68, "layer": "BG"},
        {"x": 0.920, "y": 0.920, "r": 0.020, "color": [0.44, 0.72, 0.34], "alpha": 0.20, "softness": 0.65, "layer": "BG"},
        {"x": 0.180, "y": 0.925, "r": 0.032, "color": [0.18, 0.56, 0.54], "alpha": 0.20, "softness": 0.72, "layer": "BG"},
        {"x": 0.855, "y": 0.905, "r": 0.017, "color": [0.20, 0.66, 0.72], "alpha": 0.22, "softness": 0.62, "layer": "BG"},
        # Pinpoint glowing sparkles along ribbon intersections
        {"x": 0.192, "y": 0.236, "r": 0.030, "color": [1.0, 0.93, 0.62], "alpha": 0.76, "softness": 0.85, "layer": "FG"},
        {"x": 0.376, "y": 0.118, "r": 0.020, "color": [1.0, 0.91, 0.60], "alpha": 0.64, "softness": 0.85, "layer": "FG"},
        {"x": 0.946, "y": 0.422, "r": 0.022, "color": [1.0, 0.88, 0.50], "alpha": 0.70, "softness": 0.82, "layer": "FG"},
        {"x": 0.912, "y": 0.532, "r": 0.028, "color": [1.0, 0.92, 0.58], "alpha": 0.80, "softness": 0.82, "layer": "FG"},
        {"x": 0.682, "y": 0.884, "r": 0.024, "color": [0.62, 0.98, 1.0], "alpha": 0.76, "softness": 0.84, "layer": "FG"},
        {"x": 0.422, "y": 0.898, "r": 0.020, "color": [0.96, 1.0, 0.64], "alpha": 0.64, "softness": 0.84, "layer": "FG"},
        {"x": 0.502, "y": 0.886, "r": 0.014, "color": [1.0, 0.90, 0.54], "alpha": 0.66, "softness": 0.82, "layer": "FG"},
    ]

    optical_config = {
        "schema_version": "water-world-optics/1.1-work2",
        "work2_round": round_num,
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
        "background_atmosphere": bg_atm_cfg,
        "glass_refraction": glass_cfg,
        "water_normal_distortion": water_cfg,
        "caustics": caustics_cfg,
        "bloom": bloom_cfg,
        "bokeh": {"enabled": True, "orbs": bokeh_orbs},
    }
    (mat_dir / "optical_config.json").write_text(json.dumps(optical_config, indent=2) + "\n")

    # Export Work 2 domain-specific override manifests (without mutating Work 1.6 directory)
    if round_num == 1:
        geo_dir = WORK2_OUT / "02_GEOMETRY"
        geo_dir.mkdir(parents=True, exist_ok=True)
        (geo_dir / "WORK2_GEOMETRY_TRANSFORM_DIFF.json").write_text(json.dumps({
            "round": 1,
            "policy": "RENDER_HYPOTHESIS_WORK2_REFINED (Ground Truth fish vertices untouched)",
            "sphere_gt_preserved": {"center_px": [SPHERE_CX, SPHERE_CY], "outer_radius_px": SPHERE_R, "inner_radius_px": SPHERE_INNER_R},
            "ecology_overrides": [
                {"draw_id": did, "pass_id": pid, "rect_px": list(rect), "rot_deg": rot, "flip_x": fx, "hypothesis": hyp}
                for (did, pid, _, _, rect, rot, fx, _, _, hyp, _) in ecology_quads
            ],
            "outer_droplet_radius_scale": {"before": 0.92, "after": droplet_scale},
        }, indent=2) + "\n")

    if round_num == 2:
        gw_dir = WORK2_OUT / "03_GLASS_WATER"
        gw_dir.mkdir(parents=True, exist_ok=True)
        (gw_dir / "WORK2_GLASS_SHADER_DIFF.json").write_text(json.dumps({
            "round": 2,
            "shader_files_synced_1to1": [
                "app/src/main/java/com/example/waterworld/WaterWorldShaders.kt",
                "tools/water-world-scene-assembly/WaterWorldSoftwareRenderer.java",
            ],
            "glass_refraction_config": glass_cfg,
        }, indent=2) + "\n")

    if round_num == 3:
        eco_dir = WORK2_OUT / "04_ECOLOGY"
        eco_dir.mkdir(parents=True, exist_ok=True)
        (eco_dir / "WORK2_ECOLOGY_OVERRIDE_MANIFEST.json").write_text(json.dumps({
            "round": 3,
            "fish_ground_truth_policy": {
                "WW_FISH_01": "GROUND_TRUTH_VERIFIED (vertices preserved 100%)",
                "WW_FISH_02": "GROUND_TRUTH_VERIFIED (vertices preserved 100%)",
                "WW_FISH_03": "RENDER_HYPOTHESIS_RETAINING_UNKNOWN_GT (partial uncertainty preserved)",
                "WW_FISH_04": "RENDER_HYPOTHESIS_RETAINING_UNKNOWN_GT (partial uncertainty preserved)",
            },
            "fish_material_refinement": {"tint": fish_tint, "ambient": fish_ambient},
            "ecology_tints": {did: tint for (did, _, _, _, _, _, _, _, tint, _, _) in ecology_quads},
        }, indent=2) + "\n")

    if round_num == 4:
        res_dir = WORK2_OUT / "05_RESIDENT"
        res_dir.mkdir(parents=True, exist_ok=True)
        (res_dir / "WORK2_RESIDENT_OCCLUSION_HYPOTHESIS.json").write_text(json.dumps({
            "round": 4,
            "ground_truth_occlusion_status": "UNKNOWN_FRONT_BACK_NO_CONFIRMED_OCCLUSION",
            "ground_truth_edges_preserved_empty": True,
            "render_hypothesis_splits": {
                rid: {
                    "back_segments": [s["draw_id"] for s in resident_specs if s["resident_id"] == rid and s["pass_id"] == "02_RESIDENT_BACK"],
                    "front_segments": [s["draw_id"] for s in resident_specs if s["resident_id"] == rid and s["pass_id"] == "10_RESIDENT_FRONT"],
                    "status": "RENDER_HYPOTHESIS_WORK2_REFINED",
                }
                for rid in ("G01", "G02", "A01", "A02", "C01")
            },
        }, indent=2) + "\n")

    if round_num == 5:
        opt_dir = WORK2_OUT / "06_OPTICS"
        opt_dir.mkdir(parents=True, exist_ok=True)
        (opt_dir / "WORK2_OPTICAL_CONVERGENCE_CONFIG.json").write_text(json.dumps({
            "round": 5,
            "background_atmosphere": bg_atm_cfg,
            "glass_refraction": glass_cfg,
            "water_normal_distortion": water_cfg,
            "caustics": caustics_cfg,
            "bloom": bloom_cfg,
            "bokeh_orb_count": len(bokeh_orbs),
        }, indent=2) + "\n")

    print(f"WORK2_SCENE_BUILT round={round_num} draws={len(draws)} residents={len(resident_specs)} ecology={len(ecology_quads)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--round", type=int, required=True, choices=[1, 2, 3, 4, 5])
    args = parser.parse_args()
    run(args.round)
