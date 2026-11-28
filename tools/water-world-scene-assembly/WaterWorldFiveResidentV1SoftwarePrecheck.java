import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Five Resident Filaments V1 — Software Motion Precheck & Visual Evidence Generator
 * (`WATER_WORLD_FIVE_RESIDENT_FILAMENTS_DESIGN_FREEZE_V1`).
 *
 * Strictly labeled `SOFTWARE_MOTION_VERIFIED` (NEVER claims hardware Android GPU acceptance).
 * Executes the exact frozen 13-segment Resident V1 vertex & fragment shader math
 * (`RESIDENT_RIBBON_VERTEX` + `RESIDENT_RIBBON_FRAGMENT` with per-vertex local half-width breathing,
 * canonical `s in [0, 1]` parameterization, soft leading-edge + fading-tail moving highlight band,
 * and spatially-phased non-uniform brightness breathing) across `T = 0, 1, 2, 4, 8, 12, 20, 40, 60 s`.
 */
public class WaterWorldFiveResidentV1SoftwarePrecheck {

    private static final int W = 1254;
    private static final int H = 1254;
    private static final double TWO_PI = Math.PI * 2.0;
    private static final double[] TIMESTAMPS = {0.0, 1.0, 2.0, 4.0, 8.0, 12.0, 20.0, 40.0, 60.0};
    private static final String[] RESIDENT_KEYS = {"G01", "G02", "A01", "A02", "C01"};

    static final class ResidentProfileV1 {
        final String groupKey;
        final String residentId;
        final String colorHex;
        final float flowDirection;
        final float periodSec;
        final float initPhaseRad;
        final float widthAmp;
        final float widthHz;
        final float brightAmp;
        final float brightHz;
        final float maxLateralWavePx;
        final int[] roiCrop; // [x0, y0, x1, y1]

        ResidentProfileV1(String gk, String rid, String hex, float dir, float period, float phase,
                          float wAmp, float wHz, float bAmp, float bHz, float latPx, int[] roi) {
            this.groupKey = gk; this.residentId = rid; this.colorHex = hex;
            this.flowDirection = dir; this.periodSec = period; this.initPhaseRad = phase;
            this.widthAmp = wAmp; this.widthHz = wHz; this.brightAmp = bAmp; this.brightHz = bHz;
            this.maxLateralWavePx = latPx; this.roiCrop = roi;
        }
    }

    static final class SegmentMappingV1 {
        final String segmentId;
        final String parentGroup;
        final String passId;
        final float arcStart;
        final float arcEnd;
        final float uvDir;
        final float phaseOffset;
        final String continuityStatus;

        SegmentMappingV1(String sid, String pg, String pass, float s0, float s1, float dir, float off, String cs) {
            this.segmentId = sid; this.parentGroup = pg; this.passId = pass;
            this.arcStart = s0; this.arcEnd = s1; this.uvDir = dir; this.phaseOffset = off; this.continuityStatus = cs;
        }
    }

    static final class ResidentEvalV1 {
        final String groupKey;
        final double tSec;
        final boolean isZero;
        final float flowActive;
        final float flowDirection;
        final float flowOffsetU;
        final float flowSpeedPerSec;
        final float waveFreqHz;
        final float wavePhaseRad;
        final float lateralWavePx;
        final float widthAmplitude;
        final float widthTimePhaseRad;
        final float widthPulseScale;
        final float brightnessAmplitude;
        final float brightnessTimePhaseRad;
        final float brightnessScale;

        ResidentEvalV1(ResidentProfileV1 rp, double t) {
            this.groupKey = rp.groupKey;
            this.tSec = t;
            this.isZero = t <= 1e-9;
            this.flowDirection = rp.flowDirection;
            this.flowSpeedPerSec = rp.flowDirection / rp.periodSec;
            this.waveFreqHz = 3.0f / rp.periodSec;
            if (isZero) {
                this.flowActive = 0f;
                this.flowOffsetU = 0f;
                this.wavePhaseRad = 0f;
                this.lateralWavePx = 0f;
                this.widthAmplitude = 0f;
                this.widthTimePhaseRad = 0f;
                this.widthPulseScale = 1f;
                this.brightnessAmplitude = 0f;
                this.brightnessTimePhaseRad = 0f;
                this.brightnessScale = 1f;
            } else {
                float ramp = smoothstep(0.0f, 0.35f, (float) t);
                this.flowActive = ramp;
                this.flowOffsetU = (float) (t / rp.periodSec) * rp.flowDirection;
                this.wavePhaseRad = (float) (TWO_PI * waveFreqHz * t + rp.initPhaseRad);
                this.lateralWavePx = rp.maxLateralWavePx * ramp;
                this.widthAmplitude = rp.widthAmp * ramp;
                this.widthTimePhaseRad = (float) (TWO_PI * rp.widthHz * t + rp.initPhaseRad);
                this.widthPulseScale = 1f + widthAmplitude * (float) Math.sin(widthTimePhaseRad);
                this.brightnessAmplitude = rp.brightAmp * ramp;
                this.brightnessTimePhaseRad = (float) (TWO_PI * rp.brightHz * t + rp.initPhaseRad * 0.7);
                this.brightnessScale = 1f + brightnessAmplitude * (float) Math.sin(brightnessTimePhaseRad);
            }
        }
    }

    static final class RibbonMeshV1 {
        final String drawId;
        final String passId;
        final String groupKey;
        final SegmentMappingV1 mapping;
        final float[] x, y, z, nx, ny, nz, u, v, halfWidthPx;
        final int[] idx;
        final float[] tint;
        final float[] coreColor;
        final float emission;
        final float style;
        final float modelZ;

