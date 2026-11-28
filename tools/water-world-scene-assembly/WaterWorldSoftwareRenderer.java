import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * 1:1 CPU Software Reference Renderer matching WaterWorldShaders.kt & WaterWorldRenderGraph.kt.
 * Reads ONLY the frozen V2 ZIP assets and Work 1.6 scene/optical JSONs (never reads Master PNG).
 * Executes all 11 composition stages + 5 independent optical nodes and exports diagnostic PNGs.
 */
public class WaterWorldSoftwareRenderer {
    static final int W = 1254;
    static final int H = 1254;
    static final String EXPECTED_V2_SHA = "6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095";
    static boolean captureFloatSnapshots = false;
    static Target snapshotPass01 = null;
    static Target snapshotPass02 = null;
    static Target snapshotPass09 = null;

    static class Target {
        final int w, h;
        final float[] r, g, b, a, depth;
        Target(int w, int h) {
            this.w = w; this.h = h;
            int n = w * h;
            r = new float[n]; g = new float[n]; b = new float[n]; a = new float[n];
            depth = new float[n];
            Arrays.fill(depth, 1.0f);
        }
        void copyFrom(Target src) {
            System.arraycopy(src.r, 0, r, 0, r.length);
            System.arraycopy(src.g, 0, g, 0, g.length);
            System.arraycopy(src.b, 0, b, 0, b.length);
            System.arraycopy(src.a, 0, a, 0, a.length);
            System.arraycopy(src.depth, 0, depth, 0, depth.length);
        }
        void sampleBilinear(float u, float v, float[] out) {
            float fx = clamp(u, 0f, 1f) * (w - 1);
            float fy = clamp(v, 0f, 1f) * (h - 1);
            int x0 = (int) fx, y0 = (int) fy;
            int x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
            float tx = fx - x0, ty = fy - y0;
            int i00 = y0 * w + x0, i10 = y0 * w + x1, i01 = y1 * w + x0, i11 = y1 * w + x1;
            float w00 = (1 - tx) * (1 - ty), w10 = tx * (1 - ty), w01 = (1 - tx) * ty, w11 = tx * ty;
            out[0] = r[i00]*w00 + r[i10]*w10 + r[i01]*w01 + r[i11]*w11;
            out[1] = g[i00]*w00 + g[i10]*w10 + g[i01]*w01 + g[i11]*w11;
            out[2] = b[i00]*w00 + b[i10]*w10 + b[i01]*w01 + b[i11]*w11;
            out[3] = a[i00]*w00 + a[i10]*w10 + a[i01]*w01 + a[i11]*w11;
        }
    }

    static class Tex {
        final int w, h;
        final float[] r, g, b, a;
        Tex(BufferedImage img) {
            w = img.getWidth(); h = img.getHeight();
            int n = w * h;
            r = new float[n]; g = new float[n]; b = new float[n]; a = new float[n];
            int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
            for (int i = 0; i < n; i++) {
                int p = argb[i];
                a[i] = ((p >>> 24) & 255) / 255f;
                r[i] = ((p >>> 16) & 255) / 255f;
                g[i] = ((p >>> 8) & 255) / 255f;
                b[i] = (p & 255) / 255f;
            }
        }
        void sample(float u, float v, boolean repeat, float[] out) {
            if (repeat) {
                u = u - (float) Math.floor(u);
                v = v - (float) Math.floor(v);
            } else {
                u = clamp(u, 0f, 1f);
                v = clamp(v, 0f, 1f);
            }
            float fx = u * (w - 1), fy = v * (h - 1);
            int x0 = (int) fx, y0 = (int) fy;
            int x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
            float tx = fx - x0, ty = fy - y0;
            int i00 = y0 * w + x0, i10 = y0 * w + x1, i01 = y1 * w + x0, i11 = y1 * w + x1;
            float w00 = (1 - tx) * (1 - ty), w10 = tx * (1 - ty), w01 = (1 - tx) * ty, w11 = tx * ty;
            out[0] = r[i00]*w00 + r[i10]*w10 + r[i01]*w01 + r[i11]*w11;
            out[1] = g[i00]*w00 + g[i10]*w10 + g[i01]*w01 + g[i11]*w11;
            out[2] = b[i00]*w00 + b[i10]*w10 + b[i01]*w01 + b[i11]*w11;
            out[3] = a[i00]*w00 + a[i10]*w10 + a[i01]*w01 + a[i11]*w11;
        }
    }

