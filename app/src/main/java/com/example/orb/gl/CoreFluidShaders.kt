package com.example.orb.gl

/**
 * V2 High-Fidelity GLSL ES 3.00 Shaders:
 * - Linear HDR range scaling (`uHdrScale`) so both RGBA16F and RGBA8 GPUs preserve full HDR headroom
 * - Deep emerald-teal atmospheric space + refined warm olive-gold & deep teal bokeh
 * - Concave inner back-wall emerald/cyan fluid bowl (`AIOrbInnerFluid`)
 * - Refracted-ray interior volume & 3D liquid meniscus raymarching + Beer-Lambert deep-water absorption
 *   + sharp cellular underwater caustics + double-wall blown crystal glass rim (`AIOrbCore`)
 */
object CoreFluidShaders {

  private const val GLSL_NOISE_LIB = """
vec3 mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 mod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 permute(vec4 x) { return mod289(((x * 34.0) + 1.0) * x); }
vec4 taylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }

float snoise(vec3 v) {
    const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
    const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);
    vec3 i  = floor(v + dot(v, C.yyy));
    vec3 x0 = v - i + dot(i, C.xxx);
    vec3 g = step(x0.yzx, x0.xyz);
    vec3 l = 1.0 - g;
    vec3 i1 = min(g.xyz, l.zxy);
    vec3 i2 = max(g.xyz, l.zxy);
    vec3 x1 = x0 - i1 + C.xxx;
    vec3 x2 = x0 - i2 + C.yyy;
    vec3 x3 = x0 - D.yyy;
    i = mod289(i);
    vec4 p = permute(permute(permute(
             i.z + vec4(0.0, i1.z, i2.z, 1.0))
           + i.y + vec4(0.0, i1.y, i2.y, 1.0))
           + i.x + vec4(0.0, i1.x, i2.x, 1.0));
    float n_ = 0.142857142857;
    vec3 ns = n_ * D.wyz - D.xzx;
    vec4 j = p - 49.0 * floor(p * ns.z * ns.z);
    vec4 x_ = floor(j * ns.z);
    vec4 y_ = floor(j - 7.0 * x_);
    vec4 x = x_ * ns.x + ns.yyyy;
    vec4 y = y_ * ns.x + ns.yyyy;
    vec4 h = 1.0 - abs(x) - abs(y);
    vec4 b0 = vec4(x.xy, y.xy);
    vec4 b1 = vec4(x.zw, y.zw);
    vec4 s0 = floor(b0) * 2.0 + 1.0;
    vec4 s1 = floor(b1) * 2.0 + 1.0;
    vec4 sh = -step(h, vec4(0.0));
    vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
    vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
    vec3 p0 = vec3(a0.xy, h.x);
    vec3 p1 = vec3(a0.zw, h.y);
    vec3 p2 = vec3(a1.xy, h.z);
    vec3 p3 = vec3(a1.zw, h.w);
    vec4 norm = taylorInvSqrt(vec4(dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
    p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;
    vec4 m = max(0.6 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
    m = m * m;
    return 42.0 * dot(m * m, vec4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}

float fbm3(vec3 p) {
    float val = 0.0;
    float amp = 0.52;
    for (int i = 0; i < 3; i++) {
        val += amp * snoise(p);
        p = p * 2.05 + vec3(11.3, 6.7, 4.9);
        amp *= 0.48;
    }
    return val;
}

// High-contrast lacy underwater caustic web (sharp bright filaments separated by deep dark cells)
float sharpCaustics(vec3 p, float t) {
    vec3 q = p * 2.65 + vec3(t * 0.22, -t * 0.18, t * 0.14);
    float n1 = snoise(q);
    float n2 = snoise(q * 1.68 - vec3(t * 0.27, t * 0.19, -t * 0.24) + vec3(n1 * 0.92));
    float n3 = snoise(q * 2.75 + vec3(-t * 0.16, t * 0.29, t * 0.18) + vec3(n2 * 0.70));
    float r1 = 1.0 - abs(n1 * 0.55 + n2 * 0.45);
    float r2 = 1.0 - abs(n2 * 0.50 - n3 * 0.50);
    float web1 = pow(clamp(r1, 0.0, 1.0), 9.5);
    float web2 = pow(clamp(r2, 0.0, 1.0), 11.0);
    return clamp(web1 * 0.95 + web2 * 0.85, 0.0, 1.8);
}

// Curved, slowly undulating 3D liquid meniscus surface inside the unit crystal sphere
float meniscusHeight(vec2 xz, float t) {
    float bowl = 0.16 * (xz.x * xz.x + xz.y * xz.y) - 0.10 * xz.x;
    float wave1 = 0.095 * sin(xz.x * 3.1 + t * 0.62 + xz.y * 1.4);
    float wave2 = 0.055 * cos(xz.y * 3.8 - t * 0.48 - xz.x * 1.9);
    float ripple = 0.032 * snoise(vec3(xz * 2.8, t * 0.34));
    return 0.06 + bowl + wave1 + wave2 + ripple;
}
"""

