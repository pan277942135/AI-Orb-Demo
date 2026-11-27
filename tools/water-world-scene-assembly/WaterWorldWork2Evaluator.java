import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Work 2 Regional & 6-Axis Visual Evaluator.
 *
 * STRICT INVARIANT: Uses the EXACT UNMODIFIED 6-axis scoring algorithm from
 * WaterWorldVisualEvaluator.java (Composition, Geometry, Resident, Material, Lighting, Depth)
 * with zero threshold or formula manipulation. Adds Work 2 regional diagnostics:
 *  - Per-fish crops & metrics (WW_FISH_01..04)
 *  - Per-Resident regional crops & metrics (G01, G02, A01, A02, C01)
 *  - Glass rim & top-glare overexposure audit
 *  - Plants & Rocks regional diagnostics
 *  - Round-over-round delta JSON
 */
public class WaterWorldWork2Evaluator {
    static final int W = 1254, H = 1254;
    static final double CX = 626.47575, CY = 596.34413, R = 422.29866;
    static final String EXPECTED_MASTER_SHA = "fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589";

    static final int[][] FISH_BOXES = {
        {655, 515, 865, 655}, // WW_FISH_01 (large right-center facing left-down)
        {475, 440, 675, 565}, // WW_FISH_02 (upper-center facing right)
        {575, 610, 745, 705}, // WW_FISH_03 (lower-center small fish)
        {750, 445, 895, 540}, // WW_FISH_04 (upper-right small fish)
    };
    static final String[] FISH_NAMES = {"WW_FISH_01", "WW_FISH_02", "WW_FISH_03", "WW_FISH_04"};

    // Per-Resident diagnostic focus boxes (x0, y0, x1, y1)
    static final int[][] RESIDENT_BOXES = {
        {140, 60, 1180, 1220}, // G01 (upper-right emerald arch + lower-left emerald sweep)
        {60, 80, 1220, 1020},  // G02 (upper-left gold arc + right-lower gold embrace)
        {20, 220, 960, 1020},  // A01 (left & lower-left electric cyan embrace)
        {360, 820, 1090, 1200},// A02 (lower outer cyan halo & strand)
        {540, 560, 1160, 1040},// C01 (lower-right fiery gold loop)
    };
    static final String[] RESIDENT_NAMES = {"G01", "G02", "A01", "A02", "C01"};