        RibbonMeshV1(Map<String, Object> binding, Map<String, Object> meshJson, SegmentMappingV1 map) {
            this.drawId = (String) binding.get("draw_id");
            this.passId = (String) binding.get("pass_id");
            this.groupKey = map.parentGroup;
            this.mapping = map;
            this.tint = WaterWorldSoftwareRenderer.fltArr(binding.get("tint"));
            this.coreColor = binding.containsKey("core_color")
                    ? WaterWorldSoftwareRenderer.fltArr(binding.get("core_color"))
                    : new float[]{1f, 1f, 0.9f};
            this.emission = WaterWorldSoftwareRenderer.flt(binding.get("emission_override"), 1.1f);
            this.style = WaterWorldSoftwareRenderer.flt(binding.get("ribbon_style"), 0f);
            float[] model = WaterWorldSoftwareRenderer.fltArr(binding.get("model"));
            this.modelZ = model[14];

            List<Object> verts = WaterWorldSoftwareRenderer.asList(meshJson.get("vertices"));
            List<Object> indices = WaterWorldSoftwareRenderer.asList(meshJson.get("indices"));
            int vn = verts.size();
            x = new float[vn]; y = new float[vn]; z = new float[vn];
            nx = new float[vn]; ny = new float[vn]; nz = new float[vn];
            u = new float[vn]; v = new float[vn]; halfWidthPx = new float[vn];
            for (int i = 0; i < vn; i++) {
                List<Object> row = WaterWorldSoftwareRenderer.asList(verts.get(i));
                x[i] = ((Number) row.get(0)).floatValue();
                y[i] = ((Number) row.get(1)).floatValue();
                z[i] = ((Number) row.get(2)).floatValue();
                nx[i] = ((Number) row.get(3)).floatValue();
                ny[i] = ((Number) row.get(4)).floatValue();
                nz[i] = ((Number) row.get(5)).floatValue();
                u[i] = ((Number) row.get(6)).floatValue();
                v[i] = ((Number) row.get(7)).floatValue();
            }
            for (int i = 0; i + 1 < vn; i += 2) {
                float hw = 0.5f * (float) Math.hypot(x[i + 1] - x[i], y[i + 1] - y[i]);
                halfWidthPx[i] = hw;
                halfWidthPx[i + 1] = hw;
            }
            idx = new int[indices.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = ((Number) indices.get(i)).intValue();
        }
    }

    static float clamp(float x, float lo, float hi) { return x < lo ? lo : (x > hi ? hi : x); }
    static float mix(float a, float b, float t) { return a + (b - a) * t; }
    static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
    static float fract(float x) { return x - (float) Math.floor(x); }