  // -------------------------------------------------------------------------
  // 1. DEEP EMERALD-TEAL SPACE BACKGROUND + REFINED WARM OLIVE-GOLD & TEAL BOKEH
  // -------------------------------------------------------------------------
  const val BACKGROUND_VERT = """#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
out vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(aPos, 0.999, 1.0);
}
"""

  const val BACKGROUND_FRAG = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;

uniform float uTime;
uniform vec2 uResolution;
uniform vec2 uParallax;
uniform float uOverallIntensity;
uniform float uHdrScale;

$GLSL_NOISE_LIB

float softBokeh(vec2 uv, vec2 center, float radius) {
    float d = length(uv - center) / max(radius, 1e-4);
    if (d > 1.35) return 0.0;
    // Ultra-soft out-of-focus lens bokeh falloff (no hard circular edge)
    float disc = exp(-d * d * 2.8);
    float rim = exp(-pow((d - 0.62) * 3.6, 2.0)) * 0.22;
    return disc * 0.82 + rim;
}

void main() {
    float minDim = min(uResolution.x, uResolution.y);
    vec2 p = (gl_FragCoord.xy - 0.5 * uResolution) / max(minDim, 1.0);
    vec2 pShift = p + uParallax * 0.04;
    float dist = length(p);

    // Rich abyssal emerald-teal space (deep dark edges, subtle moody forest-teal mid-depth)
    vec3 abyssCorner = vec3(0.005, 0.024, 0.032);
    vec3 deepTeal    = vec3(0.012, 0.058, 0.066);
    vec3 pineEmerald = vec3(0.018, 0.088, 0.074);

    float mist1 = fbm3(vec3(pShift * 1.4, uTime * 0.028));
    float mist2 = fbm3(vec3(pShift * 2.2 + vec2(3.1, -2.4), -uTime * 0.022));

    vec3 col = mix(deepTeal, abyssCorner, smoothstep(0.18, 0.88, dist));
    col += pineEmerald * smoothstep(-0.15, 0.72, mist1) * 0.42 * (1.0 - smoothstep(0.32, 0.95, dist));
    col += vec3(0.035, 0.085, 0.068) * smoothstep(0.10, 0.85, mist2) * 0.24 * smoothstep(0.85, 0.10, dist);

    // Very subtle central depth glow behind the orb
    float aura = exp(-dist * dist * 5.2);
    col += vec3(0.022, 0.115, 0.125) * aura * 0.55;

    // Refined sparse out-of-focus bokeh (warm olive-gold in lower-left & right, muted emerald-teal top)
    vec2 bPos[10];
    float bRad[10];
    vec3 bTint[10];

    // Warm olive-gold bokeh matching reference lower-left & far-right
    bPos[0] = vec2(-0.39, -0.09) + vec2(sin(uTime * 0.10) * 0.014, cos(uTime * 0.08) * 0.012);
    bRad[0] = 0.055; bTint[0] = vec3(0.42, 0.36, 0.11) * 0.42;

    bPos[1] = vec2(-0.30, -0.34) + vec2(cos(uTime * 0.09) * 0.012, sin(uTime * 0.11) * 0.014);
    bRad[1] = 0.072; bTint[1] = vec3(0.32, 0.34, 0.10) * 0.32;

    bPos[2] = vec2(0.42, 0.08) + vec2(sin(uTime * 0.08 + 2.4) * 0.011, cos(uTime * 0.10) * 0.012);
    bRad[2] = 0.048; bTint[2] = vec3(0.40, 0.34, 0.10) * 0.34;

    bPos[3] = vec2(-0.44, -0.02) + vec2(sin(uTime * 0.12 + 1.5) * 0.009, cos(uTime * 0.09) * 0.009);
    bRad[3] = 0.026; bTint[3] = vec3(0.46, 0.48, 0.34) * 0.36;

    // Muted emerald-teal upper & lower depth bokeh
    bPos[4] = vec2(-0.14, 0.44) + vec2(sin(uTime * 0.09 + 1.1) * 0.014, cos(uTime * 0.07) * 0.011);
    bRad[4] = 0.062; bTint[4] = vec3(0.11, 0.28, 0.23) * 0.30;

    bPos[5] = vec2(0.16, 0.46) + vec2(cos(uTime * 0.10 + 2.1) * 0.013, sin(uTime * 0.08) * 0.011);
    bRad[5] = 0.054; bTint[5] = vec3(0.20, 0.30, 0.18) * 0.28;

    bPos[6] = vec2(-0.36, 0.32) + vec2(sin(uTime * 0.11 + 3.8) * 0.012, cos(uTime * 0.08) * 0.010);
    bRad[6] = 0.074; bTint[6] = vec3(0.08, 0.24, 0.24) * 0.26;

    bPos[7] = vec2(0.32, -0.38) + vec2(cos(uTime * 0.08 + 0.7) * 0.012, sin(uTime * 0.09) * 0.012);
    bRad[7] = 0.070; bTint[7] = vec3(0.07, 0.22, 0.22) * 0.28;

    bPos[8] = vec2(0.06, -0.46) + vec2(cos(uTime * 0.07 + 1.9) * 0.012, sin(uTime * 0.10) * 0.010);
    bRad[8] = 0.052; bTint[8] = vec3(0.14, 0.26, 0.18) * 0.24;

    bPos[9] = vec2(0.38, -0.10) + vec2(sin(uTime * 0.10 + 4.5) * 0.010, cos(uTime * 0.09) * 0.010);
    bRad[9] = 0.036; bTint[9] = vec3(0.38, 0.32, 0.11) * 0.28;

    vec2 bp = p + uParallax * 0.06;
    for (int i = 0; i < 10; i++) {
        col += bTint[i] * softBokeh(bp, bPos[i], bRad[i]);
    }

    col *= (0.82 + 0.18 * uOverallIntensity);
    fragColor = vec4(col * uHdrScale, 1.0);
}
"""

  // -------------------------------------------------------------------------
  // 2. CONCAVE INNER BACK-WALL LIQUID BOWL (AIOrbInnerFluid — GL_FRONT culled!)
  //    Provides deep sapphire-teal concave depth, emerald-jade inner wall curtains,
  //    and backlit lower underwater caustics to be refracted through AIOrbCore.
  // -------------------------------------------------------------------------
  const val INNER_FLUID_VERT = """#version 300 es
precision highp float;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;

uniform mat4 uModel;
uniform mat4 uView;
uniform mat4 uProj;
uniform float uTime;
uniform float uFluidSpeed;

out vec3 vLocalPos;
out vec3 vWorldPos;
out vec3 vWorldNormal;

$GLSL_NOISE_LIB

void main() {
    float t = uTime * uFluidSpeed;
    vec3 p = normalize(aPos);
    float disp = snoise(p * 2.1 + vec3(t * 0.20, -t * 0.16, t * 0.15)) * 0.022;
    vec3 displaced = p * (1.0 + disp);

    vec4 worldPos = uModel * vec4(displaced, 1.0);
    vLocalPos = displaced;
    vWorldPos = worldPos.xyz;
    // Inward-facing concave normal for back inner bowl
    vWorldNormal = normalize(mat3(uModel) * (-aNormal));
    gl_Position = uProj * uView * worldPos;
}
"""

  const val INNER_FLUID_FRAG = """#version 300 es
precision highp float;

in vec3 vLocalPos;
in vec3 vWorldPos;
in vec3 vWorldNormal;

out vec4 fragColor;

uniform vec3 uCameraPos;
uniform float uTime;
uniform float uFluidSpeed;
uniform float uFluidIntensity;
uniform float uCoreTransparency;
uniform float uOverallIntensity;
uniform float uHdrScale;

$GLSL_NOISE_LIB

void main() {
    vec3 N = normalize(vWorldNormal);
    vec3 V = normalize(uCameraPos - vWorldPos);
    float t = uTime * uFluidSpeed;

    // 2-stage domain-warped swirling inner liquid current
    vec3 p = vLocalPos * 1.75;
    vec3 warp = vec3(
        fbm3(p + vec3(t * 0.20, -t * 0.14, t * 0.11)),
        fbm3(p + vec3(-t * 0.16, t * 0.22, -t * 0.13) + vec3(3.7, 1.4, 2.3)),
        fbm3(p + vec3(t * 0.13, t * 0.17, -t * 0.19) + vec3(-2.1, 4.8, 1.2))
    );
    vec3 wp = p + warp * 0.85;
    float swirl = fbm3(wp * 1.4 + vec3(-t * 0.22, t * 0.16, t * 0.18));
    float caustic = sharpCaustics(wp * 0.92, t * 0.85);

    float mY = meniscusHeight(vLocalPos.xz, t);
    float belowLiquid = smoothstep(mY + 0.12, mY - 0.14, vLocalPos.y);

    // Deep dark sapphire & emerald shadow base — high contrast so bright caustics pop!
    vec3 deepAbyss    = vec3(0.008, 0.055, 0.095);
    vec3 oceanTeal    = vec3(0.015, 0.185, 0.285);
    vec3 richEmerald  = vec3(0.055, 0.320, 0.140);
    vec3 brightJade   = vec3(0.240, 0.580, 0.175);
    vec3 electricCyan = vec3(0.090, 0.860, 0.980);

    // Emerald-jade translucent folds hugging left & upper-right interior curved glass wall
    float leftJade  = smoothstep(-0.18, -0.78, vLocalPos.x + warp.x * 0.22) * smoothstep(0.58, -0.68, vLocalPos.y);
    float rightJade = smoothstep(0.26, 0.82, vLocalPos.x + warp.z * 0.24) * smoothstep(-0.35, 0.62, vLocalPos.y);
    float jadeCurtain = clamp((leftJade * 1.05 + rightJade * 0.72) * (0.48 + 0.52 * smoothstep(-0.3, 0.6, swirl)), 0.0, 1.0);

    // Lower-center / lower-right focused cyan caustic pool
    float lowerBelly = smoothstep(0.12, -0.82, vLocalPos.y) * smoothstep(0.98, 0.12, length(vLocalPos.xz - vec2(0.12, -0.18)));

    vec3 col = mix(deepAbyss, oceanTeal, smoothstep(-0.35, 0.60, swirl) * (0.35 + 0.65 * belowLiquid));
    col = mix(col, mix(richEmerald, brightJade, smoothstep(0.0, 0.65, swirl)), jadeCurtain * 0.85);

    // Focused lacy cyan underwater caustics on the dark concave lower wall
    col += electricCyan * caustic * (0.18 + 0.95 * lowerBelly) * belowLiquid * 0.75 * uFluidIntensity;

    // Upper dome stays very dark & transparent so glass cavity looks crystal clear
    float cavityAlpha = mix(0.24, 0.74, belowLiquid);
    float alpha = clamp((cavityAlpha + jadeCurtain * 0.38) * (1.06 - uCoreTransparency * 0.35), 0.15, 0.84);

    col *= (0.80 + 0.24 * uOverallIntensity);
    fragColor = vec4(col * uHdrScale, alpha);
}
"""

  // -------------------------------------------------------------------------
  // 3. OUTER LIQUID-GLASS CORE WITH REFRACTED-RAY 3D MENISCUS & VOLUME TRACING
  // -------------------------------------------------------------------------
  const val CORE_GLASS_VERT = """#version 300 es
precision highp float;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;

uniform mat4 uModel;
uniform mat4 uView;
uniform mat4 uProj;
uniform float uTime;
uniform float uFluidSpeed;

out vec3 vWorldPos;
out vec3 vWorldNormal;
out vec3 vViewNormal;
out vec3 vLocalPos;

$GLSL_NOISE_LIB

vec3 displaceGlass(vec3 p, float t) {
    // Organic subtle liquid-glass surface breathing undulation
    float n1 = snoise(p * 1.85 + vec3(t * 0.22, -t * 0.17, t * 0.14));
    float n2 = snoise(p * 3.50 + vec3(-t * 0.25, t * 0.19, -t * 0.16));
    float amp = 0.014 * n1 + 0.005 * n2;
    return p * (1.0 + amp);
}

void main() {
    float t = uTime * (0.45 + 0.55 * uFluidSpeed);
    vec3 p = normalize(aPos);
    vec3 displaced = displaceGlass(p, t);

    // Finite-difference perturbed liquid-glass normal
    vec3 up = abs(p.y) < 0.92 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 tangent = normalize(cross(up, p));
    vec3 bitangent = normalize(cross(p, tangent));
    float eps = 0.014;
    vec3 pT = displaceGlass(normalize(p + tangent * eps), t);
    vec3 pB = displaceGlass(normalize(p + bitangent * eps), t);
    vec3 localNormal = normalize(cross(pT - displaced, pB - displaced));
    if (dot(localNormal, p) < 0.0) localNormal = -localNormal;

    vec4 worldPos = uModel * vec4(displaced, 1.0);
    vec3 worldN = normalize(mat3(uModel) * localNormal);
    vec3 viewN = normalize(mat3(uView) * worldN);

    vWorldPos = worldPos.xyz;
    vWorldNormal = worldN;
    vViewNormal = viewN;
    vLocalPos = displaced;
    gl_Position = uProj * uView * worldPos;
}
"""

  const val CORE_GLASS_FRAG = """#version 300 es
precision highp float;

in vec3 vWorldPos;
in vec3 vWorldNormal;
in vec3 vViewNormal;
in vec3 vLocalPos;

out vec4 fragColor;

uniform sampler2D uRefractionTex;
uniform vec2 uViewportSize;
uniform mat4 uInvModel;
uniform vec3 uCameraPos;
uniform float uTime;
uniform float uFluidSpeed;
uniform float uFluidIntensity;
uniform float uCoreTransparency;
uniform float uRefraction;
uniform float uOverallIntensity;
uniform float uHdrScale;

uniform vec3 uKeyLightDir;
uniform vec3 uKeyLightColor;
uniform vec3 uWarmRimDir;
uniform vec3 uWarmRimColor;
uniform vec3 uCoolRimDir;
uniform vec3 uCoolRimColor;
uniform vec3 uEmeraldFillColor;

$GLSL_NOISE_LIB

// Analytic 3D meniscus wave normal via central differences
vec3 meniscusNormal(vec2 xz, float t) {
    float e = 0.02;
    float hx = meniscusHeight(xz + vec2(e, 0.0), t) - meniscusHeight(xz - vec2(e, 0.0), t);
    float hz = meniscusHeight(xz + vec2(0.0, e), t) - meniscusHeight(xz - vec2(0.0, e), t);
    return normalize(vec3(-hx, 2.0 * e, -hz));
}

// High-contrast, dark-dominated crystal studio environment (keeps orb center ultra-clear!)
vec3 sampleCrystalEnv(vec3 R) {
    vec3 env = vec3(0.008, 0.032, 0.044);

    // Compact curved upper-left warm sunlit studio reflection window (~10:30)
    vec3 keyAxis = normalize(vec3(-0.56, 0.72, 0.40));
    float keySpot = smoothstep(0.84, 0.985, dot(R, keyAxis));
    env += vec3(1.85, 1.48, 0.88) * keySpot;

    // Warm golden orbital ribbon reflection curving along right & top-right edge
    float goldBand = exp(-pow((R.y - 0.22 * R.x - 0.08) * 6.5, 2.0)) * smoothstep(0.18, 0.88, R.x);
    env += vec3(1.45, 0.86, 0.22) * goldBand * 0.92;

    // Ice-cyan ribbon reflection along lower-left / bottom rim
    float cyanBand = exp(-pow((R.y + 0.36 + 0.24 * R.x) * 6.0, 2.0)) * smoothstep(0.12, -0.78, R.x);
    env += vec3(0.18, 0.88, 1.15) * cyanBand * 0.85;

    return env;
}

void main() {
    vec3 N = normalize(vWorldNormal);
    vec3 V = normalize(uCameraPos - vWorldPos);
    vec3 VN = normalize(vViewNormal);

    float t = uTime * uFluidSpeed;

    // Subtle micro-liquid ripple on outer glass normal
    float micro = snoise(vLocalPos * 4.8 + vec3(t * 0.16, -t * 0.14, t * 0.12));
    vec3 pN = normalize(N + vec3(micro * 0.018, -micro * 0.016, micro * 0.018));
    float NdotV = clamp(dot(pN, V), 0.0, 1.0);

    // -----------------------------------------------------------------------
    // 1. REAL FBO REFRACTION OF BACKGROUND + BACK RIBBONS + CONCAVE INNER BOWL
    // -----------------------------------------------------------------------
    vec2 screenUv = gl_FragCoord.xy / max(uViewportSize, vec2(1.0));
    // Lens-style refraction: stronger outward bend near the thick curved rim
    float rimCurve = pow(1.0 - NdotV, 1.15);
    vec2 refractUvShift = VN.xy * (0.018 + 0.095 * rimCurve) * uRefraction;
    float dispSplit = 0.11 * (0.45 + 0.55 * uRefraction);

    vec2 uvR = clamp(screenUv - refractUvShift * (1.0 + dispSplit), vec2(0.002), vec2(0.998));
    vec2 uvG = clamp(screenUv - refractUvShift, vec2(0.002), vec2(0.998));
    vec2 uvB = clamp(screenUv - refractUvShift * (1.0 - dispSplit), vec2(0.002), vec2(0.998));

    // Decode linear HDR out of refractionFbo
    float invHdr = 1.0 / max(uHdrScale, 1e-4);
    vec3 refractedBack;
    refractedBack.r = texture(uRefractionTex, uvR).r * invHdr;
    refractedBack.g = texture(uRefractionTex, uvG).g * invHdr;
    refractedBack.b = texture(uRefractionTex, uvB).b * invHdr;

    // -----------------------------------------------------------------------
    // 2. REFRACTED-RAY 3D INTERIOR LIQUID MENISCUS & VOLUMETRIC CAUSTICS
    // -----------------------------------------------------------------------
    vec3 safeN = dot(pN, V) > 0.02 ? pN : normalize(pN + V * 0.08);
    vec3 refrWorld = refract(-V, safeN, 1.0 / 1.36);
    if (dot(refrWorld, refrWorld) < 1e-5) refrWorld = -V;
    vec3 rayDir = normalize(mat3(uInvModel) * refrWorld);
    vec3 rayOrg = vLocalPos * 0.96;

    // Analytic distance to exit the interior unit sphere along rayDir
    float bDot = dot(rayOrg, rayDir);
    float cRad = dot(rayOrg, rayOrg) - 0.94 * 0.94;
    float disc = max(bDot * bDot - cRad, 0.0);
    float tExit = max(-bDot + sqrt(disc), 0.05);

    // March 10 steps through the interior to locate exact 3D meniscus surface & integrate liquid depth
    float liquidPath = 0.0;
    float hitMeniscus = 0.0;
    vec3 meniscusHitPos = vec3(0.0);
    vec3 volumeGlow = vec3(0.0);
    float prevDiff = rayOrg.y - meniscusHeight(rayOrg.xz, t);
    float stepLen = tExit / 10.0;

    for (int i = 1; i <= 10; i++) {
        float distS = (float(i) - 0.35) * stepLen;
        vec3 sp = rayOrg + rayDir * distS;
        float mH = meniscusHeight(sp.xz, t);
        float diff = sp.y - mH;

        // Detect first crossing of the 3D wavy water-air meniscus interface
        if (hitMeniscus < 0.5 && ((prevDiff > 0.0 && diff <= 0.0) || (prevDiff < 0.0 && diff >= 0.0))) {
            float frac = clamp(prevDiff / max(abs(prevDiff - diff), 1e-4), 0.0, 1.0);
            float tHit = (float(i - 1) + frac) * stepLen;
            meniscusHitPos = rayOrg + rayDir * tHit;
            hitMeniscus = 1.0;
        }
        prevDiff = diff;

        if (diff < 0.0) {
            liquidPath += stepLen;
            // Focus sharp glowing turquoise caustics in the lower-center/lower-right water belly
            float lowerWeight = smoothstep(0.14, -0.78, sp.y) * smoothstep(0.95, 0.08, length(sp.xz - vec2(0.10, -0.05)));
            float cSample = sharpCaustics(sp * 1.12 + vec3(0.0, t * 0.08, 0.0), t * 0.92);

            // Translucent swirling emerald-jade folds hugging left & upper-right inner volume
            float leftJade  = smoothstep(-0.12, -0.78, sp.x) * smoothstep(0.35, -0.72, sp.y);
            float rightJade = smoothstep(0.28, 0.80, sp.x) * smoothstep(-0.35, 0.50, sp.y);
            float jadeFold  = clamp(leftJade * 0.95 + rightJade * 0.65, 0.0, 1.0);
            float jadeWave  = smoothstep(-0.15, 0.65, snoise(sp * 2.4 + vec3(t * 0.24, -t * 0.18, t * 0.16)));

            vec3 cyanCausticCol = mix(vec3(0.04, 0.72, 0.92), vec3(0.45, 0.98, 1.00), clamp(cSample * 0.65, 0.0, 1.0));
            vec3 jadeLiquidCol  = mix(vec3(0.05, 0.42, 0.16), vec3(0.32, 0.72, 0.18), jadeWave);

            // Attenuate deeper slices slightly so near-mid caustics stay razor-sharp
            float depthAtten = exp(-distS * 0.85);
            volumeGlow += cyanCausticCol * cSample * (0.12 + 1.05 * lowerWeight) * stepLen * 0.68 * depthAtten;
            volumeGlow += jadeLiquidCol * jadeFold * (0.28 + 0.55 * jadeWave) * stepLen * 0.42 * depthAtten;
        }
    }

    // Beer-Lambert optical absorption through the deep water volume (preserves rich dark ocean contrast!)
    vec3 waterTransmittance = exp(-liquidPath * vec3(1.65, 0.48, 0.24) * (1.25 - uCoreTransparency * 0.42));
    vec3 interiorCol = refractedBack * waterTransmittance + volumeGlow * uFluidIntensity;

    // Crisp glossy 3D water-air meniscus wave highlight (iconic curved water horizon inside reference orb!)
    if (hitMeniscus > 0.5) {
        vec3 mN = meniscusNormal(meniscusHitPos.xz, t);
        vec3 localV = normalize(-rayDir);
        float mFres = pow(1.0 - max(dot(mN, localV), 0.0), 3.2);
        vec3 mHalf = normalize(localV + normalize(vec3(-0.45, 0.78, 0.45)));
        float mSpec = pow(max(dot(mN, mHalf), 0.0), 28.0);
        // Dark refractive meniscus under-shadow + shimmering ice-cyan wave crest highlight
        interiorCol *= (1.0 - 0.38 * mFres);
        interiorCol += vec3(0.28, 0.88, 0.96) * (mFres * 0.55 + mSpec * 0.62) * uFluidIntensity;
    }

    // -----------------------------------------------------------------------
    // 3. DOUBLE-WALL THICK BLOWN GLASS SHELL & ENERGY-CONSERVING SPECULARS
    // -----------------------------------------------------------------------
    // Keep front center ultra-clear (Fresnel ~0.04) so deep liquid & caustics remain razor sharp!
    float fresnelOuter = 0.035 + 0.965 * pow(1.0 - NdotV, 4.8);
    float razorOuterRim = pow(1.0 - NdotV, 8.5);
    // Secondary inner glass wall thickness ring (iconic blown crystal shell depth)
    float innerWallRing = exp(-pow((NdotV - 0.24) / 0.072, 2.0));
    // Subtle dark total-internal-reflection groove right between outer & inner glass walls
    float glassGroove = exp(-pow((NdotV - 0.12) / 0.045, 2.0));

    vec3 R = reflect(-V, pN);
    vec3 envRefl = sampleCrystalEnv(R);

    // Directional color-split crystal rim matching reference:
    // Fiery champagne-amber gold along upper-left, top & right; crisp ice-cyan along bottom-left & bottom
    float warmRimWeight = smoothstep(-0.22, 0.55, VN.x * 0.74 + VN.y * 0.66);
    float coolRimWeight = smoothstep(-0.15, 0.70, -VN.y * 0.78 - VN.x * 0.42);
    vec3 fieryGoldRim = vec3(1.25, 0.76, 0.22);
    vec3 champagneRim = vec3(1.35, 1.06, 0.62);
    vec3 iceCyanRim   = vec3(0.26, 0.92, 1.12);

    vec3 rimPalette = mix(iceCyanRim, mix(fieryGoldRim, champagneRim, razorOuterRim * 0.55), warmRimWeight);

    // Energy-conserving speculars (strictly NdotL-weighted & compact — NEVER washes out the left half!)
    vec3 Lkey = normalize(uKeyLightDir);
    float NdotLKey = max(dot(pN, Lkey), 0.0);
    vec3 Hkey = normalize(V + Lkey);
    float NdotHKey = max(dot(pN, Hkey), 0.0);
    float specKeyPinpoint = pow(NdotHKey, 160.0) * NdotLKey * 2.4;
    float specKeyCurved   = pow(NdotHKey, 28.0)  * NdotLKey * 0.34;

    // Delicate golden sunlit lightning-caustic veins radiating right inside the upper-left key window
    float sunSpot = smoothstep(0.70, 0.97, dot(pN, Lkey));
    float sunCaustics = sharpCaustics(vLocalPos * 2.5, t * 0.48) * sunSpot * 0.85;
    vec3 keyHighlight = vec3(1.28, 1.04, 0.64) * (specKeyPinpoint + specKeyCurved + sunCaustics);

    // Crisp right amber rim catchlight & bottom-left ice-cyan glint
    vec3 Lwarm = normalize(uWarmRimDir);
    float specWarm = pow(max(dot(pN, normalize(V + Lwarm)), 0.0), 48.0) * max(dot(pN, Lwarm), 0.0) * 1.15;
    vec3 Lcool = normalize(uCoolRimDir);
    float specCool = pow(max(dot(pN, normalize(V + Lcool)), 0.0), 44.0) * max(dot(pN, Lcool), 0.0) * 0.75;

    // Compose glass shell: crystal-clear transmission + subtle dark wall groove + double-wall rims + specular
    vec3 col = interiorCol * (1.0 - glassGroove * 0.28);
    float envBlend = clamp(fresnelOuter * (1.12 - uCoreTransparency * 0.38), 0.03, 0.76);
    col = mix(col, envRefl, envBlend * 0.58);

    // Double-rim crystal glass luminance (outer sharp shell + inner refracted wall ring)
    col += rimPalette * (fresnelOuter * 0.72 + razorOuterRim * 1.15);
    col += mix(iceCyanRim, fieryGoldRim, warmRimWeight * 0.85) * innerWallRing * 0.36;
    col += iceCyanRim * coolRimWeight * innerWallRing * 0.24;

    // Crisp specular highlights
    col += keyHighlight + fieryGoldRim * specWarm + iceCyanRim * specCool;

    col *= (0.82 + 0.24 * uOverallIntensity);

    float edgeAlpha = smoothstep(0.0, 0.045, NdotV);
    fragColor = vec4(col * uHdrScale, edgeAlpha);
}
"""
}
