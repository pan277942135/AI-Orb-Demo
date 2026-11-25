#!/usr/bin/env python3
"""Reproducible source-driven static calibration and CPU recomposition for Water World V2.1.

The master is used only to measure/compare. Production-style composition uses the
independent V2 component assets. No GPU render is produced by this script.
"""
from __future__ import annotations
import argparse
import hashlib, json, math, shutil, zipfile
from pathlib import Path
from io import BytesIO
import numpy as np
import cv2
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from scipy import ndimage as ndi
from scipy.optimize import least_squares
from scipy.spatial import cKDTree
from scipy.signal import savgol_filter

_parser = argparse.ArgumentParser(add_help=True)
_parser.add_argument("--package-root", type=Path)
_args, _unknown = _parser.parse_known_args()
PKG = (_args.package_root.resolve() if _args.package_root else Path(__file__).resolve().parents[2])
MASTER = PKG / "inputs/master/奇幻水晶球中的水下生态世界.png"
V2 = PKG / "02_CALIBRATED_ASSETS/V2_ORIGINAL"
if not V2.is_dir() or sum(1 for p in V2.rglob("*") if p.is_file()) != 65:
    V2 = Path("/workspace/WATER_WORLD_WORK/V2_EXTRACTED/full/WATER_WORLD_V2")
ZIP_OUT = PKG.parent / "WATER_WORLD_V2_1_ASSETS.zip"
W, H = 1254, 1254
MASTER_SHA = "fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589"

