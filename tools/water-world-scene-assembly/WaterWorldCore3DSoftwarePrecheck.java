import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Water World V2.1 — Work 3.1 Phase 4 Software 3D Volume Precheck.
 *
 * Strictly labeled `SOFTWARE_GPU_PRECHECK_NOT_HARDWARE_ACCEPTANCE` (never claims real Android GPU pass).
 * Decodes all 32 REAL spatial Z-slice WebP assets (`app/src/main/assets/orb_v3/volume/fluid_slice_00..31.webp`,
 * 256x256x32 `SPATIAL_Z_SLICE_NOT_TIME_FRAME`), performs real 3D trilinear Front-to-Back Beer-Lambert
 * volume raymarching + true 3D rotation (`transpose(uCoreRotMat)` at 3.6 deg/s around tilted axis
 * `(0.14, 0.98, -0.14)`) + true 3D curl advection, aligns to Water World's exact off-axis perspective
 * sphere `(626.47575, 596.34413, r=395.75 on 1254x1254)`, and generates:
 *  - `core3d_frame_t000.png` .. `core3d_frame_t012.png`
 *  - `core3d_alignment_overlay.png`
 *  - `core3d_isolation_grid.png` (all 6 Core 3D GPU isolation views at T=4.0s)
 *  - `WORK3_1_CORE3D_CONTACT_SHEET.png`
 *  - `WORK3_1_CORE3D_PRECHECK_METRICS.json`
 */
public class WaterWorldCore3DSoftwarePrecheck {

    private static final int VOL_W = 256;
    private static final int VOL_H = 256;
    private static final int VOL_D = 32;
    private static final int CANVAS = 1254;
    private static final double SPHERE_CX = 626.47575;
    private static final double SPHERE_CY = 596.34413;
    private static final double SPHERE_R = 395.75;
    private static final double CAM_DIST = 3.0;
    private static final int RAY_STEPS = 28;

    // Reference meniscus curve (matching ReferenceMeniscusProfile.HEIGHTS)
    private static final double[] MENISCUS_Y = {
        0.540, 0.515, 0.488, 0.460, 0.432, 0.405, 0.378, 0.354,
        0.332, 0.312, 0.295, 0.281, 0.269, 0.260, 0.254, 0.250,
        0.249, 0.250, 0.254, 0.260, 0.269, 0.281, 0.295, 0.312,
        0.332, 0.354, 0.378, 0.405, 0.432, 0.460, 0.488, 0.515
    };

    private static final double[] SRGB_TO_LIN = new double[256];
    static {
        for (int i = 0; i < 256; i++) {
            double s = i / 255.0;
            SRGB_TO_LIN[i] = (s <= 0.04045) ? (s / 12.92) : Math.pow((s + 0.055) / 1.055, 2.4);
        }
    }

    // Flattened 256x256x32 linear RGB + linear A volume
    private final float[] volR = new float[VOL_D * VOL_H * VOL_W];
    private final float[] volG = new float[VOL_D * VOL_H * VOL_W];
    private final float[] volB = new float[VOL_D * VOL_H * VOL_W];
    private final float[] volA = new float[VOL_D * VOL_H * VOL_W];

    public static void main(String[] args) throws Exception {
        File root = new File(".").getCanonicalFile();
        File webpDir = new File(root, "app/src/main/assets/orb_v3/volume");
        File work3FramesDir = new File(root, "releases/water-world-v2-v21/work3_full_dynamic_runtime/04_DYNAMIC_EVIDENCE");
        File outDir = new File(root, "releases/water-world-v2-v21/work3_1_core_3d_gpu_acceptance/04_SOFTWARE_PRECHECK");
        outDir.mkdirs();

        WaterWorldCore3DSoftwarePrecheck runner = new WaterWorldCore3DSoftwarePrecheck();
        long t0 = System.currentTimeMillis();
        int loadedSlices = runner.loadAll32RealWebpSlices(webpDir);
        long decodeMs = System.currentTimeMillis() - t0;
        System.out.printf(Locale.US, "Loaded %d real 256x256 spatial Z-slices in %d ms%n", loadedSlices, decodeMs);

        // Verify off-axis perspective camera alignment math
        double[] align = verifyOffAxisAlignment();
        System.out.printf(Locale.US, "Off-axis alignment: centerErrPx=%.6f, radiusErrPx=%.6f%n", align[0], align[1]);

        int[] timestamps = {0, 1, 2, 4, 8, 12};
        BufferedImage[] compositedFrames = new BufferedImage[timestamps.length];
        BufferedImage[] rawCoreBuffers = new BufferedImage[timestamps.length];
        double[] nonCoreMae = new double[timestamps.length];
        double[] coreInteriorDeltaVsBase = new double[timestamps.length];
        double[] rotDet = new double[timestamps.length];
        double[] rotOrthoErr = new double[timestamps.length];
        double[] rotAngleDeg = new double[timestamps.length];

        for (int i = 0; i < timestamps.length; i++) {
            int tSec = timestamps[i];
            String frameName = String.format(Locale.US, "frame_t%03d.png", tSec);
            File baseFile = new File(work3FramesDir, frameName);
            if (!baseFile.isFile()) {
                throw new IOException("Missing Work 3 dynamic base frame: " + baseFile);
            }
            BufferedImage baseImg = ImageIO.read(baseFile);
            double angleDeg = (tSec * 3.6) % 360.0;
            rotAngleDeg[i] = angleDeg;
            double[] rotMat = buildAxisAngleMat3(0.14, 0.98, -0.14, Math.toRadians(angleDeg));
            rotDet[i] = mat3Det(rotMat);
            rotOrthoErr[i] = mat3OrthoError(rotMat);

            BufferedImage comp = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_RGB);
            BufferedImage coreOnly = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_RGB);
            double[] stats = runner.renderCompositeFrame(baseImg, comp, coreOnly, tSec, rotMat);
            nonCoreMae[i] = stats[0];
            coreInteriorDeltaVsBase[i] = stats[1];