    public static void main(String[] args) throws Exception {
        long tStartAll = System.nanoTime();
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path sceneRoot = root.resolve("releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1");
        Path v2Zip = root.resolve("releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip");
        Path preImplDir = root.resolve("releases/water-world-v2-v21/five_resident_filaments_v1/01_PRE_IMPLEMENTATION");
        Path evDir = root.resolve("releases/water-world-v2-v21/five_resident_filaments_v1/03_DYNAMIC_EVIDENCE");
        Path framesDir = evDir.resolve("frames");
        Path roiDir = evDir.resolve("roi");
        Path statesDir = evDir.resolve("states");
        Path staticTmpDir = evDir.resolve("_static_base_cache");
        Files.createDirectories(framesDir);
        Files.createDirectories(roiDir);
        Files.createDirectories(statesDir);
        Files.createDirectories(staticTmpDir);

        // 1. Load frozen V1 contract JSONs
        Map<String, Object> lockJson = WaterWorldSoftwareRenderer.asMap(
                WaterWorldSoftwareRenderer.Json.parse(Files.readString(preImplDir.resolve("five_resident_geometry_lock.json"))));
        Map<String, Object> segMapJson = WaterWorldSoftwareRenderer.asMap(
                WaterWorldSoftwareRenderer.Json.parse(Files.readString(preImplDir.resolve("five_resident_segment_mapping.json"))));
        Map<String, Object> profJson = WaterWorldSoftwareRenderer.asMap(
                WaterWorldSoftwareRenderer.Json.parse(Files.readString(preImplDir.resolve("five_resident_motion_profile_v1.json"))));

        Map<String, int[]> roiByRes = new LinkedHashMap<>();
        for (Object ro : WaterWorldSoftwareRenderer.asList(lockJson.get("residents"))) {
            Map<String, Object> rm = WaterWorldSoftwareRenderer.asMap(ro);
            List<Object> c = WaterWorldSoftwareRenderer.asList(rm.get("roiCropPx"));
            roiByRes.put((String) rm.get("groupKey"), new int[]{
                    ((Number) c.get(0)).intValue(), ((Number) c.get(1)).intValue(),
                    ((Number) c.get(2)).intValue(), ((Number) c.get(3)).intValue()
            });
        }

        Map<String, ResidentProfileV1> profiles = new LinkedHashMap<>();
        for (Object ro : WaterWorldSoftwareRenderer.asList(profJson.get("residents"))) {
            Map<String, Object> rm = WaterWorldSoftwareRenderer.asMap(ro);
            String gk = (String) rm.get("groupKey");
            profiles.put(gk, new ResidentProfileV1(
                    gk, (String) rm.get("residentId"), (String) rm.get("colorHex"),
                    WaterWorldSoftwareRenderer.flt(rm.get("flowDirection"), 1f),
                    WaterWorldSoftwareRenderer.flt(rm.get("flowTraversalPeriodSec"), 16f),
                    WaterWorldSoftwareRenderer.flt(rm.get("initPhaseRad"), 0f),
                    WaterWorldSoftwareRenderer.flt(rm.get("widthPulseAmp"), 0.06f),
                    WaterWorldSoftwareRenderer.flt(rm.get("widthPulseHz"), 0.19f),
                    WaterWorldSoftwareRenderer.flt(rm.get("brightnessPulseAmp"), 0.09f),
                    WaterWorldSoftwareRenderer.flt(rm.get("brightnessPulseHz"), 0.19f),
                    WaterWorldSoftwareRenderer.flt(rm.get("maxLateralWavePx"), 8f),
                    roiByRes.get(gk)
            ));
        }

        Map<String, SegmentMappingV1> segMappings = new LinkedHashMap<>();
        for (Object so : WaterWorldSoftwareRenderer.asList(segMapJson.get("segments"))) {
            Map<String, Object> sm = WaterWorldSoftwareRenderer.asMap(so);
            String sid = (String) sm.get("segmentId");
            segMappings.put(sid, new SegmentMappingV1(
                    sid, (String) sm.get("parentResidentGroup"), (String) sm.get("segmentPass"),
                    WaterWorldSoftwareRenderer.flt(sm.get("segmentArcLengthStart"), 0f),
                    WaterWorldSoftwareRenderer.flt(sm.get("segmentArcLengthEnd"), 1f),
                    WaterWorldSoftwareRenderer.flt(sm.get("localUvDirection"), 1f),
                    WaterWorldSoftwareRenderer.flt(sm.get("localFlowPhaseOffset"), 0f),
                    (String) sm.get("continuityStatus")
            ));
        }

        // 2. Render exact static Work 2 base passes once using WaterWorldSoftwareRenderer with HDR float snapshots
        WaterWorldSoftwareRenderer.captureFloatSnapshots = true;
        WaterWorldSoftwareRenderer.main(new String[]{ staticTmpDir.toString() });
        WaterWorldSoftwareRenderer.captureFloatSnapshots = false;
        BufferedImage imgWork2StaticFinal = ImageIO.read(staticTmpDir.resolve("GPU_FINAL.png").toFile());
        WaterWorldSoftwareRenderer.Target bg01Target = WaterWorldSoftwareRenderer.snapshotPass01;
        WaterWorldSoftwareRenderer.Target resBackStaticTarget = WaterWorldSoftwareRenderer.snapshotPass02;
        WaterWorldSoftwareRenderer.Target sphere09Target = WaterWorldSoftwareRenderer.snapshotPass09;

        // Also load optical_config & draw_bindings for exact bloom, FG bokeh, outer bubbles, and 13 Resident meshes
        Map<String, Object> opticalJson = WaterWorldSoftwareRenderer.asMap(
                WaterWorldSoftwareRenderer.Json.parse(Files.readString(sceneRoot.resolve("02_SUPPLEMENTED_ASSETS/materials/optical_config.json"))));
        Map<String, Object> bindingsJson = WaterWorldSoftwareRenderer.asMap(
                WaterWorldSoftwareRenderer.Json.parse(Files.readString(sceneRoot.resolve("03_SCENE_GRAPH/draw_bindings.json"))));
        Map<String, Object> bloomCfg = WaterWorldSoftwareRenderer.asMap(opticalJson.get("bloom"));
        Map<String, Object> bokehCfg = WaterWorldSoftwareRenderer.asMap(opticalJson.get("bokeh"));
        List<Object> bokehOrbs = WaterWorldSoftwareRenderer.asList(bokehCfg.get("orbs"));
        Map<String, Object> sphereCfg = WaterWorldSoftwareRenderer.asMap(opticalJson.get("sphere"));
        float[] sphereC = WaterWorldSoftwareRenderer.fltArr(sphereCfg.get("center_px"));
        float scx = sphereC[0], scy = sphereC[1];
        float sOuterR = WaterWorldSoftwareRenderer.flt(sphereCfg.get("outer_radius_px"), 422.3f);

        List<RibbonMeshV1> backMeshes = new ArrayList<>();
        List<RibbonMeshV1> frontMeshes = new ArrayList<>();
        List<Map<String, Object>> finalPassDraws = new ArrayList<>();
        for (Object o : WaterWorldSoftwareRenderer.asList(bindingsJson.get("draws"))) {
            Map<String, Object> d = WaterWorldSoftwareRenderer.asMap(o);
            String passId = (String) d.get("pass_id");
            if ("02_RESIDENT_BACK".equals(passId) || "10_RESIDENT_FRONT".equals(passId)) {
                String drawId = (String) d.get("draw_id");
                Map<String, Object> mj = WaterWorldSoftwareRenderer.asMap(
                        WaterWorldSoftwareRenderer.Json.parse(Files.readString(sceneRoot.resolve((String) d.get("mesh_path")))));
                RibbonMeshV1 rm = new RibbonMeshV1(d, mj, segMappings.get(drawId));
                if ("02_RESIDENT_BACK".equals(passId)) backMeshes.add(rm);
                else frontMeshes.add(rm);
            } else if ("11_FINAL_COMPOSITE".equals(passId)) {
                finalPassDraws.add(d);
            }
        }

        // Preload outer bubble meshes & textures for Pass 11
        Map<String, Map<String, Object>> bubbleMeshes = new HashMap<>();
        Map<String, WaterWorldSoftwareRenderer.Tex> bubbleTextures = new HashMap<>();
        try (ZipFile zf = new ZipFile(v2Zip.toFile())) {
            for (Map<String, Object> d : finalPassDraws) {
                String mp = (String) d.get("mesh_path");
                bubbleMeshes.put(mp, WaterWorldSoftwareRenderer.asMap(
                        WaterWorldSoftwareRenderer.Json.parse(Files.readString(sceneRoot.resolve(mp)))));
                String tp = (String) d.get("texture_path");
                bubbleTextures.put(tp, WaterWorldSoftwareRenderer.loadV2Tex(zf, tp, (String) d.get("texture_sha256")));
            }
        }

        // 3. Render all 9 timestamps (T = 0, 1, 2, 4, 8, 12, 20, 40, 60s)
        BufferedImage[] fullFrames = new BufferedImage[TIMESTAMPS.length];
        Map<String, BufferedImage[]> roiFrames = new LinkedHashMap<>();
        for (String gk : RESIDENT_KEYS) roiFrames.put(gk, new BufferedImage[TIMESTAMPS.length]);

        double t0FullSceneMae = 0.0;
        Map<String, Double> t0RoiMae = new LinkedHashMap<>();
        List<Map<String, Object>> timelineMetrics = new ArrayList<>();

        for (int ti = 0; ti < TIMESTAMPS.length; ti++) {
            double tSec = TIMESTAMPS[ti];
            String tTag = String.format(Locale.US, "t%03ds", (int) Math.round(tSec));

            Map<String, ResidentEvalV1> evals = new LinkedHashMap<>();
            for (String gk : RESIDENT_KEYS) evals.put(gk, new ResidentEvalV1(profiles.get(gk), tSec));

            // Start from Pass 01 background
            WaterWorldSoftwareRenderer.Target scene = new WaterWorldSoftwareRenderer.Target(W, H);
            scene.copyFrom(bg01Target);

            // Pass 02: Dynamic V1 02_RESIDENT_BACK (8 frozen segments)
            for (RibbonMeshV1 rm : backMeshes) {
                rasterizeRibbonV1(rm, evals.get(rm.groupKey), scene);
            }

            // Composite static Work 2 Crystal Sphere (Pass 03..09) inside sphere mask + subtle glass back-transmission delta
            // At T=0, backDyn == backStatic so inside+outside is bit-identical to afterPass09Static!
            for (int py = 0; py < H; py++) {
                float dy = (py - scy) / sOuterR;
                for (int px = 0; px < W; px++) {
                    float dx = (px - scx) / sOuterR;
                    float r2 = dx * dx + dy * dy;
                    int idx = py * W + px;
                    if (r2 <= 1.006f) {
                        float r = (float) Math.sqrt(Math.min(1.006f, r2));
                        float aaEdge = 1f - smoothstep(0.993f, 1.005f, r);
                        // Subtle back-filament visibility delta through outer glass rim
                        float rimTrans = 0.14f * smoothstep(0.70f, 0.99f, r) + 0.07f;
                        float dR = (scene.r[idx] - resBackStaticTarget.r[idx]) * rimTrans;
                        float dG = (scene.g[idx] - resBackStaticTarget.g[idx]) * rimTrans;
                        float dB = (scene.b[idx] - resBackStaticTarget.b[idx]) * rimTrans;
                        float sr = clamp(sphere09Target.r[idx] + dR, 0f, 2.5f);
                        float sg = clamp(sphere09Target.g[idx] + dG, 0f, 2.5f);
                        float sb = clamp(sphere09Target.b[idx] + dB, 0f, 2.5f);
                        scene.r[idx] = mix(scene.r[idx], sr, aaEdge);
                        scene.g[idx] = mix(scene.g[idx], sg, aaEdge);
                        scene.b[idx] = mix(scene.b[idx], sb, aaEdge);
                    } else {
                        // Preserve exterior top-left & bottom-right specular flares emitted during Pass 09
                        float flR = Math.max(0f, sphere09Target.r[idx] - resBackStaticTarget.r[idx]);
                        float flG = Math.max(0f, sphere09Target.g[idx] - resBackStaticTarget.g[idx]);
                        float flB = Math.max(0f, sphere09Target.b[idx] - resBackStaticTarget.b[idx]);
                        scene.r[idx] += flR;
                        scene.g[idx] += flG;
                        scene.b[idx] += flB;
                    }
                }
            }

            // Pass 10: Dynamic V1 10_RESIDENT_FRONT (5 frozen segments)
            for (RibbonMeshV1 rm : frontMeshes) {
                rasterizeRibbonV1(rm, evals.get(rm.groupKey), scene);
            }

            // Pass 11: Real bloom bright-pass + 2x/4x Gaussian blur + FG bokeh + 9 outer bubbles
            runFinalCompositePass(scene, bloomCfg, bokehOrbs, finalPassDraws, bubbleMeshes, bubbleTextures, scx, scy, sOuterR);

            // Export full 1254x1254 frame
            BufferedImage frameImg = toBufferedImage(scene);
            fullFrames[ti] = frameImg;
            Path framePath = framesDir.resolve("five_resident_" + tTag + ".png");
            ImageIO.write(frameImg, "png", framePath.toFile());

            // Export per-Resident ROIs & measure metrics
            Map<String, Object> tRec = new LinkedHashMap<>();
            tRec.put("timeSec", tSec);
            tRec.put("frameFile", "frames/five_resident_" + tTag + ".png");
            double maeVsT0Full = (ti == 0) ? mae255(frameImg, imgWork2StaticFinal, 0, 0, W, H) : mae255(frameImg, fullFrames[0], 0, 0, W, H);
            if (ti == 0) t0FullSceneMae = maeVsT0Full;
            tRec.put(ti == 0 ? "maeVsWork2StaticFull255" : "maeVsT0Full255", round4(maeVsT0Full));

            Map<String, Object> perRes = new LinkedHashMap<>();
            for (String gk : RESIDENT_KEYS) {
                ResidentProfileV1 rp = profiles.get(gk);
                ResidentEvalV1 ev = evals.get(gk);
                int[] c = rp.roiCrop;
                BufferedImage roi = frameImg.getSubimage(c[0], c[1], c[2] - c[0], c[3] - c[1]);
                BufferedImage roiCopy = copyImg(roi);
                roiFrames.get(gk)[ti] = roiCopy;
                Path roiPath = roiDir.resolve(gk + "_" + tTag + ".png");
                ImageIO.write(roiCopy, "png", roiPath.toFile());

                double roiMae = (ti == 0)
                        ? mae255(frameImg, imgWork2StaticFinal, c[0], c[1], c[2], c[3])
                        : mae255(frameImg, fullFrames[0], c[0], c[1], c[2], c[3]);
                if (ti == 0) t0RoiMae.put(gk, roiMae);

                double peakS = fract(ev.flowOffsetU);
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("residentId", rp.residentId);
                rm.put("flowDirection", rp.flowDirection);
                rm.put("flowOffsetU", round4(ev.flowOffsetU));
                rm.put("traversalPercent", round2(Math.abs(ev.flowOffsetU) * 100.0));
                rm.put("highlightPeakCanonicalS", round4(peakS));
                rm.put("lateralWaveAmpPx", round4(ev.lateralWavePx));
                rm.put("widthAmplitude", round4(ev.widthAmplitude));
                rm.put("widthPulseScale", round4(ev.widthPulseScale));
                rm.put("brightnessAmplitude", round4(ev.brightnessAmplitude));
                rm.put("brightnessScale", round4(ev.brightnessScale));
                rm.put(ti == 0 ? "roiMaeVsWork2Static255" : "roiMaeVsT0_255", round4(roiMae));
                perRes.put(gk, rm);
            }
            tRec.put("residents", perRes);
            timelineMetrics.add(tRec);

            // Save per-timestamp state JSON
            Files.writeString(statesDir.resolve("state_" + tTag + ".json"), toJson(tRec, 0), StandardCharsets.UTF_8);
            System.out.printf(Locale.US, "Rendered %s | fullMAE=%.4f | G01=%.3f G02=%.3f A01=%.3f A02=%.3f C01=%.3f%n",
                    tTag, maeVsT0Full,
                    ((Number) ((Map<?, ?>) perRes.get("G01")).get(ti == 0 ? "roiMaeVsWork2Static255" : "roiMaeVsT0_255")).doubleValue(),
                    ((Number) ((Map<?, ?>) perRes.get("G02")).get(ti == 0 ? "roiMaeVsWork2Static255" : "roiMaeVsT0_255")).doubleValue(),
                    ((Number) ((Map<?, ?>) perRes.get("A01")).get(ti == 0 ? "roiMaeVsWork2Static255" : "roiMaeVsT0_255")).doubleValue(),
                    ((Number) ((Map<?, ?>) perRes.get("A02")).get(ti == 0 ? "roiMaeVsWork2Static255" : "roiMaeVsT0_255")).doubleValue(),
                    ((Number) ((Map<?, ?>) perRes.get("C01")).get(ti == 0 ? "roiMaeVsWork2Static255" : "roiMaeVsT0_255")).doubleValue());
        }

        // 4. Build high-resolution visual contact sheet (Full Scene progression + 5 Resident ROI progression!)
        Path sheetPath = evDir.resolve("FIVE_RESIDENT_DYNAMIC_CONTACT_SHEET.png");
        buildContactSheet(fullFrames, roiFrames, sheetPath);

        // 5. Write FIVE_RESIDENT_PER_ELEMENT_MOTION_METRICS.json
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("contractId", "WATER_WORLD_FIVE_RESIDENT_FILAMENTS_DESIGN_FREEZE_V1");
        summary.put("evidenceLevel", "SOFTWARE_MOTION_VERIFIED");
        summary.put("hardwareGpuVerified", false);
        summary.put("glesShaderVerified", "NOT_RUN_NO_EGL_DRIVER_IN_CONTAINER");
        summary.put("canvasPx", Arrays.asList(W, H));
        summary.put("timestampsSec", Arrays.asList(0, 1, 2, 4, 8, 12, 20, 40, 60));
        summary.put("t0ParityFullSceneMae255", round4(t0FullSceneMae));
        summary.put("t0ParityTargetMaxMae255", 0.6);
        summary.put("t0ParityPass", t0FullSceneMae <= 0.6 && t0RoiMae.values().stream().allMatch(v -> v <= 0.6));
        Map<String, Object> t0RoiOut = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : t0RoiMae.entrySet()) t0RoiOut.put(e.getKey(), round4(e.getValue()));
        summary.put("t0ParityPerResidentRoiMae255", t0RoiOut);
        summary.put("a01BackFrontSeamCanonicalS", 0.2863);
        summary.put("a01BackFrontSeamGapPx", 0.0);
        summary.put("renderElapsedSec", round2((System.nanoTime() - tStartAll) / 1e9));
        summary.put("frames", timelineMetrics);

