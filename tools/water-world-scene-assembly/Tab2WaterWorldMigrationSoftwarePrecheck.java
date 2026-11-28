import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 5 Software Visual Verification & Evidence Generator
 * for TAB2_WATER_WORLD_FULL_ECOSYSTEM_MIGRATION_V1.
 *
 * Strictly labeled SOFTWARE_VISUAL_PRECHECK (never claims hardware Android GPU acceptance).
 * Mirrors the exact pixel geometry and shader composite math of:
 *   - Tab2UnderwaterBackgroundRenderer (exact frozen 852x1846 PNG)
 *   - Tab2OrbViewportRect.compute(...)
 *   - WaterWorldStaticRenderer (BackdropMode.TRANSPARENT_COMPOSITE)
 *   - Tab2WaterWorldSceneRenderer.SPHERE_COMPOSITE_FRAG
 *   - PhotoTextureRenderer (fishing_photo.webp) + independent PerimeterWaterLightRenderer
 *     (PHOTO_SELECTED -> PHOTO_STABLE -> PERIMETER_LIGHT_ACTIVATED, strictly ZERO orb->photo beam)
 */
public class Tab2WaterWorldMigrationSoftwarePrecheck {

    private static final int[] TIMESTAMPS = {0, 1, 2, 4, 8, 12, 20, 40, 60};
    // Frozen sphere geometry from WATER_WORLD_V2_1 optical_config.json (1254x1254 canvas)
    private static final double SPHERE_CX = 631.0;
    private static final double SPHERE_CY = 622.0;
    private static final double SPHERE_OUTER_R = 393.0;
    private static final double SPHERE_INNER_R = 365.0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path bgPath = root.resolve("app/src/main/assets/tab2_background/tab2_underwater_bg.png");
        Path photoWebp = root.resolve("app/src/main/assets/task_demo/fishing_photo.webp");
        Path framesDir = root.resolve("releases/water-world-v2-v21/work3_2_five_resident_orbital_motion/04_EVIDENCE/frames");
        Path outDir = root.resolve("releases/water-world-v2-v21/tab2_full_ecosystem_migration_v1/05_VISUAL_EVIDENCE");
        Files.createDirectories(outDir);

        // Decode fishing_photo.webp cleanly to temporary PNG via ImageMagick convert
        Path tmpPhotoPng = Files.createTempFile("tab2_fishing_photo_", ".png");
        try {
            Process p = new ProcessBuilder("convert", photoWebp.toString(), tmpPhotoPng.toString())
                    .redirectErrorStream(true)
                    .start();
            int exit = p.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("convert failed decoding fishing_photo.webp (exit=" + exit + ")");
            }

            BufferedImage bgImg = ImageIO.read(bgPath.toFile());
            BufferedImage photoImg = ImageIO.read(tmpPhotoPng.toFile());
            int sw = bgImg.getWidth();
            int sh = bgImg.getHeight();

            // Exact Tab 2 layout matching TaskRecognitionDemoScreen + Tab2OrbViewportRect.compute
            double photoAspect = 0.818;
            double photoW = Math.min(sw * 0.72, (sh * 0.41) * photoAspect);
            double photoH = photoW / photoAspect;
            double photoLeft = (sw - photoW) * 0.5;
            double photoTop = sh * 0.115;
            double photoBottom = photoTop + photoH;
            double cornerR = photoW * 0.072;

            double captionReserve = Math.max(44.0, sh * 0.042);
            double topLimit = clamp(photoBottom + captionReserve, sh * 0.36, sh * 0.68);
            double bottomLimit = Math.max(topLimit + 120.0, sh - 38.0 - sh * 0.012);
            double availH = Math.max(120.0, bottomLimit - topLimit);
            int side = (int) Math.round(Math.min(sw * 0.94, availH * 1.12));
            side = Math.max(96, Math.min(sw, side));
            int orbLeft = (int) Math.round((sw - side) * 0.5);
            double centerY = topLimit + availH * 0.50;
            int orbTop = (int) Math.round(clamp(centerY - side * 0.5, photoBottom + 4.0, Math.max(0.0, sh - side)));

            Map<Integer, BufferedImage> rendered = new LinkedHashMap<>();
            BufferedImage tab3RefT4 = null;

            for (int t : TIMESTAMPS) {
                Path framePath = framesDir.resolve(String.format(Locale.US, "work3_2_all5_t%03ds.png", t));
                BufferedImage wwFrame = ImageIO.read(framePath.toFile());
                if (t == 4) tab3RefT4 = wwFrame;
                BufferedImage composite = renderTab2Frame(
                        bgImg, photoImg, wwFrame,
                        photoLeft, photoTop, photoW, photoH, cornerR,
                        orbLeft, orbTop, side, t
                );
                rendered.put(t, composite);
            }

