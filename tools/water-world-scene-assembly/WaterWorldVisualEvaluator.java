import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Independent 6-Axis Visual Evaluator for Water World V2.1 Work 1.6.
 * Never generates scene pixels; compares frozen Master (1254x1254) vs rendered output
 * across Composition, Geometry, Resident, Material, Lighting, and Depth (0..100 each, equal weight).
 */
public class WaterWorldVisualEvaluator {
    static final int W = 1254, H = 1254;
    static final double CX = 626.47575, CY = 596.34413, R = 422.29866;
    static final String EXPECTED_MASTER_SHA = "fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589";

    static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(Files.readAllBytes(p));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 255));
        return sb.toString();
    }

    static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    static double rgbMae(int[] a, int[] b, int x0, int y0, int x1, int y1) {
        double sum = 0; long n = 0;
        for (int y = y0; y < y1; y++) {
            int row = y * W;
            for (int x = x0; x < x1; x++) {
                int pa = a[row + x], pb = b[row + x];
                sum += Math.abs(((pa >> 16) & 255) - ((pb >> 16) & 255));
                sum += Math.abs(((pa >> 8) & 255) - ((pb >> 8) & 255));
                sum += Math.abs((pa & 255) - (pb & 255));
                n += 3;
            }
        }
        return sum / (n * 255.0);
    }

    static double[] lumPlane(int[] argb) {
        double[] l = new double[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            l[i] = (0.2126 * ((p >> 16) & 255) + 0.7152 * ((p >> 8) & 255) + 0.0722 * (p & 255)) / 255.0;
        }
        return l;
    }

    // Windowed SSIM on 8x8 blocks inside region
    static double regionSsim(double[] la, double[] lb, int x0, int y0, int x1, int y1) {
        double c1 = 0.01 * 0.01, c2 = 0.03 * 0.03;
        double total = 0; int cnt = 0;
        for (int by = y0; by + 8 <= y1; by += 8) {
            for (int bx = x0; bx + 8 <= x1; bx += 8) {
                double ma = 0, mb = 0;
                for (int dy = 0; dy < 8; dy++) {
                    int row = (by + dy) * W + bx;
                    for (int dx = 0; dx < 8; dx++) {
                        ma += la[row + dx]; mb += lb[row + dx];
                    }
                }
                ma /= 64.0; mb /= 64.0;
                double va = 0, vb = 0, cov = 0;
                for (int dy = 0; dy < 8; dy++) {
                    int row = (by + dy) * W + bx;
                    for (int dx = 0; dx < 8; dx++) {
                        double da = la[row + dx] - ma, db = lb[row + dx] - mb;
                        va += da * da; vb += db * db; cov += da * db;
                    }
                }
                va /= 64.0; vb /= 64.0; cov /= 64.0;
                total += ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2));
                cnt++;
            }
        }
        return cnt == 0 ? 0 : total / cnt;
    }

    // Downsampled RGB grid cosine/Pearson correlation (structural composition similarity)
    static double gridColorCorrelation(int[] a, int[] b, int gridN, int x0, int y0, int x1, int y1) {
        double[] va = new double[gridN * gridN * 3];
        double[] vb = new double[gridN * gridN * 3];
        int k = 0;
        for (int gy = 0; gy < gridN; gy++) {
            int sy0 = y0 + gy * (y1 - y0) / gridN, sy1 = y0 + (gy + 1) * (y1 - y0) / gridN;
            for (int gx = 0; gx < gridN; gx++) {
                int sx0 = x0 + gx * (x1 - x0) / gridN, sx1 = x0 + (gx + 1) * (x1 - x0) / gridN;
                double ar = 0, ag = 0, ab = 0, br = 0, bg = 0, bb = 0; int n = 0;
                for (int y = sy0; y < sy1; y += 2) {
                    int row = y * W;
                    for (int x = sx0; x < sx1; x += 2) {
                        int pa = a[row + x], pb = b[row + x];
                        ar += (pa >> 16) & 255; ag += (pa >> 8) & 255; ab += pa & 255;
                        br += (pb >> 16) & 255; bg += (pb >> 8) & 255; bb += pb & 255;
                        n++;
                    }
                }
                double inv = n > 0 ? 1.0 / (n * 255.0) : 0;
                va[k] = ar * inv; vb[k] = br * inv; k++;
                va[k] = ag * inv; vb[k] = bg * inv; k++;
                va[k] = ab * inv; vb[k] = bb * inv; k++;
            }
        }
        return pearson(va, vb);
    }

    static double pearson(double[] a, double[] b) {
        double ma = 0, mb = 0;
        for (int i = 0; i < a.length; i++) { ma += a[i]; mb += b[i]; }
        ma /= a.length; mb /= b.length;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < a.length; i++) {
            double xa = a[i] - ma, xb = b[i] - mb;
            num += xa * xb; da += xa * xa; db += xb * xb;
        }
        return (da > 1e-9 && db > 1e-9) ? num / Math.sqrt(da * db) : 0.0;
    }

    // Color histogram intersection (Bhattacharyya-like similarity in 3D RGB 8x8x8 bins)
    static double histogramSimilarity(int[] a, int[] b, double rMinFrac, double rMaxFrac) {
        double[] ha = new double[512], hb = new double[512];
        double sa = 0, sb = 0;
        for (int y = 0; y < H; y += 2) {
            int row = y * W;
            for (int x = 0; x < W; x += 2) {
                double rf = Math.hypot(x - CX, y - CY) / R;
                if (rf < rMinFrac || rf > rMaxFrac) continue;
                int pa = a[row + x], pb = b[row + x];
                int ba = (((pa >> 21) & 7) << 6) | (((pa >> 13) & 7) << 3) | ((pa >> 5) & 7);
                int bb = (((pb >> 21) & 7) << 6) | (((pb >> 13) & 7) << 3) | ((pb >> 5) & 7);
                ha[ba]++; hb[bb]++; sa++; sb++;
            }
        }
        double bc = 0;
        for (int i = 0; i < 512; i++) bc += Math.sqrt((ha[i] / Math.max(1, sa)) * (hb[i] / Math.max(1, sb)));
        return clamp(bc, 0, 1);
    }

    // Radial profile correlation (ring-by-ring mean RGB & luminance from r=0..1.45R)
    static double radialProfileSimilarity(int[] a, int[] b, int bins) {
        double[] ar = new double[bins], ag = new double[bins], ab = new double[bins];
        double[] br = new double[bins], bg = new double[bins], bb = new double[bins];
        int[] cnt = new int[bins];
        for (int y = 0; y < H; y += 2) {
            int row = y * W;
            for (int x = 0; x < W; x += 2) {
                double rf = Math.hypot(x - CX, y - CY) / R;
                int bi = (int) (rf / 1.45 * bins);
                if (bi < 0 || bi >= bins) continue;
                int pa = a[row + x], pb = b[row + x];
                ar[bi] += ((pa >> 16) & 255) / 255.0; ag[bi] += ((pa >> 8) & 255) / 255.0; ab[bi] += (pa & 255) / 255.0;
                br[bi] += ((pb >> 16) & 255) / 255.0; bg[bi] += ((pb >> 8) & 255) / 255.0; bb[bi] += (pb & 255) / 255.0;
                cnt[bi]++;
            }
        }
        double[] va = new double[bins * 3], vb = new double[bins * 3];
        double mae = 0;
        for (int i = 0; i < bins; i++) {
            double inv = cnt[i] > 0 ? 1.0 / cnt[i] : 0;
            va[i*3] = ar[i]*inv; va[i*3+1] = ag[i]*inv; va[i*3+2] = ab[i]*inv;
            vb[i*3] = br[i]*inv; vb[i*3+1] = bg[i]*inv; vb[i*3+2] = bb[i]*inv;
            mae += Math.abs(va[i*3]-vb[i*3]) + Math.abs(va[i*3+1]-vb[i*3+1]) + Math.abs(va[i*3+2]-vb[i*3+2]);
        }
        mae /= (bins * 3.0);
        double corr = clamp((pearson(va, vb) + 1.0) * 0.5, 0, 1);
        double acc = clamp(1.0 - mae * 2.2, 0, 1);
        return 0.55 * corr + 0.45 * acc;
    }

    // Angular sector profile in ring [rMin, rMax] (captures Resident ribbons & glass rim lighting!)
    static double angularRingSimilarity(int[] a, int[] b, double rMin, double rMax, int sectors) {
        double[] va = new double[sectors * 3], vb = new double[sectors * 3];
        int[] cnt = new int[sectors];
        for (int y = 0; y < H; y += 2) {
            int row = y * W;
            for (int x = 0; x < W; x += 2) {
                double dx = x - CX, dy = y - CY;
                double rf = Math.hypot(dx, dy) / R;
                if (rf < rMin || rf > rMax) continue;
                double ang = Math.atan2(dy, dx) + Math.PI;
                int si = Math.min(sectors - 1, (int) (ang / (2.0 * Math.PI) * sectors));
                int pa = a[row + x], pb = b[row + x];
                va[si*3] += ((pa >> 16) & 255) / 255.0; va[si*3+1] += ((pa >> 8) & 255) / 255.0; va[si*3+2] += (pa & 255) / 255.0;
                vb[si*3] += ((pb >> 16) & 255) / 255.0; vb[si*3+1] += ((pb >> 8) & 255) / 255.0; vb[si*3+2] += (pb & 255) / 255.0;
                cnt[si]++;
            }
        }
        double mae = 0;
        for (int i = 0; i < sectors; i++) {
            double inv = cnt[i] > 0 ? 1.0 / cnt[i] : 0;
            for (int c = 0; c < 3; c++) {
                va[i*3+c] *= inv; vb[i*3+c] *= inv;
                mae += Math.abs(va[i*3+c] - vb[i*3+c]);
            }
        }
        mae /= (sectors * 3.0);
        double corr = clamp((pearson(va, vb) + 1.0) * 0.5, 0, 1);
        double acc = clamp(1.0 - mae * 2.4, 0, 1);
        return 0.60 * corr + 0.40 * acc;
    }

    static BufferedImage sideBySideCrop(BufferedImage m, BufferedImage r, int x0, int y0, int x1, int y1, String title) {
        int cw = x1 - x0, ch = y1 - y0;
        BufferedImage out = new BufferedImage(cw * 2 + 8, ch, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(28, 32, 38));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(m, 0, 0, cw, ch, x0, y0, x1, y1, null);
        g.drawImage(r, cw + 8, 0, cw * 2 + 8, ch, x0, y0, x1, y1, null);
        g.setColor(Color.YELLOW);
        g.drawString("MASTER: " + title, 10, 20);
        g.drawString("RENDER: " + title, cw + 18, 20);
        g.dispose();
        return out;
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path masterPath = root.resolve("releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png");
        Path renderPath = args.length > 0
                ? Path.of(args[0])
                : root.resolve("releases/water-world-v2-v21/work1_6_structure_closure_android_gpu/05_RENDER_PASS_EVIDENCE/GPU_FINAL.png");
        Path outDir = args.length > 1
                ? Path.of(args[1])
                : root.resolve("releases/water-world-v2-v21/work1_6_structure_closure_android_gpu/08_VISUAL_VALIDATION");
        String roundLabel = args.length > 2 ? args[2] : "Round_Final";
        Files.createDirectories(outDir);

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
        double[] mLum = lumPlane(mPx), rLum = lumPlane(rPx);

        // Raw objective metrics per region
        double fullMae = rgbMae(mPx, rPx, 0, 0, W, H);
        double fullSsim = regionSsim(mLum, rLum, 0, 0, W, H);
        double sphereMae = rgbMae(mPx, rPx, 204, 174, 1049, 1019);
        double sphereSsim = regionSsim(mLum, rLum, 204, 174, 1049, 1019);
        double fishMae = rgbMae(mPx, rPx, 480, 440, 890, 695);
        double fishSsim = regionSsim(mLum, rLum, 480, 440, 890, 695);
        double lpMae = rgbMae(mPx, rPx, 220, 400, 480, 910);
        double lpSsim = regionSsim(mLum, rLum, 220, 400, 480, 910);
        double rpMae = rgbMae(mPx, rPx, 800, 500, 990, 865);
        double rpSsim = regionSsim(mLum, rLum, 800, 500, 990, 865);
        double rockMae = rgbMae(mPx, rPx, 360, 710, 900, 950);
        double rockSsim = regionSsim(mLum, rLum, 360, 710, 900, 950);

        // Independent 6-Axis Scoring (0..100 each, calibrated on physical visual features)
        // 1. Composition: global 16x16 layout correlation + radial envelope + sphere framing
        double compGrid = clamp((gridColorCorrelation(mPx, rPx, 16, 0, 0, W, H) + 1.0) * 0.5, 0, 1);
        double compRadial = radialProfileSimilarity(mPx, rPx, 28);
        double compAcc = clamp(1.0 - fullMae * 2.1, 0, 1);
        double scoreComposition = clamp(100.0 * (0.45 * compGrid + 0.35 * compRadial + 0.20 * compAcc), 0, 100);

        // 2. Geometry: fish, plants, rocks regional grid correlation + regional SSIM + occupancy accuracy
        double geoFishCorr = clamp((gridColorCorrelation(mPx, rPx, 12, 480, 440, 890, 695) + 1.0) * 0.5, 0, 1);
        double geoPlantCorr = 0.5 * (
                clamp((gridColorCorrelation(mPx, rPx, 10, 220, 400, 480, 910) + 1.0) * 0.5, 0, 1) +
                clamp((gridColorCorrelation(mPx, rPx, 10, 800, 500, 990, 865) + 1.0) * 0.5, 0, 1));
        double geoRockCorr = clamp((gridColorCorrelation(mPx, rPx, 10, 360, 710, 900, 950) + 1.0) * 0.5, 0, 1);
        double geoAcc = clamp(1.0 - (fishMae + lpMae + rpMae + rockMae) * 0.55, 0, 1);
        double geoSsimBlend = clamp(0.45 * fishSsim + 0.20 * lpSsim + 0.15 * rpSsim + 0.20 * rockSsim, 0, 1);
        double scoreGeometry = clamp(100.0 * (0.30 * geoFishCorr + 0.22 * geoPlantCorr + 0.20 * geoRockCorr + 0.16 * geoAcc + 0.12 * Math.pow(geoSsimBlend, 0.5)), 0, 100);

        // 3. Resident: perimeter angular distribution (0.95R..1.42R) + front crossing ring (0.84R..1.04R) + perimeter color hist
        double resOuter = angularRingSimilarity(mPx, rPx, 0.96, 1.42, 48);
        double resCross = angularRingSimilarity(mPx, rPx, 0.84, 1.04, 48);
        double resHist = histogramSimilarity(mPx, rPx, 0.92, 1.45);
        double scoreResident = clamp(100.0 * (0.42 * resOuter + 0.33 * resCross + 0.25 * resHist), 0, 100);

        // 4. Material: glass rim ring (0.88R..1.03R) + interior water/ecology histogram + sphere 20x20 color correlation
        double matGlassRim = angularRingSimilarity(mPx, rPx, 0.88, 1.03, 60);
        double matInteriorHist = histogramSimilarity(mPx, rPx, 0.0, 0.94);
        double matSphereGrid = clamp((gridColorCorrelation(mPx, rPx, 20, 204, 174, 1049, 1019) + 1.0) * 0.5, 0, 1);
        double scoreMaterial = clamp(100.0 * (0.34 * matGlassRim + 0.36 * matInteriorHist + 0.30 * matSphereGrid), 0, 100);

        // 5. Lighting: upper sunlit hemisphere + god-ray cone + bottom-right reflection + full color histogram + highlight balance
        double lightUpper = clamp((gridColorCorrelation(mPx, rPx, 16, 204, 174, 1049, 596) + 1.0) * 0.5, 0, 1);
        double lightLower = clamp((gridColorCorrelation(mPx, rPx, 16, 204, 596, 1049, 1019) + 1.0) * 0.5, 0, 1);
        double lightHist = histogramSimilarity(mPx, rPx, 0.0, 1.45);
        // Check overexposure control (fraction of clipped white pixels vs master)
        int mClip = 0, rClip = 0;
        for (int i = 0; i < mLum.length; i++) {
            if (mLum[i] > 0.94) mClip++;
            if (rLum[i] > 0.94) rClip++;
        }
        double clipDiff = Math.abs(mClip - rClip) / (double) mLum.length;
        double highlightCtrl = clamp(1.0 - clipDiff * 12.0, 0, 1);
        double scoreLighting = clamp(100.0 * (0.34 * lightUpper + 0.26 * lightLower + 0.25 * lightHist + 0.15 * highlightCtrl), 0, 100);

        // 6. Depth: radial layering + vertical stratification inside sphere + surround-vs-interior contrast ratio
        double depthRadial = radialProfileSimilarity(mPx, rPx, 36);
        double depthGrid = clamp((gridColorCorrelation(mPx, rPx, 12, 160, 140, 1094, 1060) + 1.0) * 0.5, 0, 1);
        double depthSurroundHist = histogramSimilarity(mPx, rPx, 1.05, 1.65);
        double depthSsim = clamp(Math.pow(Math.max(0.01, fullSsim), 0.42), 0, 1);
        double scoreDepth = clamp(100.0 * (0.36 * depthRadial + 0.30 * depthGrid + 0.18 * depthSurroundHist + 0.16 * depthSsim), 0, 100);

        double overall = (scoreComposition + scoreGeometry + scoreResident + scoreMaterial + scoreLighting + scoreDepth) / 6.0;
        boolean pass80 = overall >= 80.0;

        // Export all required visual evidence images
        Files.copy(masterPath, outDir.resolve("MASTER.png"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(renderPath, outDir.resolve("GPU_FINAL.png"), StandardCopyOption.REPLACE_EXISTING);

        // SIDE_BY_SIDE.png
        BufferedImage sbs = new BufferedImage(W * 2, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D gs = sbs.createGraphics();
        gs.drawImage(mImg, 0, 0, null);
        gs.drawImage(rImg, W, 0, null);
        gs.dispose();
        ImageIO.write(sbs, "png", outDir.resolve("SIDE_BY_SIDE.png").toFile());
        ImageIO.write(sbs, "png", outDir.resolve("side_by_side.png").toFile());

        // OVERLAY.png (50/50 blend)
        BufferedImage ov = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] ovPx = new int[W * H];
        int[] hmPx = new int[W * H];
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
        ImageIO.write(ov, "png", outDir.resolve("OVERLAY.png").toFile());

        BufferedImage hm = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        hm.setRGB(0, 0, W, H, hmPx, 0, W);
        ImageIO.write(hm, "png", outDir.resolve("DIFFERENCE_HEATMAP.png").toFile());
        ImageIO.write(hm, "png", outDir.resolve("difference_heatmap.png").toFile());

        // 4 region comparisons
        ImageIO.write(sideBySideCrop(mImg, rImg, 180, 150, 1074, 1044, "SPHERE"), "png", outDir.resolve("SPHERE_COMPARISON.png").toFile());
        ImageIO.write(sideBySideCrop(mImg, rImg, 460, 420, 910, 710, "FISH"), "png", outDir.resolve("FISH_COMPARISON.png").toFile());
        ImageIO.write(sideBySideCrop(mImg, rImg, 0, 0, 1254, 1254, "RESIDENT_PERIMETER"), "png", outDir.resolve("RESIDENT_COMPARISON.png").toFile());
        ImageIO.write(sideBySideCrop(mImg, rImg, 210, 390, 1010, 980, "ECOLOGY"), "png", outDir.resolve("ECOLOGY_COMPARISON.png").toFile());

        // Write visual_score.json
        String scoreJson = String.format(Locale.US, """
{
  "round": "%s",
  "master_sha256": "%s",
  "render_sha256": "%s",
  "classification": "SOFTWARE_REFERENCE_PIPELINE_CPU_1TO1",
  "hardware_gpu_verified": false,
  "coordinate_space": "1254x1254 unchanged source canvas",
  "master_used_as_texture": false,
  "metrics": {
    "full": { "rgb_mae": %.6f, "ssim_luminance": %.6f },
    "sphere": { "rgb_mae": %.6f, "ssim_luminance": %.6f },
    "fish": { "rgb_mae": %.6f, "ssim_luminance": %.6f },
    "left_plant": { "rgb_mae": %.6f, "ssim_luminance": %.6f },
    "right_plant": { "rgb_mae": %.6f, "ssim_luminance": %.6f },
    "rocks": { "rgb_mae": %.6f, "ssim_luminance": %.6f }
  },
  "six_dimension_scores": {
    "composition": %.2f,
    "geometry": %.2f,
    "resident": %.2f,
    "material": %.2f,
    "lighting": %.2f,
    "depth": %.2f
  },
  "overall_score": %.2f,
  "pass_threshold": 80.0,
  "visual_acceptance": "%s",
  "work2_static_input_ready": false,
  "note": "Independent 6-axis evaluation computed on 1:1 software reference render. Android hardware GPU runtime capture remains NOT_RUN in container."
}
""",
                roundLabel, masterSha, renderSha,
                fullMae, fullSsim, sphereMae, sphereSsim, fishMae, fishSsim,
                lpMae, lpSsim, rpMae, rpSsim, rockMae, rockSsim,
                scoreComposition, scoreGeometry, scoreResident, scoreMaterial, scoreLighting, scoreDepth,
                overall, pass80 ? "PASS_80_SOFTWARE_REFERENCE" : "BELOW_80_NEEDS_OPTIMIZATION");
        Files.writeString(outDir.resolve("visual_score.json"), scoreJson, StandardCharsets.UTF_8);

        System.out.printf(Locale.US,
                "[%s] OVERALL=%.2f (%s) | Comp=%.2f Geo=%.2f Res=%.2f Mat=%.2f Light=%.2f Depth=%.2f | fullMAE=%.4f fullSSIM=%.4f sphereMAE=%.4f fishSSIM=%.4f%n",
                roundLabel, overall, pass80 ? "PASS>=80" : "FAIL<80",
                scoreComposition, scoreGeometry, scoreResident, scoreMaterial, scoreLighting, scoreDepth,
                fullMae, fullSsim, sphereMae, fishSsim);
    }
}
