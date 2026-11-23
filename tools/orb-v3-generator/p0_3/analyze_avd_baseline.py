#!/usr/bin/env python3
"""Validate P0-3 API36 captures and quantify the unchanged three-run baseline.

The script only applies one fixed Lanczos resize to the canonical 1254 square.
It performs no translation, scale registration, crop, perspective transform, or
non-rigid alignment. Software GLES output is always labeled as software evidence.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
import statistics
import zipfile
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt
from skimage.color import deltaE_ciede2000, rgb2lab
from skimage.metrics import structural_similarity

ROOT = Path(__file__).resolve().parents[3]
P0 = ROOT / "docs/p0_3"
SIZE = (1254, 1254)
REQUIRED_IMAGES = [
    "CORE_ONLY_TRANSPARENT.png", "CAVITY_ONLY.png", "FLUID_VOLUME_ONLY.png", "GLASS_ONLY.png",
    "RESIDENTS_ONLY.png", "FULL_RENDER.png", "BLOOM_OFF.png", "PRE_TONEMAP.png", "POST_TONEMAP.png",
]
REQUIRED_MODES = [
    "CORE_ONLY_TRANSPARENT", "CAVITY_ONLY", "FLUID_VOLUME_ONLY", "GLASS_ONLY", "RESIDENTS_ONLY",
    "FULL_RENDER", "BLOOM_OFF", "PRE_TONEMAP", "POST_TONEMAP",
]


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha_file(path: Path) -> str:
    return sha(path.read_bytes())


def asset_hashes_from_apk(apk_path: Path) -> dict[str, str]:
    """Re-verify all frozen volume and auxiliary-map bytes inside the installed APK source."""
    with zipfile.ZipFile(apk_path) as apk:
        manifest = json.loads(apk.read("assets/orb_v3/manifest/ORB_V3_ASSET_MANIFEST.json"))
        paths = [f"assets/orb_v3/{item['path']}" for item in manifest["volume"]["slices"]]
        paths += [f"assets/orb_v3/{item['path']}" for item in manifest["maps"]]
        actual = {path: sha(apk.read(path)) for path in paths}
        expected = {
            f"assets/orb_v3/{item['path']}": item["sha256"]
            for item in [*manifest["volume"]["slices"], *manifest["maps"]]
        }
        if actual != expected:
            raise ValueError("PF04 installed APK asset SHA verification failed")
        return actual


def png_frame_lookup(manifest: dict) -> dict[str, dict]:
    return {frame["file"]: frame for frame in manifest.get("frames", [])}


def build_api36_evidence_archive(evidence: Path, first_manifest: dict, first_telemetry: dict) -> Path:
    """Materialize the frozen API36 evidence layout and checksum every exported file."""
    api_root = evidence / "api36_avd"
    captures_root = api_root / "captures"
    runtime_root = api_root / "runtime"
    video_root = api_root / "video"
    logs_root = api_root / "logs"
    for directory in (captures_root, runtime_root, video_root, logs_root):
        directory.mkdir(parents=True, exist_ok=True)

    frames = png_frame_lookup(first_manifest)
    for index, mode in enumerate(REQUIRED_MODES, 1):
        name = REQUIRED_IMAGES[index - 1]
        frame = frames[name]
        mode_dir = captures_root / f"mode_{index:02d}"
        mode_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(evidence / "repeats/baseline_1" / name, mode_dir / name)
        (mode_dir / "mode.json").write_text(json.dumps(frame, indent=2) + "\n")

    run_dir = evidence / "repeats/baseline_1"
    for name in ("RUNTIME_TELEMETRY.json", "CAPTURE_MANIFEST.json", "RUNTIME_RENDER_CONFIG.json", "BUILD_FINGERPRINT.json"):
        source = run_dir / name
        if source.is_file():
            shutil.copy2(source, runtime_root / name)
    (runtime_root / "gl_renderer.json").write_text(json.dumps({
        "glVendor": first_manifest["glVendor"],
        "glRenderer": first_manifest["glRenderer"],
        "glVersion": first_manifest["glVersion"],
        "renderBackend": first_manifest["renderBackend"],
        "isSoftwareRenderer": first_manifest["isSoftwareRenderer"],
        "glError": first_telemetry["glError"],
        "glErrorCode": first_manifest["captureValidation"]["glErrorCode"],
    }, indent=2) + "\n")
    full_frame = frames["FULL_RENDER.png"]
    (runtime_root / "raymarch.json").write_text(json.dumps({
        "qualityTier": full_frame["runtime"]["qualityTier"],
        "isSoftwareRenderer": full_frame["runtime"]["isSoftwareRenderer"],
        "configuredRaymarchSteps": full_frame["runtime"]["configuredRaymarchSteps"],
        "effectiveRaymarchSteps": full_frame["runtime"]["effectiveRaymarchSteps"],
        "actualUniformSnapshot": full_frame["actualUniformSnapshot"],
        "shaderProgramId": full_frame["runtime"]["shaderProgramId"],
        "shaderSourceSha256": full_frame["runtime"]["shaderSourceSha256"],
        "glVendor": full_frame["runtime"]["glVendor"],
        "glRenderer": full_frame["runtime"]["glRenderer"],
        "glVersion": full_frame["runtime"]["glVersion"],
        "glError": full_frame["runtime"]["glError"],
    }, indent=2) + "\n")
    (runtime_root / "icosphere.json").write_text(json.dumps(first_manifest["geometry"], indent=2) + "\n")

    if (evidence / "MOTION_20S.mp4").is_file():
        shutil.copy2(evidence / "MOTION_20S.mp4", video_root / "continuous_20s.mp4")
    motion_frames = evidence / "motion_frames"
    if motion_frames.is_dir():
        shutil.copytree(motion_frames, video_root / "frames", dirs_exist_ok=True)
    for source in (evidence / "android_api.txt", evidence / "avd_model.txt", evidence / "motion_duration_seconds.txt", evidence / "video_start_utc.txt", evidence / "video_end_utc.txt"):
        if source.is_file():
            shutil.copy2(source, api_root / source.name)
    logcat = logs_root / "logcat.txt"
    errors = logs_root / "gl_errors.txt"
    if not logcat.exists():
        logcat.write_text("LOGCAT_UNAVAILABLE\n")
    if not errors.exists():
        errors.write_text("GL_ERROR_SCAN_UNAVAILABLE\n")

    checksum_path = api_root / "SHA256SUMS"
    entries = []
    for file in sorted(p for p in api_root.rglob("*") if p.is_file() and p != checksum_path):
        entries.append(f"{sha_file(file)}  {file.relative_to(api_root).as_posix()}")
    checksum_path.write_text("\n".join(entries) + "\n")
    archive_path = evidence / "API36_AVD_BASELINE_EVIDENCE.zip"
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for file in sorted(p for p in api_root.rglob("*") if p.is_file()):
            archive.write(file, Path("api36_avd") / file.relative_to(api_root))
    return archive_path


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")


def load_rgba(path: Path) -> np.ndarray:
    image = Image.open(path).convert("RGBA")
    if image.size != SIZE:
        image = image.resize(SIZE, Image.Resampling.LANCZOS)
    return np.asarray(image)


def load_mask(region_id: str) -> np.ndarray:
    contract = json.loads((P0 / "contract/AI_ORB_P0_3_ACCEPTANCE_CONTRACT_V1.json").read_text())
    record = next(r for r in contract["visual_regions"]["regions"] if r["id"] == region_id)
    path = P0 / record["mask_file"]
    return np.asarray(Image.open(path).convert("L")) > 0


def centroid(mask: np.ndarray) -> tuple[float, float] | None:
    ys, xs = np.nonzero(mask)
    if len(xs) == 0:
        return None
    return float(xs.mean()), float(ys.mean())


def bbox(mask: np.ndarray) -> tuple[int, int, int, int] | None:
    ys, xs = np.nonzero(mask)
    if len(xs) == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max() + 1), int(ys.max() + 1)


def mask_centroid_error(candidate: np.ndarray, target: np.ndarray, diameter: float) -> float | None:
    a, b = centroid(candidate), centroid(target)
    if a is None or b is None:
        return None
    return math.dist(a, b) / max(diameter, 1.0)


def color_masks(image_rgba: np.ndarray) -> dict[str, np.ndarray]:
    rgb = image_rgba[:, :, :3].astype(np.float32)
    hsv = np.asarray(Image.fromarray(image_rgba[:, :, :3], "RGB").convert("HSV"))
    hue = hsv[:, :, 0].astype(np.int16)
    saturation = hsv[:, :, 1].astype(np.int16)
    value = hsv[:, :, 2].astype(np.int16)
    visible = image_rgba[:, :, 3] >= 8
    emerald = visible & (rgb[:, :, 1] > rgb[:, :, 0] * 1.08) & (rgb[:, :, 1] > rgb[:, :, 2] * 0.86) & (saturation > 42) & (value > 35)
    sapphire = visible & (value <= 144) & (hue >= 126) & (hue <= 178) & (saturation >= 65) & (rgb[:, :, 2] >= rgb[:, :, 1] * 0.92)
    aqua = visible & (hue >= 116) & (hue <= 145) & (saturation >= 42) & (value >= 75)
    aqua &= ~emerald
    sapphire &= ~(emerald | aqua)
    return {"EMERALD_FOLD": emerald, "SAPPHIRE_VALLEY": sapphire, "AQUA_LIQUID": aqua}


def metric_set(capture_dir: Path) -> dict:
    core = load_rgba(capture_dir / "CORE_ONLY_TRANSPARENT.png")
    full = load_rgba(capture_dir / "FULL_RENDER.png")
    target_rgba = np.asarray(Image.open(P0 / "references/REF_A_CORE_CRYSTAL_RGBA.png").convert("RGBA"))
    target_scene = np.asarray(Image.open(P0 / "references/REF_B_FULL_SCENE_RGB.png").convert("RGB"))
    target_silhouette = target_rgba[:, :, 3] >= 8
    candidate_silhouette = core[:, :, 3] >= 8
    sil_intersection = int(np.count_nonzero(candidate_silhouette & target_silhouette))
    sil_union = int(np.count_nonzero(candidate_silhouette | target_silhouette))
    target_box, candidate_box = bbox(target_silhouette), bbox(candidate_silhouette)
    target_center, candidate_center = centroid(target_silhouette), centroid(candidate_silhouette)
    assert target_box and candidate_box and target_center and candidate_center
    target_diameter = max(target_box[2] - target_box[0], target_box[3] - target_box[1])
    candidate_diameter = max(candidate_box[2] - candidate_box[0], candidate_box[3] - candidate_box[1])
    centers_px = math.dist(candidate_center, target_center)
    candidate_classes = color_masks(core)
    area_errors, centroid_errors = {}, {}
    for region_id, candidate in candidate_classes.items():
        target = load_mask(region_id)
        target_area = int(np.count_nonzero(target))
        candidate_area = int(np.count_nonzero(candidate))
        area_errors[region_id] = {
            "targetPixels": target_area,
            "candidatePixels": candidate_area,
            "relativeError": abs(candidate_area - target_area) / max(target_area, 1),
        }
        centroid_errors[region_id] = mask_centroid_error(candidate, target, target_diameter)

    ref_a_rgb = target_rgba[:, :, :3]
    core_rgb = core[:, :, :3]
    ref_b_rgb = target_scene
    full_rgb = full[:, :, :3]

    def roi_color_metrics(reference: np.ndarray, candidate: np.ndarray, roi: np.ndarray) -> dict:
        _, ssim_map = structural_similarity(reference, candidate, channel_axis=2, data_range=255, full=True)
        if ssim_map.ndim == 3:
            ssim_map = np.mean(ssim_map, axis=2)
        ref_lab = rgb2lab(reference.astype(np.float32) / 255.0)
        candidate_lab = rgb2lab(candidate.astype(np.float32) / 255.0)
        delta = deltaE_ciede2000(ref_lab, candidate_lab)
        vals = delta[roi]
        return {
            "ssim": float(np.mean(ssim_map[roi])) if np.any(roi) else None,
            "deltaE2000Median": float(np.median(vals)) if vals.size else None,
            "deltaE2000P90": float(np.percentile(vals, 90)) if vals.size else None,
            "roiPixels": int(np.count_nonzero(roi)),
        }

    color_roi_ids = ["OUTER_GLASS_RIM", "EMERALD_FOLD", "SAPPHIRE_VALLEY", "AQUA_LIQUID", "EMBEDDED_CRYSTAL_LENS", "GOLD_CAUSTIC_HIGHLIGHTS"]
    core_rois = {region: roi_color_metrics(ref_a_rgb, core_rgb, load_mask(region)) for region in color_roi_ids}
    full_scene_metrics = roi_color_metrics(ref_b_rgb, full_rgb, np.ones(SIZE[::-1], dtype=bool))

    # Resident path coverage is evaluated against the fixed ROI centerline masks.
    # A 6px neighborhood compensates only for the contract's rasterized path width;
    # no ROI shape or capture registration is changed.
    full_classes = color_masks(full)
    full_hsv = np.asarray(Image.fromarray(full[:, :, :3], "RGB").convert("HSV"))
    full_rgb_float = full[:, :, :3].astype(np.float32)
    full_saturation = full_hsv[:, :, 1]
    full_value = full_hsv[:, :, 2]
    cyan = (full_rgb_float[:, :, 1] > full_rgb_float[:, :, 0] * 1.25) & (full_rgb_float[:, :, 2] > full_rgb_float[:, :, 0] * 1.35) & (full_value > 85)
    gold = (full_rgb_float[:, :, 0] > full_rgb_float[:, :, 2] * 1.22) & (full_rgb_float[:, :, 1] > full_rgb_float[:, :, 2] * 1.04) & (full_saturation > 28) & (full_value > 75)
    resident_candidates = {
        "RESIDENT_FRONT_CYAN": cyan,
        "RESIDENT_FRONT_GOLD": gold,
        "RESIDENT_BACK_GREEN_GOLD": full_classes["EMERALD_FOLD"] | gold,
    }
    resident_metrics = {}
    for region, candidate in resident_candidates.items():
        target = load_mask(region)
        target_neighborhood = distance_transform_edt(~target) <= 6.0
        candidate_neighborhood = distance_transform_edt(~candidate) <= 6.0
        precision = float(np.count_nonzero(candidate & target_neighborhood) / max(np.count_nonzero(candidate), 1))
        recall = float(np.count_nonzero(target & candidate_neighborhood) / max(np.count_nonzero(target), 1))
        resident_metrics[region] = {"edgePrecision6px": precision, "pathRecall6px": recall, "edgeF1": 2 * precision * recall / max(precision + recall, 1e-12)}

    return {
        "coreSilhouette": {
            "iou": sil_intersection / max(sil_union, 1),
            "centerErrorPixels": centers_px,
            "centerErrorFrameWidth": centers_px / SIZE[0],
            "centerErrorD": centers_px / target_diameter,
            "diameterRelativeError": abs(candidate_diameter - target_diameter) / target_diameter,
            "referenceDiameterPixels": target_diameter,
            "candidateDiameterPixels": candidate_diameter,
        },
        "materialArea": area_errors,
        "materialCentroidErrorD": centroid_errors,
        "coreColorRegions": core_rois,
        "fullSceneColor": full_scene_metrics,
        "residentPath": resident_metrics,
    }


def make_diff_images(reference_path: Path, candidate_path: Path, label: str) -> tuple[Image.Image, Image.Image, Image.Image]:
    ref = Image.open(reference_path).convert("RGBA")
    candidate = Image.open(candidate_path).convert("RGBA")
    if ref.size != SIZE:
        ref = ref.resize(SIZE, Image.Resampling.LANCZOS)
    if candidate.size != SIZE:
        candidate = candidate.resize(SIZE, Image.Resampling.LANCZOS)
    diff = np.abs(np.asarray(ref).astype(np.int16) - np.asarray(candidate).astype(np.int16))
    heat = np.clip(diff[:, :, :3].max(axis=2) * 3, 0, 255).astype(np.uint8)
    heat_rgb = np.zeros((SIZE[1], SIZE[0], 3), dtype=np.uint8)
    heat_rgb[:, :, 0] = heat
    heat_rgb[:, :, 1] = np.clip(255 - heat * 0.35, 0, 255).astype(np.uint8)
    heat_rgb[:, :, 2] = np.clip(255 - heat * 0.7, 0, 255).astype(np.uint8)
    return ref.convert("RGB"), candidate.convert("RGB"), Image.fromarray(heat_rgb)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-dir", required=True, type=Path)
    parser.add_argument("--expected-apk", required=True, type=Path)
    parser.add_argument("--expected-head", required=True, type=str)
    args = parser.parse_args()
    evidence = args.evidence_dir.resolve()
    contract = json.loads((P0 / "contract/AI_ORB_P0_3_ACCEPTANCE_CONTRACT_V1.json").read_text())
    contract_modes = contract["capture_contract"]["required_render_modes"]
    if contract_modes != REQUIRED_MODES:
        raise ValueError(f"Capture mode sequence differs from frozen contract: {contract_modes}")
    repeats_root = evidence / "repeats"
    run_dirs = [repeats_root / f"baseline_{i}" for i in (1, 2, 3)]
    for run_dir in run_dirs:
        if not run_dir.is_dir():
            raise FileNotFoundError(f"Missing repeatability run: {run_dir}")

    report_path = P0 / "P0_3_PREFLIGHT_REPORT.json"
    report = json.loads(report_path.read_text()) if report_path.exists() else {}
    result = {"schema": "AI_ORB_P0_3_AVD_BASELINE_ANALYSIS_1_0", "generatedAtUtc": datetime.now(timezone.utc).isoformat(), "runs": []}
    metric_runs = []
    telemetry_fields = ["buildSha", "apkSha256", "glVendor", "glRenderer", "glVersion", "renderResolution", "raymarchSteps", "qualityTier", "configuredRaymarchSteps", "effectiveRaymarchSteps", "currentSubdivisions", "geometryStateValid", "geometrySubdivisions", "geometryVertexCount", "geometryTriangleCount", "geometryFingerprintSha256", "cameraConfigHash", "modelTransformHash"]
    expected_apk_sha = sha(args.expected_apk.read_bytes()) if args.expected_apk else None
    asset_shas = asset_hashes_from_apk(args.expected_apk) if args.expected_apk else {}
    installed_apk_record = json.loads((evidence / "api36_avd/installed_apk.json").read_text())
    if expected_apk_sha and installed_apk_record.get("apkSha256") != expected_apk_sha:
        raise ValueError("Installed APK record differs from exact-HEAD CI APK SHA")
    if expected_apk_sha and installed_apk_record.get("installedBaseApkSha256") != expected_apk_sha:
        raise ValueError("ADB-pulled installed APK bytes differ from exact-HEAD CI APK SHA")
    roi_freeze_path = P0 / "ROI_MASK_FREEZE_MANIFEST.json"
    roi_freeze_sha = sha_file(roi_freeze_path)
    mask_hashes = {record["id"]: record["sha256"] for record in json.loads(roi_freeze_path.read_text())["records"]}
    expected_mode_for_image = dict(zip(REQUIRED_IMAGES, REQUIRED_MODES))
    for index, run_dir in enumerate(run_dirs, 1):
        missing = [name for name in REQUIRED_IMAGES if not (run_dir / name).is_file()]
        if missing:
            raise FileNotFoundError(f"baseline_{index} is missing capture images: {missing}")
        manifest = json.loads((run_dir / "CAPTURE_MANIFEST.json").read_text())
        telemetry = json.loads((run_dir / "RUNTIME_TELEMETRY.json").read_text())
        run_info = json.loads((run_dir / "run_info.json").read_text())
        if run_info.get("runIndex") != index or run_info.get("packageName") != "com.aistudio.aiorbdemo.vxkrmq":
            raise ValueError(f"baseline_{index} was not produced by the cold-start instrumentation invocation")
        if run_info.get("androidApiLevel") != 36:
            raise ValueError(f"baseline_{index} runtime reports API {run_info.get('androidApiLevel')}, expected 36")
        if args.expected_head and manifest["buildSha"] != args.expected_head:
            raise ValueError(f"baseline_{index} runtime build SHA does not match requested exact HEAD")
        if expected_apk_sha and manifest["apkSha256"] != expected_apk_sha:
            raise ValueError(f"baseline_{index} installed APK SHA differs from downloaded exact-head APK")
        frames = {f["file"]: f for f in manifest["frames"]}
        if [f["mode"] for f in manifest["frames"][: len(REQUIRED_MODES)]] != REQUIRED_MODES:
            raise ValueError(f"baseline_{index} capture modes do not follow the frozen nine-mode order")
        if not all(name in frames and frames[name]["sha256"] != "MISSING" for name in REQUIRED_IMAGES):
            raise ValueError(f"baseline_{index} capture manifest omits a required frame")
        if manifest["status"] != "COMPLETE" or not manifest["captureValidation"]["framebufferComplete"] or manifest["captureValidation"]["glErrorCode"] != 0 or not manifest["captureValidation"]["allFrameRuntimeValid"]:
            raise ValueError(f"baseline_{index} failed GPU capture validation")
        for name in REQUIRED_IMAGES:
            image_path = run_dir / name
            image = Image.open(image_path)
            if image.size != tuple((manifest["surfaceWidth"], manifest["surfaceHeight"])):
                raise ValueError(f"baseline_{index} {name} dimensions do not match runtime manifest")
            if manifest["surfaceWidth"] != SIZE[0] or manifest["surfaceHeight"] != SIZE[1]:
                raise ValueError(f"baseline_{index} surface differs from frozen 1254x1254 canvas")
            if frames[name]["sha256"] != sha_file(image_path):
                raise ValueError(f"baseline_{index} {name} SHA differs from manifest")
            if frames[name]["width"] != image.width or frames[name]["height"] != image.height:
                raise ValueError(f"baseline_{index} {name} frame dimensions differ from manifest")
            mode = frames[name]["mode"]
            if mode != expected_mode_for_image[name]:
                raise ValueError(f"baseline_{index} captured unexpected mode for {name}: {mode}")
            if frames[name]["modeConfig"].get("renderDebugMode") != mode or frames[name]["modeConfig"].get("quality") != "HIGH":
                raise ValueError(f"baseline_{index} {name} did not use the frozen HIGH mode configuration")
            runtime = frames[name]["runtime"]
            if not runtime["framebufferComplete"] or runtime["readbackGlErrorCode"] != 0 or runtime["glError"] != "GL_NO_ERROR":
                raise ValueError(f"baseline_{index} {name} has a failed framebuffer or GL state")
            if runtime["shaderProgramId"] <= 0 or runtime["shaderSourceSha256"] == "UNAVAILABLE":
                raise ValueError(f"baseline_{index} {name} does not identify the production shader")
            if not runtime["geometryStateValid"] or runtime["currentSubdivisions"] != 4:
                raise ValueError(f"baseline_{index} {name} has inconsistent current mesh subdivision")
            if mode != "RESIDENTS_ONLY":
                uniform = frames[name]["actualUniformSnapshot"]
                if not uniform or not uniform["uniformStateValid"]:
                    raise ValueError(f"baseline_{index} PF02_RUNTIME_EVIDENCE_INCOMPLETE for {mode}: actual Uniform readback missing or invalid")
                if uniform["uDebugMode"] != frames[name]["modeConfig"]["shaderDebugModeId"]:
                    raise ValueError(f"baseline_{index} {mode} shader mode Uniform differs from actual production mode")
        if manifest["volume"]["loadState"] != "LOADED" or manifest["volume"]["uVolumeLoaded"] != 1 or not manifest["volume"]["all3DTexturesValid"]:
            raise ValueError(f"baseline_{index} did not use the loaded production 3D volume")
        if not manifest["geometry"]["geometryStateValid"] or manifest["geometry"]["currentSubdivisions"] != 4 or manifest["geometry"]["icosphereSubdivisions"] != 4 or manifest["geometry"]["vertexCount"] != 2562 or manifest["geometry"]["triangleCount"] != 5120:
            raise ValueError(f"baseline_{index} runtime geometry did not match HIGH tier: {manifest['geometry']}")
        software = manifest["isSoftwareRenderer"]
        if manifest["renderBackend"] != ("SOFTWARE_RENDER" if software else "HARDWARE_GPU"):
            raise ValueError(f"baseline_{index} renderer label contradicts isSoftwareRenderer")
        expected_steps = min(48, 20) if software else 48
        full_frame = frames["FULL_RENDER.png"]
        full_uniform = full_frame["actualUniformSnapshot"]
        if manifest["qualityTier"] != "HIGH" or manifest["configuredRaymarchSteps"] != 48:
            raise ValueError(f"baseline_{index} did not use configured HIGH/48 raymarch settings")
        if manifest["effectiveRaymarchSteps"] != expected_steps or full_frame["runtime"]["effectiveRaymarchSteps"] != expected_steps:
            raise ValueError(f"baseline_{index} runtime raymarch steps disagree with actual backend")
        if full_uniform["uRaymarchSteps"] != expected_steps or manifest["actualUniformSnapshot"]["uRaymarchSteps"] != expected_steps:
            raise ValueError(f"baseline_{index} PF02 actual linked-program uRaymarchSteps readback is inconsistent")
        if manifest["renderBackend"] == "SOFTWARE_RENDER" and not software:
            raise ValueError(f"baseline_{index} SOFTWARE_RENDERER classification mismatch")
        if telemetry["apkSha256"] != expected_apk_sha or telemetry["buildSha"] != manifest["buildSha"]:
            raise ValueError(f"baseline_{index} runtime telemetry APK/HEAD binding mismatch")
        if telemetry["qualityTier"] != "HIGH" or telemetry["configuredRaymarchSteps"] != 48 or telemetry["raymarchSteps"] != expected_steps:
            raise ValueError(f"baseline_{index} RUNTIME_TELEMETRY does not reflect actual FULL_RENDER uniform values")
        frame_snapshot_ids = {frames[name]["snapshotId"] for name in REQUIRED_IMAGES}
        frame_camera_hashes = {frames[name]["cameraConfigHash"] for name in REQUIRED_IMAGES}
        frame_model_hashes = {frames[name]["modelTransformHash"] for name in REQUIRED_IMAGES}
        if len(frame_snapshot_ids) != 1 or len(frame_camera_hashes) != 1 or len(frame_model_hashes) != 1:
            raise ValueError(f"baseline_{index} nine modes do not share one frozen camera/motion snapshot")
        run_metric = metric_set(run_dir)
        metric_runs.append(run_metric)
        result["runs"].append({
            "run": index,
            "captureManifest": manifest,
            "runtimeTelemetry": telemetry,
            "runInfo": run_info,
            "imageSha256": {name: sha((run_dir / name).read_bytes()) for name in REQUIRED_IMAGES},
            "metrics": run_metric,
        })

    baseline_manifest = result["runs"][0]["captureManifest"]
    repeatability_fields = [
        "buildSha", "apkSha256", "renderBackend", "glVendor", "glRenderer", "glVersion", "renderResolution",
        "qualityTier", "configuredRaymarchSteps", "effectiveRaymarchSteps", "shaderSourceSha256",
        "cameraConfigHash", "modelTransformHash",
    ]
    static_consistency = all(
        all(run["captureManifest"].get(field) == baseline_manifest.get(field) for field in repeatability_fields)
        for run in result["runs"][1:]
    )
    if not static_consistency:
        raise ValueError("Three baseline runs changed backend, build, camera, phase, quality, or render geometry")
    dynamic_consistency = all(
        all(run["runtimeTelemetry"].get(field) == result["runs"][0]["runtimeTelemetry"].get(field) for field in telemetry_fields)
        for run in result["runs"][1:]
    )
    if not dynamic_consistency:
        raise ValueError("Three baseline runs changed actual runtime GL/geometry values")
    if any(run["captureManifest"]["motionSnapshot"] != baseline_manifest["motionSnapshot"] for run in result["runs"][1:]):
        raise ValueError("Three baseline runs did not start at the identical reference-locked motion phase")
    if any(run["captureManifest"]["geometry"] != baseline_manifest["geometry"] for run in result["runs"][1:]):
        raise ValueError("PF05 geometry runtime evidence changed between cold starts")
    if any(run["runtimeTelemetry"]["actualUniformSnapshot"] != result["runs"][0]["runtimeTelemetry"]["actualUniformSnapshot"] for run in result["runs"][1:]):
        raise ValueError("PF05 actual production uniform snapshots changed between cold starts")

    def required_mode_signature(run: dict) -> list[dict]:
        frames = png_frame_lookup(run["captureManifest"])
        stable_runtime_fields = (
            "qualityTier", "isSoftwareRenderer", "configuredRaymarchSteps", "effectiveRaymarchSteps",
            "shaderSourceSha256", "glVendor", "glRenderer", "glVersion", "volumeLoadState",
            "all3DTexturesValid", "geometryStateValid", "currentSubdivisions", "geometryFingerprintSha256",
            "glError", "framebufferComplete", "readbackGlErrorCode",
        )
        return [
            {
                "file": name,
                "mode": frames[name]["mode"],
                "modeConfig": frames[name]["modeConfig"],
                "actualUniformSnapshot": frames[name]["actualUniformSnapshot"],
                "runtime": {key: frames[name]["runtime"][key] for key in stable_runtime_fields},
                "cameraConfigHash": frames[name]["cameraConfigHash"],
                "modelTransformHash": frames[name]["modelTransformHash"],
                "width": frames[name]["width"],
                "height": frames[name]["height"],
            }
            for name in REQUIRED_IMAGES
        ]

    mode_signatures = [required_mode_signature(run) for run in result["runs"]]
    mode_evidence_identical = mode_signatures[1:] == mode_signatures[:-1]
    if not mode_evidence_identical:
        raise ValueError("PF05 deterministic per-mode config, uniform, geometry, or camera evidence changed across cold starts")
    image_dimensions = {name: (Image.open(run_dirs[0] / name).width, Image.open(run_dirs[0] / name).height) for name in REQUIRED_IMAGES}
    if any((Image.open(run_dirs[i] / name).width, Image.open(run_dirs[i] / name).height) != dimensions for i in (1, 2) for name, dimensions in image_dimensions.items()):
        raise ValueError("PF05 screenshot dimensions changed across repeats")

    # Variance is measured for the full list of contract-aligned numerical metrics.
    metric_samples: dict[str, list[float]] = {}
    for idx, run in enumerate(metric_runs, 1):
        for key, value in run["coreSilhouette"].items():
            if isinstance(value, (int, float)):
                metric_samples.setdefault(f"coreSilhouette.{key}", []).append(float(value))
        for region, values in run["materialArea"].items():
            metric_samples.setdefault(f"materialArea.{region}.relativeError", []).append(float(values["relativeError"]))
        for region, value in run["materialCentroidErrorD"].items():
            if value is not None:
                metric_samples.setdefault(f"materialCentroidErrorD.{region}", []).append(float(value))
        for region, values in run["coreColorRegions"].items():
            metric_samples.setdefault(f"coreColorRegions.{region}.ssim", []).append(float(values["ssim"]))
            metric_samples.setdefault(f"coreColorRegions.{region}.deltaE2000Median", []).append(float(values["deltaE2000Median"]))
        metric_samples.setdefault("fullSceneColor.ssim", []).append(float(run["fullSceneColor"]["ssim"]))
        metric_samples.setdefault("fullSceneColor.deltaE2000Median", []).append(float(run["fullSceneColor"]["deltaE2000Median"]))
        for region, values in run["residentPath"].items():
            metric_samples.setdefault(f"residentPath.{region}.edgeF1", []).append(float(values["edgeF1"]))
    variance = {}
    for key, samples in metric_samples.items():
        variance[key] = {
            "samples": samples,
            "mean": statistics.mean(samples),
            "stddev": statistics.stdev(samples) if len(samples) > 1 else 0.0,
            "range": max(samples) - min(samples),
            "threeSigma": 3.0 * (statistics.stdev(samples) if len(samples) > 1 else 0.0),
        }
    result["repeatability"] = {
        "sameConditions": static_consistency and dynamic_consistency,
        "varianceByMetric": variance,
        "metricNoiseRule": "Future per-round error reduction must exceed 3x this baseline standard deviation.",
        "exactImageHashesAcrossRuns": {
            name: len({run["imageSha256"][name] for run in result["runs"]}) == 1
            for name in REQUIRED_IMAGES
        },
        "textureAssetSha256": asset_shas,
        "roiMaskFreezeManifestSha256": roi_freeze_sha,
        "roiMaskSha256": mask_hashes,
    }

    api = (evidence / "android_api.txt").read_text().strip() if (evidence / "android_api.txt").exists() else "UNKNOWN"
    video_duration = float((evidence / "motion_duration_seconds.txt").read_text().strip()) if (evidence / "motion_duration_seconds.txt").exists() else 0.0
    if api != "36":
        raise ValueError(f"Expected API 36 AVD, got {api}")
    if not (evidence / "MOTION_20S.mp4").is_file() or (evidence / "MOTION_20S.mp4").stat().st_size == 0:
        raise ValueError("PF04 motion video is missing or empty")
    if video_duration < 20.0:
        raise ValueError(f"Motion video is shorter than 20 seconds: {video_duration}")
    environment = json.loads((evidence / "api36_avd/environment.json").read_text())
    avd_config_path = evidence / "api36_avd/avd_config.ini"
    system_properties_path = evidence / "api36_avd/system_properties.txt"
    if not avd_config_path.is_file() or not system_properties_path.is_file():
        raise ValueError("PF04 AVD config or system property evidence is missing")
    if environment["renderBackendRequested"].startswith("SOFTWARE_RENDERER") and baseline_manifest["renderBackend"] != "SOFTWARE_RENDER":
        raise ValueError("AVD was configured for SwiftShader, but runtime telemetry did not identify a software renderer")
    backend_class = "SOFTWARE_RENDER" if baseline_manifest["renderBackend"] == "SOFTWARE_RENDER" else "EMULATOR_HARDWARE_RENDER_CANDIDATE"

    full = metric_runs[0]
    contract = json.loads((P0 / "contract/AI_ORB_P0_3_ACCEPTANCE_CONTRACT_V1.json").read_text())
    gates = contract["visual_acceptance_thresholds"]
    visual_gates = {
        "coreSilhouetteIoU": {"value": full["coreSilhouette"]["iou"], "threshold": gates["geometry"]["core_silhouette_iou_min"], "pass": full["coreSilhouette"]["iou"] >= gates["geometry"]["core_silhouette_iou_min"]},
        "coreCenterErrorD": {"value": full["coreSilhouette"]["centerErrorD"], "threshold": gates["geometry"]["core_center_error_max_d"], "pass": full["coreSilhouette"]["centerErrorD"] <= gates["geometry"]["core_center_error_max_d"]},
        "coreDiameterRelativeError": {"value": full["coreSilhouette"]["diameterRelativeError"], "threshold": gates["geometry"]["core_diameter_relative_error_max"], "pass": full["coreSilhouette"]["diameterRelativeError"] <= gates["geometry"]["core_diameter_relative_error_max"]},
    }
    for key, item in full["materialArea"].items():
        visual_gates[f"{key}.areaRelativeError"] = {"value": item["relativeError"], "threshold": gates["material_regions"]["required_region_area_relative_error_max"], "pass": item["relativeError"] <= gates["material_regions"]["required_region_area_relative_error_max"]}
    for key, value in full["materialCentroidErrorD"].items():
        threshold = gates["material_regions"]["required_region_centroid_error_max_d"]
        visual_gates[f"{key}.centroidErrorD"] = {"value": value, "threshold": threshold, "pass": value is not None and value <= threshold}
    visual_gates["coreColorRegions.ssim"] = {"value": min(v["ssim"] for v in full["coreColorRegions"].values()), "threshold": gates["material_regions"]["core_roi_ssim_min"], "pass": all(v["ssim"] >= gates["material_regions"]["core_roi_ssim_min"] for v in full["coreColorRegions"].values())}
    visual_gates["fullScene.ssim"] = {"value": full["fullSceneColor"]["ssim"], "threshold": gates["material_regions"]["full_scene_ssim_min"], "pass": full["fullSceneColor"]["ssim"] >= gates["material_regions"]["full_scene_ssim_min"]}
    # Delta-E is reported directly; both the median and P90 thresholds are preserved.
    visual_gates["coreColorRegions.deltaE2000Median"] = {"value": max(v["deltaE2000Median"] for v in full["coreColorRegions"].values()), "threshold": gates["material_regions"]["median_delta_e_2000_max"], "pass": all(v["deltaE2000Median"] <= gates["material_regions"]["median_delta_e_2000_max"] for v in full["coreColorRegions"].values())}
    visual_gates["coreColorRegions.deltaE2000P90"] = {"value": max(v["deltaE2000P90"] for v in full["coreColorRegions"].values()), "threshold": gates["material_regions"]["p90_delta_e_2000_max"], "pass": all(v["deltaE2000P90"] <= gates["material_regions"]["p90_delta_e_2000_max"] for v in full["coreColorRegions"].values())}
    visual_gates["fullScene.deltaE2000Median"] = {"value": full["fullSceneColor"]["deltaE2000Median"], "threshold": gates["material_regions"]["median_delta_e_2000_max"], "pass": full["fullSceneColor"]["deltaE2000Median"] <= gates["material_regions"]["median_delta_e_2000_max"]}
    visual_gates["fullScene.deltaE2000P90"] = {"value": full["fullSceneColor"]["deltaE2000P90"], "threshold": gates["material_regions"]["p90_delta_e_2000_max"], "pass": full["fullSceneColor"]["deltaE2000P90"] <= gates["material_regions"]["p90_delta_e_2000_max"]}
    for region, values in full["residentPath"].items():
        visual_gates[f"{region}.edgeF1"] = {"value": values["edgeF1"], "threshold": gates["resident"]["required_resident_roi_edge_alignment_min"], "pass": values["edgeF1"] >= gates["resident"]["required_resident_roi_edge_alignment_min"]}
    gold_mask = load_mask("GOLD_CAUSTIC_HIGHLIGHTS")
    core_frame = load_rgba(run_dirs[0] / "CORE_ONLY_TRANSPARENT.png")
    gold_rgb = core_frame[:, :, :3][gold_mask]
    clipped_ratio = float(np.mean(np.any(gold_rgb >= 250, axis=1))) if len(gold_rgb) else 1.0
    visual_gates["clippedGoldHighlightRatio"] = {"value": clipped_ratio, "threshold": gates["highlight_and_bloom"]["clipped_highlight_ratio_max_in_core_roi"], "pass": clipped_ratio <= gates["highlight_and_bloom"]["clipped_highlight_ratio_max_in_core_roi"]}

    unmeasured_gates = [
        "resident_identity_count_and_front_back_role_match",
        "background_bokeh_region_and_droplet_count",
        "upper_dome_haze_definition_and_gate",
        "bloom_off_core_structure_and_haze_comparison",
        "prohibited_visual_defects_manual_review",
        "motion_invariants_and_performance_fps_p95_freeze_limits",
    ]
    result["visualGateStatus"] = "FAIL" if any(not gate["pass"] for gate in visual_gates.values()) else "PARTIAL_METRICS_ONLY"
    result["visualGates"] = visual_gates
    result["unmeasuredVisualGates"] = unmeasured_gates
    result["renderEvidenceClass"] = backend_class
    result["evidenceLimits"] = ["Android API 36 emulator output", "Hardware GPU status is not inferred from successful GLES output", "Human high-fidelity visual review remains required"]
    (P0 / "VISUAL_DIFF_REPORT.json").write_text(json.dumps(result, indent=2) + "\n")

    heat_dir = P0 / "VISUAL_DIFF_HEATMAPS"
    heat_dir.mkdir(parents=True, exist_ok=True)
    reference_a_path = P0 / "references/REF_A_CORE_CRYSTAL_RGBA.png"
    reference_b_path = P0 / "references/REF_B_FULL_SCENE_RGB.png"
    baseline_dir = run_dirs[0]
    core_triplet = make_diff_images(reference_a_path, baseline_dir / "CORE_ONLY_TRANSPARENT.png", "core")
    scene_triplet = make_diff_images(reference_b_path, baseline_dir / "FULL_RENDER.png", "full")
    tiles = []
    for label, triplet in (("REF_A core", core_triplet), ("REF_B full scene", scene_triplet)):
        for title, image in zip((f"{label}: REF", f"{label}: AVD GLES", f"{label}: |diff|×3"), triplet):
            tile = image.resize((376, 376), Image.Resampling.LANCZOS)
            draw = ImageDraw.Draw(tile)
            draw.rectangle((0, 0, 376, 23), fill=(0, 0, 0))
            draw.text((6, 6), title, fill=(255, 255, 255), font=ImageFont.load_default())
            tiles.append(tile)
        triplet[2].save(heat_dir / f"{label.split()[0]}_ABS_DIFF_X3.png")
    sheet = Image.new("RGB", (3 * 376, 2 * 376), (15, 15, 15))
    for i, tile in enumerate(tiles):
        sheet.paste(tile, ((i % 3) * 376, (i // 3) * 376))
    sheet.save(P0 / "VISUAL_DIFF_CONTACT_SHEET.png")

    roi_freeze = json.loads(roi_freeze_path.read_text())
    contract_regions = contract["visual_regions"]["regions"]
    roi_count_pass = len(contract_regions) == 13 and len(roi_freeze["records"]) == 12 and roi_freeze["frozen"]
    if not roi_count_pass:
        raise ValueError("PF01 frozen contract requires 13 visual regions and exactly 12 masks")

    pf02_runs = []
    pf03_runs = []
    for run in result["runs"]:
        manifest = run["captureManifest"]
        full_frame = png_frame_lookup(manifest)["FULL_RENDER.png"]
        uniform = full_frame.get("actualUniformSnapshot")
        software = bool(manifest["isSoftwareRenderer"])
        expected_steps = min(int(manifest["configuredRaymarchSteps"]), 20) if software else int(manifest["configuredRaymarchSteps"])
        if not uniform or not uniform.get("uniformStateValid"):
            raise ValueError("PF02_RUNTIME_EVIDENCE_INCOMPLETE: production program Uniform readback is missing")
        if uniform.get("uRaymarchSteps") != expected_steps:
            raise ValueError("PF02 actual uRaymarchSteps does not match renderer-specific contract")
        pf02_runs.append({
            "runIndex": run["run"],
            "qualityTier": manifest["qualityTier"],
            "isSoftwareRenderer": software,
            "renderBackend": manifest["renderBackend"],
            "configuredRaymarchSteps": manifest["configuredRaymarchSteps"],
            "effectiveRaymarchSteps": manifest["effectiveRaymarchSteps"],
            "shaderProgramId": full_frame["runtime"]["shaderProgramId"],
            "shaderSourceSha256": full_frame["runtime"]["shaderSourceSha256"],
            "actualUniformReadback": {
                "method": "GLES30.glGetUniformiv on the linked production shader program",
                "uRaymarchSteps": uniform["uRaymarchSteps"],
                "uniformStateValid": uniform["uniformStateValid"],
            },
            "glVendor": manifest["glVendor"],
            "glRenderer": manifest["glRenderer"],
            "glVersion": manifest["glVersion"],
            "glError": full_frame["runtime"]["glError"],
            "status": "PASS",
        })
        geometry = manifest["geometry"]
        subdivision = geometry["icosphereSubdivisions"]
        expected_vertices = 10 * (4**subdivision) + 2
        expected_triangles = 20 * (4**subdivision)
        if (
            not geometry["geometryStateValid"]
            or geometry["currentSubdivisions"] != subdivision
            or geometry["vertexCount"] != expected_vertices
            or geometry["triangleCount"] != expected_triangles
            or len(geometry["geometryFingerprintSha256"]) != 64
        ):
            raise ValueError(f"PF03 measured Icosphere geometry failed at baseline_{run['run']}: {geometry}")
        pf03_runs.append({
            "runIndex": run["run"],
            "qualityTier": manifest["qualityTier"],
            "configuredSubdivision": subdivision,
            "currentSubdivisions": geometry["currentSubdivisions"],
            "vertexCount": geometry["vertexCount"],
            "expectedVertexCount": expected_vertices,
            "triangleCount": geometry["triangleCount"],
            "expectedTriangleCount": expected_triangles,
            "geometryFingerprintSha256": geometry["geometryFingerprintSha256"],
            "geometryStateValid": geometry["geometryStateValid"],
            "status": "PASS",
        })

    pf02 = {
        "schema": "AI_ORB_P0_3_PF02_RAYMARCH_RUNTIME_EVIDENCE_1_0",
        "status": "PASS",
        "source": "actual FULL_RENDER capture from OrbFluidVolumeRenderer's linked production program",
        "readbackFunction": "GlShaderProgram.getIntUniform -> GLES30.glGetUniformiv",
        "hardwareHighTierExpectedSteps": 48,
        "softwareRendererCap": 20,
        "runs": pf02_runs,
        "hardwareGpuVerification": "PENDING" if any(run["isSoftwareRenderer"] for run in pf02_runs) else "EMULATOR_RENDERER_REQUIRES_DEVICE_CLASSIFICATION",
    }
    pf03 = {
        "schema": "AI_ORB_P0_3_PF03_GEOMETRY_RUNTIME_EVIDENCE_1_0",
        "status": "PASS",
        "formula": {"vertexCount": "10 * 4^n + 2", "triangleCount": "20 * 4^n"},
        "runtimeSource": "production Icosphere mesh metadata and canonical vertex-buffer fingerprint",
        "runs": pf03_runs,
    }
    all_image_hashes_exact = all(result["repeatability"]["exactImageHashesAcrossRuns"].values())
    pf05 = {
        "schema": "AI_ORB_P0_3_PF05_REPEATABILITY_REPORT_1_0",
        "status": "PASS" if static_consistency and dynamic_consistency else "FAIL",
        "runCount": len(result["runs"]),
        "independentColdStarts": True,
        "sameConditions": static_consistency and dynamic_consistency,
        "deterministicRuntimeFieldsIdentical": static_consistency and dynamic_consistency and mode_evidence_identical,
        "perModeUniformAndConfigEvidenceIdentical": mode_evidence_identical,
        "identicalImageHashesAcrossRuns": result["repeatability"]["exactImageHashesAcrossRuns"],
        "allImageHashesIdentical": all_image_hashes_exact,
        "metricVariance": variance,
        "metricNoiseRule": result["repeatability"]["metricNoiseRule"],
        "assetSha256": asset_shas,
        "roiMaskFreezeManifestSha256": roi_freeze_sha,
        "roiMaskSha256": mask_hashes,
        "avdEnvironmentSha256": sha_file(evidence / "api36_avd/environment.json"),
        "avdConfigSha256": sha_file(avd_config_path),
        "systemPropertiesSha256": sha_file(system_properties_path),
        "runs": [
            {
                "runIndex": run["run"],
                "buildSha": run["captureManifest"]["buildSha"],
                "apkSha256": run["captureManifest"]["apkSha256"],
                "androidApiLevel": run["runInfo"]["androidApiLevel"],
                "renderBackend": run["captureManifest"]["renderBackend"],
                "glRenderer": run["captureManifest"]["glRenderer"],
                "shaderSourceSha256": run["captureManifest"]["shaderSourceSha256"],
                "geometryFingerprintSha256": run["captureManifest"]["geometry"]["geometryFingerprintSha256"],
                "snapshotId": run["captureManifest"]["snapshotId"],
                "cameraConfigHash": run["captureManifest"]["cameraConfigHash"],
                "modelTransformHash": run["captureManifest"]["modelTransformHash"],
                "imageSha256": run["imageSha256"],
                "captureFrameStats": {
                    "fps": run["runtimeTelemetry"].get("fps"),
                    "frameMsP50": run["runtimeTelemetry"].get("frameMsP50"),
                    "frameMsP95": run["runtimeTelemetry"].get("frameMsP95"),
                    "sampleCount": run["runtimeTelemetry"].get("sampleCount"),
                    "hasSufficientSamples": run["runtimeTelemetry"].get("hasSufficientSamples"),
                    "gpuFrameTimeMs": run["runtimeTelemetry"].get("gpuFrameTimeMs"),
                    "gpuFrameTimeStatus": run["runtimeTelemetry"].get("gpuFrameTimeStatus"),
                    "sourceNote": "Capture-time Android frame telemetry; 20-second motion video and gfxinfo remain separate evidence.",
                },
            }
            for run in result["runs"]
        ],
    }
    write_json(P0 / "PF02_RAYMARCH_RUNTIME_EVIDENCE.json", pf02)
    write_json(P0 / "PF03_GEOMETRY_RUNTIME_EVIDENCE.json", pf03)
    write_json(P0 / "PF05_REPEATABILITY_REPORT.json", pf05)

    if installed_apk_record.get("installationState") != "INSTALLED_AND_ACTIVITY_RUNNING":
        raise ValueError("PF04 AVD install/launch evidence is incomplete")
    if not installed_apk_record.get("applicationPid"):
        raise ValueError("PF04 application process evidence is missing")
    if installed_apk_record.get("packagePresent") is not True or installed_apk_record.get("activityStarted") is not True:
        raise ValueError("PF04 Android package/activity evidence is incomplete")
    pf_statuses = {
        "PF01_SEMANTIC_ROI_MASKS": "PASS",
        "PF02_RAYMARCH_STEPS_RUNTIME_READBACK": pf02["status"],
        "PF03_RUNTIME_GEOMETRY": pf03["status"],
        "PF04_API36_AVD_BASELINE": "PASS" if api == "36" and video_duration >= 20 and len(result["runs"]) == 3 else "FAIL",
        "PF05_REPEATABILITY": pf05["status"],
    }
    preflight_ready = all(status == "PASS" for status in pf_statuses.values())
    source_checks = report.setdefault("sourceChecks", {})
    source_checks["PF01_SEMANTIC_ROI_MASKS"] = "PASS"
    source_checks["PF02_RAYMARCH_STEPS_CONFLICT"] = {
        "status": "PASS",
        "masterFittedValue": 48,
        "hardwareHighTierSourceValue": 48,
        "softwareRendererCap": 20,
        "runtimeFullRenderEvidence": pf02_runs,
    }
    source_checks["PF03_RUNTIME_GEOMETRY_SUBDIVISION"] = {
        "status": "PASS",
        "productionHighTierSubdivisionSource": pf03_runs[0]["configuredSubdivision"],
        "runtimeGeometryEvidence": pf03_runs,
    }
    source_checks["PF04_BASELINE_ANDROID_AVD_CAPTURE"] = {
        "status": pf_statuses["PF04_API36_AVD_BASELINE"],
        "apiLevel": int(api),
        "renderBackend": baseline_manifest["renderBackend"],
        "videoDurationSeconds": video_duration,
        "runCount": len(result["runs"]),
    }
    source_checks["PF05_REPEATABILITY_NOISE"] = {
        "status": pf05["status"],
        "runCount": len(result["runs"]),
        "sameConditions": pf05["sameConditions"],
        "varianceByMetric": variance,
    }
    report.update({
        "sourceHead": args.expected_head or baseline_manifest["buildSha"],
        "runtimeChecks": {
            "PF01_SEMANTIC_ROI_MASKS": {
                "status": "PASS",
                "visualRegionCount": len(contract_regions),
                "maskCount": len(roi_freeze["records"]),
                "freezeManifestSha256": roi_freeze_sha,
                "maskSha256": mask_hashes,
                "overlapPolicy": roi_freeze["selfCheck"]["materialMaskOverlapPolicy"],
            },
            "PF02_RAYMARCH_STEPS_RUNTIME_READBACK": pf02,
            "PF03_RUNTIME_GEOMETRY": pf03,
            "PF04_API36_AVD_BASELINE": {
                "status": pf_statuses["PF04_API36_AVD_BASELINE"],
                "apiLevel": int(api),
                "model": (evidence / "avd_model.txt").read_text().strip(),
                "renderBackend": baseline_manifest["renderBackend"],
                "videoDurationSeconds": video_duration,
                "runCount": len(result["runs"]),
                "installedApkSha256": installed_apk_record["apkSha256"],
                "installedBaseApkSha256": installed_apk_record["installedBaseApkSha256"],
                "avdEnvironmentSha256": sha_file(evidence / "api36_avd/environment.json"),
                "avdConfigSha256": sha_file(avd_config_path),
            },
            "PF05_REPEATABILITY": pf05,
        },
        "preflightGates": pf_statuses,
        "preflightReadyForRound1": preflight_ready,
        "preflightReadyForOptimization": preflight_ready,
        "optimizationRoundsStarted": False,
        "hardwareGpuStatus": "PENDING_REAL_DEVICE_GPU" if backend_class == "SOFTWARE_RENDER" else "EMULATOR_BACKEND_RECORDED_DEVICE_GPU_PENDING",
        "visualFidelityStatus": "PENDING_REAL_DEVICE_VISUAL_ACCEPTANCE",
        "deviceGpuStatus": "SOFTWARE_RENDER_ONLY" if backend_class == "SOFTWARE_RENDER" else "EMULATOR_HARDWARE_RENDER_CANDIDATE",
        "avdEvidenceDir": str(evidence),
        "visualGateStatus": result["visualGateStatus"],
    })
    report_path.write_text(json.dumps(report, indent=2) + "\n")

    api_zip = build_api36_evidence_archive(evidence, baseline_manifest, result["runs"][0]["runtimeTelemetry"])
    combined_zip = evidence / "AI_ORB_P0_3_PREFLIGHT_EVIDENCE.zip"
    archive_paths = [
        api_zip,
        evidence / "MOTION_20S.mp4",
        evidence / "android_api.txt",
        evidence / "avd_model.txt",
        evidence / "motion_duration_seconds.txt",
        evidence / "api36_avd",
        evidence / "latest_capture",
        evidence / "repeats",
        P0 / "P0_3_PREFLIGHT_REPORT.json",
        P0 / "PF02_RAYMARCH_RUNTIME_EVIDENCE.json",
        P0 / "PF03_GEOMETRY_RUNTIME_EVIDENCE.json",
        P0 / "PF05_REPEATABILITY_REPORT.json",
        P0 / "VISUAL_DIFF_REPORT.json",
        P0 / "VISUAL_DIFF_CONTACT_SHEET.png",
        P0 / "VISUAL_DIFF_HEATMAPS",
        P0 / "ROI_MASK_FREEZE_MANIFEST.json",
        P0 / "ROI_MASK_MANIFEST.json",
        P0 / "DERIVED_SHA256SUMS.json",
        P0 / "contract",
        P0 / "references",
        P0 / "roi_masks",
        P0 / "overlays",
    ]
    with zipfile.ZipFile(combined_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for source in archive_paths:
            if not source.exists():
                continue
            if source.is_file():
                archive.write(source, source.relative_to(ROOT) if source.is_relative_to(ROOT) else Path("api36_avd") / source.name)
            else:
                for file in sorted(p for p in source.rglob("*") if p.is_file()):
                    archive.write(file, file.relative_to(ROOT) if file.is_relative_to(ROOT) else Path("api36_avd") / file.relative_to(source))
    print(f"[P0_3_PF01 PASS] 13 regions / 12 masks; freeze={roi_freeze_sha}")
    print(f"[P0_3_PF02 {pf02['status']}] actual linked-program Uniform readback; backend={baseline_manifest['renderBackend']}; steps={baseline_manifest['effectiveRaymarchSteps']}")
    print(f"[P0_3_PF03 {pf03['status']}] measured subdivision={baseline_manifest['geometry']['currentSubdivisions']}; vertices={baseline_manifest['geometry']['vertexCount']}; triangles={baseline_manifest['geometry']['triangleCount']}")
    print(f"[P0_3_PF04 {pf_statuses['PF04_API36_AVD_BASELINE']}] API {api}; backend={baseline_manifest['renderBackend']}; video={video_duration:.2f}s")
    print(f"[P0_3_PF05 {pf05['status']}] three cold starts; identical conditions={pf05['sameConditions']}; all hashes identical={pf05['allImageHashesIdentical']}")
    print(f"[P0_3_PREFLIGHT {'READY_FOR_ROUND_1' if preflight_ready else 'BLOCKED'}] visualGate={result['visualGateStatus']}; hardware GPU remains separate")
    print(f"[P0_3_API36_EVIDENCE] {api_zip} ({api_zip.stat().st_size} bytes)")
    print(f"[P0_3_EVIDENCE] {combined_zip} ({combined_zip.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
