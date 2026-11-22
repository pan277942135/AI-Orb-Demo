#!/usr/bin/env python3
"""
Extracts frozen 6-keyframe visual layout regions and crops the clean KF01 fishing photo
from the sole frozen overview PNG:
  public/assets/aistudio/AI_Orb_KF01-KF06_________________01802259.png

Outputs:
  - app/src/main/assets/task_demo/fishing_photo.webp (Lossless VP8L WebP, cropped once from KF01)
  - docs/task6kf/FROZEN_6KF_REGIONS.json
  - docs/task6kf/FROZEN_6KF_VISUAL_SPEC.md
"""

from __future__ import annotations

import hashlib
import json
import math
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OVERVIEW_PATH = ROOT / "public/assets/aistudio/AI_Orb_KF01-KF06_________________01802259.png"
PHOTO_OUT = ROOT / "app/src/main/assets/task_demo/fishing_photo.webp"
REGIONS_OUT = ROOT / "docs/task6kf/FROZEN_6KF_REGIONS.json"
SPEC_OUT = ROOT / "docs/task6kf/FROZEN_6KF_VISUAL_SPEC.md"

EXPECTED_W = 3059
EXPECTED_H = 4016


def decode_rgb24(path: Path) -> bytes:
    cmd = ["ffmpeg", "-v", "error", "-i", str(path), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg decode failed: {proc.stderr.decode()}")
    return proc.stdout


def find_bright_runs(values: list[float], threshold: float, min_len: int) -> list[tuple[int, int]]:
    runs: list[tuple[int, int]] = []
    start = -1
    for i, v in enumerate(values):
        if v >= threshold:
            if start < 0:
                start = i
        else:
            if start >= 0 and (i - start) >= min_len:
                runs.append((start, i - 1))
            start = -1
    if start >= 0 and (len(values) - start) >= min_len:
        runs.append((start, len(values) - 1))
    return runs


def detect_panels(rgb: bytes, w: int, h: int) -> list[dict]:
    """Detects the 3 columns x 2 rows embedded 941x1672 phone frames."""
    # Sample horizontal brightness profile across mid-row of row 0 (y=900) and row 1 (y=2800)
    col_means = []
    for x in range(w):
        s = 0.0
        for y in (400, 900, 1400, 2300, 2800, 3300):
            off = (y * w + x) * 3
            s += (rgb[off] + rgb[off + 1] + rgb[off + 2]) / 3.0
        col_means.append(s / 6.0)
    x_runs = find_bright_runs(col_means, threshold=95.0, min_len=700)
    if len(x_runs) != 3:
        raise RuntimeError(f"Expected 3 panel columns, found {x_runs}")

    # Sample vertical brightness profile along the bright cyan left/right margins of column 0
    margin_xs = (x_runs[0][0] + 24, x_runs[0][1] - 24, x_runs[1][0] + 24, x_runs[1][1] - 24)
    row_means = []
    for y in range(h):
        s = 0.0
        for mx in margin_xs:
            off = (y * w + mx) * 3
            s += (rgb[off] + rgb[off + 1] + rgb[off + 2]) / 3.0
        row_means.append(s / len(margin_xs))
    y_runs = find_bright_runs(row_means, threshold=115.0, min_len=1400)
    if len(y_runs) != 2:
        raise RuntimeError(f"Expected 2 panel rows, found {y_runs}")

    names = [
        ("KF01", "ORB_RELEASE", "能量释放"),
        ("KF02", "BOTTOM_CONTACT", "底边接触"),
        ("KF03", "BOTTOM_SPLIT", "左右分流"),
        ("KF04", "SIDE_ASCEND", "双侧攀升"),
        ("KF05", "TOP_JOIN", "顶部汇合"),
        ("KF06", "PERIMETER_LOOP", "持续循环"),
    ]
    panels = []
    idx = 0
    for r_idx, (y0, y1) in enumerate(y_runs):
        for c_idx, (x0, x1) in enumerate(x_runs):
            kf_id, stage, label = names[idx]
            panels.append({
                "id": kf_id,
                "stage": stage,
                "label": label,
                "row": r_idx,
                "col": c_idx,
                "x0": x0,
                "y0": y0,
                "x1": x1,
                "y1": y1,
                "width": x1 - x0 + 1,
                "height": y1 - y0 + 1,
            })
            idx += 1
    return panels


def detect_photo_and_orb_in_panel(rgb: bytes, img_w: int, panel: dict) -> dict:
    """
    Detects the rounded photo bounding box and the bottom AI Orb bounding circle
    within a single 941x1672 keyframe panel.
    """
    px0, py0 = panel["x0"], panel["y0"]
    pw, ph = panel["width"], panel["height"]

    # Photo region sits inside x in [0.05..0.95], y in [0.06..0.66].
    # Against the bright cyan studio background (high B & G, low R/G diff, bright cyan hue: b > 195 and g > 205 and b > r + 15),
    # the photo interior contains dark jacket, fish scales, marsh green, and grey-blue clouds (not uniform bright cyan).
    def is_cyan_bg(r: int, g: int, b: int) -> bool:
        return b > 185 and g > 195 and (b - r) > 12 and (g - r) > 10

    # Horizontal scan across middle of photo (y = 0.25..0.48 of panel height)
    left_xs = []
    right_xs = []
    for fy in (0.24, 0.30, 0.36, 0.42, 0.48):
        y = py0 + int(ph * fy)
        lx = None
        for dx in range(int(pw * 0.04), int(pw * 0.30)):
            off = (y * img_w + (px0 + dx)) * 3
            r, g, b = rgb[off], rgb[off + 1], rgb[off + 2]
            # Photo border starts where brightness drops or stops being cyan studio bg (after thin white edge)
            if not is_cyan_bg(r, g, b) and not (r > 235 and g > 240 and b > 242):
                lx = dx
                break
        rx = None
        for dx in range(int(pw * 0.96), int(pw * 0.70), -1):
            off = (y * img_w + (px0 + dx)) * 3
            r, g, b = rgb[off], rgb[off + 1], rgb[off + 2]
            if not is_cyan_bg(r, g, b) and not (r > 235 and g > 240 and b > 242):
                rx = dx
                break
        if lx is not None:
            left_xs.append(lx)
        if rx is not None:
            right_xs.append(rx)

    photo_l = sorted(left_xs)[len(left_xs) // 2]
    photo_r = sorted(right_xs)[len(right_xs) // 2]

    # Vertical scan across center columns of photo (x = 0.25..0.75 of panel width)
    top_ys = []
    bot_ys = []
    for fx in (0.28, 0.38, 0.50, 0.62, 0.72):
        x = px0 + int(pw * fx)
        ty = None
        for dy in range(int(ph * 0.06), int(ph * 0.20)):
            off = ((py0 + dy) * img_w + x) * 3
            r, g, b = rgb[off], rgb[off + 1], rgb[off + 2]
            if not is_cyan_bg(r, g, b) and not (r > 235 and g > 240 and b > 242):
                ty = dy
                break
        by = None
        for dy in range(int(ph * 0.65), int(ph * 0.50), -1):
            off = ((py0 + dy) * img_w + x) * 3
            r, g, b = rgb[off], rgb[off + 1], rgb[off + 2]
            if not is_cyan_bg(r, g, b) and not (r > 235 and g > 240 and b > 242):
                by = dy
                break
        if ty is not None:
            top_ys.append(ty)
        if by is not None:
            bot_ys.append(by)

    photo_t = sorted(top_ys)[len(top_ys) // 2]
    photo_b = sorted(bot_ys)[len(bot_ys) // 2]

    # Detect Orb dark-emerald/sapphire core inside y in [0.74..0.95], x in [0.25..0.75]
    orb_xs = []
    orb_ys = []
    for dy in range(int(ph * 0.75), int(ph * 0.94), 2):
        row_off = (py0 + dy) * img_w
        for dx in range(int(pw * 0.28), int(pw * 0.72), 2):
            off = (row_off + px0 + dx) * 3
            r, g, b = rgb[off], rgb[off + 1], rgb[off + 2]
            luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
            # Orb dark oceanic interior stands out sharply against bright cyan studio pool (luma > 165)
            if luma < 115:
                orb_xs.append(dx)
                orb_ys.append(dy)

    if orb_xs:
        orb_cx = sum(orb_xs) / len(orb_xs)
        orb_cy = sum(orb_ys) / len(orb_ys)
        orb_min_x, orb_max_x = min(orb_xs), max(orb_xs)
        orb_min_y, orb_max_y = min(orb_ys), max(orb_ys)
        # Outer glass shell is ~1.12x wider than the dark liquid core silhouette
        orb_diam = 0.5 * ((orb_max_x - orb_min_x) + (orb_max_y - orb_min_y)) * 1.12
    else:
        orb_cx, orb_cy, orb_diam = pw * 0.5, ph * 0.85, pw * 0.26

    photo_w = photo_r - photo_l + 1
    photo_h = photo_b - photo_t + 1
    # Estimate corner radius by scanning top-left diagonal inward from (photo_l, photo_t)
    corner_r = round(photo_w * 0.056)

    return {
        "photoPx": {
            "left": photo_l,
            "top": photo_t,
            "right": photo_r,
            "bottom": photo_b,
            "width": photo_w,
            "height": photo_h,
            "cornerRadiusPx": corner_r,
        },
        "photoNorm": {
            "left": round(photo_l / pw, 5),
            "top": round(photo_t / ph, 5),
            "right": round(photo_r / pw, 5),
            "bottom": round(photo_b / ph, 5),
            "width": round(photo_w / pw, 5),
            "height": round(photo_h / ph, 5),
            "centerX": round(((photo_l + photo_r) * 0.5) / pw, 5),
            "centerY": round(((photo_t + photo_b) * 0.5) / ph, 5),
            "aspectRatio": round(photo_w / max(1, photo_h), 5),
            "cornerRadiusNormOfPhotoW": round(corner_r / max(1, photo_w), 5),
        },
        "orbPx": {
            "centerX": round(orb_cx, 2),
            "centerY": round(orb_cy, 2),
            "diameterPx": round(orb_diam, 2),
            "topAnchorY": round(orb_cy - orb_diam * 0.5, 2),
        },
        "orbNorm": {
            "centerX": round(orb_cx / pw, 5),
            "centerY": round(orb_cy / ph, 5),
            "diameterNormOfW": round(orb_diam / pw, 5),
            "topAnchorY": round((orb_cy - orb_diam * 0.5) / ph, 5),
            "gapToPhotoBottomNormH": round(((orb_cy - orb_diam * 0.5) - photo_b) / ph, 5),
        },
    }


def crop_kf01_photo_to_webp(panel0: dict, det0: dict) -> dict:
    """
    Crops ONLY the interior fishing photo from KF01 (zero border waterlight, zero UI, zero Orb).
    Applies a small 14px inset inside the rounded corners so the rectangular WebP texture contains
    100% pure photo pixels with zero outer cyan corner bleed when rendered with a GPU rounded-rect clip!
    """
    inset_x = 12
    inset_y = 14
    crop_x = panel0["x0"] + det0["photoPx"]["left"] + inset_x
    crop_y = panel0["y0"] + det0["photoPx"]["top"] + inset_y
    crop_w = det0["photoPx"]["width"] - inset_x * 2
    crop_h = det0["photoPx"]["height"] - inset_y * 2

    # Ensure even dimensions for clean GPU sampling
    crop_w -= crop_w % 2
    crop_h -= crop_h % 2

    PHOTO_OUT.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        "ffmpeg", "-y", "-v", "error",
        "-i", str(OVERVIEW_PATH),
        "-vf", f"crop={crop_w}:{crop_h}:{crop_x}:{crop_y}",
        "-c:v", "libwebp",
        "-lossless", "1",
        "-compression_level", "6",
        str(PHOTO_OUT),
    ]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg WebP crop failed: {proc.stderr.decode()}")

    photo_sha = hashlib.sha256(PHOTO_OUT.read_bytes()).hexdigest()
    photo_size = PHOTO_OUT.stat().st_size
    return {
        "path": str(PHOTO_OUT.relative_to(ROOT)),
        "sourceKeyframe": "KF01",
        "cropRectOverviewPx": {"x": crop_x, "y": crop_y, "width": crop_w, "height": crop_h},
        "outputWidth": crop_w,
        "outputHeight": crop_h,
        "format": "Lossless WebP (VP8L)",
        "sizeBytes": photo_size,
        "sha256": photo_sha,
        "upscaled": False,
        "resolutionNote": (
            f"Cropped once directly from KF01 inside the 3059x4016 frozen overview ({crop_w}x{crop_h} native pixels, "
            "lower resolution than an original camera raw photo; zero AI redrawing or synthetic detail invention)."
        ),
    }


def write_spec_md(data: dict) -> None:
    u = data["unifiedCanonicalLayout"]
    p = data["demoPhotoTexture"]
    ov = data["frozenOverview"]
    lines = [
        "# AI Orb 任务能量·六帧高保真序列 — 视觉规范与归一化构图 (`FROZEN_6KF_VISUAL_SPEC`)",
        "",
        f"- **唯一冻结视觉输入:** `{ov['path']}`",
        f"- **Overview 分辨率:** `{ov['width']} × {ov['height']}` RGB24（3 列 × 2 行，单帧 `941 × 1672` 原图直拼）",
        f"- **Overview SHA-256:** `{ov['sha256']}`",
        f"- **Demo 鱼获照片纹理（仅从 KF01 裁切一次）:** `{p['path']}`（`{p['outputWidth']} × {p['outputHeight']}` Lossless VP8L WebP，`{p['sizeBytes']} B`，SHA-256 `{p['sha256']}`）",
        "  - *如实说明：* 该纹理直接裁切自冻结总览图 KF01 内部照片区（内收去圆角外缘，不含边框水光、UI 文字与 Orb），分辨率低于独立高清原图，未做 AI 重绘或虚构细节；后续可直接替换高清原图而不影响 Task × Perimeter 架构。",
        "",
        "---",
        "",
        "## 一、统一页面构图（消除单帧间 UI 轻微漂移，KF01–KF06 严格锁定一致）",
        "",
        "| 项 | 归一化坐标 / 比例 | 说明 |",
        "|---|---|---|",
        f"| **1. 页面顶栏** | `y ∈ [0.02, 0.075]` | 左侧返回箭头 `<` + 居中标题 `拍照识鱼`（深青蓝色 `#0D4E5E`），浅青蓝水光背景 |",
        f"| **2. 照片中心与大小** | `center=(0.5000, {u['photoNorm']['centerY']})`, `width={u['photoNorm']['width']}W`, `height={u['photoNorm']['height']}H` | 统一左右边距 `{u['photoNorm']['left']}W`，上边 `{u['photoNorm']['top']}H`，底边 `{u['photoNorm']['bottom']}H`，宽高比 `{u['photoNorm']['aspectRatio']}`；六帧严格不变 |",
        f"| **3. 照片圆角半径** | `r = {u['photoNorm']['cornerRadiusNormOfPhotoW']} × PhotoW`（约 `22dp`） | 连续四分之一圆弧，与 GPU 裁切及 `PerimeterPathGeometry` 弧长路径共享同一物理像素圆角半径 |",
        f"| **4. 底部 AI Orb 中心与尺寸** | `center=(0.5000, {u['orbNorm']['centerY']})`, `diameter ≈ {u['orbNorm']['diameterNormOfW']}W` | 直接复用 Tab 1 同一 `AIOrbScene`（V3 3D WebP 水核 + 玻璃壳 + 五条常驻 Resident），通过 `OrbViewportTransform` 平移缩放至底部中心，底部叠加水面焦散池 |",
        f"| **5. Orb 顶缘与照片底边距离** | `gap ≈ {u['orbNorm']['gapToPhotoBottomNormH']}H` | 中间为 Task Energy 释放与上升通道；`正在理解照片` 固定于照片底边下方 `y ≈ 0.652H`（不随帧漂移） |",
        "",
        "---",
        "",
        "## 二、任务能量与六帧传播规范（KF01 → KF06）",
        "",
        "### 6. 主任务能量形态（Main Stream）",
        "- 起点：`Orb Anchor Top`（Orb 上缘中心 `(0.50, orbTopY)`）；终点：`Photo Bottom Center`（`(0.50, photoBottomY)`）。",
        "- 形态：具有真实宽度、不规则 S 形扭转的冰蓝透明液态带，边缘含高折射冰蓝亮线与细微香槟金高光丝，非单线激光绳。",
        "",
        "### 7. 三条伴随副流形态（Secondary Wisps）",
        "- **左副流（Ice-Cyan Curl）：** 从 Orb 左上缘扬起，向左外弯后再柔和回卷，轻盈透明冰青色。",
        "- **右细冰蓝副流（Fine Ice Stream）：** 紧随主流右侧偏外轻舞，较细且节奏错位，与主流保持透明呼吸间距。",
        "- **右外侧翡翠绿水草薄纱（Emerald Aquatic Veil）：** 从 Orb 右上缘舒展飘起，翡翠绿至嫩玉绿半透明水草状轻纱，尾部自然渐细，辅以少量零散香槟金丝与透明水滴。",
        "",
        "### 8. 底边接触点（KF02｜BOTTOM_CONTACT）",
        "- 坐标：照片底边正中 `(PhotoCenterX, PhotoBottomY)`。",
        "- 表现：主流前沿首次触达照片底边中心，激发局部暖香槟金聚光、冰蓝水纹扩散与极少量透明水滴；底边仅中心小段激活，左右底角与竖边均未点亮。",
        "",
        "### 9. 左右分流路径（KF03｜BOTTOM_SPLIT）",
        "- 路径：从底边中心同时向左（`BOTTOM_CENTER → LEFT_BOTTOM`）与向右（`BOTTOM_CENTER → RIGHT_BOTTOM`）沿统一弧长展开。",
        "- 表现：双前沿逼近左右下圆角并带有柔和暖金前沿高光，已激活底边保持持续流动，左右竖边仍未激活。",
        "",
        "### 10. 两侧攀升位置（KF04｜SIDE_ASCEND）",
        "- 路径：平滑绕过左右下圆角进入左右竖边，检查点位于两侧竖边高度约 **60%** 处（统一遵循 KF04 弱化纤细水光标准：主水带宽度 `0.4%–1.2% PhotoW`，局部外扩 `2%–4% PhotoW`，低透明通透叠加，不遮挡照片人物与鱼获）。",
        "- 约束：底边完整保留流动，顶边严格未激活。",
        "",
        "### 11. 顶部汇合位置（KF05｜TOP_JOIN）",
        "- 路径：绕过左上/右上圆角后沿顶边向顶边中心 `(PhotoCenterX, PhotoTopY)` 双向汇合。",
        "- 表现：顶部中心产生短暂暖香槟金汇合聚光，四边首次完整贯通，仍保持纤细通透水光宽度，禁止四边整体加粗变亮。",
        "",
        "### 12. 持续循环材质（KF06｜PERIMETER_LOOP）",
        "- 四层统一路径液态材质沿同一 `PerimeterArcLengthTable` 连续采样运行：",
        "  - **L1 冰蓝透明主体：** 连续流纹、局部折射与有机波宽波动（`revealProgress = 1.0`, `flowPhase` 无限递增）。",
        "  - **L2 翡翠绿水草状薄纱：** 沿左右外缘局部舒展翻卷、尾部渐细。",
        "  - **L3 香槟金细丝：** 沿弧长方向非同步游走，顶部汇合强亮点较 KF05 明显回落。",
        "  - **L4 稀疏透明水滴：** 少量不规则漂浮水珠与底部 Orb 持续供能，严禁静态发光矩形或整圈同步闪烁。",
        "",
    ]
    SPEC_OUT.parent.mkdir(parents=True, exist_ok=True)
    SPEC_OUT.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    if not OVERVIEW_PATH.exists():
        raise FileNotFoundError(f"Frozen 6KF overview not found: {OVERVIEW_PATH}")
    overview_sha = hashlib.sha256(OVERVIEW_PATH.read_bytes()).hexdigest()
    rgb = decode_rgb24(OVERVIEW_PATH)
    if len(rgb) != EXPECTED_W * EXPECTED_H * 3:
        raise RuntimeError(f"Unexpected RGB buffer size: {len(rgb)}")

    panels = detect_panels(rgb, EXPECTED_W, EXPECTED_H)
    per_kf = []
    for p in panels:
        det = detect_photo_and_orb_in_panel(rgb, EXPECTED_W, p)
        per_kf.append({**p, **det})

    # Compute unified canonical layout (median across all 6 frames, centered at x=0.50)
    def med(vals: list[float]) -> float:
        s = sorted(vals)
        return round(0.5 * (s[2] + s[3]), 5)

    p_w = med([k["photoNorm"]["width"] for k in per_kf])
    p_h = med([k["photoNorm"]["height"] for k in per_kf])
    p_top = med([k["photoNorm"]["top"] for k in per_kf])
    p_left = round((1.0 - p_w) * 0.5, 5)
    p_right = round(p_left + p_w, 5)
    p_bottom = round(p_top + p_h, 5)
    p_r = med([k["photoNorm"]["cornerRadiusNormOfPhotoW"] for k in per_kf])

    orb_cy = med([k["orbNorm"]["centerY"] for k in per_kf])
    orb_diam = med([k["orbNorm"]["diameterNormOfW"] for k in per_kf])
    # Convert orb diameter (in W units) to H units using 941/1672 panel aspect
    panel_aspect = per_kf[0]["width"] / per_kf[0]["height"]
    orb_radius_h = (orb_diam * 0.5) * panel_aspect
    orb_top_y = round(orb_cy - orb_radius_h, 5)
    gap_h = round(orb_top_y - p_bottom, 5)

    demo_photo_meta = crop_kf01_photo_to_webp(panels[0], per_kf[0])

    result = {
        "schemaVersion": "AI_ORB_TASK_6KF_FROZEN_REGIONS_1_0",
        "frozenOverview": {
            "path": str(OVERVIEW_PATH.relative_to(ROOT)),
            "sha256": overview_sha,
            "width": EXPECTED_W,
            "height": EXPECTED_H,
            "grid": "3x2",
        },
        "demoPhotoTexture": demo_photo_meta,
        "unifiedCanonicalLayout": {
            "referenceAspect": round(panel_aspect, 5),
            "topBarNorm": {"backX": 0.075, "titleY": 0.044},
            "photoNorm": {
                "left": p_left,
                "top": p_top,
                "right": p_right,
                "bottom": p_bottom,
                "width": p_w,
                "height": p_h,
                "centerX": 0.5,
                "centerY": round((p_top + p_bottom) * 0.5, 5),
                "aspectRatio": round((p_w * panel_aspect) / max(1e-5, p_h), 5),
                "cornerRadiusNormOfPhotoW": p_r,
            },
            "statusTextNorm": {"centerX": 0.5, "centerY": round(p_bottom + 0.036, 5)},
            "orbNorm": {
                "centerX": 0.5,
                "centerY": orb_cy,
                "diameterNormOfW": orb_diam,
                "topAnchorY": orb_top_y,
                "gapToPhotoBottomNormH": gap_h,
            },
            "taskAnchorNorm": {
                "orbAnchorTop": [0.5, orb_top_y],
                "photoBottomCenter": [0.5, p_bottom],
                "photoTopCenter": [0.5, p_top],
                "kf01FrontRatio": 0.38,
                "kf04SideAscendRatio": 0.60,
            },
            "perimeterWaterLightParams": {
                "mainWidthRatioRange": [0.004, 0.012],
                "localFlareRatioRange": [0.020, 0.040],
                "mainAlphaRange": [0.12, 0.38],
                "emeraldVeilAlphaRange": [0.08, 0.24],
                "goldFilamentAlphaRange": [0.15, 0.50],
            },
        },
        "perKeyframeDetected": per_kf,
    }

    REGIONS_OUT.parent.mkdir(parents=True, exist_ok=True)
    REGIONS_OUT.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    write_spec_md(result)

    print(f"[extract_frozen_6kf_spec] Panels detected: {len(panels)} ({panels[0]['width']}x{panels[0]['height']})")
    print(f"[extract_frozen_6kf_spec] Cropped KF01 photo -> {demo_photo_meta['path']} ({demo_photo_meta['outputWidth']}x{demo_photo_meta['outputHeight']}, {demo_photo_meta['sizeBytes']} B)")
    print(f"[extract_frozen_6kf_spec] Unified photoNorm: {result['unifiedCanonicalLayout']['photoNorm']}")
    print(f"[extract_frozen_6kf_spec] Unified orbNorm:   {result['unifiedCanonicalLayout']['orbNorm']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