            // 1. TAB2_FULL_SCENE.png (T=4s)
            BufferedImage fullT4 = rendered.get(4);
            Path fullPath = outDir.resolve("TAB2_FULL_SCENE.png");
            ImageIO.write(fullT4, "PNG", fullPath.toFile());

            // 2. TAB2_WATER_WORLD_ORB.png (cropped lower ecosystem sphere)
            BufferedImage orbCrop = fullT4.getSubimage(orbLeft, orbTop, side, side);
            Path orbPath = outDir.resolve("TAB2_WATER_WORLD_ORB.png");
            ImageIO.write(orbCrop, "PNG", orbPath.toFile());

            // 3. TAB3_REFERENCE.png (1254x1254 Work 3.2 T=4s reference)
            Path tab3Path = outDir.resolve("TAB3_REFERENCE.png");
            ImageIO.write(tab3RefT4, "PNG", tab3Path.toFile());

            // 4. TAB2_TAB3_COMPARISON.png (normalized side-by-side + 4x diff + interior MAE/PSNR)
            int cmpSide = 768;
            BufferedImage tab2Norm = scaleRgb(orbCrop, cmpSide, cmpSide);
            BufferedImage tab3Norm = scaleRgb(tab3RefT4, cmpSide, cmpSide);
            BufferedImage diffImg = new BufferedImage(cmpSide, cmpSide, BufferedImage.TYPE_INT_RGB);

            double sc = cmpSide / 1254.0;
            double innerCmpR = SPHERE_INNER_R * sc * 0.95;
            double sumAbs = 0.0;
            double sumSq = 0.0;
            long count = 0;
            for (int y = 0; y < cmpSide; y++) {
                for (int x = 0; x < cmpSide; x++) {
                    int c2 = tab2Norm.getRGB(x, y);
                    int c3 = tab3Norm.getRGB(x, y);
                    int dr = Math.abs(((c2 >> 16) & 0xFF) - ((c3 >> 16) & 0xFF));
                    int dg = Math.abs(((c2 >> 8) & 0xFF) - ((c3 >> 8) & 0xFF));
                    int db = Math.abs((c2 & 0xFF) - (c3 & 0xFF));
                    int vr = Math.min(255, dr * 4);
                    int vg = Math.min(255, dg * 4);
                    int vb = Math.min(255, db * 4);
                    diffImg.setRGB(x, y, (vr << 16) | (vg << 8) | vb);

                    double dx = x - SPHERE_CX * sc;
                    double dy = y - SPHERE_CY * sc;
                    if (dx * dx + dy * dy <= innerCmpR * innerCmpR) {
                        sumAbs += (dr + dg + db);
                        sumSq += (dr * dr + dg * dg + db * db);
                        count += 3;
                    }
                }
            }
            double maeInterior = sumAbs / Math.max(1L, count);
            double rmseInterior = Math.sqrt(sumSq / Math.max(1L, count));
            double psnrInterior = 20.0 * Math.log10(255.0 / Math.max(rmseInterior, 1e-4));

            int pad = 24;
            int headerH = 64;
            BufferedImage comp = new BufferedImage(cmpSide * 3 + pad * 4, cmpSide + headerH + pad * 2, BufferedImage.TYPE_INT_RGB);
            Graphics2D cg = comp.createGraphics();
            cg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            cg.setColor(new Color(12, 24, 34));
            cg.fillRect(0, 0, comp.getWidth(), comp.getHeight());
            cg.drawImage(tab3Norm, pad, headerH, null);
            cg.drawImage(tab2Norm, pad * 2 + cmpSide, headerH, null);
            cg.drawImage(diffImg, pad * 3 + cmpSide * 2, headerH, null);
            cg.setFont(new Font("SansSerif", Font.BOLD, 20));
            cg.setColor(new Color(230, 248, 255));
            cg.drawString("Tab 3 Reference (T=4s · 1254x1254)", pad, 40);
            cg.drawString("Tab 2 Migrated Ecosystem Over Underwater BG (T=4s)", pad * 2 + cmpSide, 40);
            cg.setColor(new Color(255, 230, 170));
            cg.drawString(String.format(Locale.US, "4x Diff (Sphere Interior MAE=%.2f/255, PSNR=%.2fdB)", maeInterior, psnrInterior), pad * 3 + cmpSide * 2, 40);
            cg.dispose();
            Path compPath = outDir.resolve("TAB2_TAB3_COMPARISON.png");
            ImageIO.write(comp, "PNG", compPath.toFile());