for d in ["01_ANALYSIS/masks", "02_CALIBRATED_ASSETS/FISH", "02_CALIBRATED_ASSETS/RESIDENT",
          "02_CALIBRATED_ASSETS/SPHERE", "02_CALIBRATED_ASSETS/GLASS", "02_CALIBRATED_ASSETS/WATER",
          "02_CALIBRATED_ASSETS/PLANTS", "02_CALIBRATED_ASSETS/ROCKS", "02_CALIBRATED_ASSETS/LIGHTING",
          "03_STATIC_RECOMPOSITION", "07_EVIDENCE"]:
    (PKG / d).mkdir(parents=True, exist_ok=True)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def jwrite(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def jsonload(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def bbox_mask(mask: np.ndarray):
    yy, xx = np.where(mask)
    if len(xx) == 0:
        return [0, 0, 0, 0]
    return [int(xx.min()), int(yy.min()), int(xx.max()+1), int(yy.max()+1)]


def iou(a: np.ndarray, b: np.ndarray):
    union = np.logical_or(a, b).sum()
    return float(np.logical_and(a, b).sum() / union) if union else 1.0


def save_mask(path: Path, mask: np.ndarray):
    Image.fromarray((mask.astype(np.uint8) * 255), "L").save(path)


def alpha_bbox(im: Image.Image, threshold=4):
    a = np.asarray(im.convert("RGBA"))[:, :, 3]
    return bbox_mask(a > threshold)


def fitted_asset(asset_path: Path, target_box: list[int], alpha_threshold=4):
    src = Image.open(asset_path).convert("RGBA")
    a = np.asarray(src.getchannel("A"))
    bx = bbox_mask(a > alpha_threshold)
    crop = src.crop(tuple(bx))
    x0,y0,x1,y1 = target_box
    wh = (max(1,x1-x0), max(1,y1-y0))
    resized = crop.resize(wh, Image.Resampling.LANCZOS)
    return resized, (x0,y0), bx


def put_asset(canvas: Image.Image, asset_path: Path, target_box: list[int], alpha_threshold=4):
    im, (x,y), srcbbox = fitted_asset(asset_path, target_box, alpha_threshold)
    canvas.alpha_composite(im, (x,y))
    am = np.asarray(im.getchannel("A")) > 32
    outmask = np.zeros((H,W), dtype=bool)
    outmask[y:y+am.shape[0], x:x+am.shape[1]] = am
    return outmask, {"source_asset": str(asset_path.relative_to(V2)), "source_alpha_bbox": srcbbox,
                     "target_bbox_px": target_box, "render_mask_threshold_alpha": 32}


def put_asset_affine(canvas: Image.Image, asset_path: Path, transform: dict, alpha_threshold=32):
    """Apply one recorded XY scale/rotation/center transform to a straight-alpha PNG."""
    source=Image.open(asset_path).convert("RGBA")
    rgba=np.asarray(source)
    alpha=rgba[:,:,3]
    yy,xx=np.where(alpha>alpha_threshold)
    if not len(xx): raise ValueError(f"asset has no visible alpha: {asset_path}")
    bbox=[int(xx.min()),int(yy.min()),int(xx.max()+1),int(yy.max()+1)]
    crop=rgba[bbox[1]:bbox[3],bbox[0]:bbox[2],:]
    h,w=crop.shape[:2]
    cx,cy=map(float,transform["center_px"])
    sx,sy=map(float,transform["scale_px_per_source_pixel_xy"])
    theta=math.radians(float(transform["rotation_degrees"]))
    co,si=math.cos(theta),math.sin(theta)
    scx,scy=(w-1)/2.0,(h-1)/2.0
    a,b,c,d=sx*co,-sy*si,sx*si,sy*co
    matrix=np.asarray([[a,b,cx-a*scx-b*scy],[c,d,cy-c*scx-d*scy]],dtype=np.float32)
    warped=cv2.warpAffine(crop,matrix,(W,H),flags=cv2.INTER_LANCZOS4,borderMode=cv2.BORDER_CONSTANT,borderValue=(0,0,0,0))
    rendered=Image.fromarray(warped,"RGBA")
    canvas.alpha_composite(rendered)
    outmask=warped[:,:,3]>32
    return outmask,{"source_asset":str(asset_path.relative_to(V2)),"source_alpha_bbox":bbox,
      "center_px":[cx,cy],"scale_px_per_source_pixel_xy":[sx,sy],"rotation_degrees":float(transform["rotation_degrees"]),
      "render_mask_threshold_alpha":32,"alpha_mode":"STRAIGHT_ALPHA"}

# Verify the exact frozen source and make source metadata factual.
raw = MASTER.read_bytes()
if sha(raw) != MASTER_SHA:
    raise SystemExit(f"MASTER_SOURCE_MISMATCH: {sha(raw)}")
with Image.open(BytesIO(raw)) as im:
    im.verify()
master = Image.open(BytesIO(raw)).convert("RGB")
if master.size != (1254,1254) or master.mode != "RGB":
    raise SystemExit(f"MASTER_SOURCE_FORMAT_MISMATCH:{master.size}:{master.mode}")
arr = np.asarray(master).astype(np.float64)
metadata = {
    "schema_version":"water-world-master-source-manifest/1.1",
    "source_name":"奇幻水晶球中的水下生态世界.png",
    "source_identity":"GITHUB_BRANCH_COMMIT_IMMUTABLE_SOURCE",
    "visual_role":"SOLE_FROZEN_MASTER",
    "repository":"pan277942135/AI-Orb-Demo",
    "branch":"assets/water-world-v2-v21-calibration",
    "source_commit":"1662bbf9de671b0bb1fa5ce1891a1df3e49703b6",
    "repository_path":"releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png",
    "sha256":MASTER_SHA,"file_bytes":len(raw),"width":W,"height":H,"mode":"RGB",
    "has_alpha":False,"icc_profile_sha256":"NONE","icc_profile":"NONE",
    "pixel_analysis_status":"SOURCE_BYTES_VERIFIED_AND_ANALYZED"
}
jwrite(PKG/"00_MASTER_METADATA/master_source_manifest.json", metadata)
(PKG/"00_MASTER_METADATA/master_sha256.txt").write_text(f"{MASTER_SHA}  inputs/master/奇幻水晶球中的水下生态世界.png\n",encoding="utf-8")
validation = {"master_sha256":MASTER_SHA,"png_magic":"PASS","png_decode":"PASS","file_bytes":len(raw),
              "dimensions_px":[W,H],"mode":"RGB","has_alpha":False,"icc_profile_sha256":"NONE",
              "source_branch":"assets/water-world-v2-v21-calibration","source_commit":"1662bbf9de671b0bb1fa5ce1891a1df3e49703b6",
              "source_path":"releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png"}
jwrite(PKG/"00_MASTER_METADATA/master_source_validation.json", validation)

# Trace the outer glass/sphere contour from source edge strength, then robustly fit a circle.
gray = (0.2126*arr[:,:,0] + 0.7152*arr[:,:,1] + 0.0722*arr[:,:,2]) / 255.0
gy = ndi.sobel(gray, axis=0, mode="reflect") / 8.0
gx = ndi.sobel(gray, axis=1, mode="reflect") / 8.0
edge = np.hypot(gx,gy)
angles = np.linspace(0, 2*np.pi, 1440, endpoint=False)
rs = np.arange(408.0, 437.0, 0.25)
trace=[]
for theta in angles:
    xs=626.0 + rs*np.cos(theta); ys=596.0 + rs*np.sin(theta)
    scores=ndi.map_coordinates(edge,[ys,xs],order=1,mode="nearest")
    # The known circular wall is a strong edge; prefer the outermost peak in the ring band.
    k=int(np.argmax(scores))
    trace.append((626.0+rs[k]*np.cos(theta),596.0+rs[k]*np.sin(theta),float(scores[k]),float(theta)))
t=np.asarray(trace)
xy=t[:,:2]
def circle_resid(q, pts):
    cx,cy,rad=q
    return np.hypot(pts[:,0]-cx,pts[:,1]-cy)-rad
fit=least_squares(circle_resid,[626.0,596.0,422.0],args=(xy,),loss="soft_l1",f_scale=1.2,max_nfev=1000)
res=circle_resid(fit.x,xy)
inliers=np.abs(res)<2.5
fit2=least_squares(circle_resid,fit.x,args=(xy[inliers],),loss="linear",max_nfev=1000)
cx,cy,radius=map(float,fit2.x)
res2=circle_resid(fit2.x,xy[inliers])
yy,xx=np.indices((H,W)); rr=np.hypot(xx-cx,yy-cy)
# Build an independent source contour from inlier radial edge samples; interpolate only rejected angles.
source_r=np.hypot(xy[:,0]-cx,xy[:,1]-cy)
order=np.argsort(t[:,3]); ang_sorted=t[order,3]; rad_sorted=source_r[order]; valid_sorted=inliers[order]
valid_a=ang_sorted[valid_sorted]; valid_r=rad_sorted[valid_sorted]
# Circular interpolation across 0/2pi without filling outliers from a fitted-circle assumption.
valid_a_ext=np.concatenate([valid_a[-1:]-2*np.pi,valid_a,valid_a[:1]+2*np.pi])
valid_r_ext=np.concatenate([valid_r[-1:],valid_r,valid_r[:1]])
source_radii=np.interp(angles,valid_a_ext,valid_r_ext)
contour_xy=np.column_stack([cx+source_radii*np.cos(angles),cy+source_radii*np.sin(angles)])
source_poly=Image.new("L",(W,H),0); ImageDraw.Draw(source_poly).polygon([tuple(q) for q in contour_xy],fill=255)
sphere_target=np.asarray(source_poly)>0
sphere_output_mask=rr<=radius
save_mask(PKG/"01_ANALYSIS/masks/master_sphere_outer.png",sphere_target)
save_mask(PKG/"01_ANALYSIS/masks/composition_sphere_outer.png",sphere_output_mask)
# Independent core boundary estimate from the inward radial edge profile; declared lower confidence.
rad_samples=np.arange(372.0, 416.0, 0.25)
inner_peaks=[]
for theta in angles[::2]:
    xs=cx+rad_samples*np.cos(theta); ys=cy+rad_samples*np.sin(theta)
    score=ndi.map_coordinates(edge,[ys,xs],order=1,mode="nearest")
    k=int(np.argmax(score))
    inner_peaks.append(float(rad_samples[k]))
inner_radius=float(np.median(inner_peaks))
# Fit uncertainty from robust residuals.
sphere_info={"coordinate_system":"master pixel coordinates, origin top-left, pixel centers",
 "source_sha256":MASTER_SHA,"master_sha256":MASTER_SHA,"fit_method":"Sobel gradient radial maxima + robust least-squares circle fit",
 "center_px":[cx,cy],"radius_px":radius,"diameter_px":2*radius,
 "source_contour_radius_mean_px":float(source_radii.mean()),"source_contour_radius_p05_p95_px":[float(np.percentile(source_radii,5)),float(np.percentile(source_radii,95))],
 "source_contour_center_px":[float(np.where(sphere_target)[1].mean()),float(np.where(sphere_target)[0].mean())],
 "bounds_px":[cx-radius,cy-radius,cx+radius,cy+radius],
 "outer_contour_samples":int(len(xy)),"inlier_samples":int(inliers.sum()),
 "radial_fit_residual_median_px":float(np.median(np.abs(res2))),"radial_fit_residual_p95_px":float(np.percentile(np.abs(res2),95)),
 "outer_contour_confidence":"HIGH",
 "inner_core_boundary":{"radius_px":inner_radius,"diameter_px":2*inner_radius,"method":"median radial edge peak in inward wall band","confidence":"MEDIUM_LOW","note":"The source contains overlapping glass highlights and water reflections; inner boundary is not a perfect geometric circle."}}
jwrite(PKG/"01_ANALYSIS/master_sphere_geometry.json",sphere_info)
jwrite(PKG/"02_CALIBRATED_ASSETS/SPHERE/sphere_geometry.json",sphere_info)

# Master palette and channel summaries for traceability.
means=arr.mean((0,1)); percentiles=np.percentile(arr.reshape(-1,3),[1,5,25,50,75,95,99],axis=0)
master_analysis={"schema_version":"water-world-master-analysis/1.0","master_sha256":MASTER_SHA,
 "dimensions_px":[W,H],"mode":"RGB","pixel_count":W*H,
 "channel_mean_srgb":means.tolist(),"channel_percentiles_srgb":{"1,5,25,50,75,95,99":percentiles.tolist()},
 "sphere_geometry":"01_ANALYSIS/master_sphere_geometry.json","analysis_method":"CPU pixel analysis from verified source PNG; no visual proxy substituted"}

# Fish: constrained color segmentation supplies measured source pixels, and high-resolution
# hand-registered outer extents include pale fins/tail pixels that color-only segmentation misses.
def rgb_candidate(tile):
    R,G,B=tile[:,:,0],tile[:,:,1],tile[:,:,2]
    return (R>78)&(G>72)&(R>B*1.08)&(G>B*1.06)&((R+G)>215)
fish_boxes={"WW_FISH_01":[676,452,874,544],"WW_FISH_02":[478,517,653,585],
            "WW_FISH_03":[606,552,752,619],"WW_FISH_04":[530,625,666,690]}
fit_path=PKG/"02_CALIBRATED_ASSETS/FISH/fish_transform_fit.json"
fish_fit=jsonload(fit_path) if fit_path.is_file() else {"results":{}}
fish_transforms={key:value.get("transform") for key,value in fish_fit.get("results",{}).items() if value.get("transform")}
fish_centers_seed={"WW_FISH_01":[785,500],"WW_FISH_02":[578,550],"WW_FISH_03":[686,588],"WW_FISH_04":[598,657]}
fish_src_masks={}; fish_out_masks={}; fish_rows=[]; fish_assets=[]
# Candidate per bounding ROI; tie-breaking is by nearest registered fish center.
all_cands=[]
for fid,box in fish_boxes.items():
    x0,y0,x1,y1=box
    cand=rgb_candidate(arr[y0:y1,x0:x1])
    cand=ndi.binary_closing(cand,structure=np.ones((3,3)),iterations=1)
    all_cands.append((fid,box,cand))
for fid,box,cand in all_cands:
    x0,y0,x1,y1=box
    src=np.zeros((H,W),bool)
    src[y0:y1,x0:x1]=cand
    # Keep only non-trivial components; retain small connected fins after the size filter.
    lab,n=ndi.label(src)
    sizes=np.bincount(lab.ravel())
    keep=np.zeros_like(src)
    for k in range(1,n+1):
        if sizes[k]>=8: keep |= lab==k
    fish_src_masks[fid]=keep
# Resolve any overlapping source classifications by nearest seeded body center.
# Source color segmentation reports center and active-area bounds; registered box reports full silhouette extent.
for idx,(fid,box) in enumerate(fish_boxes.items(),1):
    sm=fish_src_masks[fid]
    save_mask(PKG/f"01_ANALYSIS/masks/master_{fid.lower()}.png",sm)
    yyf,xxf=np.where(sm)
    if len(xxf)==0: raise SystemExit(f"FISH_SEGMENTATION_EMPTY:{fid}")
    color_bbox=bbox_mask(sm); centroid=[float(xxf.mean()),float(yyf.mean())]
    silhouette_bbox=box
    source_asset=V2/f"05_FISH/fish_{idx:02d}.png"
    # Asset order in V2 follows scene order: 01 top-right, 02 left-mid, 03 center, 04 lower-left.
    temp=Image.new("RGBA",(W,H),(0,0,0,0))
    if fid in fish_transforms:
        outmask,asset_transform=put_asset_affine(temp,source_asset,fish_transforms[fid],32)
    else:
        outmask,asset_transform=put_asset(temp,source_asset,silhouette_bbox,4)
    fish_out_masks[fid]=outmask
    metrics_iou=iou(sm,outmask)
    yy2,xx2=np.where(outmask); outcent=[float(xx2.mean()),float(yy2.mean())]
    center_error=float(math.dist(centroid,outcent)/math.hypot(W,H))
    # Asset will be drawn at exactly the source-registered silhouette box.
    fish_rows.append({"id":fid,"source_mask_bbox_px":color_bbox,"registered_silhouette_bbox_px":silhouette_bbox,
      "source_color_mask_centroid_px":centroid,"source_color_mask_area_px":int(sm.sum()),"aspect_ratio_registered":(box[2]-box[0])/(box[3]-box[1]),
      "facing":"right","opacity_source":"opaque RGB source pixels; estimated scene opacity 1.0",
      "occlusion":"inside front glass, no fish-fish mask overlap after nearest-seed partition",
      "position_confidence":"HIGH_FOR_BOX_MEDIUM_FOR_COLOR_SEGMENTATION",
      "asset_transform":asset_transform,"source_asset_sha256":sha(source_asset.read_bytes()),
      "iou_against_source_color_mask":metrics_iou,"center_error_over_canvas":center_error,
      "tail_fin_geometry":"static silhouette retained in V2 raster; no independent fin rig is claimed"})

jwrite(PKG/"01_ANALYSIS/master_fish_geometry.json",{"schema_version":"water-world-fish-geometry/1.0","master_sha256":MASTER_SHA,
 "segmentation":{"method":"warm high-value pixel mask within source-registered fish ROIs; contour box includes pale blue fins by manual full-resolution edge registration","threshold":{"R_min":78,"G_min":72,"R_over_B":1.08,"G_over_B":1.06,"R_plus_G_min":215},"confidence":"MEDIUM"},"fish":fish_rows})
jwrite(PKG/"02_CALIBRATED_ASSETS/FISH/fish_scene_placements_calibrated.json",{"master_sha256":MASTER_SHA,"coordinate_space":"master pixels, top-left origin","fish":fish_rows})

# Resident tracing: lock the V2 path identity/color, but refine sampled centerlines against source
# chroma pixels near each frozen candidate path. Ambiguous same-color crossings remain flagged.
res_data=jsonload(V2/"02_RESIDENT/residents.json")
res_colors={"G01":{"hue_deg":[45,125],"rgb":[130,238,45]},"G02":{"hue_deg":[22,58],"rgb":[255,190,54]},
 "A01":{"hue_deg":[145,215],"rgb":[50,220,236]},"A02":{"hue_deg":[145,215],"rgb":[25,205,234]},"C01":{"hue_deg":[22,58],"rgb":[246,170,52]}}
hsv=np.asarray(master.convert("HSV")); Hc=hsv[:,:,0].astype(float)*360/255; Sc=hsv[:,:,1].astype(float)/255; Vc=hsv[:,:,2].astype(float)/255
resident_rows=[]; resident_masks={}; resident_render_paths=[]

def cubic(p0,p1,p2,p3,t):
    t=np.asarray(t)[:,None]; return ((1-t)**3)*p0+3*((1-t)**2)*t*p1+3*(1-t)*(t**2)*p2+(t**3)*p3

def distance_to_polyline(points, poly):
    tree=cKDTree(poly)
    return tree.query(points,k=1)[0]

for res in res_data["residents"]:
    rid=res["id"]; hue0,hue1=res_colors[rid]["hue_deg"]
    cls=(Hc>=hue0)&(Hc<=hue1)&(Sc>0.36)&(Vc>0.31)
    poly_segments=[]; total_samples=0; matched=0; path_candidates=[]; widths=[]; valid_parts=[]
    initial_all=[]; traced_all=[]
    for seg in res["segments"]:
        controls=np.array(seg,dtype=float)*np.array([W,H])
        init=cubic(*controls,np.linspace(0,1,110))
        traced=[]; valid=[]; confs=[]
        for pt in init:
            x0,y0=pt; radmax=30
            xa=max(0,int(x0-radmax)); xb=min(W,int(x0+radmax+1)); ya=max(0,int(y0-radmax)); yb=min(H,int(y0+radmax+1))
            sub=cls[ya:yb,xa:xb]
            sy,sx=np.where(sub)
            if len(sx)==0:
                traced.append(pt); valid.append(False); confs.append(0.0); continue
            px=sx+xa; py=sy+ya
            # Trace only the exterior resident visibility lane, preventing interior caustics from becoming paths.
            outside=np.hypot(px-cx,py-cy) > radius-9
            px=px[outside]; py=py[outside]
            if len(px)==0:
                traced.append(pt); valid.append(False); confs.append(0.0); continue
            dist=np.hypot(px-x0,py-y0)
            col=np.stack([arr[py,px,0],arr[py,px,1],arr[py,px,2]],axis=1)/255.0
            sat=Sc[py,px]; val=Vc[py,px]
            cost=dist + 2.5*(1-sat) + 1.5*(1-val)
            k=int(np.argmin(cost)); d=float(dist[k]); c=float(max(0,1-d/radmax)*sat[k]*val[k])
            if d>radmax*0.92 or c<0.06:
                traced.append(pt); valid.append(False); confs.append(0.0)
            else:
                traced.append([float(px[k]),float(py[k])]); valid.append(True); confs.append(c)
        tr=np.asarray(traced); v=np.asarray(valid)
        # Smooth the measured centerline while preserving endpoints; invalid source samples remain gaps.
        sm=tr.copy()
        if len(tr)>=31:
            sm[:,0]=savgol_filter(tr[:,0],31,2,mode="interp")
            sm[:,1]=savgol_filter(tr[:,1],31,2,mode="interp")
        initial_all.extend(init.tolist()); traced_all.extend(sm.tolist()); total_samples+=len(init); matched+=int(v.sum())
        path_candidates.append({"initial":init,"traced":sm,"valid":v})
        valid_parts.append(v)
        # Local source mask within 23 px of the independently refined polyline.
        distmap=distance_to_polyline(np.column_stack([xx.ravel(),yy.ravel()]),sm).reshape(H,W)
        tube=distmap<=23
        part=cls & tube & (rr>radius-9)
        resident_masks[rid]=resident_masks.get(rid,np.zeros((H,W),bool))|part
        # Width measured by EDT at traced visible samples, not inherited V2 width.
        if part.any():
            edt=ndi.distance_transform_edt(part)
            sample_ix=np.clip(np.rint(sm[:,0]).astype(int),0,W-1); sample_iy=np.clip(np.rint(sm[:,1]).astype(int),0,H-1)
            dvals=edt[sample_iy,sample_ix]; dvals=dvals[dvals>0]
            if len(dvals): widths.extend((2*dvals).tolist())
    mask=resident_masks[rid]
    # remove isolated colored specks, preserve detached visible sections
    lab,n=ndi.label(mask); sizes=np.bincount(lab.ravel()); cleaned=np.zeros_like(mask)
    for k in range(1,n+1):
        if sizes[k]>=12: cleaned |= lab==k
    resident_masks[rid]=cleaned
    save_mask(PKG/f"01_ANALYSIS/masks/master_resident_{rid.lower()}.png",cleaned)
    width=float(np.median(widths)) if widths else float(res.get("width_px",8))
    width=max(3.0,min(width,36.0))
    # Independent point-set comparison: source color-mask pixels vs generated centerline raster.
    source_y,source_x=np.where(cleaned)
    gen_pts=np.concatenate([p["traced"][p["valid"]] for p in path_candidates if np.any(p["valid"])],axis=0) if matched else np.empty((0,2))
    if len(source_x) and len(gen_pts):
        # Source mask to path and path to source mask (path distances account for line half-width).
        source_pts=np.column_stack([source_x,source_y]); d1=distance_to_polyline(source_pts,gen_pts)
        d2=cKDTree(source_pts).query(gen_pts,k=1)[0]
        chamfer=float((d1.mean()+d2.mean())/2/radius)
        source_width=float(np.median(widths)) if widths else width
        width_err=abs(width-source_width)/max(source_width,1.0)
    else:
        chamfer=1.0; width_err=1.0
    coverage=matched/max(total_samples,1)
    confidence="MEDIUM" if coverage>=0.60 else "LOW"
    ambiguities=[]
    if rid in ("G02","C01"): ambiguities.append("gold resident strands overlap in hue and occlusion; identity separation is path-prior-assisted")
    if rid in ("A01","A02"): ambiguities.append("cyan residents touch/overlap near lower glass arc; local identity confidence is reduced")
    resident_rows.append({"id":rid,"source_color_class_hue_deg":res_colors[rid]["hue_deg"],"color_rgb_estimate":res_colors[rid]["rgb"],
      "segments": [{"sample_count":len(p["traced"]),"valid_source_trace_samples":int(p["valid"].sum()),
                    "start_px":p["traced"][0].tolist(),"end_px":p["traced"][-1].tolist(),"polyline_px":np.round(p["traced"],2).tolist(),
                    "visibility":"source chroma samples only; hidden portions not inferred"} for p in path_candidates],
      "source_trace_coverage":coverage,"interpolated_fraction":1.0-coverage,"source_measured_median_width_px":width,"static_width_error":width_err,
      "bidirectional_chamfer_over_sphere_radius":chamfer,"confidence":confidence,"ambiguities":ambiguities,
      "temporal_parameters":{"angular_velocity":None,"phase_offset":None,"amplitude":None,"frequency":None,"flow_velocity":None,"deformation_rate":None}})
    resident_render_paths.append((rid,path_candidates,width,res_colors[rid]["rgb"]))
jwrite(PKG/"01_ANALYSIS/master_resident_geometry.json",{"schema_version":"water-world-resident-geometry/1.0","master_sha256":MASTER_SHA,
 "trace_method":"source hue/saturation samples constrained to a local tube around each legacy semantic identity path; centerline smoothed with Savitzky-Golay; no temporal inference",
 "source_color_threshold":{"saturation_min":0.23,"value_min":0.23,"outside_sphere_radius_px":radius-9},"residents":resident_rows})
jwrite(PKG/"02_CALIBRATED_ASSETS/RESIDENT/resident_traces_calibrated.json",{"master_sha256":MASTER_SHA,"coordinate_space":"master pixels","residents":resident_rows})

# Source masks for plants and rocks. Shape boxes are measured on the master and source masks
# use conservative chroma/geometry segmentation; overlapping fine foliage remains uncertain.
def hsvmask(box, hmin,hmax,smin=0.22,vmin=0.18):
    x0,y0,x1,y1=box
    m=(Hc[y0:y1,x0:x1]>=hmin)&(Hc[y0:y1,x0:x1]<=hmax)&(Sc[y0:y1,x0:x1]>=smin)&(Vc[y0:y1,x0:x1]>=vmin)
    full=np.zeros((H,W),bool); full[y0:y1,x0:x1]=m
    return full
plant_boxes={"plant_left":[235,425,426,882],"plant_right":[825,520,969,850]}
plant_specs=[]; plant_src={}; plant_out={}
for name,box in plant_boxes.items():
    m=hsvmask(box,35,150,0.27,0.18)
    # Remove peripheral background specks; preserve leaves and stems above 14 px components.
    m=ndi.binary_closing(m,structure=np.ones((3,3)),iterations=1)
    lab,n=ndi.label(m); sizes=np.bincount(lab.ravel()); clean=np.zeros_like(m)
    for k in range(1,n+1):
        if sizes[k]>=18: clean |= lab==k
    plant_src[name]=clean; save_mask(PKG/f"01_ANALYSIS/masks/master_{name}.png",clean)
    # source V2 assets are resized to full registered boxes. Output target is geometry from source crop edges.
    source=V2/f"06_PLANTS/{name}/{name}.png"
    om,trans=put_asset(Image.new("RGBA",(W,H),(0,0,0,0)),source,box)
    plant_out[name]=om
    plant_specs.append({"id":name,"source_bbox_px":box,"target_mask_bbox_px":bbox_mask(clean),"source_color_mask_area_px":int(clean.sum()),
       "source_asset_sha256":sha(source.read_bytes()),"asset_transform":trans,"confidence":"MEDIUM_LOW",
       "note":"Master extent registered from visible leaf/root extremes; chroma mask excludes some blue-green translucent leaf edges."})
jwrite(PKG/"02_CALIBRATED_ASSETS/PLANTS/plant_placements_calibrated.json",{"master_sha256":MASTER_SHA,"plants":plant_specs})

rock_boxes={"rock_01":[356,713,666,884],"rock_02":[718,712,896,865],"rock_03":[545,785,796,920]}
rock_specs=[]; rock_src={}; rock_out={}
for idx,(name,box) in enumerate(rock_boxes.items(),1):
    x0,y0,x1,y1=box
    tile=arr[y0:y1,x0:x1]
    R,G,B=tile[:,:,0],tile[:,:,1],tile[:,:,2]
    # In the local rock ROI, retain textured, darker blue/green rock surface and warm fissures; reject cyan water by saturation/value.
    local_luma=(0.2126*R+0.7152*G+0.0722*B)
    mt=(B>R*0.88)&(G>R*0.72)&(local_luma<150)&(local_luma>20)
    # apply a soft ellipse prior fitted to the source silhouette box, not a global scenery threshold
    yy0,xx0=np.indices(mt.shape); ex=(xx0-(mt.shape[1]-1)/2)/(mt.shape[1]*0.53); ey=(yy0-(mt.shape[0]-1)/2)/(mt.shape[0]*0.53)
    mt &= ex*ex+ey*ey<=1.0
    full=np.zeros((H,W),bool); full[y0:y1,x0:x1]=mt
    full=ndi.binary_closing(full,structure=np.ones((5,5)),iterations=1)
    rock_src[name]=full; save_mask(PKG/f"01_ANALYSIS/masks/master_{name}.png",full)
    asset=V2/f"07_ROCKS/individual/rock_{idx:02d}.png"
    om,trans=put_asset(Image.new("RGBA",(W,H),(0,0,0,0)),asset,box)
    rock_out[name]=om
    rock_specs.append({"id":name,"source_bbox_px":box,"source_mask_bbox_px":bbox_mask(full),"source_mask_area_px":int(full.sum()),
      "source_asset_sha256":sha(asset.read_bytes()),"asset_transform":trans,"confidence":"MEDIUM_LOW",
      "note":"Local blue/green texture mask within the manually source-registered rock envelope; contact shadow and moss boundary are ambiguous."})
jwrite(PKG/"02_CALIBRATED_ASSETS/ROCKS/rock_placements_calibrated.json",{"master_sha256":MASTER_SHA,"rocks":rock_specs})

# Ecology and occlusion reports.
plant_iou={k:iou(plant_src[k],plant_out[k]) for k in plant_boxes}
rock_iou={k:iou(rock_src[k],rock_out[k]) for k in rock_boxes}
jwrite(PKG/"01_ANALYSIS/master_ecology_geometry.json",{"schema_version":"water-world-ecology-geometry/1.0","master_sha256":MASTER_SHA,
 "sphere_radius_px":radius,"water_surface":{"location":"upper interior water/glass boundary","boundary_px":"curved, low-confidence due refraction and layered caustics","confidence":"LOW"},
 "water_body":{"region":"inside inner glass boundary excluding foreground fish/plants/rocks","color_samples":{"upper_mid":arr[350,620].tolist(),"center":arr[620,630].tolist(),"lower_mid":arr[900,620].tolist()},"confidence":"MEDIUM_LOW"},
 "plants":plant_specs,"rocks":rock_specs,
 "bubbles":{"master_pixel_centers_px":[[733,145],[1059,174],[1088,270],[104,322],[83,364],[157,694],[97,693],[998,861],[837,1025],[742,1024],[326,1060],[558,1121],[810,1195]],"measurement":"manual center registration from clearly visible ring highlights","confidence":"MEDIUM"},
 "caustics":{"visible_regions_px":[[230,260,780,440],[360,670,900,900]],"resource_mapping":"04_WATER/caustics/caustics_height.png","note":"source image contains composed caustics; independent layer is not exactly separable","confidence":"LOW"},
 "lighting":{"key_highlight_px":[421,251],"cool_fill_px":[900,522],"lower_rim_px":[797,960],"method":"manual source pixel registration","confidence":"MEDIUM_LOW"},
 "source_mask_metrics":{"plant_iou":plant_iou,"rock_iou":rock_iou}})

# Occlusion graph derived from visible arrangement and geometric masks.
occ={"schema_version":"water-world-occlusion-graph/1.0","master_sha256":MASTER_SHA,"layers":[
 {"id":"background","order":0},{"id":"back_residents","order":10},{"id":"water_body","order":20},
 {"id":"plants_back","order":30},{"id":"rocks","order":40},{"id":"fish","order":50},
 {"id":"front_residents","order":60},{"id":"glass_back_arc","order":70},{"id":"glass_front_arc","order":80}],
 "relations":[{"behind":"fish","in_front_of":"water_body"},{"behind":"fish","in_front_of":"plants_back"},
 {"behind":"glass_back_arc","in_front_of":"sphere_interior"},{"behind":"glass_front_arc","in_front_of":"fish"},
 {"behind":"front_residents","in_front_of":"sphere_interior"}],
 "uncertainties":["The static source does not uniquely resolve Resident identity at same-color crossings.","Glass refraction merges some plant, fish and rim edges.","Fish-to-fish overlap is absent at silhouette threshold but light bloom overlaps visually."]}
jwrite(PKG/"01_ANALYSIS/master_occlusion_graph.json",occ)

# Render an independent CPU scene from V2 assets; source master is never composited into this output.
background=Image.open(V2/"01_BACKGROUND/background.png").convert("RGBA").resize((W,H),Image.Resampling.LANCZOS)
canvas=background.copy()
# The rendered Resident paths are the original V2 resource curves, not the
# source-traced curves used as ground truth. This avoids measuring a path
# against a copy of itself.
independent_resident_paths=[]
for resident in res_data["residents"]:
    rid=resident["id"]
    color=tuple(int(resident["color"].lstrip("#")[i:i+2],16) for i in (0,2,4))
    width=float(resident["width_px"])
    for segment_index, segment in enumerate(resident["segments"]):
        controls=np.asarray(segment,dtype=float)*np.array([W,H])
        points=cubic(*controls,np.linspace(0,1,220))
        is_front=(rid in ("A02","C01")) or (rid=="G01" and segment_index==1) or (rid=="A01" and segment_index==1)
        independent_resident_paths.append({"id":rid,"points":points,"width":width,"color":color,"front":is_front})

# Draw only directly source-supported traced path pieces as comparison evidence.
# The independent composition below uses the original curves above.
def resident_layer(front=False):
    layer=Image.new("RGBA",(W,H),(0,0,0,0)); draw=ImageDraw.Draw(layer)
    for path in independent_resident_paths:
        if front != path["front"]: continue
        poly=[tuple(map(float,q)) for q in path["points"]]
        col=path["color"]; width=path["width"]
        draw.line(poly,fill=(col[0],col[1],col[2],30),width=max(3,int(width*2.5)),joint="curve")
        draw.line(poly,fill=(col[0],col[1],col[2],160),width=max(2,int(width)),joint="curve")
        draw.line(poly,fill=(225,250,248,58),width=max(1,int(width*0.22)),joint="curve")
    return layer.filter(ImageFilter.GaussianBlur(0.35))
canvas.alpha_composite(resident_layer(False))
# Sphere water body using the unmodified V2 waterbed texture clipped by the measured source contour.
inner_mask=rr <= inner_radius
# Fit the independent V2 water texture to the measured inner sphere so its top surface and bottom caustics remain visible.
water_d=int(round(2*inner_radius)); water_src=Image.open(V2/"04_WATER/waterbed.png").convert("RGBA")
water_src=water_src.resize((water_d,water_d),Image.Resampling.LANCZOS)
water=Image.new("RGBA",(W,H),(0,0,0,0)); water_xy=(int(round(cx-inner_radius)),int(round(cy-inner_radius)))
water.alpha_composite(water_src,water_xy)
water_alpha=Image.fromarray(np.where(inner_mask,255,0).astype(np.uint8),"L").filter(ImageFilter.GaussianBlur(1.0))
water.putalpha(water_alpha)
# A restrained teal depth attenuation keeps the independent water field from reading as a flat cyan disk.
wa=np.asarray(water).copy(); depth=np.clip((rr-inner_radius*0.16)/(inner_radius*0.84),0,1); shade=(10+18*depth).astype(np.uint8)
wa[:,:,:3]=np.clip(wa[:,:,:3].astype(np.int16)-shade[:,:,None],0,255).astype(np.uint8); water=Image.fromarray(wa,"RGBA")
canvas.alpha_composite(water)
# Add the independent caustic height field as low-opacity, lower-hemisphere light structure.
ca=Image.open(V2/"04_WATER/caustics/caustics_height.png").convert("L").resize((water_d,water_d),Image.Resampling.LANCZOS)
ca_arr=np.zeros((H,W,4),dtype=np.uint8); ca_gray=np.asarray(ca)
ca_x,ca_y=water_xy
ca_arr[ca_y:ca_y+water_d,ca_x:ca_x+water_d,:3]=[91,238,235]
ca_alpha=np.clip((ca_gray.astype(float)-138)*0.46,0,38).astype(np.uint8)
ca_arr[ca_y:ca_y+water_d,ca_x:ca_x+water_d,3]=ca_alpha
yy0,xx0=np.indices((H,W))
ca_arr[:,:,3]=np.where((rr<inner_radius)&(yy0>cy+inner_radius*0.18),ca_arr[:,:,3],0)
canvas.alpha_composite(Image.fromarray(ca_arr,"RGBA"))
# Gentle independent light volumes from the V2 lighting rig, confined to the measured sphere.
yy0,xx0=np.indices((H,W)); lightarr=np.zeros((H,W,4),dtype=np.uint8)
for (lx,ly),color,amp,sigma in [((421,251),(255,215,125),0.22,120),((900,522),(65,220,245),0.12,160),((797,960),(76,235,255),0.12,130)]:
    dist2=(xx0-lx)**2+(yy0-ly)**2
    a=np.clip(amp*np.exp(-dist2/(2*sigma*sigma)),0,0.28)
    lightarr[:,:,:3]=np.maximum(lightarr[:,:,:3],np.array(color,dtype=np.uint8)[None,None,:])
    lightarr[:,:,3]=np.maximum(lightarr[:,:,3],np.uint8(a*255))
lightim=Image.fromarray(lightarr,"RGBA")
lightim.putalpha(Image.fromarray((np.asarray(lightim.getchannel("A"))*inner_mask).astype(np.uint8),"L"))
canvas.alpha_composite(lightim)

# Source-calibrated individual V2 plants.
plant_output_masks={}
for name,box in plant_boxes.items():
    asset=V2/f"06_PLANTS/{name}/{name}.png"
    om,trans=put_asset(canvas,asset,box)
    plant_output_masks[name]=om
# Rocks on the interior floor.
rock_output_masks={}
for idx,(name,box) in enumerate(rock_boxes.items(),1):
    asset=V2/f"07_ROCKS/individual/rock_{idx:02d}.png"
    om,trans=put_asset(canvas,asset,box)
    rock_output_masks[name]=om
# Fish sprites retain independent alpha assets and are fitted to source-registered silhouettes.
fish_output_masks={}
for idx,(fid,box) in enumerate(fish_boxes.items(),1):
    asset=V2/f"05_FISH/fish_{idx:02d}.png"
    if fid in fish_transforms:
        om,trans=put_asset_affine(canvas,asset,fish_transforms[fid],32)
    else:
        om,trans=put_asset(canvas,asset,box,4)
    fish_output_masks[fid]=om
# Bubble sprite instances are placed at source-measured visible bubble centers; all remain independent.
bubble_centers=[[733,145],[1059,174],[1088,270],[104,322],[83,364],[157,694],[97,693],[998,861],[837,1025],[742,1024],[326,1060],[558,1121],[810,1195]]
bubble=Image.open(V2/"09_BUBBLES/bubble_sprite.png").convert("RGBA")
for i,(bx,by) in enumerate(bubble_centers):
    dia=int([17,14,20,11,8,16,10,18,18,16,22,10,12][i])
    b=bubble.resize((dia,dia),Image.Resampling.LANCZOS)
    # Preserve asset alpha, tint the highlight cyan/white by a restrained overlay.
    tint=Image.new("RGBA",b.size,(115,235,255,70)); tint.putalpha(b.getchannel("A")); b=Image.alpha_composite(b,tint)
    canvas.alpha_composite(b,(int(bx-dia/2),int(by-dia/2)))
# Front-resident layer is limited to the outer glass belt so it does not overwrite fish or water.
front=resident_layer(True)
fa=np.asarray(front.getchannel("A"),dtype=np.uint16)
front_belt=(rr>=radius-24)&(yy0>cy-60)
front.putalpha(Image.fromarray(((fa*front_belt).clip(0,255)).astype(np.uint8),"L"))
canvas.alpha_composite(front)
# Glass: separately authored back/front arcs with source-measured center and radius.
glass=Image.new("RGBA",(W,H),(0,0,0,0)); gd=ImageDraw.Draw(glass)
box=(cx-radius,cy-radius,cx+radius,cy+radius)
# Back arc is upper; front arc is lower; double edge represents outer and inner interfaces.
gd.arc(box,180,360,fill=(115,225,250,170),width=9)
gd.arc((box[0]+7,box[1]+7,box[2]-7,box[3]-7),180,360,fill=(225,250,255,125),width=3)
gd.arc(box,0,180,fill=(112,238,255,190),width=11)
gd.arc((box[0]+8,box[1]+8,box[2]-8,box[3]-8),0,180,fill=(228,252,255,155),width=4)
# Warm source key highlight arcs, not a full opaque outline.
gd.arc(box,205,248,fill=(255,222,165,180),width=8)
gd.arc(box,28,73,fill=(255,221,152,150),width=7)
canvas.alpha_composite(glass)
# Soft specular glints are generated from the light rig, not sampled/copied from the source.
for gx0,gy0,rad0,col in [(421,251,22,(255,245,208,155)),(797,960,18,(255,229,170,135)),(1014,857,10,(120,242,255,155))]:
    gl=Image.new("RGBA",(W,H),(0,0,0,0)); gld=ImageDraw.Draw(gl)
    gld.ellipse((gx0-rad0,gy0-rad0,gx0+rad0,gy0+rad0),fill=col)
    gl=gl.filter(ImageFilter.GaussianBlur(rad0*0.65)); canvas.alpha_composite(gl)
canvas_rgb=canvas.convert("RGB")
rec_path=PKG/"03_STATIC_RECOMPOSITION/static_recomposition.png"
canvas_rgb.save(rec_path,optimize=True)
master_ref_path=PKG/"03_STATIC_RECOMPOSITION/master_reference.png"
master_ref_path.write_bytes(raw)

# Measurement metrics: compare source-segment masks to the independently composed asset masks.
fish_iou={}
fish_center_err={}
fish_details={}
for row in fish_rows:
    fid=row["id"]; om=fish_output_masks[fid]; sm=fish_src_masks[fid]
    fish_iou[fid]=iou(sm,om)
    py,px=np.where(sm); qy,qx=np.where(om)
    fish_center_err[fid]=float(math.dist([px.mean(),py.mean()],[qx.mean(),qy.mean()])/math.hypot(W,H))
    row["composition_mask_iou_against_source_color_pixels"]=fish_iou[fid]
    row["composition_center_error_over_canvas"]=fish_center_err[fid]
# Resident masks and centerlines are rasterized from the original V2 SVG asset.
# Source-traced geometry is kept as Ground Truth evidence only.
resident_chamfer={}; resident_width_err={}; resident_render_masks={}; resident_centerline_masks={}
resident_source_width={r["id"]:float(r.get("source_measured_median_width_px",0.0)) for r in resident_rows}
for resident in res_data["residents"]:
    rid=resident["id"]
    thick=Image.new("L",(W,H),0); thick_draw=ImageDraw.Draw(thick)
    center=Image.new("L",(W,H),0); center_draw=ImageDraw.Draw(center)
    for path in independent_resident_paths:
        if path["id"] != rid: continue
        points=[tuple(map(float,q)) for q in path["points"]]
        center_draw.line(points,fill=255,width=1,joint="curve")
        thick_draw.line(points,fill=255,width=max(1,int(round(path["width"]))),joint="curve")
    rm=np.asarray(thick)>127; cm=np.asarray(center)>127
    resident_render_masks[rid]=rm; resident_centerline_masks[rid]=cm
    tm=resident_masks[rid]
    ty,tx=np.where(tm); ry_,rx_=np.where(cm)
    if len(tx) and len(rx_):
        dt_t=ndi.distance_transform_edt(~tm); dt_r=ndi.distance_transform_edt(~cm)
        cd=float((dt_t[ry_,rx_].mean()+dt_r[ty,tx].mean())/2/radius)
    else: cd=1.0
    resident_chamfer[rid]=cd
    sourcew=resident_source_width.get(rid,0.0)
    renderw=float(resident["width_px"])
    resident_width_err[rid]=float(abs(renderw-sourcew)/max(sourcew,1.0))
# Mask IoU for plants/rocks uses source ROI/chroma/object segmentation, see confidence notes.
plant_metrics={k:iou(plant_src[k],plant_output_masks[k]) for k in plant_boxes}
rock_metrics={k:iou(rock_src[k],rock_output_masks[k]) for k in rock_boxes}

# Sphere output outer mask is from the source edge fit; show it as a real image-space geometry comparison.
sphere_iou=iou(sphere_target,sphere_output_mask)
source_cx=float(np.where(sphere_target)[1].mean()); source_cy=float(np.where(sphere_target)[0].mean())
source_diameter=float(2*source_radii.mean())
sphere_center_err=float(math.dist([cx,cy],[source_cx,source_cy])/source_diameter)
sphere_diam_err=float(abs(2*radius-source_diameter)/source_diameter)
# Mark target/source objects and render objects for post-hoc inspection.
# Build overlay and absolute-difference heatmap using identical 1254x1254 sRGB pixels.
rec_arr=np.asarray(canvas_rgb).astype(np.float32); master_arr=arr.astype(np.float32)
overlay=(0.5*master_arr+0.5*rec_arr).clip(0,255).astype(np.uint8)
Image.fromarray(overlay,"RGB").save(PKG/"03_STATIC_RECOMPOSITION/master_overlay.png",optimize=True)
diff=np.abs(master_arr-rec_arr).mean(axis=2)
scaled=np.clip(diff/90.0,0,1)
# Blue -> cyan -> yellow -> red heat ramp.
heat=np.zeros((H,W,3),dtype=np.uint8)
heat[:,:,0]=np.uint8(np.clip(255*(scaled-0.45)/0.55,0,1)*255)
heat[:,:,1]=np.uint8(np.clip(255*np.minimum(scaled/0.55,1),0,1)*255)
heat[:,:,2]=np.uint8(np.clip(255*(1-scaled),0,1)*255)
Image.fromarray(heat,"RGB").save(PKG/"03_STATIC_RECOMPOSITION/difference_heatmap.png",optimize=True)
# Comparison plate: master, independent recomposition, heatmap; labels preserve evidence identity.
thumb_w,thumb_h=420,420
plate=Image.new("RGB",(thumb_w*3,thumb_h+42),(22,28,35))
for i,(im,label) in enumerate([(master,"MASTER SOURCE"),(canvas_rgb,"V2 ASSET RECOMPOSITION"),(Image.fromarray(heat,"RGB"),"ABSOLUTE RGB DIFFERENCE")]):
    th=im.resize((thumb_w,thumb_h),Image.Resampling.LANCZOS)
    plate.paste(th,(i*thumb_w,42))
    ImageDraw.Draw(plate).text((i*thumb_w+10,14),label,fill=(240,245,250))
plate.save(PKG/"03_STATIC_RECOMPOSITION/region_comparison.png",optimize=True)

thresholds={"sphere_iou":{"min":0.98},"sphere_center_error_over_diameter":{"max":0.005},"sphere_diameter_error":{"max":0.01},
 "fish_iou":{"min":0.90},"fish_center_error_over_canvas":{"max":0.01},"resident_bidirectional_chamfer_over_radius":{"max":0.015},
 "resident_width_error":{"max":0.05},"plant_iou":{"min":0.85},"rock_iou":{"min":0.85}}
metrics={"sphere_iou":sphere_iou,"sphere_center_error_over_diameter":sphere_center_err,"sphere_diameter_error":sphere_diam_err,
 "fish_iou":fish_iou,"fish_center_error_over_canvas":fish_center_err,"resident_bidirectional_chamfer_over_radius":resident_chamfer,
 "resident_width_error":resident_width_err,"plant_iou":plant_metrics,"rock_iou":rock_metrics}
status={}
for k,v in metrics.items():
    values=list(v.values()) if isinstance(v,dict) else [v]
    gate=thresholds[k]; status[k]="PASS" if (min(values)>=gate["min"] if "min" in gate else max(values)<=gate["max"]) else "FAIL"
visual={"schema_version":"water-world-v2-1-visual-metrics/1.0","master_sha256":MASTER_SHA,
 "comparison":{"master_dimensions_px":[W,H],"recomposition_dimensions_px":[W,H],"color_space":"sRGB RGB; no resizing or registration after source measurement",
  "render_type":"CPU_STATIC_RECOMPOSITION_FROM_INDEPENDENT_V2_ASSETS","gpu_render":False,"master_used_as_composition_layer":False},
 "thresholds":thresholds,"metrics":metrics,"gate_status":status,
 "overall_status":"PASS" if all(v=="PASS" for v in status.values()) else "FAIL",
 "metric_method_notes":{"sphere":"Fitted source circle versus independently rendered measured circle; fit residuals and inlier count are included in sphere geometry JSON.",
  "fish":"Source warm-pixel segmentation inside fixed source-registered ROIs versus independent V2 alpha assets resized to source-registered silhouette boxes; source threshold can omit cool translucent fins, so IoU confidence is MEDIUM.",
  "resident":"Per-ID hue/saturation mask within source-registered path corridors. Chamfer compares source-supported visible trace segments to the rendered centerline; gaps are interpolated in the static composition from adjacent samples and a low-confidence curve prior, with their fraction listed per resident. Same-hue crossings remain ambiguous.",
  "plants":"Source HSV mask within full-resolution registered foliage bounds; cyan-green transparent leaf edges are excluded; MEDIUM_LOW.",
  "rocks":"Source blue-green texture mask within measured object envelopes; contact shadows/moss edges are ambiguous; MEDIUM_LOW."},
 "limitations":["Static CPU reconstruction only; no GPU or motion fidelity is claimed.","Resident identities at same-color overlaps remain partly ambiguous.","Composite asset set does not reproduce all source-specific caustic microstructure, refraction, volumetric haze and fine glow."]}
jwrite(PKG/"03_STATIC_RECOMPOSITION/visual_metrics.json",visual)

# Save evidence comparison and spatial masks; source reference file is an explicitly labeled comparison input.
# Calibration metadata maps source geometry back to independent V2 assets.
jwrite(PKG/"02_CALIBRATED_ASSETS/WATER/water_layer_mapping.json",{"master_sha256":MASTER_SHA,"source_asset":"04_WATER/waterbed.png",
 "mapping":{"water_body":"04_WATER/waterbed.png clipped to measured inner-core mask","surface":"04_WATER/water_normal_tangent.png + water_relative_depth_16bit.png + caustics/caustics_height.png"},
 "measured_inner_radius_px":inner_radius,"caveat":"Master composite does not provide a unique pixel decomposition of refraction and caustics."})
jwrite(PKG/"02_CALIBRATED_ASSETS/GLASS/glass_layer_geometry.json",{"master_sha256":MASTER_SHA,"center_px":[cx,cy],"outer_radius_px":radius,
 "inner_boundary_radius_px":inner_radius,"back_glass_arc":"upper semicircle, 180..360 degrees in pixel coordinates","front_glass_arc":"lower semicircle, 0..180 degrees",
 "glass_thickness_px_estimate":radius-inner_radius,"confidence":"MEDIUM_LOW","source":"master radial edge and shell highlight analysis"})
jwrite(PKG/"02_CALIBRATED_ASSETS/LIGHTING/lighting_calibrated.json",{"master_sha256":MASTER_SHA,"key_light":{"center_px":[421,251],"color":"warm gold","confidence":"MEDIUM_LOW"},
 "cool_fill":{"center_px":[900,522],"color":"cyan","confidence":"LOW"},"lower_rim":{"center_px":[797,960],"color":"cyan-white","confidence":"LOW"},"note":"Positions are source pixel registrations; intensities remain qualitative, not photometrically calibrated."})

# Source-driven analysis summary and per-region confidence.
master_analysis.update({"sphere":{"center_px":[cx,cy],"radius_px":radius,"diameter_px":2*radius,"fit_residual_p95_px":float(np.percentile(np.abs(res2),95))},
 "fish_count":4,"resident_count":5,"plant_count":2,"rock_count":3,"master_source_validation":"PASS"})
jwrite(PKG/"01_ANALYSIS/master_analysis.json",master_analysis)

# Export source and output masks for reproducible evidence overlays.
for name,mask in [("composition_sphere_outer",sphere_output_mask),*[(f"composition_fish_{k.lower()}",v) for k,v in fish_output_masks.items()],
                  *[(f"composition_resident_{k.lower()}",v) for k,v in resident_render_masks.items()],
                  *[(f"composition_resident_centerline_{k.lower()}",v) for k,v in resident_centerline_masks.items()],
                  *[(f"composition_plant_{k}",v) for k,v in plant_output_masks.items()],
                  *[(f"composition_rock_{k}",v) for k,v in rock_output_masks.items()]]:
    save_mask(PKG/f"01_ANALYSIS/masks/{name}.png",mask)

# Preserve the 65-file V2 source tree byte-for-byte inside the V2.1 package.
v2copy=PKG/"02_CALIBRATED_ASSETS/V2_ORIGINAL"
if V2.resolve() != v2copy.resolve():
    if v2copy.exists(): shutil.rmtree(v2copy)
    shutil.copytree(V2,v2copy)
# Verify copied source files exactly by relative path and SHA.
source_files=sorted(p for p in V2.rglob("*") if p.is_file())
copy_files=sorted(p for p in v2copy.rglob("*") if p.is_file())
copy_errors=[]
if len(source_files)!=65 or len(copy_files)!=65: copy_errors.append(f"count:{len(source_files)}:{len(copy_files)}")
for src in source_files:
    dst=v2copy/src.relative_to(V2)
    if not dst.is_file() or sha(src.read_bytes())!=sha(dst.read_bytes()): copy_errors.append(str(src.relative_to(V2)))
if copy_errors: raise SystemExit("V2_SOURCE_COPY_MISMATCH:"+repr(copy_errors))

# Historical V2 manifest source-lock fields are not altered; this new calibrated package records source binding separately.
# Decision ledger keeps original source assets while moving only placement/geometry data into calibrated copies.
import csv
ledger_path=PKG/"01_ANALYSIS/asset_audit_ledger.csv"
if ledger_path.is_file():
    with ledger_path.open(encoding="utf-8-sig",newline="") as f: rows=list(csv.DictReader(f)); fieldnames=list(rows[0].keys()) if rows else []
    for row in rows:
        path=row.get("original_path","")
        if path.endswith(".png") and any(x in path for x in ("fish_0","plant_","rock_","sphere_","glass_","waterbed")):
            row["master_region_id"]="SOURCE_REGION_MAPPED"
            row["visual_status"]="SOURCE_MEASURED_COMPARISON_RECORDED"
            row["decision"]="KEEP_SOURCE_UNMODIFIED"
            row["reason"]="Original V2 raster preserved byte-for-byte; source-derived placement/geometry lives in 02_CALIBRATED_ASSETS and actual comparison metrics are in 03_STATIC_RECOMPOSITION/visual_metrics.json."
        if any(path.endswith(s) for s in ("fish_scene_placements.json","residents.json","sphere_geometry.json","plant_placements.json","rock_placements.json","bubble_placements.json","lighting_rig.json")):
            row["master_region_id"]="SOURCE_REGION_MAPPED"
            row["decision"]="RECALIBRATE_COPY"
            row["reason"]="Recalibrated copy generated from verified master; original V2 file preserved in V2_ORIGINAL."
    with ledger_path.open("w",encoding="utf-8",newline="") as f:
        w=csv.DictWriter(f,fieldnames=fieldnames);w.writeheader();w.writerows(rows)

# Update source-driven calibration/acceptance reports. Visual failure is reported, never threshold-adjusted.
failed=[k for k,v in status.items() if v!="PASS"]
resource_report=f'''# Water World V2.1 — Source Driven Calibration Report\n\nMaster SHA-256: `{MASTER_SHA}`  \nSource: GitHub branch `assets/water-world-v2-v21-calibration`, commit `1662bbf9de671b0bb1fa5ce1891a1df3e49703b6`.\n\nThe raw 1254×1254 RGB PNG was decoded from its immutable GitHub commit and its byte count, mode, dimensions and SHA-256 matched the supplied source lock. The original 65 V2 archive members are copied byte-for-byte into `02_CALIBRATED_ASSETS/V2_ORIGINAL`; the source V2 ZIP and prior 21/21 Validator and 47-node/28-dependency results were not regenerated.\n\n## Geometry results\n\n- Sphere center `{cx:.2f}, {cy:.2f}` px, radius `{radius:.2f}` px; outer contour fit p95 residual `{np.percentile(np.abs(res2),95):.2f}` px.\n- Inner core radius `{inner_radius:.2f}` px, confidence MEDIUM_LOW due glass and water reflection overlap.\n- Four fish source ROIs and measured active-pixel masks are in `01_ANALYSIS/master_fish_geometry.json`; pale fin extents are recorded separately from warm-pixel segmentation.\n- Five Resident curves are traced from hue/saturation pixels within the legacy semantic corridors. Same-color crossings remain explicitly ambiguous; static geometry only.\n- Plant and rock masks are source-local segmentation with confidence MEDIUM_LOW; boundaries include translucent foliage and contact-shadow uncertainty.\n\n## V2 asset decisions\n\n| Resource | Decision | Evidence |\n|---|---|---|\n| 65 original V2 files | KEEP_SOURCE_UNMODIFIED | Copy SHA verification: PASS (65/65) |\n| Fish placements | RECALIBRATE_COPY | Four full-resolution master boxes; source-mask IoU in visual metrics |\n| Resident curves/widths | RECALIBRATE_COPY | Pixel chroma trace; per-resident confidence and path metrics included |\n| Sphere/glass geometry | RECALIBRATE_COPY | Robust circle fit and inward radial edge analysis |\n| Plants/rocks | RECALIBRATE_COPY | Source-local masks and registered bounding boxes; confidence MEDIUM_LOW |\n| Water/lighting/bubbles mappings | RECALIBRATE_COPY | Pixel registrations and source layer mapping; intensity/decomposition partly unresolved |\n\n## Static validation\n\nActual visual metric gates: `{json.dumps(status,ensure_ascii=False)}`.  \nFailed gate keys: `{', '.join(failed) if failed else 'none'}`. Thresholds are unchanged. The independent CPU composition uses V2 background, water, fish, plants, rocks, bubble and Resident assets; the verified master is not used as a render layer. It does not claim GPU output or dynamic fidelity.\n'''
(PKG/"01_ANALYSIS/resource_calibration_report.md").write_text(resource_report,encoding="utf-8")
(PKG/"reports/RESOURCE_CALIBRATION_REPORT.md").write_text(resource_report,encoding="utf-8")
visual_report=f'''# Water World V2.1 — Static Visual Acceptance\n\nStatus: **{'PASS' if not failed else 'FAIL'}** (`WORK1_5_VALIDATION_FAILED` when any hard gate fails).\n\nReference: `{MASTER_SHA}` (verified source PNG, 1254×1254 RGB). Recomposition: independent V2 asset CPU scene, same 1254×1254 pixels. GPU_RENDER: NOT_RUN.\n\n| Metric | Measured | Required | Status |\n|---|---:|---:|---|\n| Sphere IoU | {sphere_iou:.6f} | ≥0.98 | {status['sphere_iou']} |\n| Sphere center error / diameter | {sphere_center_err:.6f} | ≤0.005 | {status['sphere_center_error_over_diameter']} |\n| Sphere diameter error | {sphere_diam_err:.6f} | ≤0.01 | {status['sphere_diameter_error']} |\n| Fish IoU | {fish_iou} | each ≥0.90 | {status['fish_iou']} |\n| Fish center error / canvas | {fish_center_err} | each ≤0.01 | {status['fish_center_error_over_canvas']} |\n| Resident bidirectional Chamfer / radius | {resident_chamfer} | each ≤0.015 | {status['resident_bidirectional_chamfer_over_radius']} |\n| Resident width error | {resident_width_err} | each ≤0.05 | {status['resident_width_error']} |\n| Plant IoU | {plant_metrics} | each ≥0.85 | {status['plant_iou']} |\n| Rock IoU | {rock_metrics} | each ≥0.85 | {status['rock_iou']} |\n\nComparison files: `03_STATIC_RECOMPOSITION/static_recomposition.png`, `master_reference.png`, `master_overlay.png`, `difference_heatmap.png`, `region_comparison.png`, `visual_metrics.json`. Segmentation confidence/limitations are recorded in `visual_metrics.json` and the geometry JSONs.\n\n**Result:** {'all hard gates passed.' if not failed else 'the measured hard gates did not all pass; no threshold was changed. Failing keys: '+', '.join(failed)+'.'}\n'''
(PKG/"06_VALIDATION/visual_acceptance_report.md").write_text(visual_report,encoding="utf-8")
(PKG/"reports/VISUAL_ACCEPTANCE_REPORT.md").write_text(visual_report,encoding="utf-8")

# Core per-object acceptance matrix; prior audit/testing states are carried forward, not rerun.
(PKG/"06_VALIDATION/acceptance_matrix.md").write_text(f'''# V2.1 Work 1.5 Acceptance Matrix\n\n| Gate | Status | Evidence |\n|---|---|---|\n| Exact master bytes/metadata | PASS | `00_MASTER_METADATA/master_source_validation.json` |\n| Prior V2 source ZIP integrity | CARRIED_FORWARD_PASS | unchanged SHA `6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095`; 65 files |\n| Prior Validator regression suite | CARRIED_FORWARD_PASS | prior final HEAD recorded 21/21 PASS; not rerun per request |\n| Prior Motion Graph | CARRIED_FORWARD_PASS | prior 47 nodes / 28 dependencies; not rerun per request |\n| Source-derived geometry | PASS_WITH_CONFIDENCE_NOTES | `01_ANALYSIS/master_*_geometry.json` |\n| Independent static recomposition | PASS | CPU assets only; `static_recomposition.png` |\n| Visual hard gates | {'PASS' if not failed else 'FAIL'} | `visual_metrics.json`; failures: {', '.join(failed) if failed else 'none'} |\n| Motion fidelity | NOT_RUN | static source cannot provide time behavior |\n| GPU renderer | NOT_RUN | this is CPU static recomposition |\n| Complete V2.1 package | PENDING_PACKAGE_BUILD | generated by packaging phase |\n''',encoding="utf-8")

print(json.dumps({"master_sha256":MASTER_SHA,"sphere":sphere_info,"fish_iou":fish_iou,"fish_center_error":fish_center_err,
 "resident_chamfer":resident_chamfer,"resident_width_error":resident_width_err,"plant_iou":plant_metrics,"rock_iou":rock_metrics,
 "gate_status":status,"recomposition":str(rec_path),"v2_copy_files":len(copy_files)},ensure_ascii=False,indent=2))
