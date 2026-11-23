#!/usr/bin/env python3
"""
AI-Orb-Demo V3.2 — Stage A: Reference Camera & True 3D Icosphere Projection Fitter

Freezes:
  - fovYDeg = 42.0
  - coreScale = 1.0
  - coreShellScale = 0.80

Solves `cameraDistance`, `visualBoundRadius`, `targetOffsetX`, `targetOffsetY` via
EXACT 3D icosphere mesh projection:
  Viewport * PerspectiveDivide(Proj * View * Model * v)

Guarantees consistent horizontal sphere framing across BOTH:
  - Square 1:1 Reference Viewport (1254x1254)
  - Portrait 9:20 Production Phone Viewport (1080x2400)
Target gates:
  - Diameter relative error <= 2.0%
  - Sphere center error <= 1.0% W
  - Outer sphere silhouette IoU >= 0.95
"""

from __future__ import annotations

import math
from typing import Any

from parameter_schema import get_val, set_val


def mat4_perspective(fov_y_deg: float, aspect: float, near: float, far: float) -> list[float]:
    f = 1.0 / math.tan(math.radians(fov_y_deg) * 0.5)
    nf = 1.0 / (near - far)
    # Column-major OpenGL matrix
    return [
        f / aspect, 0.0, 0.0, 0.0,
        0.0, f, 0.0, 0.0,
        0.0, 0.0, (far + near) * nf, -1.0,
        0.0, 0.0, 2.0 * far * near * nf, 0.0,
    ]


def mat4_look_at(
    eye: tuple[float, float, float],
    center: tuple[float, float, float],
    up: tuple[float, float, float] = (0.0, 1.0, 0.0),
) -> list[float]:
    fx = center[0] - eye[0]
    fy = center[1] - eye[1]
    fz = center[2] - eye[2]
    fl = math.sqrt(fx * fx + fy * fy + fz * fz)
    fx, fy, fz = fx / fl, fy / fl, fz / fl

    sx = fy * up[2] - fz * up[1]
    sy = fz * up[0] - fx * up[2]
    sz = fx * up[1] - fy * up[0]
    sl = math.sqrt(sx * sx + sy * sy + sz * sz)
    sx, sy, sz = sx / sl, sy / sl, sz / sl

    ux = sy * fz - sz * fy
    uy = sz * fx - sx * fz
    uz = sx * fy - sy * fx

    # Column-major OpenGL lookAt
    return [
        sx, ux, -fx, 0.0,
        sy, uy, -fy, 0.0,
        sz, uz, -fz, 0.0,
        -(sx * eye[0] + sy * eye[1] + sz * eye[2]),
        -(ux * eye[0] + uy * eye[1] + uz * eye[2]),
        (fx * eye[0] + fy * eye[1] + fz * eye[2]),
        1.0,
    ]


def mat4_mul(a: list[float], b: list[float]) -> list[float]:
    out = [0.0] * 16
    for col in range(4):
        for row in range(4):
            out[col * 4 + row] = (
                a[0 * 4 + row] * b[col * 4 + 0]
                + a[1 * 4 + row] * b[col * 4 + 1]
                + a[2 * 4 + row] * b[col * 4 + 2]
                + a[3 * 4 + row] * b[col * 4 + 3]
            )
    return out


def project_point(mvp: list[float], pt: tuple[float, float, float], vp_w: float, vp_h: float) -> tuple[float, float, float]:
    x, y, z = pt
    cx = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]
    cy = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]
    cz = mvp[2] * x + mvp[6] * y + mvp[10] * z + mvp[14]
    cw = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
    inv_w = 1.0 / max(1e-6, cw)
    ndc_x = cx * inv_w
    ndc_y = cy * inv_w
    ndc_z = cz * inv_w
    px = (ndc_x * 0.5 + 0.5) * vp_w
    py = (0.5 - ndc_y * 0.5) * vp_h
    return px, py, ndc_z