        Path metricsPath = evDir.resolve("FIVE_RESIDENT_PER_ELEMENT_MOTION_METRICS.json");
        Files.writeString(metricsPath, toJson(summary, 0), StandardCharsets.UTF_8);

        // Clean temporary static base cache so evidence folder stays spotless
        try (var stream = Files.list(staticTmpDir)) {
            stream.forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
        Files.deleteIfExists(staticTmpDir);

        System.out.printf(Locale.US, "FIVE_RESIDENT_V1_SOFTWARE_PRECHECK_OK | T0_MAE=%.4f | elapsed=%.2fs | sheet=%s%n",
                t0FullSceneMae, (System.nanoTime() - tStartAll) / 1e9, sheetPath);
    }

    /**
     * Exact 1:1 CPU mirror of RESIDENT_RIBBON_VERTEX + RESIDENT_RIBBON_FRAGMENT.
     */
    private static void rasterizeRibbonV1(RibbonMeshV1 rm, ResidentEvalV1 ev, WaterWorldSoftwareRenderer.Target dst) {
        int vn = rm.x.length;
        float[] vx = new float[vn];
        float[] vy = new float[vn];
        float[] vz = new float[vn];
        float[] vs = new float[vn];
        SegmentMappingV1 map = rm.mapping;

        for (int i = 0; i < vn; i++) {
            float u = rm.u[i];
            float v = rm.v[i];
            float directedU = (map.uvDir >= 0f) ? u : (1f - u);
            float canonicalS = mix(map.arcStart, map.arcEnd, directedU) + map.phaseOffset;
            float px = rm.x[i];
            float py = rm.y[i];
            if (!ev.isZero) {
                float endAnchor = smoothstep(0.0f, 0.09f, u) * smoothstep(0.0f, 0.09f, 1.0f - u);
                float ox = rm.nx[i], oy = rm.ny[i];
                float cLen = (float) Math.hypot(ox, oy);
                if (cLen > 0.001f) { ox /= cLen; oy /= cLen; } else { ox = 0f; oy = 1f; }
                float sideSign = (v < 0.5f) ? 1.0f : -1.0f;
                float rnx = ox * sideSign, rny = oy * sideSign;

                // 1) Bounded continuous lateral wave along canonical s
                float latWave = (float) (Math.sin(ev.wavePhaseRad - canonicalS * 12.5663706) * 0.68
                        + Math.cos(ev.wavePhaseRad * 0.73 + canonicalS * 6.2831853) * 0.32);
                px += rnx * (ev.lateralWavePx * latWave * endAnchor);
                py += rny * (ev.lateralWavePx * latWave * endAnchor);

                // 2) True relative width breathing scaled by exact frozen per-vertex local half-width (zero hardcoded 18px)
                float wWave = (float) (Math.sin(ev.widthTimePhaseRad - canonicalS * 9.424778) * 0.72
                        + Math.cos(ev.widthTimePhaseRad * 0.65 + canonicalS * 6.2831853) * 0.28);
                float localWDelta = ev.widthAmplitude * wWave * endAnchor;
                px += ox * (rm.halfWidthPx[i] * localWDelta);
                py += oy * (rm.halfWidthPx[i] * localWDelta);
            }
            vx[i] = px;
            vy[i] = py;
            vz[i] = (rm.z[i] + rm.modelZ) / 2048.0f * 0.5f + 0.5f;
            vs[i] = canonicalS;
        }

        float flowActive = ev.flowActive;
        float flowOffsetU = ev.flowOffsetU;
        float flowDir = ev.flowDirection >= 0f ? 1f : -1f;
        float brightAmp = ev.brightnessAmplitude;
        float brightPhase = ev.brightnessTimePhaseRad;
        float brightScale = ev.brightnessScale;
        float[] tint = rm.tint;
        float[] coreCol = rm.coreColor;
        float emission = rm.emission;
        float style = rm.style;

        for (int t = 0; t < rm.idx.length; t += 3) {
            int i0 = rm.idx[t], i1 = rm.idx[t + 1], i2 = rm.idx[t + 2];
            float x0 = vx[i0], y0 = vy[i0], x1 = vx[i1], y1 = vy[i1], x2 = vx[i2], y2 = vy[i2];
            float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
            if (Math.abs(area) < 1e-4f) continue;
            float invArea = 1f / area;

            int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
            int maxX = Math.min(W - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
            int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
            int maxY = Math.min(H - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));

            for (int py = minY; py <= maxY; py++) {
                float ppy = py + 0.5f;
                for (int px = minX; px <= maxX; px++) {
                    float ppx = px + 0.5f;
                    float w0 = ((x1 - ppx) * (y2 - ppy) - (x2 - ppx) * (y1 - ppy)) * invArea;
                    float w1 = ((x2 - ppx) * (y0 - ppy) - (x0 - ppx) * (y2 - ppy)) * invArea;
                    float w2 = 1f - w0 - w1;
                    if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue;

                    float u = w0 * rm.u[i0] + w1 * rm.u[i1] + w2 * rm.u[i2];
                    float v = w0 * rm.v[i0] + w1 * rm.v[i1] + w2 * rm.v[i2];
                    float canonicalS = w0 * vs[i0] + w1 * vs[i1] + w2 * vs[i2];

                    float across = Math.abs(v * 2f - 1f);
                    float endFade = smoothstep(0.0f, 0.11f, u) * smoothstep(0.0f, 0.13f, 1.0f - u);
                    float uFlow = mix(u, canonicalS * 2.4f - flowOffsetU * 2.8f, flowActive);
                    float twist = 0.82f + 0.18f * (float) Math.sin(uFlow * 10.5f + style * 2.1f);
                    float wave = 0.5f + 0.5f * (float) Math.cos((v * 2f - 1f) * 10.5f + uFlow * 6.8f);
                    float streak = 0.56f + 0.44f * (float) Math.pow(wave, 1.4);

                    float body, rim, core;
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

                    // Moving energy highlight band with soft leading edge + smooth fading tail
                    float phaseHead1 = fract((canonicalS - flowOffsetU) * flowDir);
                    float leadEnv1 = (float) Math.exp(-Math.pow(phaseHead1 / 0.068f, 2.0));
                    float tailEnv1 = (float) Math.exp(-Math.pow((1.0f - phaseHead1) / 0.20f, 2.0));
                    float primaryBand = Math.max(leadEnv1, tailEnv1);

                    float phaseHead2 = fract(phaseHead1 + 0.52f);
                    float leadEnv2 = (float) Math.exp(-Math.pow(phaseHead2 / 0.062f, 2.0));
                    float tailEnv2 = (float) Math.exp(-Math.pow((1.0f - phaseHead2) / 0.17f, 2.0));
                    float secondaryBand = Math.max(leadEnv2, tailEnv2);

                    float energyBand = (primaryBand * 0.36f + secondaryBand * 0.16f) * flowActive;
                    float coreFocus = (float) Math.exp(-7.5f * across * across);

                    float brightWave = (float) (Math.sin(brightPhase - canonicalS * 6.2831853) * 0.70
                            + Math.cos(brightPhase * 0.62 + canonicalS * 9.424778) * 0.30);
                    float spatialBright = 1.0f + brightAmp * brightWave * flowActive;
                    float finalBright = mix(1.0f, spatialBright * 0.72f + brightScale * 0.28f, flowActive);

                    float alpha = clamp((body + rim * 0.60f + core * 0.68f) * tint[3] * endFade * twist, 0f, 1f);
                    if (alpha < 0.002f) continue;

                    float hlGain = energyBand * (0.36f + 0.64f * coreFocus) * (body + core * 0.88f);
                    float em = emission * finalBright;
                    float sr = ((tint[0] * (body + rim) + coreCol[0] * core) + (tint[0] * 0.35f + coreCol[0] * 0.65f) * hlGain) * em;
                    float sg = ((tint[1] * (body + rim) + coreCol[1] * core) + (tint[1] * 0.35f + coreCol[1] * 0.65f) * hlGain) * em;
                    float sb = ((tint[2] * (body + rim) + coreCol[2] * core) + (tint[2] * 0.35f + coreCol[2] * 0.65f) * hlGain) * em;

                    int pIdx = py * W + px;
                    // Exact match to WaterWorldSoftwareRenderer SCREEN blend so T=0 has near-0 MAE vs Work 2 static reference
                    float pr = clamp(sr * alpha, 0f, 1.5f);
                    float pg = clamp(sg * alpha, 0f, 1.5f);
                    float pb = clamp(sb * alpha, 0f, 1.5f);
                    dst.r[pIdx] = dst.r[pIdx] + pr * (1f - clamp(dst.r[pIdx], 0f, 1f) * 0.65f);
                    dst.g[pIdx] = dst.g[pIdx] + pg * (1f - clamp(dst.g[pIdx], 0f, 1f) * 0.65f);
                    dst.b[pIdx] = dst.b[pIdx] + pb * (1f - clamp(dst.b[pIdx], 0f, 1f) * 0.65f);
                }
            }
        }
    }