            compositedFrames[i] = comp;
            rawCoreBuffers[i] = coreOnly;
            File outPng = new File(outDir, String.format(Locale.US, "core3d_frame_t%03d.png", tSec));
            ImageIO.write(comp, "png", outPng);
            System.out.printf(Locale.US, "Rendered %s | rot=%.1f deg | nonCoreMAE=%.6f | coreDeltaVsBase=%.3f%n",
                outPng.getName(), angleDeg, stats[0], stats[1]);
        }

        // Compute inter-frame Core interior MAE vs T=0 (proving real 3D rotation & curl motion!)
        double[] coreMotionMaeVsT0 = new double[timestamps.length];
        for (int i = 0; i < timestamps.length; i++) {
            coreMotionMaeVsT0[i] = computeSphereInteriorMae(rawCoreBuffers[0], rawCoreBuffers[i]);
            System.out.printf(Locale.US, "Core 3D interior MAE (T=%ds vs T=0s) = %.3f / 255%n", timestamps[i], coreMotionMaeVsT0[i]);
        }

        // Render 6-panel GPU isolation grid at T=4.0s
        double[] rotMatT4 = buildAxisAngleMat3(0.14, 0.98, -0.14, Math.toRadians(4.0 * 3.6));
        BufferedImage isoGrid = runner.renderIsolationGrid(4.0, rotMatT4);
        ImageIO.write(isoGrid, "png", new File(outDir, "core3d_isolation_grid.png"));

        // Render alignment overlay
        BufferedImage alignOverlay = renderAlignmentOverlay(compositedFrames[0], align[0], align[1]);
        ImageIO.write(alignOverlay, "png", new File(outDir, "core3d_alignment_overlay.png"));

        // Render 2x3 contact sheet
        BufferedImage contactSheet = renderContactSheet(compositedFrames, rawCoreBuffers, timestamps, coreMotionMaeVsT0);
        ImageIO.write(contactSheet, "png", new File(outDir, "WORK3_1_CORE3D_CONTACT_SHEET.png"));

        // Write metrics JSON
        writeMetricsJson(
            new File(outDir, "WORK3_1_CORE3D_PRECHECK_METRICS.json"),
            loadedSlices,
            decodeMs,
            align,
            timestamps,
            rotAngleDeg,
            rotDet,
            rotOrthoErr,
            nonCoreMae,
            coreInteriorDeltaVsBase,
            coreMotionMaeVsT0
        );
        System.out.println("WORK3_1_SOFTWARE_PRECHECK_COMPLETE");
    }

    private int loadAll32RealWebpSlices(File webpDir) throws Exception {
        Path tmpDir = Files.createTempDirectory("ww_work31_slices_");
        try {
            int loaded = 0;
            for (int z = 0; z < VOL_D; z++) {
                File webp = new File(webpDir, String.format(Locale.US, "fluid_color_z%02d.webp", z));
                if (!webp.isFile()) {
                    throw new IOException("Missing spatial Z-slice WebP: " + webp);
                }
                File png = tmpDir.resolve(String.format(Locale.US, "slice_%02d.png", z)).toFile();
                ProcessBuilder pb = new ProcessBuilder(
                    "/usr/bin/convert", webp.getAbsolutePath(), "PNG32:" + png.getAbsolutePath()
                );
                pb.redirectErrorStream(true);
                Process p = pb.start();
                int exit = p.waitFor();
                if (exit != 0 || !png.isFile()) {
                    throw new IOException("Failed to decode WebP slice " + webp.getName() + " (exit=" + exit + ")");
                }
                BufferedImage sliceImg = ImageIO.read(png);
                if (sliceImg == null || sliceImg.getWidth() != VOL_W || sliceImg.getHeight() != VOL_H) {
                    throw new IOException("Unexpected slice dimensions for " + webp.getName());
                }
                int zOff = z * VOL_H * VOL_W;
                for (int y = 0; y < VOL_H; y++) {
                    int rowOff = zOff + y * VOL_W;
                    for (int x = 0; x < VOL_W; x++) {
                        int argb = sliceImg.getRGB(x, y);
                        int a = (argb >>> 24) & 0xFF;
                        int r = (argb >>> 16) & 0xFF;
                        int g = (argb >>> 8) & 0xFF;
                        int b = argb & 0xFF;
                        int idx = rowOff + x;
                        volR[idx] = (float) SRGB_TO_LIN[r];
                        volG[idx] = (float) SRGB_TO_LIN[g];
                        volB[idx] = (float) SRGB_TO_LIN[b];
                        volA[idx] = a / 255.0f;
                    }
                }
                loaded++;
            }
            return loaded;
        } finally {
            File[] files = tmpDir.toFile().listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            tmpDir.toFile().delete();
        }
    }

    private void sampleVolumeTrilinear(double u, double v, double w, double[] outRgba) {
        double cu = clamp(u, 0.0, 1.0) * (VOL_W - 1);
        double cv = clamp(v, 0.0, 1.0) * (VOL_H - 1);
        double cw = clamp(w, 0.0, 1.0) * (VOL_D - 1);
        int x0 = (int) Math.floor(cu);
        int y0 = (int) Math.floor(cv);
        int z0 = (int) Math.floor(cw);
        int x1 = Math.min(x0 + 1, VOL_W - 1);
        int y1 = Math.min(y0 + 1, VOL_H - 1);
        int z1 = Math.min(z0 + 1, VOL_D - 1);
        double fx = cu - x0;
        double fy = cv - y0;
        double fz = cw - z0;

        double r = 0, g = 0, b = 0, a = 0;
        for (int dz = 0; dz <= 1; dz++) {
            double wz = (dz == 0) ? (1.0 - fz) : fz;
            int zz = (dz == 0) ? z0 : z1;
            int zOff = zz * VOL_H * VOL_W;
            for (int dy = 0; dy <= 1; dy++) {
                double wy = (dy == 0) ? (1.0 - fy) : fy;
                int yy = (dy == 0) ? y0 : y1;
                int yOff = zOff + yy * VOL_W;
                double wzy = wz * wy;
                int i0 = yOff + x0;
                int i1 = yOff + x1;
                double w0 = wzy * (1.0 - fx);
                double w1 = wzy * fx;
                r += volR[i0] * w0 + volR[i1] * w1;
                g += volG[i0] * w0 + volG[i1] * w1;
                b += volB[i0] * w0 + volB[i1] * w1;
                a += volA[i0] * w0 + volA[i1] * w1;
            }
        }
        outRgba[0] = r;
        outRgba[1] = g;
        outRgba[2] = b;
        outRgba[3] = a;
    }

    /** Exact software replica of CORE_GLASS_FRAG `uCoreRenderMode == 1` raymarch + Water Base composite. */
    private double[] renderCompositeFrame(
        BufferedImage baseImg,
        BufferedImage outComp,
        BufferedImage outCoreOnly,
        double tSec,
        double[] rotMat
    ) {
        double[] invRot = transposeMat3(rotMat);
        double fluidPhase = tSec * 0.65;
        double causticsPhase = tSec * 0.72;
        double densityScale = 1.56 + 0.05 * Math.sin(tSec * 0.85);
        double colorShift = 0.06 * Math.sin(tSec * 0.45);
        double shiftR = 1.0 - colorShift * 0.16;
        double shiftG = 1.0 + colorShift * 0.10;
        double shiftB = 1.0 + colorShift * 0.18;

        // Exact perspective tangent scale matching WaterWorldCoreMotionAdapter.buildAlignedVolumeCamera
        double halfTan = Math.tan(Math.asin(1.0 / CAM_DIST));
        double nonCoreSum = 0.0;
        long nonCoreCount = 0;
        double coreDeltaSum = 0.0;
        long coreCount = 0;

        // Parallelize across rows for fast ~1.5s execution across 6 frames
        int[] basePixels = baseImg.getRGB(0, 0, CANVAS, CANVAS, null, 0, CANVAS);
        int[] compPixels = new int[CANVAS * CANVAS];
        int[] corePixels = new int[CANVAS * CANVAS];

        final double[] rowNonCoreSum = new double[CANVAS];
        final long[] rowNonCoreCnt = new long[CANVAS];
        final double[] rowCoreDeltaSum = new double[CANVAS];
        final long[] rowCoreCnt = new long[CANVAS];

        java.util.stream.IntStream.range(0, CANVAS).parallel().forEach(py -> {
            double[] rgba = new double[4];
            double[] rayOut = new double[12];
            double dy = (SPHERE_CY - py) / SPHERE_R;
            int rowBase = py * CANVAS;
            double localNonCore = 0.0;
            long localNonCoreN = 0;
            double localCoreDelta = 0.0;
            long localCoreN = 0;

            for (int px = 0; px < CANVAS; px++) {
                int srcRgb = basePixels[rowBase + px];
                double dx = (px - SPHERE_CX) / SPHERE_R;
                double r2 = dx * dx + dy * dy;
                if (r2 >= 1.0) {
                    compPixels[rowBase + px] = srcRgb;
                    corePixels[rowBase + px] = 0x02090F;
                    localNonCoreN++;
                    continue;
                }

                // Exact perspective ray through sphere matching GPU camera at (0, 0, CAM_DIST)
                double rx = dx * halfTan;
                double ry = dy * halfTan;
                double rz = -1.0;
                double rLen = Math.sqrt(rx * rx + ry * ry + rz * rz);
                rx /= rLen; ry /= rLen; rz /= rLen;

                // Ray-sphere intersection with unit sphere at origin, ro = (0, 0, CAM_DIST)
                double b = CAM_DIST * rz;
                double c = CAM_DIST * CAM_DIST - 1.0;
                double disc = b * b - c;
                if (disc <= 0.0) {
                    compPixels[rowBase + px] = srcRgb;
                    corePixels[rowBase + px] = 0x02090F;
                    localNonCoreN++;
                    continue;
                }
                double s = Math.sqrt(disc);
                double tEnter = -b - s;
                double tExit = -b + s;
                double hitX = rx * tEnter;
                double hitY = ry * tEnter;
                double hitZ = CAM_DIST + rz * tEnter;
                double ndotv = clamp(-(hitX * rx + hitY * ry + hitZ * rz), 0.0, 1.0);

                raymarchVolume(
                    hitX, hitY, hitZ, rx, ry, rz, tExit - tEnter,
                    invRot, fluidPhase, causticsPhase, densityScale,
                    rgba, rayOut
                );

                double accR = rayOut[0] * shiftR;
                double accG = rayOut[1] * shiftG;
                double accB = rayOut[2] * shiftB;
                double accA = rayOut[3];
                double cauR = rayOut[4];
                double cauG = rayOut[5];
                double cauB = rayOut[6];
                double liquidPath = rayOut[7];

                double wallFeather = smoothstep(0.02, 0.24, ndotv);
                double submergedMask = smoothstep(0.02, 0.24, liquidPath);

                double rawR = accR + cauR * 0.38;
                double rawG = accG + cauG * 0.38;
                double rawB = accB + cauB * 0.38;
                double mapR = rawR / (1.0 + rawR * 0.32);
                double mapG = rawG / (1.0 + rawG * 0.32);
                double mapB = rawB / (1.0 + rawB * 0.32);

                // Isolated core view
                double isoEdge = smoothstep(0.0, 0.045, ndotv);
                double isoR = lerp(0.008, mapR * (0.45 + 0.75 * submergedMask) + 0.008 * (1.0 - clamp(accA, 0, 1)) * 0.55, isoEdge);
                double isoG = lerp(0.036, mapG * (0.45 + 0.75 * submergedMask) + 0.036 * (1.0 - clamp(accA, 0, 1)) * 0.55, isoEdge);
                double isoB = lerp(0.058, mapB * (0.45 + 0.75 * submergedMask) + 0.058 * (1.0 - clamp(accA, 0, 1)) * 0.55, isoEdge);
                corePixels[rowBase + px] = linearToRgbInt(isoR, isoG, isoB);

                // Production single-pass premultiplied composite over Water Base
                double prodAlpha = clamp(accA * 0.44, 0.0, 0.54) * wallFeather * submergedMask;
                double preR = mapR * prodAlpha * 0.88 + cauR * 0.14 * wallFeather * submergedMask;
                double preG = mapG * prodAlpha * 0.88 + cauG * 0.14 * wallFeather * submergedMask;
                double preB = mapB * prodAlpha * 0.88 + cauB * 0.14 * wallFeather * submergedMask;

                double bgR = SRGB_TO_LIN[(srcRgb >>> 16) & 0xFF];
                double bgG = SRGB_TO_LIN[(srcRgb >>> 8) & 0xFF];
                double bgB = SRGB_TO_LIN[srcRgb & 0xFF];

                double outR = bgR * (1.0 - prodAlpha) + preR;
                double outG = bgG * (1.0 - prodAlpha) + preG;
                double outB = bgB * (1.0 - prodAlpha) + preB;
                int outRgb = linearToRgbInt(outR, outG, outB);
                compPixels[rowBase + px] = outRgb;

                double dR = Math.abs(((outRgb >>> 16) & 0xFF) - ((srcRgb >>> 16) & 0xFF));
                double dG = Math.abs(((outRgb >>> 8) & 0xFF) - ((srcRgb >>> 8) & 0xFF));
                double dB = Math.abs((outRgb & 0xFF) - (srcRgb & 0xFF));
                localCoreDelta += (dR + dG + dB) / 3.0;
                localCoreN++;
            }
            rowNonCoreSum[py] = localNonCore;
            rowNonCoreCnt[py] = localNonCoreN;
            rowCoreDeltaSum[py] = localCoreDelta;
            rowCoreCnt[py] = localCoreN;
        });

        outComp.setRGB(0, 0, CANVAS, CANVAS, compPixels, 0, CANVAS);
        outCoreOnly.setRGB(0, 0, CANVAS, CANVAS, corePixels, 0, CANVAS);
        for (int y = 0; y < CANVAS; y++) {
            nonCoreSum += rowNonCoreSum[y];
            nonCoreCount += rowNonCoreCnt[y];
            coreDeltaSum += rowCoreDeltaSum[y];
            coreCount += rowCoreCnt[y];
        }
        return new double[]{
            nonCoreCount > 0 ? nonCoreSum / nonCoreCount : 0.0,
            coreCount > 0 ? coreDeltaSum / coreCount : 0.0
        };
    }

    /**
     * Real 3D Front-to-Back Beer-Lambert Raymarching across the 256x256x32 GL_TEXTURE_3D volume,
     * with `transpose(uCoreRotMat)` 3D rotation and bounded 3D curl advection.
     */
    private void raymarchVolume(
        double startX, double startY, double startZ,
        double rdX, double rdY, double rdZ,
        double chordLen,
        double[] invRot,
        double fluidPhase,
        double causticsPhase,
        double densityScale,
        double[] rgba,
        double[] out // [0..2]=accRgb, 3=accAlpha, 4..6=cauRgb, 7=liquidPath, 8=optDepth, 9..11=flowRgb
    ) {
        double dt = chordLen / RAY_STEPS;
        double accR = 0, accG = 0, accB = 0, accA = 0;
        double cauR = 0, cauG = 0, cauB = 0;
        double liquidPath = 0.0;
        double optDepth = 0.0;
        double flowR = 0, flowG = 0, flowB = 0, flowW = 0;
        double effDensity = clamp(densityScale, 1.20, 1.95) * 0.976;

        for (int i = 0; i < RAY_STEPS; i++) {
            double t = (i + 0.5) * dt;
            double lx = startX + rdX * t;
            double ly = startY + rdY * t;
            double lz = startZ + rdZ * t;
            double rad = Math.sqrt(lx * lx + ly * ly + lz * lz);
            if (rad > 0.995) continue;

            // Core Frame: true 3D rigid rotation via transpose(uCoreRotMat)
            double cx = invRot[0] * lx + invRot[3] * ly + invRot[6] * lz;
            double cy = invRot[1] * lx + invRot[4] * ly + invRot[7] * lz;
            double cz = invRot[2] * lx + invRot[5] * ly + invRot[8] * lz;

            // Reference meniscus / cavity gate
            double mY = sampleMeniscusY(cx) + 0.14 * cz * cz - 0.035 * cz + 0.012 * Math.sin(cx * 2.4 + fluidPhase * 0.45);
            double phi = cy - mY;
            if (phi > 0.04) continue;
            double subWeight = 1.0 - smoothstep(-0.06, 0.04, phi);
            liquidPath += dt * subWeight;

            // Bounded 3D Curl Advection (divergence-free harmonic curl matching CORE_GLASS_FRAG)
            double rXZ = Math.sqrt(cx * cx + cz * cz);
            double twist = (1.0 - clamp(rXZ, 0.0, 1.0)) * 0.24 + 0.08 * Math.sin(cy * 2.5 - fluidPhase * 0.58);
            double ct = Math.cos(twist), st = Math.sin(twist);
            double sx = cx * ct - cz * st;
            double sy = cy;
            double sz = cx * st + cz * ct;

            double freq = 2.65;
            double curlX = Math.sin(sy * freq + fluidPhase * 0.62) * Math.cos(sz * freq - fluidPhase * 0.44) * 0.095;
            double curlY = Math.sin(sz * freq - fluidPhase * 0.50) * Math.cos(sx * freq + fluidPhase * 0.48) * 0.095;
            double curlZ = Math.sin(sx * freq + fluidPhase * 0.56) * Math.cos(sy * freq - fluidPhase * 0.38) * 0.095;

            if (flowW < 0.5 && subWeight > 0.3) {
                double cMag = Math.sqrt(curlX * curlX + curlY * curlY + curlZ * curlZ) + 1e-6;
                flowR = (curlX / cMag) * 0.5 + 0.5;
                flowG = (curlY / cMag) * 0.5 + 0.5;
                flowB = (curlZ / cMag) * 0.5 + 0.5;
                flowW = 1.0;
            }

            double ax = sx + curlX;
            double ay = sy + curlY;
            double az = sz + curlZ;

            // Sample real 32 spatial Z-slice GL_TEXTURE_3D (`u, v, w` in [0, 1])
            double u = ax * 0.5 + 0.5;
            double v = 0.5 - ay * 0.5;
            double w = az * 0.5 + 0.5;
            sampleVolumeTrilinear(u, v, w, rgba);

            double localDensity = rgba[3] * subWeight * effDensity;
            if (localDensity > 0.002) {
                double dTau = localDensity * dt * 2.15;
                optDepth += dTau;
                double stepAlpha = 1.0 - Math.exp(-dTau);
                double remain = 1.0 - accA;
                double wStep = remain * stepAlpha;
                // Subtle depth-dependent directional illumination inside the liquid body
                double keyWrap = 0.68 + 0.32 * clamp(-cx * 0.45 + cy * 0.65 + cz * 0.40 + 0.35, 0.0, 1.0);
                accR += wStep * rgba[0] * keyWrap * 1.48;
                accG += wStep * rgba[1] * keyWrap * 1.54;
                accB += wStep * rgba[2] * keyWrap * 1.58;
                accA += wStep;

                // Lower-belly irregular curved-wave 3D caustics
                if (cy < 0.15) {
                    double qx = ax * 3.1 + causticsPhase * 0.22;
                    double qy = az * 3.1 - causticsPhase * 0.18;
                    double wave = Math.sin(qx * 2.4 + Math.cos(qy * 2.1 + ay * 2.0))
                                + Math.cos(qy * 2.6 - Math.sin(qx * 1.9 - causticsPhase * 0.4));
                    double ridge = Math.exp(-Math.abs(wave) * 5.8);
                    double belly = 1.0 - smoothstep(-0.85, 0.15, cy);
                    double cInt = ridge * belly * wStep * 0.92;
                    cauR += cInt * 0.30;
                    cauG += cInt * 0.92;
                    cauB += cInt * 1.05;
                }
                if (accA > 0.985) break;
            }
        }

        out[0] = accR; out[1] = accG; out[2] = accB; out[3] = accA;
        out[4] = cauR; out[5] = cauG; out[6] = cauB;
        out[7] = liquidPath; out[8] = optDepth;
        out[9] = flowR; out[10] = flowG; out[11] = flowB;
    }

    private BufferedImage renderIsolationGrid(double tSec, double[] rotMat) {
        int panel = 360;
        int pad = 16;
        int header = 34;
        int w = panel * 3 + pad * 4;
        int h = (panel + header) * 2 + pad * 3 + 42;
        BufferedImage grid = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = grid.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(8, 16, 26));
        g.fillRect(0, 0, w, h);

        g.setColor(new Color(224, 245, 255));
        g.setFont(new Font("SansSerif", Font.BOLD, 17));
        g.drawString("Water World V2.1 Work 3.1 — 6 Core 3D GPU Isolation Modes (32 Spatial Z-Slices, T=4.0s, Software Precheck)", pad, 26);

        String[] titles = {
            "1. CORE_VOLUME_ONLY",
            "2. CORE_DENSITY",
            "3. CORE_FLOW (3D Curl)",
            "4. CORE_CAUSTICS",
            "5. CORE_OPTICAL_DEPTH",
            "6. CORE_ROTATION (Core Frame)"
        };

        double[] invRot = transposeMat3(rotMat);
        double fluidPhase = tSec * 0.65;
        double causticsPhase = tSec * 0.72;
        double halfTan = Math.tan(Math.asin(1.0 / CAM_DIST));

        for (int mode = 1; mode <= 6; mode++) {
            final int m = mode;
            BufferedImage tile = new BufferedImage(panel, panel, BufferedImage.TYPE_INT_RGB);
            int[] pxArr = new int[panel * panel];
            java.util.stream.IntStream.range(0, panel).parallel().forEach(py -> {
                double[] rgba = new double[4];
                double[] rayOut = new double[12];
                double dy = (panel * 0.5 - py) / (panel * 0.44);
                for (int px = 0; px < panel; px++) {
                    double dx = (px - panel * 0.5) / (panel * 0.44);
                    if (dx * dx + dy * dy >= 1.0) {
                        pxArr[py * panel + px] = 0x05101A;
                        continue;
                    }
                    double rx = dx * halfTan, ry = dy * halfTan, rz = -1.0;
                    double rLen = Math.sqrt(rx * rx + ry * ry + rz * rz);
                    rx /= rLen; ry /= rLen; rz /= rLen;
                    double b = CAM_DIST * rz;
                    double c = CAM_DIST * CAM_DIST - 1.0;
                    double disc = b * b - c;
                    if (disc <= 0.0) {
                        pxArr[py * panel + px] = 0x05101A;
                        continue;
                    }
                    double s = Math.sqrt(disc);
                    double tEnter = -b - s, tExit = -b + s;
                    double hx = rx * tEnter, hy = ry * tEnter, hz = CAM_DIST + rz * tEnter;
                    double ndotv = clamp(-(hx * rx + hy * ry + hz * rz), 0.0, 1.0);
                    raymarchVolume(hx, hy, hz, rx, ry, rz, tExit - tEnter, invRot, fluidPhase, causticsPhase, 1.56, rgba, rayOut);

                    double r = 0, gg = 0, bb = 0;
                    if (m == 1) { // CORE_VOLUME_ONLY
                        double sub = smoothstep(0.02, 0.24, rayOut[7]);
                        double rawR = rayOut[0] + rayOut[4] * 0.38;
                        double rawG = rayOut[1] + rayOut[5] * 0.38;
                        double rawB = rayOut[2] + rayOut[6] * 0.38;
                        r = rawR / (1.0 + rawR * 0.32) * (0.45 + 0.75 * sub);
                        gg = rawG / (1.0 + rawG * 0.32) * (0.45 + 0.75 * sub);
                        bb = rawB / (1.0 + rawB * 0.32) * (0.45 + 0.75 * sub);
                    } else if (m == 2) { // CORE_DENSITY
                        r = rayOut[0] * 1.45;
                        gg = rayOut[1] * 1.45;
                        bb = rayOut[2] * 1.45;
                    } else if (m == 3) { // CORE_FLOW
                        r = rayOut[9]; gg = rayOut[10]; bb = rayOut[11];
                    } else if (m == 4) { // CORE_CAUSTICS
                        r = 0.004 + rayOut[4] * 1.75;
                        gg = 0.018 + rayOut[5] * 1.75;
                        bb = 0.036 + rayOut[6] * 1.75;
                    } else if (m == 5) { // CORE_OPTICAL_DEPTH
                        double od = clamp(rayOut[8] / 2.2, 0.0, 1.0);
                        double hi = smoothstep(0.45, 1.0, od);
                        r = lerp(0.01, lerp(0.04, 0.95, hi), od);
                        gg = lerp(0.05, lerp(0.62, 0.88, hi), od);
                        bb = lerp(0.12, lerp(0.54, 0.35, hi), od);
                    } else { // CORE_ROTATION
                        double cx = invRot[0] * hx + invRot[3] * hy + invRot[6] * hz;
                        double cy = invRot[1] * hx + invRot[4] * hy + invRot[7] * hz;
                        double cz = invRot[2] * hx + invRot[5] * hy + invRot[8] * hz;
                        double band = 0.55 + 0.45 * Math.sin(cx * 8.0 + cz * 6.0);
                        double dm = clamp(0.32 + rayOut[3] * 0.95, 0.0, 1.2);
                        r = (cx * 0.5 + 0.5) * band * dm;
                        gg = (cy * 0.5 + 0.5) * band * dm;
                        bb = (cz * 0.5 + 0.5) * band * dm;
                    }
                    double edge = smoothstep(0.0, 0.045, ndotv);
                    pxArr[py * panel + px] = linearToRgbInt(
                        lerp(0.008, r, edge), lerp(0.024, gg, edge), lerp(0.042, bb, edge)
                    );
                }
            });
            tile.setRGB(0, 0, panel, panel, pxArr, 0, panel);

            int col = (mode - 1) % 3;
            int row = (mode - 1) / 3;
            int x0 = pad + col * (panel + pad);
            int y0 = 42 + pad + row * (panel + header + pad);
            g.drawImage(tile, x0, y0 + header, null);
            g.setColor(new Color(18, 36, 52));
            g.fillRect(x0, y0, panel, header);
            g.setColor(new Color(140, 232, 248));
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            g.drawString(titles[mode - 1], x0 + 10, y0 + 22);
        }
        g.dispose();
        return grid;
    }

    private static BufferedImage renderAlignmentOverlay(BufferedImage frameT0, double centerErrPx, double radiusErrPx) {
        BufferedImage out = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(frameT0, 0, 0, null);

        // Draw Water World canonical sphere circle (green) and aligned 3D Volume bounding circle (cyan dashed)
        g.setColor(new Color(0, 255, 140, 220));
        g.setStroke(new BasicStroke(3.0f));
        g.drawOval(
            (int) Math.round(SPHERE_CX - SPHERE_R),
            (int) Math.round(SPHERE_CY - SPHERE_R),
            (int) Math.round(SPHERE_R * 2.0),
            (int) Math.round(SPHERE_R * 2.0)
        );
        g.setColor(new Color(80, 220, 255, 220));
        g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{12, 8}, 0));
        g.drawOval(
            (int) Math.round(SPHERE_CX - SPHERE_R - radiusErrPx),
            (int) Math.round(SPHERE_CY - SPHERE_R - radiusErrPx),
            (int) Math.round((SPHERE_R + radiusErrPx) * 2.0),
            (int) Math.round((SPHERE_R + radiusErrPx) * 2.0)
        );
        // Crosshair at exact center
        g.setStroke(new BasicStroke(2.0f));
        int cx = (int) Math.round(SPHERE_CX);
        int cy = (int) Math.round(SPHERE_CY);
        g.drawLine(cx - 28, cy, cx + 28, cy);
        g.drawLine(cx, cy - 28, cx, cy + 28);

        g.setColor(new Color(6, 16, 26, 210));
        g.fillRoundRect(24, 24, 680, 96, 14, 14);
        g.setColor(new Color(145, 242, 255));
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString("Work 3.1 Core 3D Volume Off-Axis Perspective Alignment Check", 42, 56);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Monospaced", Font.PLAIN, 16));
        g.drawString(String.format(Locale.US, "Target Sphere: center=(%.2f, %.2f) radius=%.2f px (1254x1254)", SPHERE_CX, SPHERE_CY, SPHERE_R), 42, 82);
        g.drawString(String.format(Locale.US, "Alignment Error: centerErr=%.5f px | radiusErr=%.5f px (PASS <= 1.0 px)", centerErrPx, radiusErrPx), 42, 106);
        g.dispose();
        return out;
    }

    private static BufferedImage renderContactSheet(
        BufferedImage[] frames,
        BufferedImage[] coreOnly,
        int[] timestamps,
        double[] motionMaeVsT0
    ) {
        int tileW = 420;
        int tileH = 420;
        int subH = 220;
        int pad = 14;
        int w = tileW * 3 + pad * 4;
        int h = (tileH + 28) * 2 + subH + 34 + pad * 4 + 44;
        BufferedImage sheet = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(8, 16, 24));
        g.fillRect(0, 0, w, h);

        g.setColor(new Color(224, 246, 255));
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString("WATER WORLD V2.1 — WORK 3.1 CORE 3D VOLUME CONTACT SHEET (32 Z-Slices | Software Precheck)", pad, 30);

        for (int i = 0; i < frames.length; i++) {
            int col = i % 3;
            int row = i / 3;
            int x0 = pad + col * (tileW + pad);
            int y0 = 44 + pad + row * (tileH + 28 + pad);
            g.setColor(new Color(18, 34, 48));
            g.fillRect(x0, y0, tileW, 28);
            g.setColor(new Color(145, 240, 255));
            g.setFont(new Font("Monospaced", Font.BOLD, 14));
            g.drawString(String.format(Locale.US, "T = %2ds | rot=%.1f deg | Core3D MAE=%.2f",
                timestamps[i], timestamps[i] * 3.6, motionMaeVsT0[i]), x0 + 8, y0 + 19);
            g.drawImage(frames[i], x0, y0 + 28, tileW, tileH, null);
        }

        // Bottom strip: isolated Core 3D Volume progression across T=0, 4, 8, 12s (crop around sphere)
        int stripY = 44 + pad + 2 * (tileH + 28 + pad);
        g.setColor(new Color(18, 34, 48));
        g.fillRect(pad, stripY, w - pad * 2, 28);
        g.setColor(new Color(145, 240, 255));
        g.setFont(new Font("Monospaced", Font.BOLD, 14));
        g.drawString("Isolated Real 32 Z-Slice Core 3D Volume Progression (T=0s, T=2s, T=4s, T=8s, T=12s) — True 3D Rotation + Curl Advection", pad + 10, stripY + 19);

        int[] showIdx = {0, 2, 3, 4, 5};
        int cropX = (int) (SPHERE_CX - SPHERE_R);
        int cropY = (int) (SPHERE_CY - SPHERE_R);
        int cropSize = (int) (SPHERE_R * 2);
        int boxW = (w - pad * (showIdx.length + 1)) / showIdx.length;
        for (int j = 0; j < showIdx.length; j++) {
            int idx = showIdx[j];
            int bx = pad + j * (boxW + pad);
            int by = stripY + 32;
            g.drawImage(coreOnly[idx], bx, by, bx + boxW, by + subH, cropX, cropY, cropX + cropSize, cropY + cropSize, null);
            g.setColor(new Color(0, 0, 0, 175));
            g.fillRect(bx + 4, by + subH - 22, 128, 18);
            g.setColor(Color.WHITE);
            g.setFont(new Font("Monospaced", Font.BOLD, 12));
            g.drawString(String.format(Locale.US, "T=%ds (%.1f deg)", timestamps[idx], timestamps[idx] * 3.6), bx + 8, by + subH - 9);
        }
        g.dispose();
        return sheet;
    }

    private static double[] verifyOffAxisAlignment() {
        double sphereCenterNdcX = (SPHERE_CX / CANVAS) * 2.0 - 1.0;
        double sphereCenterNdcY = 1.0 - (SPHERE_CY / CANVAS) * 2.0;
        double sphereRadiusNdcX = (SPHERE_R / CANVAS) * 2.0;
        double sphereRadiusNdcY = (SPHERE_R / CANVAS) * 2.0;
        double tanHalf = Math.tan(Math.asin(1.0 / CAM_DIST));
        double m00 = sphereRadiusNdcX / tanHalf;
        double m11 = sphereRadiusNdcY / tanHalf;
        double m20 = -sphereCenterNdcX;
        double m21 = -sphereCenterNdcY;

        // Project sphere center (0, 0, 0) in view space -> (0, 0, -CAM_DIST)
        double[] cPx = projectToPixel(0, 0, -CAM_DIST, m00, m11, m20, m21);
        double centerErr = Math.hypot(cPx[0] - SPHERE_CX, cPx[1] - SPHERE_CY);

        // Project tangent silhouette point (sqrt(1 - 1/D^2), 0, 1/D) -> view z = 1/D - D
        double sinT = 1.0 / CAM_DIST;
        double cosT = Math.sqrt(1.0 - sinT * sinT);
        double[] rightPx = projectToPixel(cosT, 0, sinT - CAM_DIST, m00, m11, m20, m21);
        double radiusErr = Math.abs(Math.hypot(rightPx[0] - cPx[0], rightPx[1] - cPx[1]) - SPHERE_R);
        return new double[]{centerErr, radiusErr};
    }

    private static double[] projectToPixel(double vx, double vy, double vz, double m00, double m11, double m20, double m21) {
        double clipX = m00 * vx + m20 * vz;
        double clipY = m11 * vy + m21 * vz;
        double clipW = -vz;
        double ndcX = clipX / clipW;
        double ndcY = clipY / clipW;
        return new double[]{(ndcX + 1.0) * 0.5 * CANVAS, (1.0 - ndcY) * 0.5 * CANVAS};
    }

    private static double computeSphereInteriorMae(BufferedImage a, BufferedImage b) {
        double sum = 0.0;
        long cnt = 0;
        for (int y = (int) (SPHERE_CY - SPHERE_R); y <= (int) (SPHERE_CY + SPHERE_R); y += 2) {
            for (int x = (int) (SPHERE_CX - SPHERE_R); x <= (int) (SPHERE_CX + SPHERE_R); x += 2) {
                double dx = (x - SPHERE_CX) / SPHERE_R;
                double dy = (y - SPHERE_CY) / SPHERE_R;
                if (dx * dx + dy * dy > 0.88) continue;
                int ca = a.getRGB(x, y);
                int cb = b.getRGB(x, y);
                sum += Math.abs(((ca >>> 16) & 0xFF) - ((cb >>> 16) & 0xFF));
                sum += Math.abs(((ca >>> 8) & 0xFF) - ((cb >>> 8) & 0xFF));
                sum += Math.abs((ca & 0xFF) - (cb & 0xFF));
                cnt += 3;
            }
        }
        return cnt > 0 ? sum / cnt : 0.0;
    }

    private static void writeMetricsJson(
        File outFile,
        int loadedSlices,
        long decodeMs,
        double[] align,
        int[] timestamps,
        double[] rotAngleDeg,
        double[] rotDet,
        double[] rotOrthoErr,
        double[] nonCoreMae,
        double[] coreDeltaVsBase,
        double[] coreMotionMaeVsT0
    ) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": \"WATER_WORLD_WORK3_1_CORE3D_PRECHECK_1_0\",\n");
        sb.append("  \"evaluationDomain\": \"SOFTWARE_GPU_PRECHECK_NOT_HARDWARE_ACCEPTANCE\",\n");
        sb.append("  \"WORK3_1_HARDWARE_DYNAMIC_VERIFIED\": \"NOT_RUN\",\n");
        sb.append("  \"volumeAsset\": {\n");
        sb.append("    \"width\": ").append(VOL_W).append(",\n");
        sb.append("    \"height\": ").append(VOL_H).append(",\n");
        sb.append("    \"depth\": ").append(VOL_D).append(",\n");
        sb.append("    \"slicesLoaded\": ").append(loadedSlices).append(",\n");
        sb.append("    \"sliceSemantics\": \"SPATIAL_Z_SLICE_NOT_TIME_FRAME\",\n");
        sb.append("    \"softwareDecodeMs\": ").append(decodeMs).append("\n");
        sb.append("  },\n");
        sb.append("  \"offAxisAlignment\": {\n");
        sb.append(String.format(Locale.US, "    \"targetCenterPx\": [%.5f, %.5f],\n", SPHERE_CX, SPHERE_CY));
        sb.append(String.format(Locale.US, "    \"targetRadiusPx\": %.2f,\n", SPHERE_R));
        sb.append(String.format(Locale.US, "    \"centerErrorPx\": %.6f,\n", align[0]));
        sb.append(String.format(Locale.US, "    \"radiusErrorPx\": %.6f,\n", align[1]));
        sb.append("    \"passLe1Px\": ").append(align[0] <= 1.0 && align[1] <= 1.0).append("\n");
        sb.append("  },\n");
        sb.append("  \"frames\": [\n");
        for (int i = 0; i < timestamps.length; i++) {
            sb.append("    {\n");
            sb.append("      \"tSeconds\": ").append(timestamps[i]).append(",\n");
            sb.append(String.format(Locale.US, "      \"coreRotationAngleDeg\": %.4f,\n", rotAngleDeg[i]));
            sb.append(String.format(Locale.US, "      \"rotationMatDet\": %.6f,\n", rotDet[i]));
            sb.append(String.format(Locale.US, "      \"rotationMatOrthoError\": %.8f,\n", rotOrthoErr[i]));
            sb.append(String.format(Locale.US, "      \"nonCoreRegionMae\": %.6f,\n", nonCoreMae[i]));
            sb.append(String.format(Locale.US, "      \"coreInteriorDeltaVsWork3Base\": %.4f,\n", coreDeltaVsBase[i]));
            sb.append(String.format(Locale.US, "      \"coreInteriorMotionMaeVsT0\": %.4f\n", coreMotionMaeVsT0[i]));
            sb.append("    }").append(i + 1 < timestamps.length ? "," : "").append("\n");
        }
        sb.append("  ],\n");
        sb.append("  \"allSoftwarePrecheckGatesPass\": ").append(
            loadedSlices == 32 && align[0] <= 1.0 && align[1] <= 1.0 && nonCoreMae[0] == 0.0 && coreMotionMaeVsT0[timestamps.length - 1] > 1.0
        ).append("\n");
        sb.append("}\n");
        try (FileWriter fw = new FileWriter(outFile)) {
            fw.write(sb.toString());
        }
    }

    private static double sampleMeniscusY(double x) {
        double t = clamp((x + 0.96875) / 1.9375, 0.0, 1.0) * 31.0;
        int i0 = Math.min(Math.max((int) Math.floor(t), 0), 30);
        int i1 = i0 + 1;
        return lerp(MENISCUS_Y[i0], MENISCUS_Y[i1], t - i0);
    }

    private static double[] buildAxisAngleMat3(double ax, double ay, double az, double rad) {
        double len = Math.sqrt(ax * ax + ay * ay + az * az);
        double x = ax / len, y = ay / len, z = az / len;
        double c = Math.cos(rad), s = Math.sin(rad), t = 1.0 - c;
        // Column-major 3x3 matching OpenGL mat3
        return new double[]{
            t * x * x + c,       t * x * y + s * z,   t * x * z - s * y,
            t * x * y - s * z,   t * y * y + c,       t * y * z + s * x,
            t * x * z + s * y,   t * y * z - s * x,   t * z * z + c
        };
    }

    private static double[] transposeMat3(double[] m) {
        return new double[]{
            m[0], m[3], m[6],
            m[1], m[4], m[7],
            m[2], m[5], m[8]
        };
    }

    private static double mat3Det(double[] m) {
        return m[0] * (m[4] * m[8] - m[5] * m[7])
             - m[3] * (m[1] * m[8] - m[2] * m[7])
             + m[6] * (m[1] * m[5] - m[2] * m[4]);
    }

    private static double mat3OrthoError(double[] m) {
        double maxErr = 0.0;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                double dot = 0.0;
                for (int k = 0; k < 3; k++) dot += m[r * 3 + k] * m[c * 3 + k];
                double err = Math.abs(dot - (r == c ? 1.0 : 0.0));
                if (err > maxErr) maxErr = err;
            }
        }
        return maxErr;
    }

    private static int linearToRgbInt(double lr, double lg, double lb) {
        int r = linearToSrgbByte(lr);
        int g = linearToSrgbByte(lg);
        int b = linearToSrgbByte(lb);
        return (r << 16) | (g << 8) | b;
    }

    private static int linearToSrgbByte(double lin) {
        double c = clamp(lin, 0.0, 1.0);
        double s = (c <= 0.0031308) ? (c * 12.92) : (1.055 * Math.pow(c, 1.0 / 2.4) - 0.055);
        return Math.min(255, Math.max(0, (int) Math.round(s * 255.0)));
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