def build_subdivided_icosphere_vertices(radius: float, subdivisions: int = 3) -> list[tuple[float, float, float]]:
    """Generates a deterministic geodesic icosphere mesh for exact silhouette projection."""
    t = (1.0 + math.sqrt(5.0)) * 0.5
    raw_verts = [
        (-1, t, 0), (1, t, 0), (-1, -t, 0), (1, -t, 0),
        (0, -1, t), (0, 1, t), (0, -1, -t), (0, 1, -t),
        (t, 0, -1), (t, 0, 1), (-t, 0, -1), (-t, 0, 1),
    ]
    verts = []
    for vx, vy, vz in raw_verts:
        l = math.sqrt(vx * vx + vy * vy + vz * vz)
        verts.append((vx / l, vy / l, vz / l))

    faces = [
        (0, 11, 5), (0, 5, 1), (0, 1, 7), (0, 7, 10), (0, 10, 11),
        (1, 5, 9), (5, 11, 4), (11, 10, 2), (10, 7, 6), (7, 1, 8),
        (3, 9, 4), (3, 4, 2), (3, 2, 6), (3, 6, 8), (3, 8, 9),
        (4, 9, 5), (2, 4, 11), (6, 2, 10), (8, 6, 7), (9, 8, 1),
    ]

    for _ in range(subdivisions):
        mid_cache: dict[tuple[int, int], int] = {}

        def midpoint(i0: int, i1: int) -> int:
            key = (i0, i1) if i0 < i1 else (i1, i0)
            if key in mid_cache:
                return mid_cache[key]
            ax, ay, az = verts[i0]
            bx, by, bz = verts[i1]
            mx, my, mz = ax + bx, ay + by, az + bz
            l = math.sqrt(mx * mx + my * my + mz * mz)
            idx = len(verts)
            verts.append((mx / l, my / l, mz / l))
            mid_cache[key] = idx
            return idx

        new_faces = []
        for a, b, c in faces:
            ab = midpoint(a, b)
            bc = midpoint(b, c)
            ca = midpoint(c, a)
            new_faces.extend([(a, ab, ca), (b, bc, ab), (c, ca, bc), (ab, bc, ca)])
        faces = new_faces

    return [(x * radius, y * radius, z * radius) for (x, y, z) in verts]


def compute_circle_iou(cx0: float, cy0: float, r0: float, cx1: float, cy1: float, r1: float) -> float:
    """Exact analytic circle-circle Intersection over Union."""
    d = math.hypot(cx0 - cx1, cy0 - cy1)
    if d >= r0 + r1:
        return 0.0
    if d <= abs(r0 - r1):
        r_min = min(r0, r1)
        r_max = max(r0, r1)
        return (r_min * r_min) / max(1e-9, r_max * r_max)
    r0_2 = r0 * r0
    r1_2 = r1 * r1
    a0 = math.acos(max(-1.0, min(1.0, (d * d + r0_2 - r1_2) / (2.0 * d * r0))))
    a1 = math.acos(max(-1.0, min(1.0, (d * d + r1_2 - r0_2) / (2.0 * d * r1))))
    inter = (
        r0_2 * a0
        + r1_2 * a1
        - 0.5 * math.sqrt(max(0.0, (-d + r0 + r1) * (d + r0 - r1) * (d - r0 + r1) * (d + r0 + r1)))
    )
    union = math.pi * (r0_2 + r1_2) - inter
    return inter / max(1e-9, union)


def measure_projected_sphere(
    verts: list[tuple[float, float, float]],
    fov_y_deg: float,
    cam_dist: float,
    target_off_x: float,
    target_off_y: float,
    vp_w: int,
    vp_h: int,
) -> dict[str, float]:
    aspect = vp_w / float(vp_h)
    proj = mat4_perspective(fov_y_deg, aspect, 0.1, 100.0)
    # Camera looks parallel to -Z at (target_off_x, target_off_y, 0) from (target_off_x, target_off_y, cam_dist)
    view = mat4_look_at((target_off_x, target_off_y, cam_dist), (target_off_x, target_off_y, 0.0))
    mvp = mat4_mul(proj, view)

    min_x, max_x = 1e9, -1e9
    min_y, max_y = 1e9, -1e9
    for v in verts:
        px, py, _ = project_point(mvp, v, float(vp_w), float(vp_h))
        if px < min_x:
            min_x = px
        if px > max_x:
            max_x = px
        if py < min_y:
            min_y = py
        if py > max_y:
            max_y = py

    diam_x = max_x - min_x
    diam_y = max_y - min_y
    diam = 0.5 * (diam_x + diam_y)
    cx = 0.5 * (min_x + max_x)
    cy = 0.5 * (min_y + max_y)
    return {
        "centerX": cx,
        "centerY": cy,
        "centerNormX": cx / vp_w,
        "centerNormY": cy / vp_h,
        "diameterPx": diam,
        "diameterRatioW": diam / vp_w,
        "radiusPx": diam * 0.5,
    }