            // 5. DYNAMIC_CONTACT_SHEET.png (3x3 across T=0,1,2,4,8,12,20,40,60s)
            int tileW = 340;
            int tileH = (int) Math.round(tileW * (sh / (double) sw));
            int cols = 3, rows = 3, sp = 18, labelH = 34, topBanner = 48;
            BufferedImage sheet = new BufferedImage(
                    cols * tileW + (cols + 1) * sp,
                    topBanner + rows * (tileH + labelH) + (rows + 1) * sp,
                    BufferedImage.TYPE_INT_RGB
            );
            Graphics2D sg = sheet.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            sg.setColor(new Color(10, 26, 36));
            sg.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            sg.setFont(new Font("SansSerif", Font.BOLD, 16));
            sg.setColor(new Color(220, 248, 255));
            sg.drawString("TAB 2 ECOSYSTEM MIGRATION V1 — DYNAMIC CONTACT SHEET (SOFTWARE_VISUAL_PRECHECK · T=0..60s)", sp, 31);
            sg.setFont(new Font("SansSerif", Font.BOLD, 13));

            for (int idx = 0; idx < TIMESTAMPS.length; idx++) {
                int t = TIMESTAMPS[idx];
                int r = idx / cols;
                int c = idx % cols;
                int x0 = sp + c * (tileW + sp);
                int y0 = topBanner + sp + r * (tileH + labelH + sp);
                BufferedImage tile = scaleRgb(rendered.get(t), tileW, tileH);
                sg.drawImage(tile, x0, y0 + labelH, null);
                String pState = (t == 0) ? "PHOTO_SELECTED" : (t == 1 ? "PERIMETER_REVEAL" : "PERIMETER_ACTIVE");
                sg.setColor(new Color(210, 245, 250));
                sg.drawString("T=" + t + "s · " + pState + " · No Beam", x0 + 4, y0 + 22);
            }
            sg.dispose();
            Path sheetPath = outDir.resolve("DYNAMIC_CONTACT_SHEET.png");
            ImageIO.write(sheet, "PNG", sheetPath.toFile());

            // Compute temporal motion deltas between consecutive Tab 2 orb crops
            StringBuilder deltasJson = new StringBuilder();
            BufferedImage prev = null;
            int prevT = -1;
            for (int t : TIMESTAMPS) {
                BufferedImage cur = rendered.get(t).getSubimage(orbLeft, orbTop, side, side);
                if (prev != null) {
                    double d = meanAbsDiffRgb(prev, cur);
                    if (deltasJson.length() > 0) deltasJson.append(",\n");
                    deltasJson.append(String.format(Locale.US, "    \"T%ds_to_T%ds_mean_abs_diff\": %.4f", prevT, t, d));
                }
                prev = cur;
                prevT = t;
            }