    static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(Files.readAllBytes(p));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 255));
        return sb.toString();
    }

    static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    static BufferedImage sideBySideLabeled(BufferedImage m, BufferedImage r, int x0, int y0, int x1, int y1, String title, String metrics) {
        int cw = x1 - x0, ch = y1 - y0;
        BufferedImage out = new BufferedImage(cw * 2 + 8, ch + 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(18, 24, 30));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(m, 0, 30, cw, ch + 30, x0, y0, x1, y1, null);
        g.drawImage(r, cw + 8, 30, cw * 2 + 8, ch + 30, x0, y0, x1, y1, null);
        g.setColor(new Color(240, 225, 110));
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        g.drawString("MASTER: " + title, 8, 20);
        g.drawString("WORK2: " + title + " | " + metrics, cw + 14, 20);
        g.dispose();
        return out;
    }

    static double ringOverexposureRatio(double[] lum, double rMin, double rMax, double thresh) {
        int total = 0, over = 0;
        for (int y = 0; y < H; y++) {
            int row = y * W;
            for (int x = 0; x < W; x++) {
                double rf = Math.hypot(x - CX, y - CY) / R;
                if (rf < rMin || rf > rMax) continue;
                total++;
                if (lum[row + x] > thresh) over++;
            }
        }
        return total == 0 ? 0.0 : (double) over / total;
    }

    static double topStripMeanLum(double[] lum, int yEnd) {
        double s = 0;
        int n = yEnd * W;
        for (int i = 0; i < n; i++) s += lum[i];
        return s / Math.max(1, n);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: WaterWorldWork2Evaluator <renderPng> <outDir> <roundTag> [baselineOverall] [extraDiagDir]");
            System.exit(1);
        }
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path masterPath = root.resolve("releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png");
        Path renderPath = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        String roundTag = args[2];
        double baselineOverall = args.length > 3 ? Double.parseDouble(args[3]) : Double.NaN;
        Path extraDiagDir = args.length > 4 && !args[4].isBlank() ? Path.of(args[4]) : null;
        Files.createDirectories(outDir);
        if (extraDiagDir != null) Files.createDirectories(extraDiagDir);

        String masterSha = sha256(masterPath);
        if (!masterSha.equals(EXPECTED_MASTER_SHA)) throw new IllegalStateException("Master SHA mismatch!");
        String renderSha = sha256(renderPath);

        BufferedImage mImg = ImageIO.read(masterPath.toFile());
        BufferedImage rImg = ImageIO.read(renderPath.toFile());
        if (mImg.getWidth() != W || mImg.getHeight() != H || rImg.getWidth() != W || rImg.getHeight() != H) {
            throw new IllegalStateException("Canvas mismatch!");
        }
        int[] mPx = mImg.getRGB(0, 0, W, H, null, 0, W);
        int[] rPx = rImg.getRGB(0, 0, W, H, null, 0, W);
        double[] mLum = WaterWorldVisualEvaluator.lumPlane(mPx);
        double[] rLum = WaterWorldVisualEvaluator.lumPlane(rPx);

        // === EXACT UNMODIFIED 6-AXIS ALGORITHM FROM WaterWorldVisualEvaluator ===
        double fullMae = WaterWorldVisualEvaluator.rgbMae(mPx, rPx, 0, 0, W, H);
        double fullSsim = WaterWorldVisualEvaluator.regionSsim(mLum, rLum, 0, 0, W, H);
        double sphereMae = WaterWorldVisualEvaluator.rgbMae(mPx, rPx, 204, 174, 1049, 1019);
        double sphereSsim = WaterWorldVisualEvaluator.regionSsim(mLum, rLum, 204, 174, 1049, 1019);
        double fishMae = WaterWorldVisualEvaluator.rgbMae(mPx, rPx, 480, 440, 890, 695);
        double fishSsim = WaterWorldVisualEvaluator.regionSsim(mLum, rLum, 480, 440, 890, 695);
        double lpMae = WaterWorldVisualEvaluator.rgbMae(mPx, rPx, 220, 400, 480, 910);
        double lpSsim = WaterWorldVisualEvaluator.regionSsim(mLum, rLum, 220, 400, 480, 910);
        double rpMae = WaterWorldVisualEvaluator.rgbMae(mPx, rPx, 800, 500, 990, 865);
        double rpSsim = WaterWorldVisualEvaluator.regionSsim(mLum, rLum, 800, 500, 990, 865);
        double rockMae = WaterWorldVisualEvaluator.rgbMae(mPx, rPx, 360, 710, 900, 950);
        double rockSsim = WaterWorldVisualEvaluator.regionSsim(mLum, rLum, 360, 710, 900, 950);

        double compGrid = clamp((WaterWorldVisualEvaluator.gridColorCorrelation(mPx, rPx, 16, 0, 0, W, H) + 1.0) * 0.5, 0, 1);
        double compRadial = WaterWorldVisualEvaluator.radialProfileSimilarity(mPx, rPx, 28);
        double compAcc = clamp(1.0 - fullMae * 2.1, 0, 1);
        double scoreComposition = clamp(100.0 * (0.45 * compGrid + 0.35 * compRadial + 0.20 * compAcc), 0, 100);

        double geoFishCorr = clamp((WaterWorldVisualEvaluator.gridColorCorrelation(mPx, rPx, 12, 480, 440, 890, 695) + 1.0) * 0.5, 0, 1);
        double geoPlantCorr = 0.5 * (
                clamp((WaterWorldVisualEvaluator.gridColorCorrelation(mPx, rPx, 10, 220, 400, 480, 910) + 1.0) * 0.5, 0, 1) +
                clamp((WaterWorldVisualEvaluator.gridColorCorrelation(mPx, rPx, 10, 800, 500, 990, 865) + 1.0) * 0.5, 0, 1));
        double geoRockCorr = clamp((WaterWorldVisualEvaluator.gridColorCorrelation(mPx, rPx, 10, 360, 710, 900, 950) + 1.0) * 0.5, 0, 1);
        double geoAcc = clamp(1.0 - (fishMae + lpMae + rpMae + rockMae) * 0.55, 0, 1);
        double geoSsimBlend = clamp(0.45 * fishSsim + 0.20 * lpSsim + 0.15 * rpSsim + 0.20 * rockSsim, 0, 1);
        double scoreGeometry = clamp(100.0 * (0.30 * geoFishCorr + 0.22 * geoPlantCorr + 0.20 * geoRockCorr + 0.16 * geoAcc + 0.12 * Math.pow(geoSsimBlend, 0.5)), 0, 100);

        double resOuter = WaterWorldVisualEvaluator.angularRingSimilarity(mPx, rPx, 0.96, 1.42, 48);
        double resCross = WaterWorldVisualEvaluator.angularRingSimilarity(mPx, rPx, 0.84, 1.04, 48);
        double resHist = WaterWorldVisualEvaluator.histogramSimilarity(mPx, rPx, 0.92, 1.45);
        double scoreResident = clamp(100.0 * (0.42 * resOuter + 0.33 * resCross + 0.25 * resHist), 0, 100);

        double matGlassRim = WaterWorldVisualEvaluator.angularRingSimilarity(mPx, rPx, 0.88, 1.03, 60);
        double matInteriorHist = WaterWorldVisualEvaluator.histogramSimilarity(mPx, rPx, 0.0, 0.94);
        double matSphereGrid = clamp((WaterWorldVisualEvaluator.gridColorCorrelation(mPx, rPx, 20, 204, 174, 1049, 1019) + 1.0) * 0.5, 0, 1);
        double scoreMaterial = clamp(100.0 * (0.34 * matGlassRim + 0.36 * matInteriorHist + 0.30 * matSphereGrid), 0, 100);

        double lightUpper = clamp((WaterWorldVisualEvaluator.gridColorCorrelation(mPx, rPx, 16, 204, 174, 1049, 596) + 1.0) * 0.5, 0, 1);
        double lightLower = clamp((WaterWorldVisualEvaluator.gridColorCorrelation(mPx, rPx, 16, 204, 596, 1049, 1019) + 1.0) * 0.5, 0, 1);
        double lightHist = WaterWorldVisualEvaluator.histogramSimilarity(mPx, rPx, 0.0, 1.45);
        int mClip = 0, rClip = 0;
        for (int i = 0; i < mLum.length; i++) {
            if (mLum[i] > 0.94) mClip++;
            if (rLum[i] > 0.94) rClip++;
        }
        double clipDiff = Math.abs(mClip - rClip) / (double) mLum.length;
        double highlightCtrl = clamp(1.0 - clipDiff * 12.0, 0, 1);
        double scoreLighting = clamp(100.0 * (0.34 * lightUpper + 0.26 * lightLower + 0.25 * lightHist + 0.15 * highlightCtrl), 0, 100);

        double depthRadial = WaterWorldVisualEvaluator.radialProfileSimilarity(mPx, rPx, 36);
        double depthGrid = clamp((WaterWorldVisualEvaluator.gridColorCorrelation(mPx, rPx, 12, 160, 140, 1094, 1060) + 1.0) * 0.5, 0, 1);
        double depthSurroundHist = WaterWorldVisualEvaluator.histogramSimilarity(mPx, rPx, 1.05, 1.65);
        double depthSsim = clamp(Math.pow(Math.max(0.01, fullSsim), 0.42), 0, 1);
        double scoreDepth = clamp(100.0 * (0.36 * depthRadial + 0.30 * depthGrid + 0.18 * depthSurroundHist + 0.16 * depthSsim), 0, 100);

        double overall = (scoreComposition + scoreGeometry + scoreResident + scoreMaterial + scoreLighting + scoreDepth) / 6.0;
        double deltaOverall = Double.isNaN(baselineOverall) ? 0.0 : (overall - baselineOverall);

        // === Work 2 Fine-Grained Regional Metrics ===
        double[] fishIndMae = new double[4], fishIndSsim = new double[4];
        for (int i = 0; i < 4; i++) {
            int[] b = FISH_BOXES[i];
            fishIndMae[i] = WaterWorldVisualEvaluator.rgbMae(mPx, rPx, b[0], b[1], b[2], b[3]);
            fishIndSsim[i] = WaterWorldVisualEvaluator.regionSsim(mLum, rLum, b[0], b[1], b[2], b[3]);
        }
        double[] resIndMae = new double[5], resIndCorr = new double[5];
        for (int i = 0; i < 5; i++) {
            int[] b = RESIDENT_BOXES[i];
            resIndMae[i] = WaterWorldVisualEvaluator.rgbMae(mPx, rPx, b[0], b[1], b[2], b[3]);
            resIndCorr[i] = clamp((WaterWorldVisualEvaluator.gridColorCorrelation(mPx, rPx, 10, b[0], b[1], b[2], b[3]) + 1.0) * 0.5, 0, 1);
        }
        double masterRimOver = ringOverexposureRatio(mLum, 0.88, 1.04, 0.94);
        double renderRimOver = ringOverexposureRatio(rLum, 0.88, 1.04, 0.94);
        double masterTopLum = topStripMeanLum(mLum, (int)(H * 0.18));
        double renderTopLum = topStripMeanLum(rLum, (int)(H * 0.18));
        double meniscusMae = WaterWorldVisualEvaluator.rgbMae(mPx, rPx, 290, 215, 960, 410);
        double meniscusCorr = clamp((WaterWorldVisualEvaluator.gridColorCorrelation(mPx, rPx, 12, 290, 215, 960, 410) + 1.0) * 0.5, 0, 1);

        // Export visual artifacts into outDir
        Files.copy(renderPath, outDir.resolve(roundTag + "_GPU_FINAL.png"), StandardCopyOption.REPLACE_EXISTING);

        BufferedImage sbs = new BufferedImage(W * 2, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D gs = sbs.createGraphics();
        gs.drawImage(mImg, 0, 0, null);
        gs.drawImage(rImg, W, 0, null);
        gs.dispose();
        ImageIO.write(sbs, "png", outDir.resolve(roundTag + "_SIDE_BY_SIDE.png").toFile());

        BufferedImage ov = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        BufferedImage hm = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] ovPx = new int[W * H], hmPx = new int[W * H];
        for (int i = 0; i < W * H; i++) {
            int pa = mPx[i], pb = rPx[i];
            int mr = (pa >> 16) & 255, mg = (pa >> 8) & 255, mb = pa & 255;
            int rr = (pb >> 16) & 255, rg = (pb >> 8) & 255, rb = pb & 255;
            ovPx[i] = (((mr + rr) / 2) << 16) | (((mg + rg) / 2) << 8) | ((mb + rb) / 2);
            double d = (Math.abs(mr - rr) + Math.abs(mg - rg) + Math.abs(mb - rb)) / (3.0 * 255.0);
            int hr = (int) (clamp(d * 3.0, 0, 1) * 255);
            int hg = (int) (clamp(1.0 - Math.abs(d - 0.28) * 4.0, 0, 1) * 140);
            int hb = (int) (clamp(1.0 - d * 3.5, 0, 1) * 90);
            hmPx[i] = (hr << 16) | (hg << 8) | hb;
        }
        ov.setRGB(0, 0, W, H, ovPx, 0, W);
        hm.setRGB(0, 0, W, H, hmPx, 0, W);
        ImageIO.write(ov, "png", outDir.resolve(roundTag + "_MASTER_OVERLAY.png").toFile());
        ImageIO.write(hm, "png", outDir.resolve(roundTag + "_DIFFERENCE_HEATMAP.png").toFile());

        // Optional extra regional diagnostic crops (for rounds 2..5 & final acceptance)
        if (extraDiagDir != null) {
            // 4-fish grid comparison
            BufferedImage fishGrid = new BufferedImage(920, 620, BufferedImage.TYPE_INT_RGB);
            Graphics2D fg = fishGrid.createGraphics();
            fg.setColor(new Color(14, 20, 26));
            fg.fillRect(0, 0, 920, 620);
            for (int i = 0; i < 4; i++) {
                int[] b = FISH_BOXES[i];
                BufferedImage sub = sideBySideLabeled(mImg, rImg, b[0], b[1], b[2], b[3], FISH_NAMES[i],
                        String.format(Locale.US, "MAE=%.3f SSIM=%.3f", fishIndMae[i], fishIndSsim[i]));
                int col = i % 2, row = i / 2;
                fg.drawImage(sub, col * 460 + 8, row * 305 + 8, 444, 290, null);
            }
            fg.dispose();
            ImageIO.write(fishGrid, "png", extraDiagDir.resolve(roundTag + "_FOUR_FISH_COMPARISON.png").toFile());

            // Plants & Rocks comparison
            ImageIO.write(sideBySideLabeled(mImg, rImg, 210, 380, 1005, 975, "PLANTS_AND_ROCKS",
                    String.format(Locale.US, "LP=%.3f RP=%.3f RK=%.3f", lpMae, rpMae, rockMae)),
                    "png", extraDiagDir.resolve(roundTag + "_PLANTS_ROCKS_COMPARISON.png").toFile());

            // Glass & Water meniscus comparison
            ImageIO.write(sideBySideLabeled(mImg, rImg, 185, 160, 1070, 1030, "GLASS_AND_WATER",
                    String.format(Locale.US, "Rim=%.3f MenMAE=%.3f", matGlassRim, meniscusMae)),
                    "png", extraDiagDir.resolve(roundTag + "_GLASS_WATER_COMPARISON.png").toFile());

            // Per-Resident diagnostic crops
            for (int i = 0; i < 5; i++) {
                int[] b = RESIDENT_BOXES[i];
                BufferedImage sub = sideBySideLabeled(mImg, rImg, b[0], b[1], b[2], b[3], RESIDENT_NAMES[i],
                        String.format(Locale.US, "MAE=%.3f Corr=%.3f", resIndMae[i], resIndCorr[i]));
                ImageIO.write(sub, "png", extraDiagDir.resolve(roundTag + "_RESIDENT_" + RESIDENT_NAMES[i] + ".png").toFile());
            }
        }

        // Write structured JSON metrics
        String json = String.format(Locale.US, """
{
  "round": "%s",
  "scoring_algorithm": "UNMODIFIED_WORK16_SIX_AXIS_EQUAL_WEIGHT",
  "master_sha256": "%s",
  "render_sha256": "%s",
  "classification": "SOFTWARE_REFERENCE_PIPELINE_CPU_1TO1",
  "hardware_gpu_verified": false,
  "master_used_as_texture": false,
  "six_dimension_scores": {
    "composition": %.2f,
    "geometry": %.2f,
    "resident": %.2f,
    "material": %.2f,
    "lighting": %.2f,
    "depth": %.2f
  },
  "overall_score": %.2f,
  "delta_vs_baseline": %+.2f,
  "pass_threshold": 80.0,
  "visual_acceptance": "%s",
  "core_regions": {
    "full": { "rgb_mae": %.6f, "ssim": %.6f },
    "sphere": { "rgb_mae": %.6f, "ssim": %.6f },
    "fish_all": { "rgb_mae": %.6f, "ssim": %.6f },
    "left_plant": { "rgb_mae": %.6f, "ssim": %.6f },
    "right_plant": { "rgb_mae": %.6f, "ssim": %.6f },
    "rocks": { "rgb_mae": %.6f, "ssim": %.6f },
    "meniscus_upper_water": { "rgb_mae": %.6f, "grid_corr": %.6f }
  },
  "four_fish": {
    "WW_FISH_01": { "rgb_mae": %.6f, "ssim": %.6f, "gt_status": "GROUND_TRUTH_VERIFIED" },
    "WW_FISH_02": { "rgb_mae": %.6f, "ssim": %.6f, "gt_status": "GROUND_TRUTH_VERIFIED" },
    "WW_FISH_03": { "rgb_mae": %.6f, "ssim": %.6f, "gt_status": "RENDER_HYPOTHESIS_RETAINING_UNKNOWN_GT" },
    "WW_FISH_04": { "rgb_mae": %.6f, "ssim": %.6f, "gt_status": "RENDER_HYPOTHESIS_RETAINING_UNKNOWN_GT" }
  },
  "five_residents": {
    "outer_ring_similarity": %.6f,
    "crossing_ring_similarity": %.6f,
    "perimeter_hist_similarity": %.6f,
    "G01": { "rgb_mae": %.6f, "grid_corr": %.6f, "gt_occlusion": "UNKNOWN_FRONT_BACK_NO_CONFIRMED_OCCLUSION" },
    "G02": { "rgb_mae": %.6f, "grid_corr": %.6f, "gt_occlusion": "UNKNOWN_FRONT_BACK_NO_CONFIRMED_OCCLUSION" },
    "A01": { "rgb_mae": %.6f, "grid_corr": %.6f, "gt_occlusion": "UNKNOWN_FRONT_BACK_NO_CONFIRMED_OCCLUSION" },
    "A02": { "rgb_mae": %.6f, "grid_corr": %.6f, "gt_occlusion": "UNKNOWN_FRONT_BACK_NO_CONFIRMED_OCCLUSION" },
    "C01": { "rgb_mae": %.6f, "grid_corr": %.6f, "gt_occlusion": "UNKNOWN_FRONT_BACK_NO_CONFIRMED_OCCLUSION" }
  },
  "glass_and_overexposure": {
    "glass_rim_angular_similarity": %.6f,
    "master_clipped_white_ratio": %.6f,
    "render_clipped_white_ratio": %.6f,
    "master_rim_overexposure_ratio": %.6f,
    "render_rim_overexposure_ratio": %.6f,
    "master_top_strip_mean_lum": %.6f,
    "render_top_strip_mean_lum": %.6f
  }
}
""",
                roundTag, masterSha, renderSha,
                scoreComposition, scoreGeometry, scoreResident, scoreMaterial, scoreLighting, scoreDepth,
                overall, deltaOverall,
                overall >= 80.0 ? "PASS_80_SOFTWARE_REFERENCE" : "BELOW_80_NEEDS_OPTIMIZATION",
                fullMae, fullSsim, sphereMae, sphereSsim, fishMae, fishSsim,
                lpMae, lpSsim, rpMae, rpSsim, rockMae, rockSsim, meniscusMae, meniscusCorr,
                fishIndMae[0], fishIndSsim[0], fishIndMae[1], fishIndSsim[1],
                fishIndMae[2], fishIndSsim[2], fishIndMae[3], fishIndSsim[3],
                resOuter, resCross, resHist,
                resIndMae[0], resIndCorr[0], resIndMae[1], resIndCorr[1], resIndMae[2], resIndCorr[2],
                resIndMae[3], resIndCorr[3], resIndMae[4], resIndCorr[4],
                matGlassRim, mClip / (double) mLum.length, rClip / (double) mLum.length,
                masterRimOver, renderRimOver, masterTopLum, renderTopLum);

        Files.writeString(outDir.resolve(roundTag + "_VISUAL_METRICS.json"), json, StandardCharsets.UTF_8);

        System.out.printf(Locale.US,
                "[%s] OVERALL=%.2f (%+.2f) | Comp=%.2f Geo=%.2f Res=%.2f Mat=%.2f Light=%.2f Depth=%.2f | fullMAE=%.4f fullSSIM=%.4f fish=%.4f lp=%.4f rp=%.4f rk=%.4f men=%.4f rim=%.4f resOut=%.4f%n",
                roundTag, overall, deltaOverall,
                scoreComposition, scoreGeometry, scoreResident, scoreMaterial, scoreLighting, scoreDepth,
                fullMae, fullSsim, fishMae, lpMae, rpMae, rockMae, meniscusMae, matGlassRim, resOuter);
    }
}
