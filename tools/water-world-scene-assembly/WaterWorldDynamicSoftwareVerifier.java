import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * 1:1 CPU Dynamic Software Reference Verifier for Water World V2.1 Work 3.
 *
 * Mirrors WaterWorldMotionEvaluator.kt, WaterWorldCoreMotionAdapter.kt, WaterWorldMesh.kt,
 * WaterWorldShaders.kt, and WaterWorldStaticRenderer.kt to:
 * 1. Render deterministic 1254x1254 frames at T = 0, 1, 2, 4, 8, 12 seconds.
 * 2. Prove exact T=0 static regression parity against Work 2 GPU_FINAL.png.
 * 3. Quantify real frame-to-frame visual motion (RGB MAE) across T = 0..12s.
 * 4. Generate a labeled 2x3 visual contact sheet (WORK3_DYNAMIC_CONTACT_SHEET.png).
 * 5. Execute 10s / 30s / 60s continuous timeline stability checks (300/900/1800 frames).
 *
 * Strictly honest: marks hardware_gpu_verified=false and WORK3_HARDWARE_DYNAMIC_VERIFIED=NOT_RUN.
 */
public class WaterWorldDynamicSoftwareVerifier {
    static final int W = 1254;
    static final int H = 1254;
    static final double TWO_PI = 2.0 * Math.PI;
    static final String EXPECTED_V2_SHA = "6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095";
    static final double[] CANONICAL_TIMESTAMPS = {0.0, 1.0, 2.0, 4.0, 8.0, 12.0};

    static final Object[][] INNER_BUBBLE_SPECS = {
        {"IB01", 8.8f, 118f}, {"IB02", 9.7f, 130f}, {"IB03", 10.6f, 142f}, {"IB04", 11.4f, 152f},
        {"IB05", 12.1f, 165f}, {"IB06", 13.0f, 178f}, {"IB07", 13.9f, 196f}, {"IB08", 14.6f, 205f},
        {"IB09", 11.8f, 188f}, {"IB10", 12.7f, 198f}, {"IB11", 10.3f, 140f}, {"IB12", 11.5f, 158f},
        {"IB13", 9.1f, 92f}, {"IB14", 9.9f, 108f}
    };

    static class Vec3 {
        final float dx, dy, dz;
        Vec3(float dx, float dy, float dz) { this.dx = dx; this.dy = dy; this.dz = dz; }
    }

    static class FishState {
        String id, drawId;
        float pivotX, pivotY, offsetX, offsetY, offsetZ, pitchRad, facingScaleX, depthScale;
        float tailPhaseRad, tailAmpPx, finPhaseRad, finAmpPx, pathProgress;
    }

    static class ResidentState {
        String id, groupKey;
        float flowPhaseRad, flowOffsetU, widthPulseScale, brightnessScale, lateralWavePx, wavePhaseRad;
    }

    static class PlantState {
        String id;
        float swayAmpPx, swayPhaseRad, leafFlutterAmpPx, leafFlutterPhaseRad;
    }

    static class BubbleState {
        String id, drawId;
        float offsetX, offsetY, scaleMul, alphaMul, cycleProgress;
    }

    static class WaterOpticsState {
        float waterNormalOffsetU, waterNormalOffsetV;
        float meniscusWavePhaseRad, meniscusSecondaryPhaseRad;
        float waterFlowPhaseRad, causticsPhaseRad;
        float lightingShimmerScale, glassRefractionScale, glassFresnelScale, glassOpticalPhaseRad;
        float bloomResponseScale, bokehDriftOffsetU, bokehDriftOffsetV, bokehPhaseRad;
        float coreRotationAngleDeg, coreFluidPhaseRad, coreDensityScale, coreColorShift, coreGlassOptical;
    }

    static class DynamicSnapshot {
        double timeSec;
        boolean isZero;
        Map<String, FishState> fish = new LinkedHashMap<>();
        Map<String, ResidentState> residents = new LinkedHashMap<>();
        Map<String, PlantState> plants = new LinkedHashMap<>();
        Map<String, BubbleState> outerBubbles = new LinkedHashMap<>();
        Map<String, BubbleState> innerBubbles = new LinkedHashMap<>();
        WaterOpticsState wo = new WaterOpticsState();
    }