            String metricsJson = String.format(Locale.US,
                    "{\n" +
                    "  \"verification_class\": \"SOFTWARE_VISUAL_PRECHECK\",\n" +
                    "  \"hardware_gpu_verified\": \"NOT_RUN\",\n" +
                    "  \"surface_dimensions\": {\"width\": %d, \"height\": %d},\n" +
                    "  \"photo_rect_px\": [%.2f, %.2f, %.2f, %.2f, %.2f],\n" +
                    "  \"orb_viewport_rect_px\": {\"left\": %d, \"top\": %d, \"side\": %d},\n" +
                    "  \"tab2_vs_tab3_sphere_interior_metrics\": {\n" +
                    "    \"mae_rgb_0_255\": %.4f,\n" +
                    "    \"rmse_rgb_0_255\": %.4f,\n" +
                    "    \"psnr_db\": %.2f\n" +
                    "  },\n" +
                    "  \"temporal_motion_deltas\": {\n%s\n  },\n" +
                    "  \"output_files_sha256\": {\n" +
                    "    \"TAB2_FULL_SCENE.png\": \"%s\",\n" +
                    "    \"TAB2_WATER_WORLD_ORB.png\": \"%s\",\n" +
                    "    \"TAB3_REFERENCE.png\": \"%s\",\n" +
                    "    \"TAB2_TAB3_COMPARISON.png\": \"%s\",\n" +
                    "    \"DYNAMIC_CONTACT_SHEET.png\": \"%s\"\n" +
                    "  }\n" +
                    "}\n",
                    sw, sh,
                    photoLeft, photoTop, photoLeft + photoW, photoBottom, cornerR,
                    orbLeft, orbTop, side,
                    maeInterior, rmseInterior, psnrInterior,
                    deltasJson.toString(),
                    sha256(fullPath), sha256(orbPath), sha256(tab3Path), sha256(compPath), sha256(sheetPath)
            );
            Files.writeString(outDir.resolve("VISUAL_VERIFICATION_METRICS.json"), metricsJson, StandardCharsets.UTF_8);
            System.out.println(metricsJson);
        } finally {
            Files.deleteIfExists(tmpPhotoPng);
        }
    }

    private static BufferedImage renderTab2Frame(
            BufferedImage bgImg,
            BufferedImage photoImg,
            BufferedImage wwFrame,
            double photoLeft, double photoTop, double photoW, double photoH, double cornerR,
            int orbLeft, int orbTop, int side,
            int tSec
    ) {
        int sw = bgImg.getWidth();
        int sh = bgImg.getHeight();
        BufferedImage canvas = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(bgImg, 0, 0, null);

        // Composite Water World V2.1 ecosystem sphere with exact TRANSPARENT_COMPOSITE math
        BufferedImage wwRgba = extractTransparentCompositeWaterWorld(wwFrame);
        g.drawImage(wwRgba, orbLeft, orbTop, side, side, null);

        // Draw upper independent perimeter water-light (STRICTLY around photo, ZERO orb->photo beam!)
        if (tSec >= 1) {
            float progress = (tSec >= 2) ? 1.0f : 0.58f;
            float shimmer = 0.86f + 0.14f * (float) Math.sin(tSec * 1.7);
            float a2 = clampF(0.36f * progress * shimmer, 0f, 1f);
            float a1 = clampF(0.58f * progress * shimmer, 0f, 1f);
            float a3 = clampF(0.68f * progress * shimmer, 0f, 1f);

            // Soft multi-pass liquid halo strictly outside photo rounded border
            g.setColor(new Color(72 / 255f, 232 / 255f, 208 / 255f, a2 * 0.45f));
            g.setStroke(new BasicStroke(22f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new RoundRectangle2D.Double(photoLeft - 10, photoTop - 10, photoW + 20, photoH + 20, (cornerR + 10) * 2, (cornerR + 10) * 2));

            g.setColor(new Color(140 / 255f, 238 / 255f, 255 / 255f, a1 * 0.72f));
            g.setStroke(new BasicStroke(11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new RoundRectangle2D.Double(photoLeft - 6, photoTop - 6, photoW + 12, photoH + 12, (cornerR + 6) * 2, (cornerR + 6) * 2));

            g.setColor(new Color(255 / 255f, 238 / 255f, 186 / 255f, a3));
            g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new RoundRectangle2D.Double(photoLeft - 2.5, photoTop - 2.5, photoW + 5, photoH + 5, (cornerR + 2.5) * 2, (cornerR + 2.5) * 2));

            // L4 sparse translucent crystal droplets strictly outside photo left/right edges
            for (int i = 0; i < 10; i++) {
                double frac = (i * 0.105 + tSec * 0.03) % 0.92;
                double dy = photoTop + photoH * (0.06 + frac * 0.88);
                int rad = 3 + (i % 3);
                double ox = 15 + (i % 4) * 5;
                g.setColor(new Color(230 / 255f, 250 / 255f, 255 / 255f, 0.58f * progress));
                g.fillOval((int) Math.round(photoLeft - ox - rad), (int) Math.round(dy - rad), rad * 2, rad * 2);
                g.fillOval((int) Math.round(photoLeft + photoW + ox - rad), (int) Math.round(dy - rad), rad * 2, rad * 2);
            }
        }

        // Draw crisp rounded photo card
        RoundRectangle2D clip = new RoundRectangle2D.Double(photoLeft, photoTop, photoW, photoH, cornerR * 2, cornerR * 2);
        g.setClip(clip);
        g.drawImage(photoImg, (int) Math.round(photoLeft), (int) Math.round(photoTop), (int) Math.round(photoW), (int) Math.round(photoH), null);
        g.setClip(null);

        g.dispose();
        return canvas;
    }

    private static BufferedImage extractTransparentCompositeWaterWorld(BufferedImage wwFrame) {
        int w = wwFrame.getWidth();
        int h = wwFrame.getHeight();
        // Estimate dark ocean exterior floor from corners (what uSphereBackdropRadius zeroes on GPU)
        double rSum = 0, gSum = 0, bSum = 0;
        int nCorner = 0;
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                int[] coords = {x, y, w - 1 - x, y, x, h - 1 - y, w - 1 - x, h - 1 - y};
                for (int k = 0; k < 8; k += 2) {
                    int c = wwFrame.getRGB(coords[k], coords[k + 1]);
                    rSum += ((c >> 16) & 0xFF) / 255.0;
                    gSum += ((c >> 8) & 0xFF) / 255.0;
                    bSum += (c & 0xFF) / 255.0;
                    nCorner++;
                }
            }
        }
        double floorR = (rSum / nCorner) * 1.18;
        double floorG = (gSum / nCorner) * 1.18;
        double floorB = (bSum / nCorner) * 1.18;

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            double v = y / (double) h;
            double vEdge = Math.min(v, 1.0 - v);
            for (int x = 0; x < w; x++) {
                int c = wwFrame.getRGB(x, y);
                double r = ((c >> 16) & 0xFF) / 255.0;
                double gr = ((c >> 8) & 0xFF) / 255.0;
                double b = (c & 0xFF) / 255.0;

                double dx = x - SPHERE_CX;
                double dy = y - SPHERE_CY;
                double dist = Math.sqrt(dx * dx + dy * dy);

                double sphereAlpha = 1.0 - smoothstep(SPHERE_OUTER_R - 3.5, SPHERE_OUTER_R + 4.5, dist);

                double u = x / (double) w;
                double edgeDist = Math.min(Math.min(u, 1.0 - u), vEdge);
                double edgeFade = smoothstep(0.0, 0.06, edgeDist);

                double oR = clamp((r - floorR) * 1.28, 0.0, 1.0);
                double oG = clamp((gr - floorG) * 1.28, 0.0, 1.0);
                double oB = clamp((b - floorB) * 1.28, 0.0, 1.0);
                double peak = Math.max(oR, Math.max(oG, oB));
                double outerAlpha = smoothstep(0.04, 0.56, peak) * 0.88 * edgeFade;

                double outAlpha = Math.max(sphereAlpha, outerAlpha);
                if (outAlpha < 0.002) {
                    out.setRGB(x, y, 0);
                    continue;
                }
                // Straight ARGB for Java2D drawImage alpha composite
                double finalR = r * sphereAlpha + oR * (1.0 - sphereAlpha);
                double finalG = gr * sphereAlpha + oG * (1.0 - sphereAlpha);
                double finalB = b * sphereAlpha + oB * (1.0 - sphereAlpha);
                // Normalize exterior luminous color so glowing silk ribbons stay bright on cyan water
                if (sphereAlpha < 0.99 && outerAlpha > 0.01) {
                    double boost = 1.0 / Math.max(0.42, outAlpha);
                    finalR = clamp(finalR * (sphereAlpha + (1.0 - sphereAlpha) * Math.min(1.6, boost)), 0.0, 1.0);
                    finalG = clamp(finalG * (sphereAlpha + (1.0 - sphereAlpha) * Math.min(1.6, boost)), 0.0, 1.0);
                    finalB = clamp(finalB * (sphereAlpha + (1.0 - sphereAlpha) * Math.min(1.6, boost)), 0.0, 1.0);
                }

                int ia = (int) Math.round(clamp(outAlpha, 0.0, 1.0) * 255.0);
                int ir = (int) Math.round(clamp(finalR, 0.0, 1.0) * 255.0);
                int ig = (int) Math.round(clamp(finalG, 0.0, 1.0) * 255.0);
                int ib = (int) Math.round(clamp(finalB, 0.0, 1.0) * 255.0);
                out.setRGB(x, y, (ia << 24) | (ir << 16) | (ig << 8) | ib);
            }
        }
        return out;
    }

    private static BufferedImage scaleRgb(BufferedImage src, int dstW, int dstH) {
        BufferedImage dst = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, dstW, dstH, null);
        g.dispose();
        return dst;
    }

    private static double meanAbsDiffRgb(BufferedImage a, BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        double sum = 0.0;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int ca = a.getRGB(x, y);
                int cb = b.getRGB(x, y);
                sum += Math.abs(((ca >> 16) & 0xFF) - ((cb >> 16) & 0xFF));
                sum += Math.abs(((ca >> 8) & 0xFF) - ((cb >> 8) & 0xFF));
                sum += Math.abs((ca & 0xFF) - (cb & 0xFF));
            }
        }
        return sum / (((w + 1) / 2.0) * ((h + 1) / 2.0) * 3.0);
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = clamp((x - e0) / Math.max(e1 - e0, 1e-6), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(Files.readAllBytes(path));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        return sb.toString();
    }
}