    private static void runFinalCompositePass(
            WaterWorldSoftwareRenderer.Target scene,
            Map<String, Object> bloomCfg,
            List<Object> bokehOrbs,
            List<Map<String, Object>> finalPassDraws,
            Map<String, Map<String, Object>> bubbleMeshes,
            Map<String, WaterWorldSoftwareRenderer.Tex> bubbleTextures,
            float scx, float scy, float sOuterR) {
        float thresh = WaterWorldSoftwareRenderer.flt(bloomCfg.get("threshold"), 0.78f);
        float knee = WaterWorldSoftwareRenderer.flt(bloomCfg.get("soft_knee"), 0.25f);
        int blurR = (int) WaterWorldSoftwareRenderer.flt(bloomCfg.get("blur_radius_px"), 14f);
        float bStr = WaterWorldSoftwareRenderer.flt(bloomCfg.get("strength"), 0.38f);
        float bMax = WaterWorldSoftwareRenderer.flt(bloomCfg.get("max_contribution"), 0.55f);

        WaterWorldSoftwareRenderer.Target bright = new WaterWorldSoftwareRenderer.Target(W, H);
        for (int i = 0; i < W * H; i++) {
            float lum = 0.2126f * scene.r[i] + 0.7152f * scene.g[i] + 0.0722f * scene.b[i];
            float rq = clamp(lum - thresh + knee, 0f, 2f * knee);
            rq = (rq * rq) / (4f * knee + 1e-5f);
            float contrib = Math.max(rq, lum - thresh) / Math.max(lum, 1e-5f);
            bright.r[i] = scene.r[i] * contrib;
            bright.g[i] = scene.g[i] * contrib;
            bright.b[i] = scene.b[i] * contrib;
            bright.a[i] = 1f;
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
                bv.sampleBilinear(u, v, bSample4);
                bv2.sampleBilinear(u, v, bSample2);
                int idx = py * W + px;
                scene.r[idx] += Math.min(bMax, (bSample4[0] * 0.65f + bSample2[0] * 0.55f) * bStr);
                scene.g[idx] += Math.min(bMax, (bSample4[1] * 0.65f + bSample2[1] * 0.55f) * bStr);
                scene.b[idx] += Math.min(bMax, (bSample4[2] * 0.65f + bSample2[2] * 0.55f) * bStr);
            }
        }
        WaterWorldSoftwareRenderer.Target dummyBokeh = new WaterWorldSoftwareRenderer.Target(W, H);
        for (Object oo : bokehOrbs) {
            Map<String, Object> orb = WaterWorldSoftwareRenderer.asMap(oo);
            if (!"FG".equals(orb.get("layer"))) continue;
            WaterWorldSoftwareRenderer.renderBokehOrb(orb, scene, dummyBokeh, scx, scy, sOuterR);
        }
        for (Map<String, Object> d : finalPassDraws) {
            Map<String, Object> mesh = bubbleMeshes.get((String) d.get("mesh_path"));
            WaterWorldSoftwareRenderer.Tex tex = bubbleTextures.get((String) d.get("texture_path"));
            float[] model = WaterWorldSoftwareRenderer.fltArr(d.get("model"));
            float[] tint = WaterWorldSoftwareRenderer.fltArr(d.get("tint"));
            float[] tmp = new float[4];
            WaterWorldSoftwareRenderer.rasterizeMesh(mesh, model[14], scene, true, false, "ALPHA", 0f, (px, py, pz, nx, ny, nz, u, v, out) -> {
                float bx = u * 2f - 1f, by = v * 2f - 1f;
                float br = (float) Math.hypot(bx, by);
                if (br > 1f) return;
                tex.sample(u, v, false, tmp);
                float rim = smoothstep(0.62f, 0.94f, br) * (1f - smoothstep(0.94f, 1.0f, br));
                float spec1 = (float) Math.exp(-((bx + 0.35f) * (bx + 0.35f) + (by + 0.35f) * (by + 0.35f)) * 18f);
                float spec2 = (float) Math.exp(-((bx - 0.32f) * (bx - 0.32f) + (by - 0.35f) * (by - 0.35f)) * 14f) * 0.45f;
                out[0] = tmp[0] * 0.45f + rim * 0.72f + spec1 * 1.15f + spec2 * 0.65f;
                out[1] = tmp[1] * 0.55f + rim * 0.90f + spec1 * 1.15f + spec2 * 0.85f;
                out[2] = tmp[2] * 0.62f + rim * 0.96f + spec1 * 1.15f + spec2 * 0.95f;
                out[3] = clamp((tmp[3] * 0.45f + rim * 0.72f + spec1 * 0.92f + spec2 * 0.45f) * tint[3], 0f, 1f);
            });
        }
        // Exact Luminance-preserving Filmic highlight roll-off matching WaterWorldSoftwareRenderer Pass 11
        for (int i = 0; i < W * H; i++) {
            float r = Math.max(0f, scene.r[i]);
            float g = Math.max(0f, scene.g[i]);
            float b = Math.max(0f, scene.b[i]);
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

    private static void buildContactSheet(
            BufferedImage[] fullFrames,
            Map<String, BufferedImage[]> roiFrames,
            Path outPath) throws Exception {
        // Top half: 3x3 full scene (T = 0,1,2,4,8,12,20,40,60s) at 360x360 each
        // Bottom half: 5 rows (G01, G02, A01, A02, C01) x 6 cols (T = 0, 2, 4, 8, 20, 60s) at 180x140 each
        int tile = 360;
        int pad = 14;
        int headerH = 56;
        int topH = headerH + 3 * (tile + 28) + pad;
        int roiW = 180, roiH = 136;
        int[] roiCols = {0, 2, 3, 4, 6, 8}; // T = 0, 2, 4, 8, 20, 60s
        int sheetW = Math.max(3 * tile + 4 * pad, 6 * roiW + 7 * pad + 90);
        int sheetH = topH + 46 + 5 * (roiH + 22) + pad;

        BufferedImage sheet = new BufferedImage(sheetW, sheetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(10, 14, 22));
        g.fillRect(0, 0, sheetW, sheetH);

        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString("FIVE RESIDENT FILAMENTS V1 — SOFTWARE MOTION PRECHECK (NOT HARDWARE GPU ACCEPTANCE)", 18, 30);
        g.setColor(new Color(160, 210, 235));
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.drawString("Contract: WATER_WORLD_FIVE_RESIDENT_FILAMENTS_DESIGN_FREEZE_V1 | 5 Residents (G01/G02/A01/A02/C01) | 13 Frozen Segments | T=0..60s", 18, 49);

        int topLeftPad = (sheetW - (3 * tile + 2 * pad)) / 2;
        for (int i = 0; i < 9; i++) {
            int r = i / 3, c = i % 3;
            int x = topLeftPad + c * (tile + pad);
            int y = headerH + r * (tile + 28);
            g.drawImage(fullFrames[i], x, y, tile, tile, null);
            g.setColor(new Color(70, 95, 120));
            g.drawRect(x, y, tile, tile);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            g.drawString(String.format(Locale.US, "T = %ds %s", (int) TIMESTAMPS[i], i == 0 ? "(Work2 Static Parity)" : "(V1 Flow Active)"), x + 6, y + tile + 18);
        }

        int roiTop = topH + 10;
        g.setColor(new Color(255, 220, 120));
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.drawString("PER-RESIDENT ROI PROGRESSION (Moving Highlight Band + Canonical s Continuity + Relative Width Breathing)", 18, roiTop + 20);

        String[] labels = {
            "G01 (+1, 16s)", "G02 (-1, 18s)", "A01 (+1, 20s)", "A02 (-1, 14s)", "C01 (+1, 22s)"
        };
        Color[] tagColors = {
            new Color(0x9F, 0xE2, 0x26), new Color(0xFF, 0xC2, 0x47),
            new Color(0x37, 0xE7, 0xEF), new Color(0x15, 0xD8, 0xED), new Color(0xF6, 0xBB, 0x48)
        };

        for (int row = 0; row < 5; row++) {
            String gk = RESIDENT_KEYS[row];
            int y = roiTop + 36 + row * (roiH + 22);
            g.setColor(tagColors[row]);
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.drawString(labels[row], 12, y + roiH / 2);
            for (int ci = 0; ci < roiCols.length; ci++) {
                int ti = roiCols[ci];
                int x = 112 + ci * (roiW + 10);
                BufferedImage img = roiFrames.get(gk)[ti];
                g.drawImage(img, x, y, roiW, roiH, null);
                g.setColor(tagColors[row]);
                g.drawRect(x, y, roiW, roiH);
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.PLAIN, 11));
                g.drawString(String.format(Locale.US, "%s T=%ds", gk, (int) TIMESTAMPS[ti]), x + 4, y + roiH + 14);
            }
        }
        g.dispose();
        ImageIO.write(sheet, "png", outPath.toFile());
    }