    static float clamp(float x, float lo, float hi) { return x < lo ? lo : (x > hi ? hi : x); }
    static float mix(float a, float b, float t) { return a + (b - a) * t; }
    static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    static float catmullScalar(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3);
    }

    static Vec3 evalCatmullRaw(List<Vec3> pts, float sNorm) {
        int n = pts.size();
        float scaled = (((sNorm % 1f) + 1f) % 1f) * n;
        int i1 = ((int) Math.floor(scaled)) % n;
        float t = scaled - (float) Math.floor(scaled);
        Vec3 p0 = pts.get((i1 - 1 + n) % n);
        Vec3 p1 = pts.get(i1);
        Vec3 p2 = pts.get((i1 + 1) % n);
        Vec3 p3 = pts.get((i1 + 2) % n);
        return new Vec3(
            catmullScalar(p0.dx, p1.dx, p2.dx, p3.dx, t),
            catmullScalar(p0.dy, p1.dy, p2.dy, p3.dy, t),
            catmullScalar(p0.dz, p1.dz, p2.dz, p3.dz, t)
        );
    }

    static Vec3 sampleClosedCatmullArcLen(List<Vec3> pts, float uNorm) {
        int n = pts.size();
        if (n == 0) return new Vec3(0, 0, 0);
        if (n == 1) return pts.get(0);
        float u = ((uNorm % 1f) + 1f) % 1f;
        if (u == 0f) return pts.get(0);
        int samples = 96;
        float[] cum = new float[samples + 1];
        Vec3 prev = evalCatmullRaw(pts, 0f);
        for (int i = 1; i <= samples; i++) {
            Vec3 cur = evalCatmullRaw(pts, i / (float) samples);
            float dx = cur.dx - prev.dx, dy = cur.dy - prev.dy;
            cum[i] = cum[i - 1] + (float) Math.sqrt(dx * dx + dy * dy);
            prev = cur;
        }
        float total = cum[samples];
        if (total < 1e-5f) return pts.get(0);
        float target = u * total;
        int lo = 0, hi = samples;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (cum[mid] <= target) lo = mid; else hi = mid;
        }
        float seg = Math.max(1e-6f, cum[hi] - cum[lo]);
        float frac = clamp((target - cum[lo]) / seg, 0f, 1f);
        return evalCatmullRaw(pts, (lo + frac) / (float) samples);
    }

    static BubbleState evalBubble(String id, String drawId, float period, float span, float driftAmp, float driftHz, float phaseRad, float scaleAmp, double t, boolean isZero) {
        BubbleState b = new BubbleState();
        b.id = id; b.drawId = drawId;
        if (isZero) {
            b.scaleMul = 1f; b.alphaMul = 1f;
            return b;
        }
        float u = (float) ((t / period) - Math.floor(t / period));
        float wrap = 0.62f;
        if (u < wrap) {
            b.offsetY = -u * span;
            b.alphaMul = u > 0.46f ? 1f - smoothstep(0.46f, wrap, u) : 1f;
        } else {
            b.offsetY = (1f - u) * span;
            b.alphaMul = u < 0.76f ? smoothstep(wrap, 0.76f, u) : 1f;
        }
        float angle = (float) (TWO_PI * driftHz * t);
        b.offsetX = driftAmp * ((float) Math.sin(angle + phaseRad) - (float) Math.sin(phaseRad)) * 0.5f;
        b.scaleMul = 1f + scaleAmp * (float) Math.sin(angle * 1.35f);
        b.alphaMul = clamp(b.alphaMul, 0f, 1f);
        b.cycleProgress = u;
        return b;
    }

    @SuppressWarnings("unchecked")
    static DynamicSnapshot evaluateSnapshot(Map<String, Object> profile, double timeSec) {
        DynamicSnapshot snap = new DynamicSnapshot();
        boolean isZero = timeSec <= 0.0;
        double t = isZero ? 0.0 : timeSec;
        snap.timeSec = t;
        snap.isZero = isZero;
        float ramp = isZero ? 0f : smoothstep(0f, 0.35f, (float) t);

        Map<String, Object> sphere = WaterWorldSoftwareRenderer.asMap(profile.get("sphere"));
        float[] sc = WaterWorldSoftwareRenderer.fltArr(sphere.get("center_px"));
        float sInnerR = WaterWorldSoftwareRenderer.flt(sphere.get("inner_radius_px"), 395.75f);

        // Core & WaterOptics
        Map<String, Object> co = WaterWorldSoftwareRenderer.asMap(profile.get("core"));
        Map<String, Object> woCfg = WaterWorldSoftwareRenderer.asMap(profile.get("water_and_optics"));
        WaterOpticsState wo = snap.wo;
        if (isZero) {
            wo.lightingShimmerScale = 1f; wo.glassRefractionScale = 1f; wo.glassFresnelScale = 1f;
            wo.bloomResponseScale = 1f; wo.coreDensityScale = 1f;
        } else {
            wo.coreRotationAngleDeg = (float) (t * WaterWorldSoftwareRenderer.flt(co.get("rotation_deg_per_sec"), 3.6f));
            wo.coreFluidPhaseRad = (float) (t * WaterWorldSoftwareRenderer.flt(co.get("fluid_rad_per_sec"), 0.24f));
            wo.coreDensityScale = 1f + WaterWorldSoftwareRenderer.flt(co.get("density_pulse_amp"), 0.045f) * (float) Math.sin(TWO_PI * WaterWorldSoftwareRenderer.flt(co.get("density_pulse_hz"), 0.14f) * t);
            wo.coreColorShift = WaterWorldSoftwareRenderer.flt(co.get("color_shift_amp"), 0.04f) * (float) Math.sin(TWO_PI * WaterWorldSoftwareRenderer.flt(co.get("color_shift_hz"), 0.11f) * t);
            wo.coreGlassOptical = WaterWorldSoftwareRenderer.flt(co.get("glass_optical_coupling"), 0.035f)
                    * (0.65f * (float) Math.sin(wo.coreFluidPhaseRad * 0.85f) + 0.35f * (1f - (float) Math.cos(wo.coreFluidPhaseRad * 0.55f)));

            float[] uvVel = WaterWorldSoftwareRenderer.fltArr(woCfg.get("water_normal_uv_velocity"));
            wo.waterNormalOffsetU = (float) (t * uvVel[0]);
            wo.waterNormalOffsetV = (float) (t * uvVel[1]);
            wo.meniscusWavePhaseRad = (float) (t * WaterWorldSoftwareRenderer.flt(woCfg.get("water_meniscus_wave_rad_per_sec"), 0.62f));
            wo.meniscusSecondaryPhaseRad = (float) (t * WaterWorldSoftwareRenderer.flt(woCfg.get("water_meniscus_secondary_rad_per_sec"), 0.94f));
            wo.waterFlowPhaseRad = (float) (t * WaterWorldSoftwareRenderer.flt(woCfg.get("water_flow_rad_per_sec"), 0.24f));
            wo.causticsPhaseRad = (float) (t * WaterWorldSoftwareRenderer.flt(woCfg.get("caustics_phase_rad_per_sec"), 0.145f));
            wo.lightingShimmerScale = 1f + WaterWorldSoftwareRenderer.flt(woCfg.get("god_ray_shimmer_amp"), 0.065f) * (float) Math.sin(TWO_PI * WaterWorldSoftwareRenderer.flt(woCfg.get("god_ray_shimmer_hz"), 0.18f) * t);
            wo.glassOpticalPhaseRad = wo.waterFlowPhaseRad * 0.72f + wo.coreFluidPhaseRad * 0.28f;
            wo.glassRefractionScale = 1f + WaterWorldSoftwareRenderer.flt(woCfg.get("glass_refraction_shimmer_amp"), 0.032f) * (float) Math.sin(wo.glassOpticalPhaseRad) + wo.coreGlassOptical * 0.5f;
            wo.glassFresnelScale = 1f + WaterWorldSoftwareRenderer.flt(woCfg.get("glass_fresnel_pulse_amp"), 0.035f) * (float) Math.sin(wo.glassOpticalPhaseRad * 1.18f) + wo.coreGlassOptical;
            wo.bloomResponseScale = 1f + WaterWorldSoftwareRenderer.flt(woCfg.get("bloom_response_coupling"), 0.04f) * (wo.lightingShimmerScale - 1f) * 3.0f;
            float bokehAng = (float) (TWO_PI * WaterWorldSoftwareRenderer.flt(woCfg.get("bokeh_drift_hz"), 0.09f) * t);
            float bAmp = WaterWorldSoftwareRenderer.flt(woCfg.get("bokeh_drift_uv_amp"), 0.0045f);
            wo.bokehDriftOffsetU = bAmp * (float) Math.sin(bokehAng);
            wo.bokehDriftOffsetV = bAmp * (1f - (float) Math.cos(bokehAng * 0.82f)) * 0.5f;
            wo.bokehPhaseRad = bokehAng;
        }

        // 4 Fish
        for (Object fo : WaterWorldSoftwareRenderer.asList(profile.get("fish"))) {
            Map<String, Object> fp = WaterWorldSoftwareRenderer.asMap(fo);
            FishState fs = new FishState();
            fs.id = (String) fp.get("id");
            fs.drawId = (String) fp.get("draw_id");
            float[] t0c = WaterWorldSoftwareRenderer.fltArr(fp.get("t0_center_px"));
            float[] sz = WaterWorldSoftwareRenderer.fltArr(fp.get("size_px"));
            fs.pivotX = t0c[0]; fs.pivotY = t0c[1];
            if (isZero) {
                fs.facingScaleX = 1f; fs.depthScale = 1f;
            } else {
                List<Vec3> wps = new ArrayList<>();
                for (Object w : WaterWorldSoftwareRenderer.asList(fp.get("waypoints_offset_px"))) {
                    float[] wa = WaterWorldSoftwareRenderer.fltArr(w);
                    wps.add(new Vec3(wa[0], wa[1], wa[2]));
                }
                float period = WaterWorldSoftwareRenderer.flt(fp.get("path_period_sec"), 24f);
                float u = (float) ((t / period) % 1.0);
                if (u < 0f) u += 1f;
                Vec3 s0 = sampleClosedCatmullArcLen(wps, u);
                float eps = 0.004f;
                Vec3 s1 = sampleClosedCatmullArcLen(wps, (u + eps) % 1f);
                float bob = WaterWorldSoftwareRenderer.flt(fp.get("vertical_bob_px"), 5f)
                        * (float) Math.sin(TWO_PI * WaterWorldSoftwareRenderer.flt(fp.get("vertical_bob_hz"), 0.3f) * t) * ramp;
                float absX = t0c[0] + s0.dx;
                float absY = clamp(t0c[1] + s0.dy + bob, 390f + sz[1] * 0.42f, 710f - sz[1] * 0.35f);
                float maxR = Math.max(120f, sInnerR - sz[0] * 0.46f - 18f);
                float rx = absX - sc[0], ry = absY - sc[1];
                float dist = (float) Math.hypot(rx, ry);
                if (dist > maxR && dist > 1e-4f) {
                    absX = sc[0] + rx * (maxR / dist);
                    absY = sc[1] + ry * (maxR / dist);
                }
                fs.offsetX = absX - t0c[0];
                fs.offsetY = absY - t0c[1];
                fs.offsetZ = s0.dz;
                float vx = (s1.dx - s0.dx) / eps, vy = (s1.dy - s0.dy) / eps;
                float spd = Math.max(1e-3f, (float) Math.hypot(vx, vy));
                float dirX = clamp(vx / spd, -1f, 1f);
                float[] dr = WaterWorldSoftwareRenderer.fltArr(fp.get("depth_scale_range"));
                float dT = clamp((s0.dz + 0.55f) / 0.85f, 0f, 1f);
                float dScale = mix(dr[0], dr[1], dT);
                float tgtFacing = dirX >= 0f ? (0.48f + 0.52f * dirX) : -(0.48f + 0.52f * (-dirX));
                fs.facingScaleX = mix(1f, tgtFacing, ramp);
                float maxPitch = (float) Math.toRadians(WaterWorldSoftwareRenderer.flt(fp.get("max_pitch_deg"), 11f));
                float rawPitch = (float) Math.atan2(vy, Math.max(25f, Math.abs(vx)));
                fs.pitchRad = clamp(rawPitch, -maxPitch, maxPitch) * (fs.facingScaleX >= 0f ? 1f : -1f) * ramp;
                fs.depthScale = mix(1f, dScale, ramp);
                float phaseOff = WaterWorldSoftwareRenderer.flt(fp.get("phase_offset_rad"), 0f);
                fs.tailPhaseRad = (float) (TWO_PI * WaterWorldSoftwareRenderer.flt(fp.get("tail_freq_hz"), 1.2f) * t + phaseOff);
                fs.finPhaseRad = (float) (TWO_PI * WaterWorldSoftwareRenderer.flt(fp.get("fin_freq_hz"), 0.9f) * t + phaseOff * 1.37f);
                fs.tailAmpPx = (sz[1] * 0.145f * (WaterWorldSoftwareRenderer.flt(fp.get("tail_amp_deg"), 6f) / 6f)) * ramp;
                fs.finAmpPx = (sz[1] * 0.068f * (WaterWorldSoftwareRenderer.flt(fp.get("fin_amp_deg"), 4.5f) / 4.5f)) * ramp;
                fs.pathProgress = u;
            }
            snap.fish.put(fs.drawId, fs);
        }

        // 5 Residents
        for (Object ro : WaterWorldSoftwareRenderer.asList(profile.get("residents"))) {
            Map<String, Object> rp = WaterWorldSoftwareRenderer.asMap(ro);
            ResidentState rs = new ResidentState();
            rs.id = (String) rp.get("id");
            rs.groupKey = (String) rp.get("group_key");
            if (isZero) {
                rs.widthPulseScale = 1f; rs.brightnessScale = 1f;
            } else {
                float period = WaterWorldSoftwareRenderer.flt(rp.get("cycle_period_sec"), 28f);
                float dir = WaterWorldSoftwareRenderer.flt(rp.get("flow_direction"), 1f);
                float phase = WaterWorldSoftwareRenderer.flt(rp.get("phase_offset_rad"), 0f);
                float cycle = (float) (t / period);
                rs.flowPhaseRad = cycle * (float) TWO_PI * dir;
                rs.flowOffsetU = cycle * dir;
                rs.widthPulseScale = 1f + WaterWorldSoftwareRenderer.flt(rp.get("width_pulse_amp"), 0.04f) * (float) Math.sin(TWO_PI * WaterWorldSoftwareRenderer.flt(rp.get("width_pulse_hz"), 0.2f) * t + phase) * ramp;
                rs.brightnessScale = 1f + WaterWorldSoftwareRenderer.flt(rp.get("brightness_pulse_amp"), 0.06f) * (float) Math.sin(TWO_PI * WaterWorldSoftwareRenderer.flt(rp.get("brightness_pulse_hz"), 0.2f) * t + phase * 0.7f) * ramp;
                rs.lateralWavePx = WaterWorldSoftwareRenderer.flt(rp.get("lateral_wave_px"), 4.8f) * ramp;
                rs.wavePhaseRad = (float) (TWO_PI * (1.0 / period) * t * 3.0 + phase);
            }
            snap.residents.put(rs.groupKey, rs);
        }

        // 2 Plants
        for (Object po : WaterWorldSoftwareRenderer.asList(profile.get("plants"))) {
            Map<String, Object> pp = WaterWorldSoftwareRenderer.asMap(po);
            PlantState ps = new PlantState();
            ps.id = (String) pp.get("id");
            if (!isZero) {
                float phase = WaterWorldSoftwareRenderer.flt(pp.get("phase_offset_rad"), 0f);
                float coup = WaterWorldSoftwareRenderer.flt(pp.get("water_flow_coupling"), 0.4f);
                ps.swayPhaseRad = (float) (TWO_PI * WaterWorldSoftwareRenderer.flt(pp.get("sway_freq_hz"), 0.12f) * t + phase + wo.waterFlowPhaseRad * coup);
                ps.leafFlutterPhaseRad = (float) (TWO_PI * WaterWorldSoftwareRenderer.flt(pp.get("leaf_flutter_hz"), 0.28f) * t + phase * 1.4f);
                ps.swayAmpPx = (450f * Math.min(WaterWorldSoftwareRenderer.flt(pp.get("max_sway_height_ratio"), 0.018f), 0.02f)) * ramp;
                ps.leafFlutterAmpPx = ps.swayAmpPx * 0.34f;
            }
            snap.plants.put(ps.id, ps);
        }

        // 9 Outer Bubbles + 14 Inner Bubbles
        for (Object bo : WaterWorldSoftwareRenderer.asList(profile.get("bubbles"))) {
            Map<String, Object> bp = WaterWorldSoftwareRenderer.asMap(bo);
            BubbleState bs = evalBubble(
                (String) bp.get("id"), (String) bp.get("draw_id"),
                WaterWorldSoftwareRenderer.flt(bp.get("rise_period_sec"), 11f),
                WaterWorldSoftwareRenderer.flt(bp.get("rise_span_px"), 160f),
                WaterWorldSoftwareRenderer.flt(bp.get("drift_amp_px"), 9f),
                WaterWorldSoftwareRenderer.flt(bp.get("drift_hz"), 0.28f),
                WaterWorldSoftwareRenderer.flt(bp.get("phase_rad"), 0f),
                WaterWorldSoftwareRenderer.flt(bp.get("scale_pulse_amp"), 0.06f),
                t, isZero
            );
            snap.outerBubbles.put(bs.drawId, bs);
        }
        for (int i = 0; i < INNER_BUBBLE_SPECS.length; i++) {
            String did = (String) INNER_BUBBLE_SPECS[i][0];
            float per = (Float) INNER_BUBBLE_SPECS[i][1];
            float span = (Float) INNER_BUBBLE_SPECS[i][2];
            snap.innerBubbles.put(did, evalBubble(
                "WW_INNER_BUBBLE_" + did, did, per, span,
                4.2f + (i % 4) * 1.1f, 0.28f + (i % 5) * 0.04f, i * 0.83f, 0.05f, t, isZero
            ));
        }

        return snap;
    }

    static String resolveResidentGroup(String drawId) {
        if (drawId.startsWith("G01")) return "G01";
        if (drawId.startsWith("G02")) return "G02";
        if (drawId.startsWith("A01")) return "A01";
        if (drawId.startsWith("A02")) return "A02";
        if (drawId.startsWith("C01")) return "C01";
        return null;
    }

    static class MeshBuf {
        float[] x, y, z, nx, ny, nz, u, v;
        int[] indices;
        float cx, cy;
    }

    static MeshBuf loadMesh(Map<String, Object> meshJson, boolean subdividePlant) {
        List<Object> verts = WaterWorldSoftwareRenderer.asList(meshJson.get("vertices"));
        List<Object> idxs = WaterWorldSoftwareRenderer.asList(meshJson.get("indices"));
        MeshBuf mb = new MeshBuf();
        if (subdividePlant && verts.size() == 4 && idxs.size() == 6) {
            int cols = 6, rows = 12;
            float[][] c = new float[4][8];
            for (int i = 0; i < 4; i++) {
                List<Object> r = WaterWorldSoftwareRenderer.asList(verts.get(i));
                for (int j = 0; j < 8; j++) c[i][j] = ((Number) r.get(j)).floatValue();
            }
            float[] c00 = c[0], c10 = c[0], c01 = c[0], c11 = c[0];
            for (float[] row : c) {
                if (row[6] + row[7] < c00[6] + c00[7]) c00 = row;
                if (row[6] - row[7] > c10[6] - c10[7]) c10 = row;
                if (row[7] - row[6] > c01[7] - c01[6]) c01 = row;
                if (row[6] + row[7] > c11[6] + c11[7]) c11 = row;
            }
            int nv = (cols + 1) * (rows + 1);
            mb.x = new float[nv]; mb.y = new float[nv]; mb.z = new float[nv];
            mb.nx = new float[nv]; mb.ny = new float[nv]; mb.nz = new float[nv];
            mb.u = new float[nv]; mb.v = new float[nv];
            int k = 0;
            float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (int r = 0; r <= rows; r++) {
                float tv = r / (float) rows;
                for (int col = 0; col <= cols; col++) {
                    float tu = col / (float) cols;
                    float[] f = new float[8];
                    for (int j = 0; j < 8; j++) {
                        float top = c00[j] + (c10[j] - c00[j]) * tu;
                        float bot = c01[j] + (c11[j] - c01[j]) * tu;
                        f[j] = top + (bot - top) * tv;
                    }
                    mb.x[k] = f[0]; mb.y[k] = f[1]; mb.z[k] = f[2];
                    mb.nx[k] = f[3]; mb.ny[k] = f[4]; mb.nz[k] = f[5];
                    mb.u[k] = f[6]; mb.v[k] = f[7];
                    if (f[0] < minX) minX = f[0]; if (f[0] > maxX) maxX = f[0];
                    if (f[1] < minY) minY = f[1]; if (f[1] > maxY) maxY = f[1];
                    k++;
                }
            }
            mb.cx = (minX + maxX) * 0.5f; mb.cy = (minY + maxY) * 0.5f;
            mb.indices = new int[cols * rows * 6];
            int p = 0, stride = cols + 1;
            for (int r = 0; r < rows; r++) {
                for (int col = 0; col < cols; col++) {
                    int i00 = r * stride + col, i10 = i00 + 1, i01 = i00 + stride, i11 = i01 + 1;
                    mb.indices[p++] = i00; mb.indices[p++] = i10; mb.indices[p++] = i11;
                    mb.indices[p++] = i00; mb.indices[p++] = i11; mb.indices[p++] = i01;
                }
            }
            return mb;
        }
        int nv = verts.size();
        mb.x = new float[nv]; mb.y = new float[nv]; mb.z = new float[nv];
        mb.nx = new float[nv]; mb.ny = new float[nv]; mb.nz = new float[nv];
        mb.u = new float[nv]; mb.v = new float[nv];
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < nv; i++) {
            List<Object> r = WaterWorldSoftwareRenderer.asList(verts.get(i));
            mb.x[i] = ((Number) r.get(0)).floatValue();
            mb.y[i] = ((Number) r.get(1)).floatValue();
            mb.z[i] = ((Number) r.get(2)).floatValue();
            mb.nx[i] = ((Number) r.get(3)).floatValue();
            mb.ny[i] = ((Number) r.get(4)).floatValue();
            mb.nz[i] = ((Number) r.get(5)).floatValue();
            mb.u[i] = ((Number) r.get(6)).floatValue();
            mb.v[i] = ((Number) r.get(7)).floatValue();
            if (mb.x[i] < minX) minX = mb.x[i]; if (mb.x[i] > maxX) maxX = mb.x[i];
            if (mb.y[i] < minY) minY = mb.y[i]; if (mb.y[i] > maxY) maxY = mb.y[i];
        }
        mb.cx = (minX + maxX) * 0.5f; mb.cy = (minY + maxY) * 0.5f;
        mb.indices = new int[idxs.size()];
        for (int i = 0; i < idxs.size(); i++) mb.indices[i] = ((Number) idxs.get(i)).intValue();
        return mb;
    }

    static void rasterizeDynamic(
        MeshBuf mb, float[] mat4, int deformMode, float d0, float d1, float d2, float d3,
        WaterWorldSoftwareRenderer.Target dst, boolean depthTest, boolean depthWrite,
        String blendMode, float alphaCutoff, WaterWorldSoftwareRenderer.FragShader shader
    ) {
        int nv = mb.x.length;
        float[] vx = new float[nv], vy = new float[nv], vz = new float[nv];
        for (int i = 0; i < nv; i++) {
            float px = mb.x[i], py = mb.y[i], pz = mb.z[i];
            float u = mb.u[i], v = mb.v[i];
            if (deformMode == 1) { // Fish tail & fin wave
                float tailW = (1f - u) * (1f - u);
                py += (float) Math.sin(d0 + (1f - u) * 5.2f) * d1 * tailW;
                px -= Math.abs((float) Math.sin(d0)) * d1 * 0.14f * tailW;
                float edgeV = Math.abs(v * 2f - 1f);
                float finW = smoothstep(0.45f, 1.0f, edgeV) * (1f - smoothstep(0.78f, 0.98f, u));
                py += (float) Math.sin(d2 + u * 6.0f) * d3 * finW * (v >= 0.5f ? 1f : -1f);
            } else if (deformMode == 2) { // Root-pinned plant sway (v=1.0 fixed!)
                float h = clamp(1f - v, 0f, 1f);
                float flex = h * h;
                float sway = (float) Math.sin(d0 + u * 1.4f) * d1 * flex
                        + (float) Math.sin(d2 + u * 4.5f + h * 3.8f) * d3 * flex;
                px += sway;
                py += Math.abs(sway) * 0.08f * flex;
            } else if (deformMode == 3) { // Resident lateral wave
                float endPin = smoothstep(0.0f, 0.14f, u) * smoothstep(0.0f, 0.14f, 1f - u);
                float wave = (float) Math.sin(d0 + u * 6.28318f * 1.5f) * d1 * endPin;
                px += wave * 0.65f;
                py += wave * 0.76f;
            }
            // Apply column-major 4x4 matrix
            float wx = mat4[0] * px + mat4[4] * py + mat4[8] * pz + mat4[12];
            float wy = mat4[1] * px + mat4[5] * py + mat4[9] * pz + mat4[13];
            float wz = mat4[2] * px + mat4[6] * py + mat4[10] * pz + mat4[14];
            vx[i] = wx;
            vy[i] = wy;
            vz[i] = (wz / 2048f) * 0.5f + 0.5f;
        }
        float[] rgba = new float[4];
        for (int t = 0; t < mb.indices.length; t += 3) {
            int i0 = mb.indices[t], i1 = mb.indices[t + 1], i2 = mb.indices[t + 2];
            float x0 = vx[i0], y0 = vy[i0], x1 = vx[i1], y1 = vy[i1], x2 = vx[i2], y2 = vy[i2];
            float area = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
            if (Math.abs(area) < 1e-5f) continue;
            float invA = 1f / area;
            int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
            int maxX = Math.min(dst.w - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
            int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
            int maxY = Math.min(dst.h - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
            for (int py = minY; py <= maxY; py++) {
                float cy = py + 0.5f;
                for (int px = minX; px <= maxX; px++) {
                    float cx = px + 0.5f;
                    float w0 = ((x1 - cx) * (y2 - cy) - (y1 - cy) * (x2 - cx)) * invA;
                    float w1 = ((x2 - cx) * (y0 - cy) - (y2 - cy) * (x0 - cx)) * invA;
                    float w2 = 1f - w0 - w1;
                    if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue;
                    float pz = vz[i0]*w0 + vz[i1]*w1 + vz[i2]*w2;
                    int idx = py * dst.w + px;
                    if (depthTest && pz > dst.depth[idx] + 1e-5f) continue;
                    float u = mb.u[i0]*w0 + mb.u[i1]*w1 + mb.u[i2]*w2;
                    float v = mb.v[i0]*w0 + mb.v[i1]*w1 + mb.v[i2]*w2;
                    rgba[0] = rgba[1] = rgba[2] = rgba[3] = 0f;
                    shader.shade(px, py, pz, 0f, 0f, 1f, u, v, rgba);
                    float a = rgba[3];
                    if (a <= 0.001f) continue;
                    if (alphaCutoff > 0f && a < alphaCutoff) continue;
                    if (depthWrite) dst.depth[idx] = pz;
                    if ("NONE".equals(blendMode)) {
                        dst.r[idx] = rgba[0]; dst.g[idx] = rgba[1]; dst.b[idx] = rgba[2]; dst.a[idx] = 1f;
                    } else if ("SCREEN".equals(blendMode)) {
                        float sr = clamp(rgba[0] * a, 0f, 1.5f), sg = clamp(rgba[1] * a, 0f, 1.5f), sb = clamp(rgba[2] * a, 0f, 1.5f);
                        dst.r[idx] = dst.r[idx] + sr * (1f - clamp(dst.r[idx], 0f, 1f) * 0.65f);
                        dst.g[idx] = dst.g[idx] + sg * (1f - clamp(dst.g[idx], 0f, 1f) * 0.65f);
                        dst.b[idx] = dst.b[idx] + sb * (1f - clamp(dst.b[idx], 0f, 1f) * 0.65f);
                        dst.a[idx] = 1f;
                    } else {
                        float ca = clamp(a, 0f, 1f), inv = 1f - ca;
                        dst.r[idx] = rgba[0] * ca + dst.r[idx] * inv;
                        dst.g[idx] = rgba[1] * ca + dst.g[idx] * inv;
                        dst.b[idx] = rgba[2] * ca + dst.b[idx] * inv;
                        dst.a[idx] = ca + dst.a[idx] * inv;
                    }
                }
            }
        }
    }

    static float[] fishMatrix(float[] baseModel, FishState fs) {
        float sx = fs.facingScaleX * fs.depthScale, sy = fs.depthScale;
        float c = (float) Math.cos(fs.pitchRad), s = (float) Math.sin(fs.pitchRad);
        float a = c * sx, b = -s * sy, d = s * sx, e = c * sy;
        float tx = fs.pivotX + fs.offsetX - (a * fs.pivotX + b * fs.pivotY);
        float ty = fs.pivotY + fs.offsetY - (d * fs.pivotX + e * fs.pivotY);
        return new float[] { a, d, 0, 0, b, e, 0, 0, 0, 0, 1, 0, tx, ty, baseModel[14] + fs.offsetZ * 6f, 1 };
    }

    static float[] bubbleMatrix(float[] baseModel, float cx, float cy, BubbleState bs) {
        float sc = bs.scaleMul;
        float tx = cx + bs.offsetX - sc * cx;
        float ty = cy + bs.offsetY - sc * cy;
        return new float[] { sc, 0, 0, 0, 0, sc, 0, 0, 0, 0, 1, 0, tx, ty, baseModel[14], 1 };
    }

    static double computeImageMae(BufferedImage a, BufferedImage b) {
        int w = a.getWidth(), h = a.getHeight();
        long sum = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y), pb = b.getRGB(x, y);
                sum += Math.abs(((pa >> 16) & 255) - ((pb >> 16) & 255));
                sum += Math.abs(((pa >> 8) & 255) - ((pb >> 8) & 255));
                sum += Math.abs((pa & 255) - (pb & 255));
            }
        }
        return sum / (double) (w * h * 3L);
    }

    public static void main(String[] args) throws Exception {
        long tStart = System.nanoTime();
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path v2Zip = root.resolve("releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip");
        Path sceneRoot = root.resolve("releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1");
        Path profilePath = root.resolve("releases/water-world-v2-v21/work3_full_dynamic_runtime/02_MOTION/work3_motion_profile.json");
        Path outDir = root.resolve("releases/water-world-v2-v21/work3_full_dynamic_runtime/04_DYNAMIC_EVIDENCE");
        Path work2RefPng = root.resolve("releases/water-world-v2-v21/work2_gpu_static_fidelity/08_VISUAL_ACCEPTANCE/GPU_FINAL.png");
        Files.createDirectories(outDir);

        String v2Sha = WaterWorldSoftwareRenderer.sha256(Files.readAllBytes(v2Zip));
        if (!v2Sha.equals(EXPECTED_V2_SHA)) throw new IllegalStateException("V2 ZIP SHA mismatch!");

        Map<String, Object> profile = WaterWorldSoftwareRenderer.asMap(WaterWorldSoftwareRenderer.Json.parse(Files.readString(profilePath)));
        Map<String, Object> bindingsJson = WaterWorldSoftwareRenderer.asMap(WaterWorldSoftwareRenderer.Json.parse(Files.readString(sceneRoot.resolve("03_SCENE_GRAPH/draw_bindings.json"))));
        Map<String, Object> lightingJson = WaterWorldSoftwareRenderer.asMap(WaterWorldSoftwareRenderer.Json.parse(Files.readString(sceneRoot.resolve("02_SUPPLEMENTED_ASSETS/lighting/lighting.json"))));
        Map<String, Object> opticalJson = WaterWorldSoftwareRenderer.asMap(WaterWorldSoftwareRenderer.Json.parse(Files.readString(sceneRoot.resolve("02_SUPPLEMENTED_ASSETS/materials/optical_config.json"))));

        float[] sphereCenter = WaterWorldSoftwareRenderer.fltArr(WaterWorldSoftwareRenderer.asMap(opticalJson.get("sphere")).get("center_px"));
        final float scx = sphereCenter[0], scy = sphereCenter[1];
        final float sOuterR = WaterWorldSoftwareRenderer.flt(WaterWorldSoftwareRenderer.asMap(opticalJson.get("sphere")).get("outer_radius_px"), 422.3f);
        final float sInnerR = WaterWorldSoftwareRenderer.flt(WaterWorldSoftwareRenderer.asMap(opticalJson.get("sphere")).get("inner_radius_px"), 395.75f);

        Map<String, Object> bgAtm = WaterWorldSoftwareRenderer.asMap(opticalJson.get("background_atmosphere"));
        Map<String, Object> glassCfg = WaterWorldSoftwareRenderer.asMap(opticalJson.get("glass_refraction"));
        Map<String, Object> waterCfg = WaterWorldSoftwareRenderer.asMap(opticalJson.get("water_normal_distortion"));
        Map<String, Object> causticsCfg = WaterWorldSoftwareRenderer.asMap(opticalJson.get("caustics"));
        Map<String, Object> bloomCfg = WaterWorldSoftwareRenderer.asMap(opticalJson.get("bloom"));
        Map<String, Object> bokehCfg = WaterWorldSoftwareRenderer.asMap(opticalJson.get("bokeh"));
        float[] keyColor = WaterWorldSoftwareRenderer.fltArr(WaterWorldSoftwareRenderer.asMap(lightingJson.get("key")).get("color"));

        List<Object> allDraws = WaterWorldSoftwareRenderer.asList(bindingsJson.get("draws"));
        String[] passOrder = {
            "01_BACKGROUND", "02_RESIDENT_BACK", "03_SPHERE_BACK", "04_WATER_INTERIOR",
            "05_FAR_ECOLOGY", "06_LIGHTING", "07_FISH", "08_NEAR_ECOLOGY",
            "09_SPHERE_FRONT", "10_RESIDENT_FRONT", "11_FINAL_COMPOSITE"
        };
        Map<String, List<Map<String, Object>>> drawsByPass = new LinkedHashMap<>();
        for (String p : passOrder) drawsByPass.put(p, new ArrayList<>());
        Map<String, MeshBuf> meshCache = new HashMap<>();
        for (Object o : allDraws) {
            Map<String, Object> d = WaterWorldSoftwareRenderer.asMap(o);
            drawsByPass.get((String) d.get("pass_id")).add(d);
            String mp = (String) d.get("mesh_path");
            boolean isPlant = "plant".equals(d.get("material"));
            String mKey = mp + "|" + isPlant;
            if (!meshCache.containsKey(mKey)) {
                Map<String, Object> mj = WaterWorldSoftwareRenderer.asMap(WaterWorldSoftwareRenderer.Json.parse(Files.readString(sceneRoot.resolve(mp))));
                meshCache.put(mKey, loadMesh(mj, isPlant));
            }
        }

        List<BufferedImage> renderedImgs = new ArrayList<>();
        List<String> frameNames = new ArrayList<>();
        List<String> frameShas = new ArrayList<>();
        List<Double> frameRenderMs = new ArrayList<>();

        try (ZipFile zf = new ZipFile(v2Zip.toFile())) {
            Map<String, Object> aux = WaterWorldSoftwareRenderer.asMap(opticalJson.get("auxiliary_textures"));
            final WaterWorldSoftwareRenderer.Tex waterNormalTex = WaterWorldSoftwareRenderer.loadV2Tex(zf, (String) WaterWorldSoftwareRenderer.asMap(aux.get("water_normal")).get("path"), (String) WaterWorldSoftwareRenderer.asMap(aux.get("water_normal")).get("sha256"));
            final WaterWorldSoftwareRenderer.Tex waterDepthTex = WaterWorldSoftwareRenderer.loadV2Tex(zf, (String) WaterWorldSoftwareRenderer.asMap(aux.get("water_depth")).get("path"), (String) WaterWorldSoftwareRenderer.asMap(aux.get("water_depth")).get("sha256"));
            final WaterWorldSoftwareRenderer.Tex causticsTex = WaterWorldSoftwareRenderer.loadV2Tex(zf, (String) WaterWorldSoftwareRenderer.asMap(aux.get("caustics_height")).get("path"), (String) WaterWorldSoftwareRenderer.asMap(aux.get("caustics_height")).get("sha256"));
            final WaterWorldSoftwareRenderer.Tex glassShellTex = WaterWorldSoftwareRenderer.loadV2Tex(zf, (String) WaterWorldSoftwareRenderer.asMap(aux.get("glass_shell_mask")).get("path"), (String) WaterWorldSoftwareRenderer.asMap(aux.get("glass_shell_mask")).get("sha256"));
            final List<Object> bokehOrbs = WaterWorldSoftwareRenderer.asList(bokehCfg.get("orbs"));

            for (double tSec : CANONICAL_TIMESTAMPS) {
                long fStart = System.nanoTime();
                DynamicSnapshot snap = evaluateSnapshot(profile, tSec);
                WaterOpticsState wo = snap.wo;
                WaterWorldSoftwareRenderer.Target scene = new WaterWorldSoftwareRenderer.Target(W, H);
                WaterWorldSoftwareRenderer.Target dummyBokeh = new WaterWorldSoftwareRenderer.Target(W, H);

                for (String passId : passOrder) {
                    List<Map<String, Object>> passDraws = drawsByPass.get(passId);
                    if ("01_BACKGROUND".equals(passId)) {
                        for (Map<String, Object> d : passDraws) {
                            MeshBuf mb = meshCache.get(d.get("mesh_path") + "|false");
                            WaterWorldSoftwareRenderer.Tex bgTex = WaterWorldSoftwareRenderer.loadV2Tex(zf, (String) d.get("texture_path"), (String) d.get("texture_sha256"));
                            float[] model = WaterWorldSoftwareRenderer.fltArr(d.get("model"));
                            float topSuppress = WaterWorldSoftwareRenderer.flt(bgAtm.get("top_glare_suppression"), 0.86f);
                            float vigStrength = WaterWorldSoftwareRenderer.flt(bgAtm.get("vignette_strength"), 0.62f);
                            float[] deepTint = WaterWorldSoftwareRenderer.fltArr(bgAtm.get("deep_tint"));
                            float[] haloC = WaterWorldSoftwareRenderer.fltArr(bgAtm.get("top_center_halo_center"));
                            float haloR = WaterWorldSoftwareRenderer.flt(bgAtm.get("top_center_halo_radius"), 0.24f);
                            float[] haloCol = WaterWorldSoftwareRenderer.fltArr(bgAtm.get("top_center_halo_color"));
                            float haloInt = WaterWorldSoftwareRenderer.flt(bgAtm.get("top_center_halo_intensity"), 0.42f);
                            float[] tmp = new float[4];
                            rasterizeDynamic(mb, model, 0, 0, 0, 0, 0, scene, true, true, "NONE", 0f, (px, py, pz, nx, ny, nz, u, v, out) -> {
                                bgTex.sample(u, v, false, tmp);
                                float topMask = 1f - smoothstep(0.0f, 0.24f, v);
                                float gSup = 1f - topMask * topSuppress;
                                float rr = tmp[0] * gSup * 0.55f + deepTint[0] * 0.55f;
                                float gg = tmp[1] * gSup * 0.62f + deepTint[1] * 0.60f;
                                float bb = tmp[2] * gSup * 0.62f + deepTint[2] * 0.60f;
                                float dx = (px - scx) / (W * 0.58f), dy = (py - scy) / (H * 0.58f);
                                float vig = 1f - smoothstep(0.55f, 1.32f, (float) Math.sqrt(dx*dx + dy*dy)) * vigStrength;
                                rr *= vig; gg *= vig; bb *= vig;
                                float hd = (float) Math.hypot(u - haloC[0], v - haloC[1]) / haloR;
                                float halo = (float) Math.exp(-hd * hd * 1.6f) * haloInt;
                                out[0] = rr + haloCol[0] * halo; out[1] = gg + haloCol[1] * halo; out[2] = bb + haloCol[2] * halo; out[3] = 1f;
                            });
                        }
                        for (Object oo : bokehOrbs) {
                            Map<String, Object> orb = WaterWorldSoftwareRenderer.asMap(oo);
                            if (!"BG".equals(orb.get("layer"))) continue;
                            renderDynamicBokeh(orb, scene, wo.bokehDriftOffsetU, wo.bokehDriftOffsetV);
                        }
                    } else if ("02_RESIDENT_BACK".equals(passId) || "10_RESIDENT_FRONT".equals(passId)) {
                        for (Map<String, Object> d : passDraws) {
                            MeshBuf mb = meshCache.get(d.get("mesh_path") + "|false");
                            float[] model = WaterWorldSoftwareRenderer.fltArr(d.get("model"));
                            float[] tint = WaterWorldSoftwareRenderer.fltArr(d.get("tint"));
                            float[] coreCol = d.containsKey("core_color") ? WaterWorldSoftwareRenderer.fltArr(d.get("core_color")) : new float[]{1f, 1f, 0.9f};
                            float emission = WaterWorldSoftwareRenderer.flt(d.get("emission_override"), 1.1f);
                            float style = WaterWorldSoftwareRenderer.flt(d.get("ribbon_style"), 0f);
                            String drawId = (String) d.get("draw_id");
                            ResidentState rs = snap.residents.get(resolveResidentGroup(drawId));
                            float flowU = rs != null ? rs.flowOffsetU : 0f;
                            float wScale = rs != null ? rs.widthPulseScale : 1f;
                            float bScale = rs != null ? rs.brightnessScale : 1f;
                            float lWave = rs != null ? rs.lateralWavePx : 0f;
                            float wPhase = rs != null ? rs.wavePhaseRad : 0f;
                            rasterizeDynamic(mb, model, (!snap.isZero && lWave > 0f) ? 3 : 0, wPhase, lWave, 0, 0, scene, true, false, "SCREEN", 0f, (px, py, pz, nx, ny, nz, u, v, out) -> {
                                float across = Math.abs(v * 2f - 1f) / Math.max(0.75f, wScale);
                                float endFade = smoothstep(0.0f, 0.11f, u) * smoothstep(0.0f, 0.13f, 1f - u);
                                float uFlow = u - flowU * 2.2f;
                                float twist = 0.82f + 0.18f * (float) Math.sin(uFlow * 10.5f + style * 2.1f);
                                float wave = 0.5f + 0.5f * (float) Math.cos((v * 2f - 1f) * 10.5f + uFlow * 6.8f);
                                float streak = 0.56f + 0.44f * (float) Math.pow(wave, 1.4);
                                float body, core, rim;
                                if (style < 0.5f) {
                                    body = (1f - smoothstep(0.16f, 1.0f, across)) * 0.52f * streak;
                                    rim = (float) Math.exp(-28f * (across - 0.64f) * (across - 0.64f)) * 0.62f;
                                    core = (float) Math.exp(-24f * across * across) * 0.60f;
                                } else if (style < 1.5f) {
                                    body = (float) Math.exp(-2.9f * across * across) * 0.54f * streak;
                                    rim = (float) Math.exp(-26f * (across - 0.52f) * (across - 0.52f)) * 0.40f;
                                    core = (float) Math.exp(-34f * across * across) * 1.06f;
                                } else {
                                    body = (1f - smoothstep(0.15f, 1.0f, across)) * 0.50f * streak;
                                    rim = (float) Math.exp(-26f * (across - 0.58f) * (across - 0.58f)) * 0.50f;
                                    core = (float) Math.exp(-30f * across * across) * 0.94f;
                                }
                                float alpha = clamp((body + rim * 0.60f + core * 0.68f) * tint[3] * endFade * twist, 0f, 1f);
                                float em = emission * bScale;
                                out[0] = (tint[0] * (body + rim) + coreCol[0] * core) * em;
                                out[1] = (tint[1] * (body + rim) + coreCol[1] * core) * em;
                                out[2] = (tint[2] * (body + rim) + coreCol[2] * core) * em;
                                out[3] = alpha;
                            });
                        }
                    } else if ("03_SPHERE_BACK".equals(passId)) {
                        WaterWorldSoftwareRenderer.Target preGlass = new WaterWorldSoftwareRenderer.Target(W, H);
                        preGlass.copyFrom(scene);
                        float backRef = WaterWorldSoftwareRenderer.flt(glassCfg.get("back_refraction_strength"), 0.014f) * wo.glassRefractionScale;
                        float[] absCol = WaterWorldSoftwareRenderer.fltArr(glassCfg.get("absorption_color"));
                        float[] coolRim = WaterWorldSoftwareRenderer.fltArr(glassCfg.get("rim_color_cool"));
                        float[] sample = new float[4], maskS = new float[4];
                        for (int py = 0; py < H; py++) {
                            for (int px = 0; px < W; px++) {
                                float dx = (px - scx) / sOuterR, dy = (py - scy) / sOuterR;
                                float r2 = dx*dx + dy*dy;
                                if (r2 > 1.0f) continue;
                                float r = (float) Math.sqrt(r2), nz = (float) Math.sqrt(Math.max(0.0001f, 1f - r2));
                                float u = px / (float) W, v = py / (float) H;
                                float refractMask = smoothstep(0.55f, 1.0f, r);
                                preGlass.sampleBilinear(u - dx * backRef * refractMask, v - dy * backRef * refractMask, sample);
                                glassShellTex.sample(u, v, false, maskS);
                                float fresnel = (float) Math.pow(1f - nz, 4.2f);
                                float rimGlow = (fresnel * 0.20f + maskS[0] * 0.08f) * wo.glassFresnelScale;
                                int idx = py * W + px;
                                scene.r[idx] = mix(sample[0], absCol[0], 0.14f + fresnel * 0.12f) + coolRim[0] * rimGlow * 0.25f;
                                scene.g[idx] = mix(sample[1], absCol[1], 0.16f + fresnel * 0.14f) + coolRim[1] * rimGlow * 0.30f;
                                scene.b[idx] = mix(sample[2], absCol[2], 0.18f + fresnel * 0.16f) + coolRim[2] * rimGlow * 0.35f;
                            }
                        }
                    } else if ("04_WATER_INTERIOR".equals(passId)) {
                        WaterWorldSoftwareRenderer.Target preWater = new WaterWorldSoftwareRenderer.Target(W, H);
                        preWater.copyFrom(scene);
                        Map<String, Object> wd = passDraws.get(0);
                        WaterWorldSoftwareRenderer.Tex waterbedTex = WaterWorldSoftwareRenderer.loadV2Tex(zf, (String) wd.get("texture_path"), (String) wd.get("texture_sha256"));
                        float normStr = WaterWorldSoftwareRenderer.flt(waterCfg.get("normal_distortion_strength"), 0.010f);
                        float menBase = WaterWorldSoftwareRenderer.flt(waterCfg.get("meniscus_base_y_norm"), 0.244f);
                        float menAmp = WaterWorldSoftwareRenderer.flt(waterCfg.get("meniscus_wave_amp_norm"), 0.016f);
                        float menThick = WaterWorldSoftwareRenderer.flt(waterCfg.get("meniscus_thickness_norm"), 0.020f);
                        float[] airTop = WaterWorldSoftwareRenderer.fltArr(waterCfg.get("air_dome_top_color"));
                        float[] airWarm = WaterWorldSoftwareRenderer.fltArr(waterCfg.get("air_dome_warm_color"));
                        float[] wShal = WaterWorldSoftwareRenderer.fltArr(waterCfg.get("water_shallow_color"));
                        float[] wMid = WaterWorldSoftwareRenderer.fltArr(waterCfg.get("water_mid_color"));
                        float[] wDeep = WaterWorldSoftwareRenderer.fltArr(waterCfg.get("water_deep_color"));
                        float bedBlend = WaterWorldSoftwareRenderer.flt(waterCfg.get("waterbed_blend"), 0.14f);
                        float[] wn = new float[4], wdep = new float[4], wbed = new float[4], preS = new float[4], cau = new float[4];
                        for (int py = 0; py < H; py++) {
                            for (int px = 0; px < W; px++) {
                                float dx = (px - scx) / (sInnerR + 8f), dy = (py - scy) / (sInnerR + 8f);
                                float r2 = dx*dx + dy*dy;
                                if (r2 > 1.0f) continue;
                                float r = (float) Math.sqrt(r2), edgeSoft = 1f - smoothstep(0.960f, 1.0f, r);
                                float u = px / (float) W, v = py / (float) H;
                                waterNormalTex.sample(u * 2.0f + wo.waterNormalOffsetU, v * 2.0f + wo.waterNormalOffsetV, true, wn);
                                float tnx = wn[0] * 2f - 1f, tny = wn[1] * 2f - 1f;
                                waterDepthTex.sample(u, v, false, wdep);
                                float relDepth = wdep[0];
                                float du = tnx * normStr * (0.35f + 0.65f * relDepth);
                                float dv = tny * normStr * (0.35f + 0.65f * relDepth);
                                preWater.sampleBilinear(u + du, v + dv, preS);
                                waterbedTex.sample(u + du * 1.2f, v + dv * 1.2f, false, wbed);
                                causticsTex.sample((u + du * 1.5f) * 1.8f + wo.waterNormalOffsetU * 0.7f, (v + dv * 1.5f) * 1.8f - wo.waterNormalOffsetV * 0.7f, true, cau);
                                float waveY = menBase
                                        + menAmp * (float) Math.sin((u - 0.36f) * 13.5f + wo.meniscusWavePhaseRad)
                                        + menAmp * 0.38f * (float) Math.cos((u - 0.54f) * 24.0f - wo.meniscusSecondaryPhaseRad)
                                        - 0.026f * smoothstep(0.56f, 0.72f, u)
                                        + 0.024f * smoothstep(0.70f, 0.84f, u)
                                        + tny * 0.005f;
                                float signedY = v - waveY;
                                float rr, gg, bb;
                                if (signedY < 0f) {
                                    float airFrac = clamp(-signedY / 0.10f, 0f, 1f);
                                    float sunSide = 1f - smoothstep(0.25f, 0.58f, u);
                                    float skyReflect = (float) Math.exp(-Math.pow((u - 0.51f) / 0.17f, 2.0)) * (1f - airFrac * 0.52f);
                                    rr = mix(airTop[0], airWarm[0], sunSide * 0.86f) + 0.18f * skyReflect;
                                    gg = mix(airTop[1], airWarm[1], sunSide * 0.74f) + 0.40f * skyReflect;
                                    bb = mix(airTop[2], airWarm[2], sunSide * 0.44f) + 0.48f * skyReflect;
                                    float topDark = smoothstep(0.68f, 0.98f, r) * (1f - sunSide * 0.52f);
                                    rr *= (1f - topDark * 0.54f); gg *= (1f - topDark * 0.44f); bb *= (1f - topDark * 0.32f);
                                    rr = mix(preS[0], rr, 0.79f); gg = mix(preS[1], gg, 0.81f); bb = mix(preS[2], bb, 0.82f);
                                } else {
                                    float depthT = clamp(signedY / 0.58f, 0f, 1f);
                                    float rightDeep = smoothstep(0.45f, 0.82f, u);
                                    float r0 = mix(mix(wShal[0], wMid[0], smoothstep(0f, 0.40f, depthT)), wDeep[0], smoothstep(0.42f, 1.0f, depthT));
                                    float g0 = mix(mix(wShal[1], wMid[1], smoothstep(0f, 0.40f, depthT)), wDeep[1], smoothstep(0.42f, 1.0f, depthT));
                                    float b0 = mix(mix(wShal[2], wMid[2], smoothstep(0f, 0.40f, depthT)), wDeep[2], smoothstep(0.42f, 1.0f, depthT));
                                    r0 *= (1f - rightDeep * 0.35f); g0 *= (1f - rightDeep * 0.24f); b0 *= (1f - rightDeep * 0.10f);
                                    float wbFactor = bedBlend * smoothstep(0.30f, 0.90f, depthT);
                                    rr = mix(r0, wbed[0] * 0.46f, wbFactor); gg = mix(g0, wbed[1] * 0.62f, wbFactor); bb = mix(b0, wbed[2] * 0.64f, wbFactor);
                                    float wallShade = 1f - smoothstep(0.62f, 0.99f, r) * 0.44f;
                                    rr *= wallShade; gg *= wallShade; bb *= wallShade;
                                    float coreWave = (float) Math.sin((u - 0.5f) * 9.0f + wo.coreFluidPhaseRad) * (float) Math.cos((v - 0.55f) * 8.0f - wo.coreFluidPhaseRad * 0.7f);
                                    float coreMod = wo.coreDensityScale + (wo.coreFluidPhaseRad != 0f ? coreWave * 0.045f : 0f);
                                    float basinD = (float) Math.hypot((u - 0.54f) / 0.22f, (v - 0.62f) / 0.16f);
                                    float basinGlow = (float) Math.exp(-basinD * basinD * 1.4f) * 0.26f * coreMod;
                                    rr += (0.04f + wo.coreColorShift * 0.20f) * basinGlow;
                                    gg += (0.74f + wo.coreColorShift * 0.12f) * basinGlow;
                                    bb += (0.78f - wo.coreColorShift * 0.10f) * basinGlow;
                                    rr = mix(preS[0], rr, 0.91f); gg = mix(preS[1], gg, 0.91f); bb = mix(preS[2], bb, 0.91f);
                                }
                                float menLine = (float) Math.exp(-(signedY * signedY) / (menThick * menThick * 0.35f));
                                float underRipple = (signedY > 0f && signedY < 0.090f)
                                        ? (float) Math.exp(-Math.pow((signedY - 0.025f) / 0.032f, 2.0)) * (0.38f + 0.62f * cau[0])
                                        : 0f;
                                float sunWarmth = 1f - smoothstep(0.25f, 0.60f, u);
                                float menR = mix(0.20f, 1.00f, sunWarmth), menG = mix(0.88f, 0.93f, sunWarmth), menB = mix(0.96f, 0.52f, sunWarmth);
                                rr = mix(rr, menR * (0.90f + 0.28f * cau[0]), menLine * 0.88f) + mix(0.08f, 0.68f, sunWarmth) * underRipple * 0.52f;
                                gg = mix(gg, menG * (0.91f + 0.24f * cau[0]), menLine * 0.88f) + mix(0.50f, 0.58f, sunWarmth) * underRipple * 0.52f;
                                bb = mix(bb, menB * (0.92f + 0.20f * cau[0]), menLine * 0.88f) + mix(0.60f, 0.22f, sunWarmth) * underRipple * 0.52f;
                                int idx = py * W + px;
                                scene.r[idx] = mix(scene.r[idx], rr, edgeSoft);
                                scene.g[idx] = mix(scene.g[idx], gg, edgeSoft);
                                scene.b[idx] = mix(scene.b[idx], bb, edgeSoft);
                            }
                        }
                    } else if ("06_LIGHTING".equals(passId)) {
                        float[] sunOrig = WaterWorldSoftwareRenderer.fltArr(causticsCfg.get("sun_origin_norm"));
                        float rayInt = WaterWorldSoftwareRenderer.flt(causticsCfg.get("god_ray_intensity"), 0.56f) * wo.lightingShimmerScale;
                        float[] rayCol = WaterWorldSoftwareRenderer.fltArr(causticsCfg.get("god_ray_color"));
                        List<Object> beams = WaterWorldSoftwareRenderer.asList(causticsCfg.get("god_ray_beams"));
                        float[] bAng = new float[beams.size()], bWid = new float[beams.size()], bStr = new float[beams.size()];
                        for (int i = 0; i < beams.size(); i++) {
                            Map<String, Object> bm = WaterWorldSoftwareRenderer.asMap(beams.get(i));
                            bAng[i] = WaterWorldSoftwareRenderer.flt(bm.get("angle_deg"), 60f);
                            bWid[i] = WaterWorldSoftwareRenderer.flt(bm.get("width_deg"), 6f);
                            bStr[i] = WaterWorldSoftwareRenderer.flt(bm.get("strength"), 0.8f);
                        }
                        float surfCau = WaterWorldSoftwareRenderer.flt(causticsCfg.get("surface_caustic_strength"), 0.72f);
                        float bedCau = WaterWorldSoftwareRenderer.flt(causticsCfg.get("bed_caustic_strength"), 0.55f);
                        float[] bedCol = WaterWorldSoftwareRenderer.fltArr(causticsCfg.get("bed_caustic_color"));
                        float[] cauS = new float[4], wn = new float[4];
                        float cOffU = (float) (Math.sin(wo.causticsPhaseRad) * 0.042 + wo.causticsPhaseRad * 0.018);
                        float cOffV = (float) (Math.cos(wo.causticsPhaseRad * 0.85f) * 0.030 - wo.causticsPhaseRad * 0.012);
                        for (int py = 0; py < H; py++) {
                            for (int px = 0; px < W; px++) {
                                float dx = (px - scx) / (sInnerR + 4f), dy = (py - scy) / (sInnerR + 4f);
                                float r2 = dx*dx + dy*dy;
                                if (r2 > 1.0f) continue;
                                float sphereFade = 1f - smoothstep(0.90f, 1.0f, (float) Math.sqrt(r2));
                                float u = px / (float) W, v = py / (float) H;
                                float sdx = u - sunOrig[0], sdy = v - sunOrig[1];
                                float dist = (float) Math.hypot(sdx, sdy);
                                float raySum = 0f;
                                if (sdy > 0.01f && dist < 0.44f) {
                                    float angDeg = (float) Math.toDegrees(Math.atan2(sdy, sdx));
                                    for (int i = 0; i < bAng.length; i++) {
                                        float da = angDeg - bAng[i];
                                        raySum += bStr[i] * (float) Math.exp(-(da * da) / (2f * bWid[i] * bWid[i]));
                                    }
                                    raySum = clamp(raySum, 0f, 1.05f) * smoothstep(0.02f, 0.08f, dist) * (1f - smoothstep(0.16f, 0.40f, dist)) * rayInt;
                                }
                                waterNormalTex.sample(u * 1.8f + cOffU * 0.5f, v * 1.8f + cOffV * 0.5f, true, wn);
                                causticsTex.sample(u * 1.35f + (wn[0] - 0.5f) * 0.12f + cOffU, v * 1.35f + (wn[1] - 0.5f) * 0.12f + cOffV, true, cauS);
                                float cVal = (float) Math.pow(clamp(cauS[0], 0f, 1f), 2.1f);
                                float upperBand = (float) Math.exp(-Math.pow((v - 0.255f) / 0.042f, 2.0));
                                float lowerBed = smoothstep(0.60f, 0.74f, v) * (1f - smoothstep(0.78f, 0.86f, v));
                                int idx = py * W + px;
                                scene.r[idx] += (rayCol[0] * raySum + rayCol[0] * cVal * upperBand * surfCau * 0.35f + bedCol[0] * cVal * lowerBed * bedCau * 0.34f) * sphereFade;
                                scene.g[idx] += (rayCol[1] * raySum + rayCol[1] * cVal * upperBand * surfCau * 0.40f + bedCol[1] * cVal * lowerBed * bedCau * 0.38f) * sphereFade;
                                scene.b[idx] += (rayCol[2] * raySum + 0.78f * cVal * upperBand * surfCau * 0.36f + bedCol[2] * cVal * lowerBed * bedCau * 0.26f) * sphereFade;
                            }
                        }
                    } else if ("05_FAR_ECOLOGY".equals(passId) || "07_FISH".equals(passId) || "08_NEAR_ECOLOGY".equals(passId)) {
                        for (Map<String, Object> d : passDraws) {
                            String matName = (String) d.get("material");
                            String drawId = (String) d.get("draw_id");
                            String shaderProg = (String) d.get("shader_program");
                            boolean isPlant = "plant".equals(matName);
                            MeshBuf mb = meshCache.get(d.get("mesh_path") + "|" + isPlant);
                            WaterWorldSoftwareRenderer.Tex tex = WaterWorldSoftwareRenderer.loadV2Tex(zf, (String) d.get("texture_path"), (String) d.get("texture_sha256"));
                            float[] baseModel = WaterWorldSoftwareRenderer.fltArr(d.get("model"));
                            float[] tint = d.containsKey("tint") ? WaterWorldSoftwareRenderer.fltArr(d.get("tint")) : new float[]{1f, 1f, 1f, 1f};
                            boolean depthWrite = WaterWorldSoftwareRenderer.bool(d.get("depth_write"), false);
                            boolean depthTest = WaterWorldSoftwareRenderer.bool(d.get("depth_test"), true);
                            float alphaCut = WaterWorldSoftwareRenderer.flt(d.get("alpha_cutoff"), 0f);
                            float ambient = WaterWorldSoftwareRenderer.flt(d.get("ambient"), 0.55f);
                            boolean sphereClip = WaterWorldSoftwareRenderer.bool(d.get("sphere_clip"), true);

                            float[] mat = baseModel;
                            int defMode = 0;
                            float d0 = 0, d1 = 0, d2 = 0, d3 = 0;
                            float dynAlpha = 1f;

                            if ("fish".equals(matName) && !snap.isZero && snap.fish.containsKey(drawId)) {
                                FishState fs = snap.fish.get(drawId);
                                mat = fishMatrix(baseModel, fs);
                                defMode = 1; d0 = fs.tailPhaseRad; d1 = fs.tailAmpPx; d2 = fs.finPhaseRad; d3 = fs.finAmpPx;
                            } else if (isPlant && !snap.isZero) {
                                PlantState ps = snap.plants.get(drawId.contains("left") ? "WW_PLANT_LEFT" : "WW_PLANT_RIGHT");
                                if (ps != null) {
                                    defMode = 2; d0 = ps.swayPhaseRad; d1 = ps.swayAmpPx; d2 = ps.leafFlutterPhaseRad; d3 = ps.leafFlutterAmpPx;
                                }
                            } else if ("PARTICLE_BOKEH".equals(shaderProg) && !snap.isZero && snap.innerBubbles.containsKey(drawId)) {
                                BubbleState bs = snap.innerBubbles.get(drawId);
                                mat = bubbleMatrix(baseModel, mb.cx, mb.cy, bs);
                                dynAlpha = bs.alphaMul;
                            }
                            final float fDynAlpha = dynAlpha;
                            final float rockCauOffU = snap.isZero ? 0f : (float) (Math.sin(wo.causticsPhaseRad) * 0.036 + wo.causticsPhaseRad * 0.015);
                            final float rockCauOffV = snap.isZero ? 0f : (float) (Math.cos(wo.causticsPhaseRad * 0.85f) * 0.025 - wo.causticsPhaseRad * 0.010);
                            float[] tmp = new float[4], cauS = new float[4];

                            rasterizeDynamic(mb, mat, defMode, d0, d1, d2, d3, scene, depthTest, depthWrite, "ALPHA", alphaCut, (px, py, pz, nx, ny, nz, u, v, out) -> {
                                float clipAlpha = 1f;
                                if (sphereClip) {
                                    float sr = (float) Math.hypot(px - scx, py - scy) / (sInnerR + 6f);
                                    if (sr >= 1.0f) return;
                                    clipAlpha = 1f - smoothstep(0.93f, 1.0f, sr);
                                }
                                tex.sample(u, v, false, tmp);
                                if (tmp[3] <= 0.005f) return;
                                float su = px / (float) W, sv = py / (float) H;
                                if ("PARTICLE_BOKEH".equals(shaderProg)) {
                                    float bx = u * 2f - 1f, by = v * 2f - 1f;
                                    float br = (float) Math.hypot(bx, by);
                                    if (br > 1f) return;
                                    float rim = smoothstep(0.55f, 0.92f, br) * (1f - smoothstep(0.92f, 1.0f, br));
                                    float spec = (float) Math.exp(-((bx + 0.34f)*(bx + 0.34f) + (by + 0.34f)*(by + 0.34f)) * 16f);
                                    out[0] = clamp(tmp[0] * tint[0] * 0.7f + rim * 0.65f + spec * 1.1f, 0f, 1.4f);
                                    out[1] = clamp(tmp[1] * tint[1] * 0.8f + rim * 0.85f + spec * 1.1f, 0f, 1.4f);
                                    out[2] = clamp(tmp[2] * tint[2] * 0.85f + rim * 0.95f + spec * 1.1f, 0f, 1.4f);
                                    out[3] = clamp((tmp[3] * 0.65f + spec * 0.85f + rim * 0.5f) * tint[3] * clipAlpha * fDynAlpha, 0f, 1f);
                                    return;
                                }
                                float rr = tmp[0] * tint[0], gg = tmp[1] * tint[1], bb = tmp[2] * tint[2];
                                float topLight = clamp(1.12f - sv * 0.52f + (0.5f - su) * 0.20f, 0.52f, 1.20f);
                                if ("fish".equals(matName)) {
                                    float dorsal = 1f - smoothstep(0.15f, 0.72f, v);
                                    rr = rr * (ambient + 0.44f * topLight) + dorsal * 0.15f * keyColor[0];
                                    gg = gg * (ambient + 0.43f * topLight) + dorsal * 0.12f * keyColor[1];
                                    bb = bb * (ambient + 0.39f * topLight) + (1f - dorsal) * 0.055f;
                                } else if (isPlant) {
                                    float tipSun = (1f - smoothstep(0.05f, 0.68f, v)) * (0.62f + 0.38f * (1f - su));
                                    rr = rr * (ambient + 0.36f * topLight + tipSun * 0.44f);
                                    gg = gg * (ambient + 0.43f * topLight + tipSun * 0.34f);
                                    bb = bb * (ambient + 0.24f * topLight + tipSun * 0.04f);
                                    if ("05_FAR_ECOLOGY".equals(passId)) {
                                        rr = mix(rr, 0.022f, 0.44f); gg = mix(gg, 0.26f, 0.44f); bb = mix(bb, 0.28f, 0.44f);
                                    }
                                } else if ("rock".equals(matName)) {
                                    float[] wnR = new float[4];
                                    waterNormalTex.sample(su * 2.2f + rockCauOffU * 0.5f, sv * 2.2f + rockCauOffV * 0.5f, true, wnR);
                                    causticsTex.sample(su * 1.75f + (wnR[0] - 0.5f) * 0.24f + 0.15f + rockCauOffU, sv * 2.05f + (wnR[1] - 0.5f) * 0.24f + 0.09f + rockCauOffV, true, cauS);
                                    float topSurface = 1f - smoothstep(0.04f, 0.66f, v);
                                    float cGlow = (float) Math.pow(clamp(cauS[0], 0f, 1f), 4.0f) * topSurface * ("rock_03".equals(drawId) ? 0.92f : 1.48f);
                                    float mossBoost = "rock_03".equals(drawId) ? (float) Math.pow(topSurface, 1.4f) * 0.36f : 0f;
                                    rr = rr * (ambient + 0.40f * topLight) + cGlow * 1.02f + mossBoost * 0.88f;
                                    gg = gg * (ambient + 0.44f * topLight) + cGlow * 0.96f + mossBoost * 1.00f;
                                    bb = bb * (ambient + 0.42f * topLight) + cGlow * 0.68f + mossBoost * 0.16f;
                                    if ("05_FAR_ECOLOGY".equals(passId)) {
                                        rr = mix(rr, 0.022f, 0.46f); gg = mix(gg, 0.26f, 0.46f); bb = mix(bb, 0.29f, 0.46f);
                                    }
                                }
                                out[0] = rr; out[1] = gg; out[2] = bb;
                                out[3] = tmp[3] * tint[3] * clipAlpha * fDynAlpha;
                            });
                        }
                    } else if ("09_SPHERE_FRONT".equals(passId)) {
                        WaterWorldSoftwareRenderer.Target preFront = new WaterWorldSoftwareRenderer.Target(W, H);
                        preFront.copyFrom(scene);
                        float frontRef = WaterWorldSoftwareRenderer.flt(glassCfg.get("front_refraction_strength"), 0.026f) * wo.glassRefractionScale;
                        float disp = WaterWorldSoftwareRenderer.flt(glassCfg.get("chromatic_dispersion"), 0.0012f);
                        float fPow = WaterWorldSoftwareRenderer.flt(glassCfg.get("fresnel_power"), 4.2f);
                        float[] coolRim = WaterWorldSoftwareRenderer.fltArr(glassCfg.get("rim_color_cool"));
                        float[] warmRim = WaterWorldSoftwareRenderer.fltArr(glassCfg.get("rim_color_warm"));
                        float[] tlPos = WaterWorldSoftwareRenderer.fltArr(glassCfg.get("top_left_flare_norm"));
                        float tlRad = WaterWorldSoftwareRenderer.flt(glassCfg.get("top_left_flare_radius"), 0.088f);
                        float[] tlCol = WaterWorldSoftwareRenderer.fltArr(glassCfg.get("top_left_flare_color"));
                        float tlInt = WaterWorldSoftwareRenderer.flt(glassCfg.get("top_left_flare_intensity"), 1.05f) * wo.glassFresnelScale;
                        float[] brPos = WaterWorldSoftwareRenderer.fltArr(glassCfg.get("bottom_right_flare_norm"));
                        float brRad = WaterWorldSoftwareRenderer.flt(glassCfg.get("bottom_right_flare_radius"), 0.082f);
                        float[] brCol = WaterWorldSoftwareRenderer.fltArr(glassCfg.get("bottom_right_flare_color"));
                        float brInt = WaterWorldSoftwareRenderer.flt(glassCfg.get("bottom_right_flare_intensity"), 1.00f) * wo.glassFresnelScale;
                        float[] rArcPos = WaterWorldSoftwareRenderer.fltArr(glassCfg.get("right_rim_arc_norm"));
                        float rArcInt = WaterWorldSoftwareRenderer.flt(glassCfg.get("right_rim_arc_intensity"), 0.58f);
                        float[] sR = new float[4], sG = new float[4], sB = new float[4], shellM = new float[4];
                        for (int py = 0; py < H; py++) {
                            for (int px = 0; px < W; px++) {
                                float dx = (px - scx) / sOuterR, dy = (py - scy) / sOuterR;
                                float r2 = dx*dx + dy*dy;
                                float u = px / (float) W, v = py / (float) H;
                                int idx = py * W + px;
                                if (r2 <= 1.006f) {
                                    float r = (float) Math.sqrt(Math.min(1.0f, r2));
                                    float nz = (float) Math.sqrt(Math.max(0.0001f, 1f - Math.min(0.9999f, r2)));
                                    float wallFactor = smoothstep(0.84f, 0.995f, r);
                                    float dispMask = smoothstep(0.91f, 0.995f, r);
                                    float off = frontRef * wallFactor * (1f - nz * 0.55f);
                                    preFront.sampleBilinear(u - dx * (off + disp * dispMask), v - dy * (off + disp * dispMask), sR);
                                    preFront.sampleBilinear(u - dx * off, v - dy * off, sG);
                                    preFront.sampleBilinear(u - dx * (off - disp * dispMask), v - dy * (off - disp * dispMask), sB);
                                    float rr = sR[0], gg = sG[1], bb = sB[2];
                                    float fresnel = (float) Math.pow(1f - nz, fPow);
                                    float outerBevel = (float) Math.exp(-Math.pow((r - 0.990f) / 0.011f, 2.0));
                                    float innerDarkBand = (float) Math.exp(-Math.pow((r - 0.958f) / 0.013f, 2.0));
                                    float innerBrightRing = (float) Math.exp(-Math.pow((r - 0.936f) / 0.014f, 2.0));
                                    glassShellTex.sample(u, v, false, shellM);
                                    rr *= (1f - innerDarkBand * 0.28f); gg *= (1f - innerDarkBand * 0.20f); bb *= (1f - innerDarkBand * 0.14f);
                                    float ulSunGlass = (float) Math.exp(-Math.pow(dx + 0.72f, 2.0) * 1.45 - Math.pow(dy + 0.42f, 2.0) * 1.45);
                                    float lrSunGlass = (float) Math.exp(-Math.pow(dx - 0.56f, 2.0) * 1.55 - Math.pow(dy - 0.54f, 2.0) * 1.55);
                                    float warmWeight = clamp(ulSunGlass + lrSunGlass * 0.95f, 0f, 1f);
                                    float rimR = mix(coolRim[0], warmRim[0], warmWeight), rimG = mix(coolRim[1], warmRim[1], warmWeight), rimB = mix(coolRim[2], warmRim[2], warmWeight);
                                    float rimSpec = (outerBevel * 1.05f + innerBrightRing * 0.62f + fresnel * 0.30f + shellM[0] * 0.10f) * wo.glassFresnelScale;
                                    float rightCrescent = (float) Math.exp(-Math.pow((u - rArcPos[0]) / 0.068f, 2.0) - Math.pow((v - rArcPos[1]) / 0.22f, 2.0)) * wallFactor * rArcInt;
                                    float topArch = smoothstep(0.25f, 0.88f, -dy) * (outerBevel * 0.80f + innerBrightRing * 0.58f);
                                    float blAqua = smoothstep(0.05f, 0.80f, dy) * smoothstep(0.15f, 0.85f, -dx) * (outerBevel * 0.55f + innerBrightRing * 0.52f + fresnel * 0.28f);
                                    float sunWallGlow = (ulSunGlass * 0.58f + lrSunGlass * 0.52f) * smoothstep(0.76f, 0.99f, r);
                                    rr += rimR * rimSpec * 0.88f + 0.88f * rightCrescent + 0.76f * topArch * 0.45f + 0.18f * blAqua + 1.00f * sunWallGlow;
                                    gg += rimG * rimSpec * 0.92f + 0.97f * rightCrescent + 0.96f * topArch * 0.52f + 0.86f * blAqua + 0.82f * sunWallGlow;
                                    bb += rimB * rimSpec * 0.95f + 1.00f * rightCrescent + 1.00f * topArch * 0.55f + 1.00f * blAqua + 0.42f * sunWallGlow;
                                    float aaEdge = 1f - smoothstep(0.993f, 1.005f, (float) Math.sqrt(r2));
                                    scene.r[idx] = mix(scene.r[idx], rr, aaEdge);
                                    scene.g[idx] = mix(scene.g[idx], gg, aaEdge);
                                    scene.b[idx] = mix(scene.b[idx], bb, aaEdge);
                                }
                                float dTL = (float) Math.hypot(u - tlPos[0], v - tlPos[1]) / tlRad;
                                float flareTL = ((float) Math.exp(-dTL * dTL * 3.4f) * 0.62f + (float) Math.exp(-dTL * dTL * 26.0f) * 1.38f) * tlInt;
                                float dBR = (float) Math.hypot(u - brPos[0], v - brPos[1]) / brRad;
                                float flareBR = ((float) Math.exp(-dBR * dBR * 3.4f) * 0.60f + (float) Math.exp(-dBR * dBR * 24.0f) * 1.32f) * brInt;
                                scene.r[idx] += tlCol[0] * flareTL + brCol[0] * flareBR;
                                scene.g[idx] += tlCol[1] * flareTL + brCol[1] * flareBR;
                                scene.b[idx] += tlCol[2] * flareTL + brCol[2] * flareBR;
                            }
                        }
                    } else if ("11_FINAL_COMPOSITE".equals(passId)) {
                        float thresh = WaterWorldSoftwareRenderer.flt(bloomCfg.get("threshold"), 0.78f);
                        float knee = WaterWorldSoftwareRenderer.flt(bloomCfg.get("soft_knee"), 0.25f);
                        int blurR = (int) WaterWorldSoftwareRenderer.flt(bloomCfg.get("blur_radius_px"), 14f);
                        float bStr = WaterWorldSoftwareRenderer.flt(bloomCfg.get("strength"), 0.38f) * wo.bloomResponseScale;
                        float bMax = WaterWorldSoftwareRenderer.flt(bloomCfg.get("max_contribution"), 0.55f);
                        WaterWorldSoftwareRenderer.Target bright = new WaterWorldSoftwareRenderer.Target(W, H);
                        for (int i = 0; i < W * H; i++) {
                            float lum = 0.2126f * scene.r[i] + 0.7152f * scene.g[i] + 0.0722f * scene.b[i];
                            float rq = clamp(lum - thresh + knee, 0f, 2f * knee);
                            rq = (rq * rq) / (4f * knee + 1e-5f);
                            float contrib = Math.max(rq, lum - thresh) / Math.max(lum, 1e-5f);
                            bright.r[i] = scene.r[i] * contrib; bright.g[i] = scene.g[i] * contrib; bright.b[i] = scene.b[i] * contrib; bright.a[i] = 1f;
                        }
                        WaterWorldSoftwareRenderer.Target ds2 = WaterWorldSoftwareRenderer.downsample2x(bright);
                        WaterWorldSoftwareRenderer.Target ds4 = WaterWorldSoftwareRenderer.downsample2x(ds2);
                        WaterWorldSoftwareRenderer.Target bv = WaterWorldSoftwareRenderer.blur1D(WaterWorldSoftwareRenderer.blur1D(ds4, blurR, true), blurR, false);
                        WaterWorldSoftwareRenderer.Target bv2 = WaterWorldSoftwareRenderer.blur1D(WaterWorldSoftwareRenderer.blur1D(ds2, Math.max(4, blurR / 2), true), Math.max(4, blurR / 2), false);
                        float[] bSample4 = new float[4], bSample2 = new float[4];
                        for (int py = 0; py < H; py++) {
                            float v = py / (float) H;
                            for (int px = 0; px < W; px++) {
                                float u = px / (float) W;
                                bv.sampleBilinear(u, v, bSample4); bv2.sampleBilinear(u, v, bSample2);
                                int idx = py * W + px;
                                scene.r[idx] += Math.min(bMax, (bSample4[0] * 0.65f + bSample2[0] * 0.55f) * bStr);
                                scene.g[idx] += Math.min(bMax, (bSample4[1] * 0.65f + bSample2[1] * 0.55f) * bStr);
                                scene.b[idx] += Math.min(bMax, (bSample4[2] * 0.65f + bSample2[2] * 0.55f) * bStr);
                            }
                        }
                        for (Object oo : bokehOrbs) {
                            Map<String, Object> orb = WaterWorldSoftwareRenderer.asMap(oo);
                            if (!"FG".equals(orb.get("layer"))) continue;
                            renderDynamicBokeh(orb, scene, wo.bokehDriftOffsetU, wo.bokehDriftOffsetV);
                        }
                        for (Map<String, Object> d : passDraws) {
                            String drawId = (String) d.get("draw_id");
                            MeshBuf mb = meshCache.get(d.get("mesh_path") + "|false");
                            WaterWorldSoftwareRenderer.Tex tex = WaterWorldSoftwareRenderer.loadV2Tex(zf, (String) d.get("texture_path"), (String) d.get("texture_sha256"));
                            float[] baseModel = WaterWorldSoftwareRenderer.fltArr(d.get("model"));
                            float[] tint = WaterWorldSoftwareRenderer.fltArr(d.get("tint"));
                            float[] mat = baseModel;
                            float dynAlpha = 1f;
                            if (!snap.isZero && snap.outerBubbles.containsKey(drawId)) {
                                BubbleState bs = snap.outerBubbles.get(drawId);
                                mat = bubbleMatrix(baseModel, mb.cx, mb.cy, bs);
                                dynAlpha = bs.alphaMul;
                            }
                            final float fDynA = dynAlpha;
                            float[] tmp = new float[4];
                            rasterizeDynamic(mb, mat, 0, 0, 0, 0, 0, scene, true, false, "ALPHA", 0f, (px, py, pz, nx, ny, nz, u, v, out) -> {
                                float bx = u * 2f - 1f, by = v * 2f - 1f;
                                float br = (float) Math.hypot(bx, by);
                                if (br > 1f) return;
                                tex.sample(u, v, false, tmp);
                                float rim = smoothstep(0.62f, 0.94f, br) * (1f - smoothstep(0.94f, 1.0f, br));
                                float spec1 = (float) Math.exp(-((bx + 0.35f)*(bx + 0.35f) + (by + 0.35f)*(by + 0.35f)) * 18f);
                                float spec2 = (float) Math.exp(-((bx - 0.32f)*(bx - 0.32f) + (by - 0.35f)*(by - 0.35f)) * 14f) * 0.45f;
                                out[0] = tmp[0] * 0.45f + rim * 0.72f + spec1 * 1.15f + spec2 * 0.65f;
                                out[1] = tmp[1] * 0.55f + rim * 0.90f + spec1 * 1.15f + spec2 * 0.85f;
                                out[2] = tmp[2] * 0.62f + rim * 0.96f + spec1 * 1.15f + spec2 * 0.95f;
                                out[3] = clamp((tmp[3] * 0.45f + rim * 0.72f + spec1 * 0.92f + spec2 * 0.45f) * tint[3] * fDynA, 0f, 1f);
                            });
                        }
                        for (int i = 0; i < W * H; i++) {
                            float r = Math.max(0f, scene.r[i]), g = Math.max(0f, scene.g[i]), b = Math.max(0f, scene.b[i]);
                            float maxC = Math.max(r, Math.max(g, b));
                            if (maxC > 0.82f) {
                                float targetMax = 0.82f + 0.18f * (float) Math.tanh((maxC - 0.82f) * 1.45f);
                                float scale = targetMax / maxC;
                                float hot = clamp((maxC - 1.55f) * 0.25f, 0f, 0.35f);
                                scene.r[i] = clamp(mix(r * scale, targetMax, hot), 0f, 1f);
                                scene.g[i] = clamp(mix(g * scale, targetMax, hot), 0f, 1f);
                                scene.b[i] = clamp(mix(b * scale, targetMax, hot), 0f, 1f);
                            } else {
                                scene.r[i] = r; scene.g[i] = g; scene.b[i] = b;
                            }
                        }
                    }
                }

                String fName = String.format(Locale.US, "frame_t%03d.png", (int) tSec);
                Path fPath = outDir.resolve(fName);
                String sha = WaterWorldSoftwareRenderer.savePng(scene, fPath);
                double ms = (System.nanoTime() - fStart) / 1e6;
                frameNames.add(fName);
                frameShas.add(sha);
                frameRenderMs.add(ms);
                renderedImgs.add(ImageIO.read(fPath.toFile()));
                System.out.printf(Locale.US, "FRAME %s rendered in %.1fms sha=%s%n", fName, ms, sha);
            }
        }

        // 1. Exact T=0 Static Regression vs Work 2 GPU_FINAL.png
        double t0VsWork2Mae = -1.0;
        String work2Sha = "MISSING";
        if (Files.isRegularFile(work2RefPng)) {
            work2Sha = WaterWorldSoftwareRenderer.sha256(Files.readAllBytes(work2RefPng));
            t0VsWork2Mae = computeImageMae(renderedImgs.get(0), ImageIO.read(work2RefPng.toFile()));
        }

        // 2. Pairwise motion deltas across T=0..12s
        Map<String, Double> pairwiseMae = new LinkedHashMap<>();
        for (int i = 0; i < renderedImgs.size() - 1; i++) {
            String key = frameNames.get(i) + "_to_" + frameNames.get(i + 1);
            pairwiseMae.put(key, computeImageMae(renderedImgs.get(i), renderedImgs.get(i + 1)));
        }
        pairwiseMae.put("frame_t000.png_to_frame_t008.png", computeImageMae(renderedImgs.get(0), renderedImgs.get(4)));
        pairwiseMae.put("frame_t000.png_to_frame_t012.png", computeImageMae(renderedImgs.get(0), renderedImgs.get(5)));

        // 3. Build 2x3 Visual Inspection Contact Sheet (1881x1254)
        int tile = 627;
        BufferedImage sheet = new BufferedImage(tile * 3, tile * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = sheet.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(new Color(4, 16, 22));
        g2.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g2.setFont(new Font("SansSerif", Font.BOLD, 24));
        for (int i = 0; i < renderedImgs.size(); i++) {
            int col = i % 3, row = i / 3;
            int ox = col * tile, oy = row * tile;
            g2.drawImage(renderedImgs.get(i), ox, oy, tile, tile, null);
            g2.setColor(new Color(4, 18, 26, 200));
            g2.fillRoundRect(ox + 16, oy + 14, 240, 38, 12, 12);
            g2.setColor(new Color(145, 242, 255));
            String label = String.format(Locale.US, "T = %.0fs (%s)", CANONICAL_TIMESTAMPS[i], i == 0 ? "STATIC REF" : "DYNAMIC");
            g2.drawString(label, ox + 28, oy + 41);
        }
        g2.dispose();
        Path sheetPath = outDir.resolve("WORK3_DYNAMIC_CONTACT_SHEET.png");
        ImageIO.write(sheet, "png", sheetPath.toFile());
        String sheetSha = WaterWorldSoftwareRenderer.sha256(Files.readAllBytes(sheetPath));

        // 4. 10s / 30s / 60s continuous timeline stability simulation (300 / 900 / 1800 frames @ 30Hz)
        int[] frameCounts = {300, 900, 1800};
        StringBuilder stabJson = new StringBuilder("{\n  \"schemaVersion\": \"WATER_WORLD_V21_WORK3_STABILITY_V1\",\n  \"runs\": [\n");
        for (int rIdx = 0; rIdx < frameCounts.length; rIdx++) {
            int nFrames = frameCounts[rIdx];
            long s0 = System.nanoTime();
            int nanCount = 0;
            double maxFishStep = 0.0;
            double prevFx = 0.0, prevFy = 0.0;
            for (int f = 0; f <= nFrames; f++) {
                double ts = f / 30.0;
                DynamicSnapshot sp = evaluateSnapshot(profile, ts);
                FishState f1 = sp.fish.get("WW_FISH_01");
                if (!Float.isFinite(f1.offsetX) || !Float.isFinite(f1.offsetY)) nanCount++;
                if (f > 0) {
                    double step = Math.hypot(f1.offsetX - prevFx, f1.offsetY - prevFy);
                    if (step > maxFishStep) maxFishStep = step;
                }
                prevFx = f1.offsetX; prevFy = f1.offsetY;
            }
            double evalMs = (System.nanoTime() - s0) / 1e6;
            stabJson.append(String.format(Locale.US,
                "    {\"durationSec\": %d, \"framesEvaluated\": %d, \"evalTotalMs\": %.2f, \"meanEvalUsPerFrame\": %.2f, \"nanOrInfCount\": %d, \"maxSingleStepFishPx\": %.3f, \"passed\": %s}%s\n",
                nFrames / 30, nFrames, evalMs, (evalMs * 1000.0) / nFrames, nanCount, maxFishStep,
                (nanCount == 0 && maxFishStep < 15.0) ? "true" : "false",
                rIdx + 1 < frameCounts.length ? "," : ""
            ));
        }
        stabJson.append("  ]\n}\n");
        Files.writeString(outDir.resolve("stability_10s_30s_60s.json"), stabJson.toString(), StandardCharsets.UTF_8);

        // 5. Write dynamic_render_telemetry.json + motion_timeline.json + manifest
        StringBuilder dt = new StringBuilder("{\n");
        dt.append("  \"schemaVersion\": \"WATER_WORLD_V21_WORK3_SOFTWARE_DYNAMIC_TELEMETRY_V1\",\n");
        dt.append("  \"classification\": \"SOFTWARE_DYNAMIC_REFERENCE_CPU_1TO1\",\n");
        dt.append("  \"hardware_gpu_verified\": false,\n");
        dt.append("  \"WORK3_HARDWARE_DYNAMIC_VERIFIED\": \"NOT_RUN\",\n");
        dt.append("  \"provenance\": \"WORK3_AUTHORED_RUNTIME_HYPOTHESIS\",\n");
        dt.append("  \"waterWorldMotionValuesValidated\": false,\n");
        dt.append("  \"coreVolumeSliceCount\": 32,\n");
        dt.append("  \"coreVolumeSliceSemantics\": \"SPATIAL_Z_SLICE_NOT_TIME_FRAME\",\n");
        dt.append("  \"t0VsWork2GpuFinalRgbMae\": ").append(String.format(Locale.US, "%.6f", t0VsWork2Mae)).append(",\n");
        dt.append("  \"t0ExactMatchWithWork2\": ").append(t0VsWork2Mae == 0.0).append(",\n");
        dt.append("  \"work2ReferenceSha256\": \"").append(work2Sha).append("\",\n");
        dt.append("  \"contactSheetSha256\": \"").append(sheetSha).append("\",\n");
        dt.append("  \"totalWallTimeMs\": ").append(String.format(Locale.US, "%.2f", (System.nanoTime() - tStart) / 1e6)).append(",\n");
        dt.append("  \"frames\": [\n");
        for (int i = 0; i < frameNames.size(); i++) {
            dt.append(String.format(Locale.US,
                "    {\"timeSec\": %.1f, \"file\": \"%s\", \"sha256\": \"%s\", \"renderMs\": %.2f}%s\n",
                CANONICAL_TIMESTAMPS[i], frameNames.get(i), frameShas.get(i), frameRenderMs.get(i),
                i + 1 < frameNames.size() ? "," : ""
            ));
        }
        dt.append("  ],\n  \"pairwiseRgbMae\": {\n");
        int pIdx = 0;
        for (Map.Entry<String, Double> e : pairwiseMae.entrySet()) {
            dt.append(String.format(Locale.US, "    \"%s\": %.4f%s\n", e.getKey(), e.getValue(), (++pIdx < pairwiseMae.size() ? "," : "")));
        }
        dt.append("  }\n}\n");
        Files.writeString(outDir.resolve("dynamic_render_telemetry.json"), dt.toString(), StandardCharsets.UTF_8);

        System.out.printf(Locale.US,
            "WORK3_DYNAMIC_VERIFY_OK t0VsWork2MAE=%.6f t0ToT8MAE=%.4f t0ToT12MAE=%.4f sheetSha=%s%n",
            t0VsWork2Mae, pairwiseMae.get("frame_t000.png_to_frame_t008.png"),
            pairwiseMae.get("frame_t000.png_to_frame_t012.png"), sheetSha);
    }

    static void renderDynamicBokeh(Map<String, Object> orb, WaterWorldSoftwareRenderer.Target scene, float du, float dv) {
        float cx = (WaterWorldSoftwareRenderer.flt(orb.get("x"), 0.5f) + du) * W;
        float cy = (WaterWorldSoftwareRenderer.flt(orb.get("y"), 0.5f) + dv) * H;
        float rad = WaterWorldSoftwareRenderer.flt(orb.get("r"), 0.02f) * W;
        float[] col = WaterWorldSoftwareRenderer.fltArr(orb.get("color"));
        float alpha = WaterWorldSoftwareRenderer.flt(orb.get("alpha"), 0.35f);
        float soft = WaterWorldSoftwareRenderer.flt(orb.get("softness"), 0.6f);
        int x0 = Math.max(0, (int) Math.floor(cx - rad * 1.4f));
        int x1 = Math.min(W - 1, (int) Math.ceil(cx + rad * 1.4f));
        int y0 = Math.max(0, (int) Math.floor(cy - rad * 1.4f));
        int y1 = Math.min(H - 1, (int) Math.ceil(cy + rad * 1.4f));
        boolean isStar = soft > 0.80f;
        for (int py = y0; py <= y1; py++) {
            for (int px = x0; px <= x1; px++) {
                float d = (float) Math.hypot(px - cx, py - cy) / rad;
                if (d > 1.35f) continue;
                float falloff = isStar
                        ? ((float) Math.exp(-d * d * 4.5f) * 0.55f + (float) Math.exp(-d * d * 24.0f) * 0.95f)
                        : (1f - smoothstep(1f - soft, 1.05f, d));
                float a = falloff * alpha;
                int idx = py * W + px;
                scene.r[idx] += col[0] * a; scene.g[idx] += col[1] * a; scene.b[idx] += col[2] * a;
            }
        }
    }
}