    static float clamp(float x, float lo, float hi) { return x < lo ? lo : (x > hi ? hi : x); }
    static float mix(float a, float b, float t) { return a + (b - a) * t; }
    static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    // Minimal recursive-descent JSON parser (stdlib only)
    static class Json {
        final String s; int pos = 0;
        Json(String s) { this.s = s; }
        static Object parse(String s) { return new Json(s).val(); }
        void ws() { while (pos < s.length() && s.charAt(pos) <= ' ') pos++; }
        Object val() {
            ws();
            char c = s.charAt(pos);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (c == 't') { pos += 4; return Boolean.TRUE; }
            if (c == 'f') { pos += 5; return Boolean.FALSE; }
            if (c == 'n') { pos += 4; return null; }
            return num();
        }
        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; ws();
            if (s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                ws(); String k = str(); ws(); pos++; // ':'
                m.put(k, val()); ws();
                if (s.charAt(pos) == '}') { pos++; return m; }
                pos++; // ','
            }
        }
        List<Object> arr() {
            List<Object> l = new ArrayList<>();
            pos++; ws();
            if (s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                l.add(val()); ws();
                if (s.charAt(pos) == ']') { pos++; return l; }
                pos++; // ','
            }
        }
        String str() {
            pos++; StringBuilder sb = new StringBuilder();
            while (s.charAt(pos) != '"') {
                char c = s.charAt(pos++);
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    if (e == 'n') sb.append('\n');
                    else if (e == 't') sb.append('\t');
                    else if (e == 'r') sb.append('\r');
                    else sb.append(e);
                } else sb.append(c);
            }
            pos++; return sb.toString();
        }
        Double num() {
            int st = pos;
            while (pos < s.length() && "0123456789+-.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            return Double.parseDouble(s.substring(st, pos));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) { return (List<Object>) o; }
    static float flt(Object o, float def) { return o == null ? def : ((Number) o).floatValue(); }
    static boolean bool(Object o, boolean def) { return o == null ? def : (Boolean) o; }
    static float[] fltArr(Object o) {
        List<Object> l = asList(o);
        float[] a = new float[l.size()];
        for (int i = 0; i < l.size(); i++) a[i] = ((Number) l.get(i)).floatValue();
        return a;
    }

    static String sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 255));
        return sb.toString();
    }

    static String savePng(Target t, Path path) throws Exception {
        Files.createDirectories(path.getParent());
        BufferedImage img = new BufferedImage(t.w, t.h, BufferedImage.TYPE_INT_RGB);
        int[] rgb = new int[t.w * t.h];
        for (int i = 0; i < rgb.length; i++) {
            int rr = (int) (clamp(t.r[i], 0f, 1f) * 255f + 0.5f);
            int gg = (int) (clamp(t.g[i], 0f, 1f) * 255f + 0.5f);
            int bb = (int) (clamp(t.b[i], 0f, 1f) * 255f + 0.5f);
            rgb[i] = (rr << 16) | (gg << 8) | bb;
        }
        img.setRGB(0, 0, t.w, t.h, rgb, 0, t.w);
        ImageIO.write(img, "png", path.toFile());
        return sha256(Files.readAllBytes(path));
    }

    static final Map<String, Tex> texCache = new HashMap<>();
    static Tex loadV2Tex(ZipFile zf, String relPath, String expectedSha) throws Exception {
        if (relPath.toLowerCase().contains("master") || relPath.contains("奇幻")) {
            throw new IllegalArgumentException("FORBIDDEN master texture: " + relPath);
        }
        if (texCache.containsKey(expectedSha)) return texCache.get(expectedSha);
        String entryName = "WATER_WORLD_V2/" + relPath.replaceFirst("^01_REUSED_ASSETS/", "");
        ZipEntry ze = zf.getEntry(entryName);
        if (ze == null) throw new FileNotFoundException("Missing V2 ZIP member: " + entryName);
        byte[] bytes = zf.getInputStream(ze).readAllBytes();
        String actual = sha256(bytes);
        if (!actual.equals(expectedSha)) throw new IllegalStateException("SHA mismatch for " + entryName);
        Tex tex = new Tex(ImageIO.read(new ByteArrayInputStream(bytes)));
        texCache.put(expectedSha, tex);
        return tex;
    }

    interface FragShader {
        void shade(int px, int py, float pz, float nx, float ny, float nz, float u, float v, float[] outRGBA);
    }

    static void rasterizeMesh(Map<String, Object> meshJson, float zOffset, Target dst,
                              boolean depthTest, boolean depthWrite, String blendMode,
                              float alphaCutoff, FragShader shader) {
        List<Object> verts = asList(meshJson.get("vertices"));
        List<Object> idxs = asList(meshJson.get("indices"));
        int nv = verts.size();
        float[] vx = new float[nv], vy = new float[nv], vz = new float[nv];
        float[] vnx = new float[nv], vny = new float[nv], vnz = new float[nv];
        float[] vu = new float[nv], vv = new float[nv];
        for (int i = 0; i < nv; i++) {
            List<Object> row = asList(verts.get(i));
            vx[i] = ((Number) row.get(0)).floatValue();
            vy[i] = ((Number) row.get(1)).floatValue();
            vz[i] = (((Number) row.get(2)).floatValue() + zOffset) / 2048f * 0.5f + 0.5f;
            vnx[i] = ((Number) row.get(3)).floatValue();
            vny[i] = ((Number) row.get(4)).floatValue();
            vnz[i] = ((Number) row.get(5)).floatValue();
            vu[i] = ((Number) row.get(6)).floatValue();
            vv[i] = ((Number) row.get(7)).floatValue();
        }
        float[] rgba = new float[4];
        for (int t = 0; t < idxs.size(); t += 3) {
            int i0 = ((Number) idxs.get(t)).intValue();
            int i1 = ((Number) idxs.get(t + 1)).intValue();
            int i2 = ((Number) idxs.get(t + 2)).intValue();
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
                    float nx = vnx[i0]*w0 + vnx[i1]*w1 + vnx[i2]*w2;
                    float ny = vny[i0]*w0 + vny[i1]*w1 + vny[i2]*w2;
                    float nz = vnz[i0]*w0 + vnz[i1]*w1 + vnz[i2]*w2;
                    float u = vu[i0]*w0 + vu[i1]*w1 + vu[i2]*w2;
                    float v = vv[i0]*w0 + vv[i1]*w1 + vv[i2]*w2;
                    rgba[0] = rgba[1] = rgba[2] = rgba[3] = 0f;
                    shader.shade(px, py, pz, nx, ny, nz, u, v, rgba);
                    float a = rgba[3];
                    if (a <= 0.001f) continue;
                    if (alphaCutoff > 0f && a < alphaCutoff) continue;
                    if (depthWrite) dst.depth[idx] = pz;
                    if ("NONE".equals(blendMode)) {
                        dst.r[idx] = rgba[0]; dst.g[idx] = rgba[1]; dst.b[idx] = rgba[2]; dst.a[idx] = 1f;
                    } else if ("ADDITIVE".equals(blendMode)) {
                        dst.r[idx] += rgba[0] * a; dst.g[idx] += rgba[1] * a; dst.b[idx] += rgba[2] * a;
                        dst.a[idx] = 1f;
                    } else if ("SCREEN".equals(blendMode)) {
                        float sr = clamp(rgba[0] * a, 0f, 1.5f);
                        float sg = clamp(rgba[1] * a, 0f, 1.5f);
                        float sb = clamp(rgba[2] * a, 0f, 1.5f);
                        dst.r[idx] = dst.r[idx] + sr * (1f - clamp(dst.r[idx], 0f, 1f) * 0.65f);
                        dst.g[idx] = dst.g[idx] + sg * (1f - clamp(dst.g[idx], 0f, 1f) * 0.65f);
                        dst.b[idx] = dst.b[idx] + sb * (1f - clamp(dst.b[idx], 0f, 1f) * 0.65f);
                        dst.a[idx] = 1f;
                    } else { // ALPHA
                        float ca = clamp(a, 0f, 1f);
                        float inv = 1f - ca;
                        dst.r[idx] = rgba[0] * ca + dst.r[idx] * inv;
                        dst.g[idx] = rgba[1] * ca + dst.g[idx] * inv;
                        dst.b[idx] = rgba[2] * ca + dst.b[idx] * inv;
                        dst.a[idx] = ca + dst.a[idx] * inv;
                    }
                }
            }
        }
    }

    // Separable Gaussian blur for Bloom optical node
    static Target downsample2x(Target src) {
        int dw = src.w / 2, dh = src.h / 2;
        Target dst = new Target(dw, dh);
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int i00 = (y*2)*src.w + (x*2), i10 = i00 + 1, i01 = i00 + src.w, i11 = i01 + 1;
                int o = y * dw + x;
                dst.r[o] = 0.25f * (src.r[i00] + src.r[i10] + src.r[i01] + src.r[i11]);
                dst.g[o] = 0.25f * (src.g[i00] + src.g[i10] + src.g[i01] + src.g[i11]);
                dst.b[o] = 0.25f * (src.b[i00] + src.b[i10] + src.b[i01] + src.b[i11]);
                dst.a[o] = 1f;
            }
        }
        return dst;
    }

    static Target blur1D(Target src, int radius, boolean horizontal) {
        Target dst = new Target(src.w, src.h);
        float sigma = Math.max(1.5f, radius * 0.45f);
        float[] ker = new float[radius * 2 + 1];
        float sum = 0f;
        for (int k = -radius; k <= radius; k++) {
            float w = (float) Math.exp(-(k * k) / (2f * sigma * sigma));
            ker[k + radius] = w; sum += w;
        }
        for (int k = 0; k < ker.length; k++) ker[k] /= sum;
        for (int y = 0; y < src.h; y++) {
            for (int x = 0; x < src.w; x++) {
                float rr = 0, gg = 0, bb = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sx = horizontal ? Math.max(0, Math.min(src.w - 1, x + k)) : x;
                    int sy = horizontal ? y : Math.max(0, Math.min(src.h - 1, y + k));
                    float w = ker[k + radius];
                    int si = sy * src.w + sx;
                    rr += src.r[si] * w; gg += src.g[si] * w; bb += src.b[si] * w;
                }
                int o = y * src.w + x;
                dst.r[o] = rr; dst.g[o] = gg; dst.b[o] = bb; dst.a[o] = 1f;
            }
        }
        return dst;
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path v2Zip = root.resolve("releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip");
        Path sceneRoot = root.resolve("releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1");
        Path outDir = args.length > 0
                ? Path.of(args[0])
                : root.resolve("releases/water-world-v2-v21/work1_6_structure_closure_android_gpu/05_RENDER_PASS_EVIDENCE");
        Files.createDirectories(outDir);

        String v2Sha = sha256(Files.readAllBytes(v2Zip));
        if (!v2Sha.equals(EXPECTED_V2_SHA)) throw new IllegalStateException("V2 ZIP SHA mismatch: " + v2Sha);

        Map<String, Object> bindingsJson = asMap(Json.parse(Files.readString(sceneRoot.resolve("03_SCENE_GRAPH/draw_bindings.json"))));
        Map<String, Object> materialsJson = asMap(Json.parse(Files.readString(sceneRoot.resolve("02_SUPPLEMENTED_ASSETS/materials/materials.json"))));
        Map<String, Object> lightingJson = asMap(Json.parse(Files.readString(sceneRoot.resolve("02_SUPPLEMENTED_ASSETS/lighting/lighting.json"))));
        Map<String, Object> opticalJson = asMap(Json.parse(Files.readString(sceneRoot.resolve("02_SUPPLEMENTED_ASSETS/materials/optical_config.json"))));

        float[] sphereCenter = fltArr(asMap(opticalJson.get("sphere")).get("center_px"));
        final float scx = sphereCenter[0], scy = sphereCenter[1];
        final float sOuterR = flt(asMap(opticalJson.get("sphere")).get("outer_radius_px"), 422.3f);
        final float sInnerR = flt(asMap(opticalJson.get("sphere")).get("inner_radius_px"), 395.75f);

        Map<String, Object> bgAtm = asMap(opticalJson.get("background_atmosphere"));
        Map<String, Object> glassCfg = asMap(opticalJson.get("glass_refraction"));
        Map<String, Object> waterCfg = asMap(opticalJson.get("water_normal_distortion"));
        Map<String, Object> causticsCfg = asMap(opticalJson.get("caustics"));
        Map<String, Object> bloomCfg = asMap(opticalJson.get("bloom"));
        Map<String, Object> bokehCfg = asMap(opticalJson.get("bokeh"));

        Map<String, Object> keyLight = asMap(lightingJson.get("key"));
        float[] keyDir = fltArr(keyLight.get("direction"));
        float klLen = (float) Math.sqrt(keyDir[0]*keyDir[0] + keyDir[1]*keyDir[1] + keyDir[2]*keyDir[2]);
        keyDir[0] /= klLen; keyDir[1] /= klLen; keyDir[2] /= klLen;
        float[] keyColor = fltArr(keyLight.get("color"));

        List<Object> allDraws = asList(bindingsJson.get("draws"));
        Map<String, List<Map<String, Object>>> drawsByPass = new LinkedHashMap<>();
        String[] passOrder = {
            "01_BACKGROUND", "02_RESIDENT_BACK", "03_SPHERE_BACK", "04_WATER_INTERIOR",
            "05_FAR_ECOLOGY", "06_LIGHTING", "07_FISH", "08_NEAR_ECOLOGY",
            "09_SPHERE_FRONT", "10_RESIDENT_FRONT", "11_FINAL_COMPOSITE"
        };
        for (String pid : passOrder) drawsByPass.put(pid, new ArrayList<>());
        for (Object o : allDraws) {
            Map<String, Object> d = asMap(o);
            drawsByPass.get((String) d.get("pass_id")).add(d);
        }

        Map<String, String> passPngShas = new LinkedHashMap<>();
        Map<String, String> opticalPngShas = new LinkedHashMap<>();
        Map<String, Double> passTimesMs = new LinkedHashMap<>();

        try (ZipFile zf = new ZipFile(v2Zip.toFile())) {
            Map<String, Object> aux = asMap(opticalJson.get("auxiliary_textures"));
            final Tex waterNormalTex = loadV2Tex(zf, (String) asMap(aux.get("water_normal")).get("path"), (String) asMap(aux.get("water_normal")).get("sha256"));
            final Tex waterDepthTex = loadV2Tex(zf, (String) asMap(aux.get("water_depth")).get("path"), (String) asMap(aux.get("water_depth")).get("sha256"));
            final Tex causticsTex = loadV2Tex(zf, (String) asMap(aux.get("caustics_height")).get("path"), (String) asMap(aux.get("caustics_height")).get("sha256"));
            final Tex glassShellTex = loadV2Tex(zf, (String) asMap(aux.get("glass_shell_mask")).get("path"), (String) asMap(aux.get("glass_shell_mask")).get("sha256"));

            Target scene = new Target(W, H);
            Target glassRefractDiag = new Target(W, H);
            Target waterDistortDiag = new Target(W, H);
            Target causticsDiag = new Target(W, H);
            Target bloomDiag = new Target(W, H);
            Target bokehDiag = new Target(W, H);

            // Helper: draw bokeh orbs of given layer onto a target
            final List<Object> bokehOrbs = asList(bokehCfg.get("orbs"));
            Runnable drawBgBokeh = () -> {
                for (Object oo : bokehOrbs) {
                    Map<String, Object> orb = asMap(oo);
                    if (!"BG".equals(orb.get("layer"))) continue;
                    renderBokehOrb(orb, scene, bokehDiag, scx, scy, sOuterR);
                }
            };

            // Execute all 11 composition stages in strict topological order
            for (String passId : passOrder) {
                long ps = System.nanoTime();
                List<Map<String, Object>> passDraws = drawsByPass.get(passId);

                if ("01_BACKGROUND".equals(passId)) {
                    for (Map<String, Object> d : passDraws) {
                        Map<String, Object> mesh = asMap(Json.parse(Files.readString(sceneRoot.resolve((String) d.get("mesh_path")))));
                        Tex bgTex = loadV2Tex(zf, (String) d.get("texture_path"), (String) d.get("texture_sha256"));
                        float[] model = fltArr(d.get("model"));
                        float topSuppress = flt(bgAtm.get("top_glare_suppression"), 0.86f);
                        float vigStrength = flt(bgAtm.get("vignette_strength"), 0.62f);
                        float[] deepTint = fltArr(bgAtm.get("deep_tint"));
                        float[] haloC = fltArr(bgAtm.get("top_center_halo_center"));
                        float haloR = flt(bgAtm.get("top_center_halo_radius"), 0.24f);
                        float[] haloCol = fltArr(bgAtm.get("top_center_halo_color"));
                        float haloInt = flt(bgAtm.get("top_center_halo_intensity"), 0.42f);
                        float[] tmp = new float[4];
                        rasterizeMesh(mesh, model[14], scene, true, true, "NONE", 0f, (px, py, pz, nx, ny, nz, u, v, out) -> {
                            bgTex.sample(u, v, false, tmp);
                            // Tame bright cyan glare band at top of background.png
                            float topMask = 1f - smoothstep(0.0f, 0.24f, v);
                            float gSup = 1f - topMask * topSuppress;
                            float rr = tmp[0] * gSup * 0.55f + deepTint[0] * 0.55f;
                            float gg = tmp[1] * gSup * 0.62f + deepTint[1] * 0.60f;
                            float bb = tmp[2] * gSup * 0.62f + deepTint[2] * 0.60f;
                            // Radial atmospheric vignette centered on sphere
                            float dx = (px - scx) / (W * 0.58f);
                            float dy = (py - scy) / (H * 0.58f);
                            float dist = (float) Math.sqrt(dx * dx + dy * dy);
                            float vig = 1f - smoothstep(0.55f, 1.32f, dist) * vigStrength;
                            rr *= vig; gg *= vig; bb *= vig;
                            // Soft top-center ambient cyan mist glow (as in Master top-center)
                            float hd = (float) Math.hypot(u - haloC[0], v - haloC[1]) / haloR;
                            float halo = (float) Math.exp(-hd * hd * 1.6f) * haloInt;
                            rr += haloCol[0] * halo; gg += haloCol[1] * halo; bb += haloCol[2] * halo;
                            out[0] = rr; out[1] = gg; out[2] = bb; out[3] = 1f;
                        });
                    }
                    drawBgBokeh.run();
                } else if ("02_RESIDENT_BACK".equals(passId) || "10_RESIDENT_FRONT".equals(passId)) {
                    for (Map<String, Object> d : passDraws) {
                        Map<String, Object> mesh = asMap(Json.parse(Files.readString(sceneRoot.resolve((String) d.get("mesh_path")))));
                        float[] model = fltArr(d.get("model"));
                        float[] tint = fltArr(d.get("tint"));
                        float[] coreCol = d.containsKey("core_color") ? fltArr(d.get("core_color")) : new float[]{1f, 1f, 0.9f};
                        float emission = flt(d.get("emission_override"), 1.1f);
                        float style = flt(d.get("ribbon_style"), 0f);
                        rasterizeMesh(mesh, model[14], scene, true, false, "SCREEN", 0f, (px, py, pz, nx, ny, nz, u, v, out) -> {
                            float across = Math.abs(v * 2f - 1f);
                            float endFade = smoothstep(0.0f, 0.11f, u) * smoothstep(0.0f, 0.13f, 1f - u);
                            float twist = 0.82f + 0.18f * (float) Math.sin(u * 10.5f + style * 2.1f);
                            // Multi-strand gossamer silk filament modulation across ribbon width
                            float wave = 0.5f + 0.5f * (float) Math.cos((v * 2f - 1f) * 10.5f + u * 6.8f);
                            float streak = 0.56f + 0.44f * (float) Math.pow(wave, 1.4);
                            float body, core, rim;
                            if (style < 0.5f) {
                                // Gossamer translucent emerald-chartreuse silk with sharp edge fold & slender core
                                body = (1f - smoothstep(0.16f, 1.0f, across)) * 0.52f * streak;
                                rim = (float) Math.exp(-28f * (across - 0.64f) * (across - 0.64f)) * 0.62f;
                                core = (float) Math.exp(-24f * across * across) * 0.60f;
                            } else if (style < 1.5f) {
                                // Fiery amber-gold silk-filament: translucent golden veil + razor-hot light thread
                                body = (float) Math.exp(-2.9f * across * across) * 0.54f * streak;
                                rim = (float) Math.exp(-26f * (across - 0.52f) * (across - 0.52f)) * 0.40f;
                                core = (float) Math.exp(-34f * across * across) * 1.06f;
                            } else {
                                // Electric aqua-cyan filament: glassy cyan mist + crisp glowing aqua edge & core
                                body = (1f - smoothstep(0.15f, 1.0f, across)) * 0.50f * streak;
                                rim = (float) Math.exp(-26f * (across - 0.58f) * (across - 0.58f)) * 0.50f;
                                core = (float) Math.exp(-30f * across * across) * 0.94f;
                            }
                            float alpha = clamp((body + rim * 0.60f + core * 0.68f) * tint[3] * endFade * twist, 0f, 1f);
                            float rr = (tint[0] * (body + rim) + coreCol[0] * core) * emission;
                            float gg = (tint[1] * (body + rim) + coreCol[1] * core) * emission;
                            float bb = (tint[2] * (body + rim) + coreCol[2] * core) * emission;
                            out[0] = rr; out[1] = gg; out[2] = bb; out[3] = alpha;
                        });
                    }
                } else if ("03_SPHERE_BACK".equals(passId)) {
                    // Snapshot scene before back glass refraction to prevent FBO feedback
                    Target preGlass = new Target(W, H);
                    preGlass.copyFrom(scene);
                    float backRef = flt(glassCfg.get("back_refraction_strength"), 0.014f);
                    float[] absCol = fltArr(glassCfg.get("absorption_color"));
                    float[] coolRim = fltArr(glassCfg.get("rim_color_cool"));
                    float[] sample = new float[4];
                    float[] maskS = new float[4];
                    for (int py = 0; py < H; py++) {
                        for (int px = 0; px < W; px++) {
                            float dx = (px - scx) / sOuterR;
                            float dy = (py - scy) / sOuterR;
                            float r2 = dx * dx + dy * dy;
                            if (r2 > 1.0f) continue;
                            float r = (float) Math.sqrt(r2);
                            float nz = (float) Math.sqrt(Math.max(0.0001f, 1f - r2));
                            float u = px / (float) W, v = py / (float) H;
                            float refractMask = smoothstep(0.55f, 1.0f, r);
                            float ru = u - dx * backRef * refractMask;
                            float rv = v - dy * backRef * refractMask;
                            preGlass.sampleBilinear(ru, rv, sample);
                            glassShellTex.sample(u, v, false, maskS);
                            float fresnel = (float) Math.pow(1f - nz, 4.2f);
                            float rimGlow = fresnel * 0.20f + maskS[0] * 0.08f;
                            int idx = py * W + px;
                            scene.r[idx] = mix(sample[0], absCol[0], 0.14f + fresnel * 0.12f) + coolRim[0] * rimGlow * 0.25f;
                            scene.g[idx] = mix(sample[1], absCol[1], 0.16f + fresnel * 0.14f) + coolRim[1] * rimGlow * 0.30f;
                            scene.b[idx] = mix(sample[2], absCol[2], 0.18f + fresnel * 0.16f) + coolRim[2] * rimGlow * 0.35f;
                            glassRefractDiag.r[idx] = Math.abs(ru - u) * 18f + coolRim[0] * rimGlow;
                            glassRefractDiag.g[idx] = Math.abs(rv - v) * 18f + coolRim[1] * rimGlow;
                            glassRefractDiag.b[idx] = fresnel * 0.8f + coolRim[2] * rimGlow;
                            glassRefractDiag.a[idx] = 1f;
                        }
                    }
                } else if ("04_WATER_INTERIOR".equals(passId)) {
                    Target preWater = new Target(W, H);
                    preWater.copyFrom(scene);
                    Map<String, Object> wd = passDraws.get(0);
                    Tex waterbedTex = loadV2Tex(zf, (String) wd.get("texture_path"), (String) wd.get("texture_sha256"));
                    float normStr = flt(waterCfg.get("normal_distortion_strength"), 0.010f);
                    float menBase = flt(waterCfg.get("meniscus_base_y_norm"), 0.244f);
                    float menAmp = flt(waterCfg.get("meniscus_wave_amp_norm"), 0.016f);
                    float menThick = flt(waterCfg.get("meniscus_thickness_norm"), 0.020f);
                    float[] airTop = fltArr(waterCfg.get("air_dome_top_color"));
                    float[] airWarm = fltArr(waterCfg.get("air_dome_warm_color"));
                    float[] wShal = fltArr(waterCfg.get("water_shallow_color"));
                    float[] wMid = fltArr(waterCfg.get("water_mid_color"));
                    float[] wDeep = fltArr(waterCfg.get("water_deep_color"));
                    float bedBlend = flt(waterCfg.get("waterbed_blend"), 0.14f);
                    float[] wn = new float[4], wdep = new float[4], wbed = new float[4], preS = new float[4], cau = new float[4];

                    for (int py = 0; py < H; py++) {
                        for (int px = 0; px < W; px++) {
                            float dx = (px - scx) / (sInnerR + 8f);
                            float dy = (py - scy) / (sInnerR + 8f);
                            float r2 = dx * dx + dy * dy;
                            if (r2 > 1.0f) continue;
                            float r = (float) Math.sqrt(r2);
                            float edgeSoft = 1f - smoothstep(0.960f, 1.0f, r);
                            float u = px / (float) W, v = py / (float) H;

                            waterNormalTex.sample(u * 2.0f, v * 2.0f, true, wn);
                            float tnx = (wn[0] * 2f - 1f);
                            float tny = (wn[1] * 2f - 1f);
                            waterDepthTex.sample(u, v, false, wdep);
                            float relDepth = wdep[0];

                            float du = tnx * normStr * (0.35f + 0.65f * relDepth);
                            float dv = tny * normStr * (0.35f + 0.65f * relDepth);
                            preWater.sampleBilinear(u + du, v + dv, preS);
                            waterbedTex.sample(u + du * 1.2f, v + dv * 1.2f, false, wbed);
                            causticsTex.sample((u + du * 1.5f) * 1.8f, (v + dv * 1.5f) * 1.8f, true, cau);

                            // Organic wavy air-water meniscus curve matching Master's dip & right-side crest
                            float waveY = menBase
                                    + menAmp * (float) Math.sin((u - 0.36f) * 13.5f)
                                    + menAmp * 0.38f * (float) Math.cos((u - 0.54f) * 24.0f)
                                    - 0.026f * smoothstep(0.56f, 0.72f, u)
                                    + 0.024f * smoothstep(0.70f, 0.84f, u)
                                    + tny * 0.005f;
                            float signedY = v - waveY;

                            float rr, gg, bb;
                            if (signedY < 0f) {
                                // Clear glossy air pocket above meniscus: warm sunlit left-to-center, luminous crystal sky right
                                float airFrac = clamp(-signedY / 0.10f, 0f, 1f);
                                float sunSide = 1f - smoothstep(0.25f, 0.58f, u);
                                float skyReflect = (float) Math.exp(-Math.pow((u - 0.51f) / 0.17f, 2.0)) * (1f - airFrac * 0.52f);
                                rr = mix(airTop[0], airWarm[0], sunSide * 0.86f) + 0.18f * skyReflect;
                                gg = mix(airTop[1], airWarm[1], sunSide * 0.74f) + 0.40f * skyReflect;
                                bb = mix(airTop[2], airWarm[2], sunSide * 0.44f) + 0.48f * skyReflect;
                                // Glossy dome ceiling shading near upper rim
                                float topDark = smoothstep(0.68f, 0.98f, r) * (1f - sunSide * 0.52f);
                                rr *= (1f - topDark * 0.54f);
                                gg *= (1f - topDark * 0.44f);
                                bb *= (1f - topDark * 0.32f);
                                rr = mix(preS[0], rr, 0.79f);
                                gg = mix(preS[1], gg, 0.81f);
                                bb = mix(preS[2], bb, 0.82f);
                            } else {
                                // Rich crystal ocean-teal aquatic volume below meniscus
                                float depthT = clamp(signedY / 0.58f, 0f, 1f);
                                float rightDeep = smoothstep(0.45f, 0.82f, u);
                                float r0 = mix(wShal[0], wMid[0], smoothstep(0f, 0.40f, depthT));
                                float g0 = mix(wShal[1], wMid[1], smoothstep(0f, 0.40f, depthT));
                                float b0 = mix(wShal[2], wMid[2], smoothstep(0f, 0.40f, depthT));
                                r0 = mix(r0, wDeep[0], smoothstep(0.42f, 1.0f, depthT));
                                g0 = mix(g0, wDeep[1], smoothstep(0.42f, 1.0f, depthT));
                                b0 = mix(b0, wDeep[2], smoothstep(0.42f, 1.0f, depthT));
                                r0 *= (1f - rightDeep * 0.35f);
                                g0 *= (1f - rightDeep * 0.24f);
                                b0 *= (1f - rightDeep * 0.10f);
                                // Blend frozen V2 waterbed.png gently in mid-lower depth
                                float wbFactor = bedBlend * smoothstep(0.30f, 0.90f, depthT);
                                rr = mix(r0, wbed[0] * 0.46f, wbFactor);
                                gg = mix(g0, wbed[1] * 0.62f, wbFactor);
                                bb = mix(b0, wbed[2] * 0.64f, wbFactor);
                                // Natural deep-water spherical vignette near inner glass wall
                                float wallShade = 1f - smoothstep(0.62f, 0.99f, r) * 0.44f;
                                rr *= wallShade; gg *= wallShade; bb *= wallShade;
                                // Luminous sunlit underwater turquoise scatter in lower basin
                                float basinD = (float) Math.hypot((u - 0.54f) / 0.22f, (v - 0.62f) / 0.16f);
                                float basinGlow = (float) Math.exp(-basinD * basinD * 1.4f) * 0.26f;
                                rr += 0.04f * basinGlow;
                                gg += 0.74f * basinGlow;
                                bb += 0.78f * basinGlow;
                                rr = mix(preS[0], rr, 0.91f);
                                gg = mix(preS[1], gg, 0.91f);
                                bb = mix(preS[2], bb, 0.91f);
                            }

                            // Crisp wavy sunlit golden-turquoise meniscus line + shimmering underside wave ripples
                            float menLine = (float) Math.exp(-(signedY * signedY) / (menThick * menThick * 0.35f));
                            float underRipple = (signedY > 0f && signedY < 0.090f)
                                    ? (float) Math.exp(-Math.pow((signedY - 0.025f) / 0.032f, 2.0)) * (0.38f + 0.62f * cau[0])
                                    : 0f;
                            float sunWarmth = 1f - smoothstep(0.25f, 0.60f, u);
                            float menR = mix(0.20f, 1.00f, sunWarmth);
                            float menG = mix(0.88f, 0.93f, sunWarmth);
                            float menB = mix(0.96f, 0.52f, sunWarmth);
                            rr = mix(rr, menR * (0.90f + 0.28f * cau[0]), menLine * 0.88f) + mix(0.08f, 0.68f, sunWarmth) * underRipple * 0.52f;
                            gg = mix(gg, menG * (0.91f + 0.24f * cau[0]), menLine * 0.88f) + mix(0.50f, 0.58f, sunWarmth) * underRipple * 0.52f;
                            bb = mix(bb, menB * (0.92f + 0.20f * cau[0]), menLine * 0.88f) + mix(0.60f, 0.22f, sunWarmth) * underRipple * 0.52f;

                            int idx = py * W + px;
                            scene.r[idx] = mix(scene.r[idx], rr, edgeSoft);
                            scene.g[idx] = mix(scene.g[idx], gg, edgeSoft);
                            scene.b[idx] = mix(scene.b[idx], bb, edgeSoft);
                            waterDistortDiag.r[idx] = clamp(0.5f + tnx * 0.5f, 0f, 1f) * edgeSoft;
                            waterDistortDiag.g[idx] = clamp(0.5f + tny * 0.5f, 0f, 1f) * edgeSoft;
                            waterDistortDiag.b[idx] = clamp(relDepth * 0.7f + menLine * 0.6f, 0f, 1f) * edgeSoft;
                            waterDistortDiag.a[idx] = 1f;
                        }
                    }
                } else if ("06_LIGHTING".equals(passId)) {
                    // Dedicated Caustics & Volumetric God-Rays Lighting Pass
                    float[] sunOrig = fltArr(causticsCfg.get("sun_origin_norm"));
                    float rayInt = flt(causticsCfg.get("god_ray_intensity"), 0.56f);
                    float[] rayCol = fltArr(causticsCfg.get("god_ray_color"));
                    List<Object> beams = asList(causticsCfg.get("god_ray_beams"));
                    float[] bAng = new float[beams.size()], bWid = new float[beams.size()], bStr = new float[beams.size()];
                    for (int i = 0; i < beams.size(); i++) {
                        Map<String, Object> bm = asMap(beams.get(i));
                        bAng[i] = flt(bm.get("angle_deg"), 60f);
                        bWid[i] = flt(bm.get("width_deg"), 6f);
                        bStr[i] = flt(bm.get("strength"), 0.8f);
                    }
                    float surfCau = flt(causticsCfg.get("surface_caustic_strength"), 0.72f);
                    float bedCau = flt(causticsCfg.get("bed_caustic_strength"), 0.55f);
                    float[] bedCol = fltArr(causticsCfg.get("bed_caustic_color"));
                    float[] cauS = new float[4], wn = new float[4];

                    for (int py = 0; py < H; py++) {
                        for (int px = 0; px < W; px++) {
                            float dx = (px - scx) / (sInnerR + 4f);
                            float dy = (py - scy) / (sInnerR + 4f);
                            float r2 = dx * dx + dy * dy;
                            if (r2 > 1.0f) continue;
                            float r = (float) Math.sqrt(r2);
                            float sphereFade = 1f - smoothstep(0.90f, 1.0f, r);
                            float u = px / (float) W, v = py / (float) H;

                            // Volumetric diagonal golden sunbeams fanning down-right from upper-left meniscus
                            float sdx = u - sunOrig[0];
                            float sdy = v - sunOrig[1];
                            float dist = (float) Math.hypot(sdx, sdy);
                            float raySum = 0f;
                            if (sdy > 0.01f && dist < 0.44f) {
                                float angDeg = (float) Math.toDegrees(Math.atan2(sdy, sdx));
                                for (int i = 0; i < bAng.length; i++) {
                                    float da = angDeg - bAng[i];
                                    raySum += bStr[i] * (float) Math.exp(-(da * da) / (2f * bWid[i] * bWid[i]));
                                }
                                float lenFade = smoothstep(0.02f, 0.08f, dist) * (1f - smoothstep(0.16f, 0.40f, dist));
                                raySum = clamp(raySum, 0f, 1.05f) * lenFade * rayInt;
                            }

                            // Sample caustics_height.png distorted by water_normal_tangent.png (large organic scale)
                            waterNormalTex.sample(u * 1.8f, v * 1.8f, true, wn);
                            float cu = u * 1.35f + (wn[0] - 0.5f) * 0.12f;
                            float cv = v * 1.35f + (wn[1] - 0.5f) * 0.12f;
                            causticsTex.sample(cu, cv, true, cauS);
                            float cVal = (float) Math.pow(clamp(cauS[0], 0f, 1f), 2.1f);

                            float upperBand = (float) Math.exp(-Math.pow((v - 0.255f) / 0.042f, 2.0));
                            float lowerBed = smoothstep(0.60f, 0.74f, v) * (1f - smoothstep(0.78f, 0.86f, v));
                            float cAddR = rayCol[0] * raySum + rayCol[0] * cVal * upperBand * surfCau * 0.35f + bedCol[0] * cVal * lowerBed * bedCau * 0.34f;
                            float cAddG = rayCol[1] * raySum + rayCol[1] * cVal * upperBand * surfCau * 0.40f + bedCol[1] * cVal * lowerBed * bedCau * 0.38f;
                            float cAddB = rayCol[2] * raySum + 0.78f * cVal * upperBand * surfCau * 0.36f + bedCol[2] * cVal * lowerBed * bedCau * 0.26f;

                            int idx = py * W + px;
                            scene.r[idx] += cAddR * sphereFade;
                            scene.g[idx] += cAddG * sphereFade;
                            scene.b[idx] += cAddB * sphereFade;
                            causticsDiag.r[idx] = clamp(cAddR * 2.2f * sphereFade, 0f, 1f);
                            causticsDiag.g[idx] = clamp(cAddG * 2.2f * sphereFade, 0f, 1f);
                            causticsDiag.b[idx] = clamp(cAddB * 2.2f * sphereFade, 0f, 1f);
                            causticsDiag.a[idx] = 1f;
                        }
                    }
                } else if ("05_FAR_ECOLOGY".equals(passId) || "07_FISH".equals(passId) || "08_NEAR_ECOLOGY".equals(passId)) {
                    for (Map<String, Object> d : passDraws) {
                        Map<String, Object> mesh = asMap(Json.parse(Files.readString(sceneRoot.resolve((String) d.get("mesh_path")))));
                        String shaderProg = (String) d.get("shader_program");
                        Tex tex = loadV2Tex(zf, (String) d.get("texture_path"), (String) d.get("texture_sha256"));
                        float[] model = fltArr(d.get("model"));
                        float[] tint = d.containsKey("tint") ? fltArr(d.get("tint")) : new float[]{1f, 1f, 1f, 1f};
                        boolean depthWrite = bool(d.get("depth_write"), false);
                        boolean depthTest = bool(d.get("depth_test"), true);
                        float alphaCut = flt(d.get("alpha_cutoff"), 0f);
                        float ambient = flt(d.get("ambient"), 0.55f);
                        boolean sphereClip = bool(d.get("sphere_clip"), true);
                        String matName = (String) d.get("material");
                        String drawId = (String) d.get("draw_id");
                        float[] tmp = new float[4], cauS = new float[4];

                        rasterizeMesh(mesh, model[14], scene, depthTest, depthWrite, "ALPHA", alphaCut, (px, py, pz, nx, ny, nz, u, v, out) -> {
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
                                out[3] = clamp((tmp[3] * 0.65f + spec * 0.85f + rim * 0.5f) * tint[3] * clipAlpha, 0f, 1f);
                                return;
                            }
                            float rr = tmp[0] * tint[0];
                            float gg = tmp[1] * tint[1];
                            float bb = tmp[2] * tint[2];
                            float topLight = clamp(1.12f - sv * 0.52f + (0.5f - su) * 0.20f, 0.52f, 1.20f);
                            if ("fish".equals(matName)) {
                                // 4 Fish: sunlit golden-cream dorsal back + crisp aquatic turquoise belly bounce
                                float dorsal = 1f - smoothstep(0.15f, 0.72f, v);
                                rr = rr * (ambient + 0.44f * topLight) + dorsal * 0.15f * keyColor[0];
                                gg = gg * (ambient + 0.43f * topLight) + dorsal * 0.12f * keyColor[1];
                                bb = bb * (ambient + 0.39f * topLight) + (1f - dorsal) * 0.055f;
                            } else if ("plant".equals(matName)) {
                                // Plants: natural rich emerald stems with warm sunlit golden-chartreuse upper tips
                                float tipSun = (1f - smoothstep(0.05f, 0.68f, v)) * (0.62f + 0.38f * (1f - su));
                                rr = rr * (ambient + 0.36f * topLight + tipSun * 0.44f);
                                gg = gg * (ambient + 0.43f * topLight + tipSun * 0.34f);
                                bb = bb * (ambient + 0.24f * topLight + tipSun * 0.04f);
                                if ("05_FAR_ECOLOGY".equals(passId)) {
                                    rr = mix(rr, 0.022f, 0.44f);
                                    gg = mix(gg, 0.26f, 0.44f);
                                    bb = mix(bb, 0.28f, 0.44f);
                                }
                            } else if ("rock".equals(matName)) {
                                // Rocks: crisp organic water-warped golden-white caustics + sunlit olive-moss crown
                                float[] wnR = new float[4];
                                waterNormalTex.sample(su * 2.2f, sv * 2.2f, true, wnR);
                                float cu = su * 1.75f + (wnR[0] - 0.5f) * 0.24f + 0.15f;
                                float cv = sv * 2.05f + (wnR[1] - 0.5f) * 0.24f + 0.09f;
                                causticsTex.sample(cu, cv, true, cauS);
                                float topSurface = 1f - smoothstep(0.04f, 0.66f, v);
                                float ridge = (float) Math.pow(clamp(cauS[0], 0f, 1f), 4.0f);
                                float cGlow = ridge * topSurface * ("rock_03".equals(drawId) ? 0.92f : 1.48f);
                                float mossBoost = "rock_03".equals(drawId) ? (float) Math.pow(topSurface, 1.4f) * 0.36f : 0f;
                                rr = rr * (ambient + 0.40f * topLight) + cGlow * 1.02f + mossBoost * 0.88f;
                                gg = gg * (ambient + 0.44f * topLight) + cGlow * 0.96f + mossBoost * 1.00f;
                                bb = bb * (ambient + 0.42f * topLight) + cGlow * 0.68f + mossBoost * 0.16f;
                                if ("05_FAR_ECOLOGY".equals(passId)) {
                                    rr = mix(rr, 0.022f, 0.46f);
                                    gg = mix(gg, 0.26f, 0.46f);
                                    bb = mix(bb, 0.29f, 0.46f);
                                }
                            }
                            out[0] = rr; out[1] = gg; out[2] = bb;
                            out[3] = tmp[3] * tint[3] * clipAlpha;
                        });
                    }
                } else if ("09_SPHERE_FRONT".equals(passId)) {
                    // Snapshot scene before front glass screen-space refraction (zero feedback loop)
                    Target preFront = new Target(W, H);
                    preFront.copyFrom(scene);
                    float frontRef = flt(glassCfg.get("front_refraction_strength"), 0.026f);
                    float disp = flt(glassCfg.get("chromatic_dispersion"), 0.0012f);
                    float fPow = flt(glassCfg.get("fresnel_power"), 4.2f);
                    float[] coolRim = fltArr(glassCfg.get("rim_color_cool"));
                    float[] warmRim = fltArr(glassCfg.get("rim_color_warm"));
                    float[] tlPos = fltArr(glassCfg.get("top_left_flare_norm"));
                    float tlRad = flt(glassCfg.get("top_left_flare_radius"), 0.088f);
                    float[] tlCol = fltArr(glassCfg.get("top_left_flare_color"));
                    float tlInt = flt(glassCfg.get("top_left_flare_intensity"), 1.05f);
                    float[] brPos = fltArr(glassCfg.get("bottom_right_flare_norm"));
                    float brRad = flt(glassCfg.get("bottom_right_flare_radius"), 0.082f);
                    float[] brCol = fltArr(glassCfg.get("bottom_right_flare_color"));
                    float brInt = flt(glassCfg.get("bottom_right_flare_intensity"), 1.00f);
                    float[] rArcPos = fltArr(glassCfg.get("right_rim_arc_norm"));
                    float rArcInt = flt(glassCfg.get("right_rim_arc_intensity"), 0.58f);

                    float[] sR = new float[4], sG = new float[4], sB = new float[4], shellM = new float[4];
                    for (int py = 0; py < H; py++) {
                        for (int px = 0; px < W; px++) {
                            float dx = (px - scx) / sOuterR;
                            float dy = (py - scy) / sOuterR;
                            float r2 = dx * dx + dy * dy;
                            float u = px / (float) W, v = py / (float) H;
                            int idx = py * W + px;

                            if (r2 <= 1.006f) {
                                float r = (float) Math.sqrt(Math.min(1.0f, r2));
                                float nz = (float) Math.sqrt(Math.max(0.0001f, 1f - Math.min(0.9999f, r2)));
                                // Spherical refraction confined tightly to glass wall; micro-dispersion only at outer edge
                                float wallFactor = smoothstep(0.84f, 0.995f, r);
                                float dispMask = smoothstep(0.91f, 0.995f, r);
                                float off = frontRef * wallFactor * (1f - nz * 0.55f);
                                preFront.sampleBilinear(u - dx * (off + disp * dispMask), v - dy * (off + disp * dispMask), sR);
                                preFront.sampleBilinear(u - dx * off, v - dy * off, sG);
                                preFront.sampleBilinear(u - dx * (off - disp * dispMask), v - dy * (off - disp * dispMask), sB);

                                float rr = sR[0], gg = sG[1], bb = sB[2];

                                // Physical multi-ring crystal glass wall (crisp bevel + directional sunlight transmission)
                                float fresnel = (float) Math.pow(1f - nz, fPow);
                                float outerBevel = (float) Math.exp(-Math.pow((r - 0.990f) / 0.011f, 2.0));
                                float innerDarkBand = (float) Math.exp(-Math.pow((r - 0.958f) / 0.013f, 2.0));
                                float innerBrightRing = (float) Math.exp(-Math.pow((r - 0.936f) / 0.014f, 2.0));
                                glassShellTex.sample(u, v, false, shellM);

                                // Crisp physical crystal-wall TIR groove without blackening the rim
                                rr *= (1f - innerDarkBand * 0.28f);
                                gg *= (1f - innerDarkBand * 0.20f);
                                bb *= (1f - innerDarkBand * 0.14f);

                                // Physically grounded directional sunlight entry (upper-left) & lens focus (lower-right)
                                float ulSunGlass = (float) Math.exp(-Math.pow(dx + 0.72f, 2.0) * 1.45 - Math.pow(dy + 0.42f, 2.0) * 1.45);
                                float lrSunGlass = (float) Math.exp(-Math.pow(dx - 0.56f, 2.0) * 1.55 - Math.pow(dy - 0.54f, 2.0) * 1.55);
                                float warmWeight = clamp(ulSunGlass + lrSunGlass * 0.95f, 0f, 1f);
                                float rimR = mix(coolRim[0], warmRim[0], warmWeight);
                                float rimG = mix(coolRim[1], warmRim[1], warmWeight);
                                float rimB = mix(coolRim[2], warmRim[2], warmWeight);

                                float rimSpec = outerBevel * 1.05f + innerBrightRing * 0.62f + fresnel * 0.30f + shellM[0] * 0.10f;
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

                                glassRefractDiag.r[idx] = clamp(glassRefractDiag.r[idx] + outerBevel * 0.85f + Math.abs(dx * off) * 32f, 0f, 1f);
                                glassRefractDiag.g[idx] = clamp(glassRefractDiag.g[idx] + innerBrightRing * 0.85f + Math.abs(dy * off) * 32f, 0f, 1f);
                                glassRefractDiag.b[idx] = clamp(glassRefractDiag.b[idx] + fresnel * 0.9f + rightCrescent, 0f, 1f);
                            }

                            // Crisp warm golden solar glass flare (top-left) & lower-right specular flare with pinpoint core
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
                    // Optical Node 4: 5-Stage Bloom (Bright -> 2x -> 4x -> BlurH -> BlurV -> Controlled Composite)
                    float thresh = flt(bloomCfg.get("threshold"), 0.78f);
                    float knee = flt(bloomCfg.get("soft_knee"), 0.25f);
                    int blurR = (int) flt(bloomCfg.get("blur_radius_px"), 14f);
                    float bStr = flt(bloomCfg.get("strength"), 0.38f);
                    float bMax = flt(bloomCfg.get("max_contribution"), 0.55f);

                    Target bright = new Target(W, H);
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
                    Target ds2 = downsample2x(bright);
                    Target ds4 = downsample2x(ds2);
                    Target bh = blur1D(ds4, blurR, true);
                    Target bv = blur1D(bh, blurR, false);
                    // Also medium-radius bloom at 2x for crisp specular halo
                    Target bh2 = blur1D(ds2, Math.max(4, blurR / 2), true);
                    Target bv2 = blur1D(bh2, Math.max(4, blurR / 2), false);

                    float[] bSample4 = new float[4], bSample2 = new float[4];
                    for (int py = 0; py < H; py++) {
                        float v = py / (float) H;
                        for (int px = 0; px < W; px++) {
                            float u = px / (float) W;
                            bv.sampleBilinear(u, v, bSample4);
                            bv2.sampleBilinear(u, v, bSample2);
                            float br = Math.min(bMax, (bSample4[0] * 0.65f + bSample2[0] * 0.55f) * bStr);
                            float bg = Math.min(bMax, (bSample4[1] * 0.65f + bSample2[1] * 0.55f) * bStr);
                            float bb = Math.min(bMax, (bSample4[2] * 0.65f + bSample2[2] * 0.55f) * bStr);
                            int idx = py * W + px;
                            bloomDiag.r[idx] = clamp(br * 2.0f, 0f, 1f);
                            bloomDiag.g[idx] = clamp(bg * 2.0f, 0f, 1f);
                            bloomDiag.b[idx] = clamp(bb * 2.0f, 0f, 1f);
                            bloomDiag.a[idx] = 1f;
                            scene.r[idx] += br;
                            scene.g[idx] += bg;
                            scene.b[idx] += bb;
                        }
                    }

                    // Optical Node 5: Foreground Bokeh Orbs + Sparkles + Outer Glossy Droplets
                    for (Object oo : bokehOrbs) {
                        Map<String, Object> orb = asMap(oo);
                        if (!"FG".equals(orb.get("layer"))) continue;
                        renderBokehOrb(orb, scene, bokehDiag, scx, scy, sOuterR);
                    }
                    for (Map<String, Object> d : passDraws) {
                        Map<String, Object> mesh = asMap(Json.parse(Files.readString(sceneRoot.resolve((String) d.get("mesh_path")))));
                        Tex tex = loadV2Tex(zf, (String) d.get("texture_path"), (String) d.get("texture_sha256"));
                        float[] model = fltArr(d.get("model"));
                        float[] tint = fltArr(d.get("tint"));
                        float[] tmp = new float[4];
                        rasterizeMesh(mesh, model[14], scene, true, false, "ALPHA", 0f, (px, py, pz, nx, ny, nz, u, v, out) -> {
                            float bx = u * 2f - 1f, by = v * 2f - 1f;
                            float br = (float) Math.hypot(bx, by);
                            if (br > 1f) return;
                            tex.sample(u, v, false, tmp);
                            float rim = smoothstep(0.62f, 0.94f, br) * (1f - smoothstep(0.94f, 1.0f, br));
                            float spec1 = (float) Math.exp(-((bx + 0.35f)*(bx + 0.35f) + (by + 0.35f)*(by + 0.35f)) * 18f);
                            float spec2 = (float) Math.exp(-((bx - 0.32f)*(bx - 0.32f) + (by - 0.35f)*(by - 0.35f)) * 14f) * 0.45f;
                            float rr = tmp[0] * 0.45f + rim * 0.72f + spec1 * 1.15f + spec2 * 0.65f;
                            float gg = tmp[1] * 0.55f + rim * 0.90f + spec1 * 1.15f + spec2 * 0.85f;
                            float bb = tmp[2] * 0.62f + rim * 0.96f + spec1 * 1.15f + spec2 * 0.95f;
                            float a = clamp((tmp[3] * 0.45f + rim * 0.72f + spec1 * 0.92f + spec2 * 0.45f) * tint[3], 0f, 1f);
                            out[0] = rr; out[1] = gg; out[2] = bb; out[3] = a;
                            int idx = py * W + px;
                            bokehDiag.r[idx] = clamp(bokehDiag.r[idx] + rr * a, 0f, 1f);
                            bokehDiag.g[idx] = clamp(bokehDiag.g[idx] + gg * a, 0f, 1f);
                            bokehDiag.b[idx] = clamp(bokehDiag.b[idx] + bb * a, 0f, 1f);
                        });
                    }

                    // Luminance-preserving Filmic highlight roll-off (preserves fiery gold & cyan hue saturation!)
                    for (int i = 0; i < W * H; i++) {
                        float r = Math.max(0f, scene.r[i]);
                        float g = Math.max(0f, scene.g[i]);
                        float b = Math.max(0f, scene.b[i]);
                        float maxC = Math.max(r, Math.max(g, b));
                        if (maxC > 0.82f) {
                            float targetMax = 0.82f + 0.18f * (float) Math.tanh((maxC - 0.82f) * 1.45f);
                            float scale = targetMax / maxC;
                            // Mild white core shift only at extreme HDR peaks (>1.6)
                            float hot = clamp((maxC - 1.55f) * 0.25f, 0f, 0.35f);
                            scene.r[i] = clamp(mix(r * scale, targetMax, hot), 0f, 1f);
                            scene.g[i] = clamp(mix(g * scale, targetMax, hot), 0f, 1f);
                            scene.b[i] = clamp(mix(b * scale, targetMax, hot), 0f, 1f);
                        } else {
                            scene.r[i] = r; scene.g[i] = g; scene.b[i] = b;
                        }
                    }
                }

                double elapsedMs = (System.nanoTime() - ps) / 1e6;
                passTimesMs.put(passId, elapsedMs);
                if (captureFloatSnapshots) {
                    if ("01_BACKGROUND".equals(passId)) { snapshotPass01 = new Target(W, H); snapshotPass01.copyFrom(scene); }
                    else if ("02_RESIDENT_BACK".equals(passId)) { snapshotPass02 = new Target(W, H); snapshotPass02.copyFrom(scene); }
                    else if ("09_SPHERE_FRONT".equals(passId)) { snapshotPass09 = new Target(W, H); snapshotPass09.copyFrom(scene); }
                }
                String pName = passId.toLowerCase() + ".png";
                passPngShas.put(passId, savePng(scene, outDir.resolve(pName)));
            }

            // Export 5 optical node diagnostic PNGs
            opticalPngShas.put("glass_refraction", savePng(glassRefractDiag, outDir.resolve("opt_glass_refraction.png")));
            opticalPngShas.put("water_normal_distortion", savePng(waterDistortDiag, outDir.resolve("opt_water_normal_distortion.png")));
            opticalPngShas.put("caustics", savePng(causticsDiag, outDir.resolve("opt_caustics.png")));
            opticalPngShas.put("bloom", savePng(bloomDiag, outDir.resolve("opt_bloom.png")));
            opticalPngShas.put("bokeh", savePng(bokehDiag, outDir.resolve("opt_bokeh.png")));

            String finalSha = savePng(scene, outDir.resolve("GPU_FINAL.png"));
            savePng(scene, outDir.resolve("software_gles_assembled_scene.png"));

            double totalMs = (System.nanoTime() - t0) / 1e6;
            StringBuilder tj = new StringBuilder();
            tj.append("{\n");
            tj.append("  \"classification\": \"SOFTWARE_REFERENCE_PIPELINE_CPU_1TO1\",\n");
            tj.append("  \"hardware_gpu_verified\": false,\n");
            tj.append("  \"android_runtime_verified\": false,\n");
            tj.append("  \"canvas\": [1254, 1254],\n");
            tj.append("  \"stage_count\": 11,\n");
            tj.append("  \"optical_node_count\": 5,\n");
            tj.append("  \"total_draws\": ").append(allDraws.size()).append(",\n");
            tj.append("  \"unique_v2_textures_loaded\": ").append(texCache.size()).append(",\n");
            tj.append("  \"total_render_ms\": ").append(String.format(Locale.US, "%.2f", totalMs)).append(",\n");
            tj.append("  \"final_png_sha256\": \"").append(finalSha).append("\",\n");
            tj.append("  \"pass_png_sha256\": {\n");
            int idx = 0;
            for (Map.Entry<String, String> e : passPngShas.entrySet()) {
                tj.append("    \"").append(e.getKey()).append("\": \"").append(e.getValue()).append("\"")
                  .append(++idx < passPngShas.size() ? ",\n" : "\n");
            }
            tj.append("  },\n");
            tj.append("  \"optical_png_sha256\": {\n");
            idx = 0;
            for (Map.Entry<String, String> e : opticalPngShas.entrySet()) {
                tj.append("    \"").append(e.getKey()).append("\": \"").append(e.getValue()).append("\"")
                  .append(++idx < opticalPngShas.size() ? ",\n" : "\n");
            }
            tj.append("  }\n}\n");
            Files.writeString(outDir.resolve("SOFTWARE_REFERENCE_TELEMETRY.json"), tj.toString(), StandardCharsets.UTF_8);
            System.out.printf(Locale.US, "RENDER_OK stages=11 optics=5 draws=%d uniqueTex=%d time=%.1fms sha=%s%n",
                    allDraws.size(), texCache.size(), totalMs, finalSha);
        }
    }

    static void renderBokehOrb(Map<String, Object> orb, Target scene, Target bokehDiag,
                               float scx, float scy, float sOuterR) {
        float cx = flt(orb.get("x"), 0.5f) * W;
        float cy = flt(orb.get("y"), 0.5f) * H;
        float rad = flt(orb.get("r"), 0.02f) * W;
        float[] col = fltArr(orb.get("color"));
        float alpha = flt(orb.get("alpha"), 0.35f);
        float soft = flt(orb.get("softness"), 0.6f);
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
                scene.r[idx] += col[0] * a;
                scene.g[idx] += col[1] * a;
                scene.b[idx] += col[2] * a;
                bokehDiag.r[idx] = clamp(bokehDiag.r[idx] + col[0] * a, 0f, 1f);
                bokehDiag.g[idx] = clamp(bokehDiag.g[idx] + col[1] * a, 0f, 1f);
                bokehDiag.b[idx] = clamp(bokehDiag.b[idx] + col[2] * a, 0f, 1f);
                bokehDiag.a[idx] = 1f;
            }
        }
    }
}