def fit_camera(cfg: dict[str, Any], targets: dict[str, Any]) -> dict[str, Any]:
    """
    Stage A optimizer: solves cameraDistance, visualBoundRadius, targetOffsetX/Y
    against master E01_outer_glass_sphere using true 3D icosphere matrix projection.
    """
    fov_y = float(get_val(cfg, "camera", "fovYDeg"))
    core_scale = float(get_val(cfg, "camera", "coreScale"))
    shell_scale = float(get_val(cfg, "camera", "coreShellScale"))
    sphere_r = core_scale * shell_scale  # frozen 0.80

    e01 = targets["targets"]["E01_outer_glass_sphere"]
    master_w = int(targets["referenceMaster"]["width"])
    master_h = int(targets["referenceMaster"]["height"])
    target_cx = float(e01["centerPx"][0])
    target_cy = float(e01["centerPx"][1])
    target_r = float(e01["outerRadiusPx"])
    target_diam_ratio = float(e01["diameterRatio"])

    verts = build_subdivided_icosphere_vertices(sphere_r, subdivisions=3)
    tan_half_y = math.tan(math.radians(fov_y) * 0.5)

    # Log baseline V3.1 live runtime error (visualBoundRadius = 1.36 -> camDist = 3.65)
    baseline_sq = measure_projected_sphere(verts, fov_y, 3.65, 0.0, 0.0, master_w, master_h)
    baseline_port_cam = max(1.36 / tan_half_y, 1.36 / (tan_half_y * (1080.0 / 2400.0)), 3.65)
    baseline_port = measure_projected_sphere(verts, fov_y, baseline_port_cam, 0.0, 0.0, 1080, 2400)

    def eval_loss(cam_d: float, off_x: float, off_y: float) -> tuple[float, dict]:
        m = measure_projected_sphere(verts, fov_y, cam_d, off_x, off_y, master_w, master_h)
        diam_err = abs(m["diameterRatioW"] - target_diam_ratio) / target_diam_ratio
        center_err = math.hypot(m["centerX"] - target_cx, m["centerY"] - target_cy) / master_w
        iou = compute_circle_iou(m["centerX"], m["centerY"], m["radiusPx"], target_cx, target_cy, target_r)
        loss = 4.0 * diam_err + 3.0 * center_err + 2.0 * (1.0 - iou)
        return loss, {
            "camDist": cam_d,
            "offX": off_x,
            "offY": off_y,
            "diamRatioW": m["diameterRatioW"],
            "diamRelErr": diam_err,
            "centerErrW": center_err,
            "iou": iou,
            "loss": loss,
            "proj": m,
        }

    # Start from V3.2 requested starting point visualBoundRadius = 1.54 -> camDist = 1.54 / tan(21°) = 4.0119
    cam_d = 1.54 / tan_half_y
    off_x = 0.0
    off_y = 0.0
    best_loss, best_info = eval_loss(cam_d, off_x, off_y)
    history = [{"iter": 0, "camDist": round(cam_d, 4), "loss": round(best_loss, 6), "diamRelErr": round(best_info["diamRelErr"], 5), "iou": round(best_info["iou"], 5)}]

    # Deterministic multi-scale coordinate descent on (cam_d, off_x, off_y)
    # Note: for square reference lock, allow small targetOffsetX/Y within [-0.12, 0.12] to match master center,
    # while keeping visualBoundRadius = cam_d * tan_half_y for aspect-invariant horizontal framing on portrait phones!
    steps = [(0.10, 0.04, 0.04), (0.02, 0.01, 0.01), (0.004, 0.002, 0.002), (0.001, 0.0005, 0.0005)]
    it = 0
    for sd, sx, sy in steps:
        for _ in range(18):
            improved = False
            for dc, dx, dy in (
                (sd, 0.0, 0.0), (-sd, 0.0, 0.0),
                (0.0, sx, 0.0), (0.0, -sx, 0.0),
                (0.0, 0.0, sy), (0.0, 0.0, -sy),
            ):
                nc = max(3.40, min(4.90, cam_d + dc))
                nx = max(-0.12, min(0.12, off_x + dx))
                ny = max(-0.18, min(0.18, off_y + dy))
                l_val, info = eval_loss(nc, nx, ny)
                if l_val < best_loss - 1e-9:
                    cam_d, off_x, off_y = nc, nx, ny
                    best_loss, best_info = l_val, info
                    improved = True
            it += 1
            if not improved:
                break
        history.append({
            "iter": it,
            "camDist": round(cam_d, 4),
            "offX": round(off_x, 4),
            "offY": round(off_y, 4),
            "loss": round(best_loss, 6),
            "diamRelErr": round(best_info["diamRelErr"], 5),
            "centerErrW": round(best_info["centerErrW"], 5),
            "iou": round(best_info["iou"], 5),
        })

    # Compute exact visualBoundRadius so AIOrbScene's `maxOf(vbr/tanY, vbr/(tanY*aspect))` produces EXACT cam_d on 1:1
    # AND identical horizontal sphere diameter ratio on 9:20 portrait phone!
    fitted_vbr = cam_d * tan_half_y
    port_aspect = 1080.0 / 2400.0
    port_cam_dist = max(fitted_vbr / tan_half_y, fitted_vbr / (tan_half_y * port_aspect))
    port_proj = measure_projected_sphere(verts, fov_y, port_cam_dist, 0.0, 0.0, 1080, 2400)
    port_diam_err = abs(port_proj["diameterRatioW"] - target_diam_ratio) / target_diam_ratio

    # Write fitted values into unified config
    set_val(cfg, "camera", "cameraDistance", round(cam_d, 4))
    set_val(cfg, "camera", "visualBoundRadius", round(fitted_vbr, 4))
    set_val(cfg, "camera", "targetOffsetX", round(off_x, 4))
    set_val(cfg, "camera", "targetOffsetY", round(off_y, 4))
    set_val(cfg, "camera", "targetSphereDiameterRatio", round(target_diam_ratio, 5))

    gate_pass = (
        best_info["diamRelErr"] <= 0.02
        and best_info["centerErrW"] <= 0.01
        and best_info["iou"] >= 0.95
        and port_diam_err <= 0.02
    )

    return {
        "stage": "STAGE_A_CAMERA_FIT",
        "pass": gate_pass,
        "frozen": {"fovYDeg": fov_y, "coreScale": core_scale, "coreShellScale": shell_scale},
        "baselineV31": {
            "visualBoundRadius": 1.36,
            "squareCamDist": 3.65,
            "squareDiameterRatioW": round(baseline_sq["diameterRatioW"], 5),
            "squareDiamRelErr": round(abs(baseline_sq["diameterRatioW"] - target_diam_ratio) / target_diam_ratio, 5),
            "portraitCamDist": round(baseline_port_cam, 4),
            "portraitDiameterRatioW": round(baseline_port["diameterRatioW"], 5),
        },
        "fitted": {
            "cameraDistanceSquare": round(cam_d, 4),
            "visualBoundRadius": round(fitted_vbr, 4),
            "targetOffsetX": round(off_x, 4),
            "targetOffsetY": round(off_y, 4),
            "squareDiameterRatioW": round(best_info["diamRatioW"], 5),
            "squareDiamRelErr": round(best_info["diamRelErr"], 5),
            "squareCenterErrW": round(best_info["centerErrW"], 5),
            "squareSilhouetteIoU": round(best_info["iou"], 5),
            "portrait1080x2400CamDist": round(port_cam_dist, 4),
            "portraitDiameterRatioW": round(port_proj["diameterRatioW"], 5),
            "portraitDiamRelErr": round(port_diam_err, 5),
        },
        "lossFinal": round(best_loss, 6),
        "history": history,
    }