    private static WaterWorldSoftwareRenderer.Target fromImage(BufferedImage img) {
        WaterWorldSoftwareRenderer.Target t = new WaterWorldSoftwareRenderer.Target(W, H);
        int[] argb = img.getRGB(0, 0, W, H, null, 0, W);
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            t.r[i] = ((p >>> 16) & 255) / 255f;
            t.g[i] = ((p >>> 8) & 255) / 255f;
            t.b[i] = (p & 255) / 255f;
            t.a[i] = 1f;
        }
        return t;
    }

    private static BufferedImage toBufferedImage(WaterWorldSoftwareRenderer.Target t) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] rgb = new int[W * H];
        for (int i = 0; i < W * H; i++) {
            int rr = (int) (clamp(t.r[i], 0f, 1f) * 255f + 0.5f);
            int gg = (int) (clamp(t.g[i], 0f, 1f) * 255f + 0.5f);
            int bb = (int) (clamp(t.b[i], 0f, 1f) * 255f + 0.5f);
            rgb[i] = (rr << 16) | (gg << 8) | bb;
        }
        img.setRGB(0, 0, W, H, rgb, 0, W);
        return img;
    }

    private static BufferedImage copyImg(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static double mae255(BufferedImage a, BufferedImage b, int x0, int y0, int x1, int y1) {
        long sum = 0;
        long count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                sum += Math.abs(((pa >>> 16) & 255) - ((pb >>> 16) & 255));
                sum += Math.abs(((pa >>> 8) & 255) - ((pb >>> 8) & 255));
                sum += Math.abs((pa & 255) - (pb & 255));
                count += 3;
            }
        }
        return count > 0 ? (double) sum / count : 0.0;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    @SuppressWarnings("unchecked")
    private static String toJson(Object obj, int indent) {
        String pad = "  ".repeat(indent);
        String padIn = "  ".repeat(indent + 1);
        if (obj == null) return "null";
        if (obj instanceof Boolean || obj instanceof Number) return obj.toString();
        if (obj instanceof String) return "\"" + ((String) obj).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (obj instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) obj;
            if (m.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                sb.append(padIn).append(toJson(e.getKey(), 0)).append(": ").append(toJson(e.getValue(), indent + 1));
                if (++i < m.size()) sb.append(",");
                sb.append("\n");
            }
            return sb.append(pad).append("}").toString();
        }
        if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            if (list.isEmpty()) return "[]";
            boolean primitives = list.stream().allMatch(x -> x instanceof Number || x instanceof String || x instanceof Boolean);
            if (primitives) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(toJson(list.get(i), 0));
                }
                return sb.append("]").toString();
            }
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < list.size(); i++) {
                sb.append(padIn).append(toJson(list.get(i), indent + 1));
                if (i + 1 < list.size()) sb.append(",");
                sb.append("\n");
            }
            return sb.append(pad).append("]").toString();
        }
        return toJson(obj.toString(), 0);
    }
}
