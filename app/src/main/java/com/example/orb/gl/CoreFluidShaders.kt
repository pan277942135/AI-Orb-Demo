package com.example.orb.gl

/**
 * V2 Reference Parity GLSL ES 3.00 Shaders:
 * - True Linear HDR pipeline paired with sRGB OETF in COMPOSITE_FRAG
 * - Zero inverted smoothstep calls (strictly uses `invSmoothstep(lo, hi, x)` with lo < hi)
 * - Unified Core Frame (`coreFramePos = uCoreRotMat * localPos`) driving rigid tilted-axis 3D rotation
 *   for meniscus tilt/intersection, liquid zoning, and core-locked masks (with normal inverse via `transpose(uCoreRotMat)`)
 * - Advected Frame (`advectFluidPos`) driving differential vortex swirl & divergence-free curl fluid detail
 * - 3D Flow Domain Warping irregular curved-wave underwater caustics (ZERO Voronoi / honeycomb grids),
 *   concentrated asymmetrically in the lower interior optical belly
 * - Rich emerald-jade & deep sapphire volumetric core with Beer-Lambert optical absorption
 * - Pixel-bounded FBO refraction (14-18 px max at grazing edges, ~0 px at center) & tight grazing-angle Cauchy dispersion
 * - Restrained background bokeh (60% brightness, -20% radius) preserving deep emerald-teal underwater hue
 */
object CoreFluidShaders {

  private const val GLSL_COMMON_MATH = """
// Safe inverted smoothstep guaranteeing edge0 < edge1 per GLSL ES 3.00 §8.3
float invSmoothstep(float lo, float hi, float x) {
    return 1.0 - smoothstep(lo, hi, x);
}

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

// Rigid rotated Core Frame (tilted 3D axis rotation ONLY — NO curl).
// Drives meniscus height/crossing, liquid zone masks, and caustic spatial anchoring.
vec3 toCoreFrame(vec3 localPos, mat3 coreRot) {
    return coreRot * localPos;
}

// Advected Frame: starts from Core Frame + differential azimuthal vortex swirl + divergence-free 3D curl.
// Drives internal volumetric liquid folds, currents, and caustic optical warping.
vec3 advectFluidPos(vec3 sp, mat3 coreRot, float fluidPhase) {
    vec3 rotated = toCoreFrame(sp, coreRot);
    float rXZ = length(rotated.xz);
    float twistAngle = (1.0 - clamp(rXZ, 0.0, 1.0)) * 0.92
                     + 0.26 * sin(rotated.y * 2.5 - fluidPhase * 0.58);
    float ct = cos(twistAngle);
    float st = sin(twistAngle);
    vec3 swirled = vec3(
        rotated.x * ct - rotated.z * st,
        rotated.y,
        rotated.x * st + rotated.z * ct
    );

    float freq = 2.65;
    vec3 curl = vec3(
        sin(swirled.y * freq + fluidPhase * 0.62) * cos(swirled.z * freq - fluidPhase * 0.44),
        sin(swirled.z * freq - fluidPhase * 0.50) * cos(swirled.x * freq + fluidPhase * 0.48),
        sin(swirled.x * freq + fluidPhase * 0.56) * cos(swirled.y * freq - fluidPhase * 0.38)
    ) * 0.15;

    return swirled + curl;
}

// 3D Flow Domain Warping irregular curved-wave underwater caustics (ZERO Voronoi / honeycomb grids!).
// Uses 2-stage FBM flow warping + razor-sharp non-commensurate curved filaments + asymmetric lower-belly mask.
float domainWarpCaustics(vec3 advectedPos, vec3 coreFramePos, vec2 meniscusSlope, float causticsPhase) {
    vec3 wPos = advectedPos * 1.75 + vec3(causticsPhase * 0.16, -causticsPhase * 0.12, causticsPhase * 0.14);
    vec3 warpA = vec3(
        fbm3(wPos),
        fbm3(wPos + vec3(5.2, 1.3, -3.7)),
        fbm3(wPos + vec3(-2.8, 4.6, 2.9))
    );
    vec2 warpB = vec2(
        fbm3(wPos * 1.90 + warpA * 1.35 + vec3(1.7, -2.4, 3.1)),
        fbm3(wPos * 1.90 - warpA * 1.25 + vec3(-3.9, 2.2, 1.4))
    );

    // Organic curved wave coordinates warped by 3D flow & meniscus refraction slope
    vec2 q1 = advectedPos.xz * 2.85 + vec2(advectedPos.y * 0.90, -advectedPos.y * 0.60)
            + meniscusSlope * 0.34 + warpA.xy * 1.25 + warpB * 0.65;

    float w1 = sin(q1.x * 2.45 + cos(q1.y * 2.15 + warpA.z * 2.4 + causticsPhase * 0.55))
             + cos(q1.y * 2.75 - sin(q1.x * 1.95 - warpB.x * 2.1 - causticsPhase * 0.48));
    float ridge1 = exp(-abs(w1) * 6.8);

    // Rotated irrational-ratio secondary optical branch
    vec2 q2 = vec2(q1.x * 0.68 - q1.y * 0.73, q1.x * 0.73 + q1.y * 0.68) * 1.52 + warpB.yx * 0.95;
    float w2 = sin(q2.x * 2.65 - sin(q2.y * 2.35 + warpA.x * 2.6 - causticsPhase * 0.64))
             + cos(q2.y * 2.25 + cos(q2.x * 2.50 + warpB.y * 2.0 + causticsPhase * 0.52));
    float ridge2 = exp(-abs(w2) * 8.0);

    // Fine razor-crisp shimmer filaments (non-commensurate 2.13x ratio breaks any regular pattern)
    vec2 q3 = vec2(q1.x * -0.44 + q1.y * 0.90, q1.x * -0.90 - q1.y * 0.44) * 2.18 + warpA.zy * 0.85;
    float w3 = sin(q3.x * 2.20 + cos(q3.y * 2.55 + causticsPhase * 0.78))
             - cos(q3.y * 2.40 + sin(q3.x * 2.00 - causticsPhase * 0.62));
    float ridge3 = exp(-abs(w3) * 9.0);

    // Non-uniform thickness & intensity breakup + blazing convergence nodes
    float breakMask = smoothstep(-0.30, 0.56, warpA.x * 0.65 + warpB.y * 0.55);
    float convergence = pow(clamp(ridge1 * ridge2 * 3.4, 0.0, 1.0), 1.25);
    float rawVeins = (ridge1 * 0.76 + ridge2 * 0.62 + ridge3 * 0.44 + convergence * 1.45)
                   * (0.38 + 0.88 * breakMask);

    // Asymmetric Core-Frame spatial mask concentrating caustics in lower interior water pockets
    float lowerPocket = invSmoothstep(-0.92, 0.04, coreFramePos.y);
    float asymPocket = smoothstep(-0.42, 0.60, coreFramePos.x * 0.48 - coreFramePos.y * 0.78 + warpA.z * 0.45);
    return clamp(rawVeins * lowerPocket * (0.30 + 0.70 * asymPocket), 0.0, 2.0);
}
"""

  // -------------------------------------------------------------------------
  // 1. DEEP EMERALD-TEAL UNDERWATER SPACE + RESTRAINED MULTI-DEPTH BOKEH
  //    (60% bokeh brightness, -20% bokeh radius; preserves deep emerald-teal hue)
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

$GLSL_COMMON_MATH

float softBokeh(vec2 uv, vec2 center, float radius) {
    float d = length(uv - center) / max(radius, 1e-4);
    if (d > 1.45) return 0.0;
    float disc = exp(-d * d * 2.35);
    float rim = exp(-pow((d - 0.66) * 3.8, 2.0)) * 0.24;
    return disc * 0.84 + rim;
}

void main() {
    float minDim = min(uResolution.x, uResolution.y);
    vec2 p = (gl_FragCoord.xy - 0.5 * uResolution) / max(minDim, 1.0);
    vec2 pShift = p + uParallax * 0.04;
    float dist = length(p);

    // Rich deep emerald-teal underwater gradient (unchanged signature hue)
    vec3 abyssCorner = vec3(0.0018, 0.0155, 0.0205);
    vec3 deepTeal    = vec3(0.0045, 0.0360, 0.0440);
    vec3 pineEmerald = vec3(0.0090, 0.0580, 0.0480);

    float mist1 = fbm3(vec3(pShift * 1.45, uTime * 0.028));
    float mist2 = fbm3(vec3(pShift * 2.35 + vec2(3.1, -2.4), -uTime * 0.022));

    vec3 col = mix(deepTeal, abyssCorner, smoothstep(0.16, 0.86, dist));
    col += pineEmerald * smoothstep(-0.18, 0.68, mist1) * 0.52 * invSmoothstep(0.28, 0.96, dist);
    col += vec3(0.014, 0.048, 0.042) * smoothstep(0.05, 0.80, mist2) * 0.34 * invSmoothstep(0.10, 0.88, dist);

    // Subtle deep cyan-emerald volumetric back-glow behind the suspended crystal orb
    float aura = exp(-dist * dist * 4.4);
    col += vec3(0.010, 0.068, 0.074) * aura * 0.62;

    // Multi-depth subtle underwater bokeh (brightness scaled to 60%, radii shrunk by 20%)
    vec2 bPos[12];
    float bRad[12];
    vec3 bTint[12];

    bPos[0] = vec2(-0.42, -0.07) + vec2(sin(uTime * 0.09) * 0.012, cos(uTime * 0.08) * 0.010);
    bRad[0] = 0.048; bTint[0] = vec3(0.160, 0.130, 0.024) * 0.49;

    bPos[1] = vec2(-0.34, -0.34) + vec2(cos(uTime * 0.08) * 0.011, sin(uTime * 0.10) * 0.012);
    bRad[1] = 0.062; bTint[1] = vec3(0.115, 0.118, 0.022) * 0.38;

    bPos[2] = vec2(0.44, 0.08) + vec2(sin(uTime * 0.08 + 2.4) * 0.010, cos(uTime * 0.09) * 0.010);
    bRad[2] = 0.043; bTint[2] = vec3(0.145, 0.118, 0.024) * 0.41;

    bPos[3] = vec2(-0.47, 0.01) + vec2(sin(uTime * 0.11 + 1.5) * 0.008, cos(uTime * 0.09) * 0.008);
    bRad[3] = 0.024; bTint[3] = vec3(0.190, 0.185, 0.105) * 0.34;

    bPos[4] = vec2(-0.18, 0.45) + vec2(sin(uTime * 0.09 + 1.1) * 0.012, cos(uTime * 0.07) * 0.010);
    bRad[4] = 0.054; bTint[4] = vec3(0.024, 0.098, 0.088) * 0.41;

    bPos[5] = vec2(0.14, 0.46) + vec2(cos(uTime * 0.10 + 2.1) * 0.012, sin(uTime * 0.08) * 0.010);
    bRad[5] = 0.046; bTint[5] = vec3(0.052, 0.105, 0.064) * 0.35;

    bPos[6] = vec2(-0.38, 0.34) + vec2(sin(uTime * 0.10 + 3.8) * 0.011, cos(uTime * 0.08) * 0.010);
    bRad[6] = 0.066; bTint[6] = vec3(0.018, 0.086, 0.092) * 0.37;

    bPos[7] = vec2(0.35, -0.38) + vec2(cos(uTime * 0.08 + 0.7) * 0.011, sin(uTime * 0.09) * 0.011);
    bRad[7] = 0.061; bTint[7] = vec3(0.018, 0.082, 0.088) * 0.38;

    bPos[8] = vec2(0.05, -0.46) + vec2(cos(uTime * 0.07 + 1.9) * 0.011, sin(uTime * 0.10) * 0.010);
    bRad[8] = 0.046; bTint[8] = vec3(0.036, 0.092, 0.062) * 0.31;

    bPos[9] = vec2(0.40, -0.11) + vec2(sin(uTime * 0.10 + 4.5) * 0.009, cos(uTime * 0.09) * 0.009);
    bRad[9] = 0.032; bTint[9] = vec3(0.145, 0.118, 0.028) * 0.35;

    bPos[10] = vec2(-0.24, -0.44) + vec2(sin(uTime * 0.07 + 3.1) * 0.010, cos(uTime * 0.09) * 0.010);
    bRad[10] = 0.051; bTint[10] = vec3(0.092, 0.110, 0.026) * 0.30;

    bPos[11] = vec2(0.32, 0.36) + vec2(cos(uTime * 0.09 + 1.4) * 0.010, sin(uTime * 0.08) * 0.010);
    bRad[11] = 0.058; bTint[11] = vec3(0.020, 0.080, 0.076) * 0.33;

    vec2 bp = p + uParallax * 0.06;
    for (int i = 0; i < 12; i++) {
        col += bTint[i] * softBokeh(bp, bPos[i], bRad[i]);
    }

    col *= (0.85 + 0.15 * uOverallIntensity);
    fragColor = vec4(col * uHdrScale, 1.0);
}
"""

  // -------------------------------------------------------------------------
  // 2. CONCAVE INNER BACK-WALL LIQUID BOWL (LOCKED TO UNIFIED CORE FRAME)
  //    Provides deep sapphire/ink-emerald underwater abyss & soft ambient glow ONLY
  //    (duplicate bright caustic web removed so single crisp floor caustics shine cleanly!)
  // -------------------------------------------------------------------------
  const val INNER_FLUID_VERT = """#version 300 es
precision highp float;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;

uniform mat4 uModel;
uniform mat4 uView;
uniform mat4 uProj;
uniform float uFluidPhase;

out vec3 vLocalPos;
out vec3 vWorldPos;
out vec3 vWorldNormal;

$GLSL_COMMON_MATH

void main() {
    vec3 p = normalize(aPos);
    float disp = snoise(p * 2.0 + vec3(uFluidPhase * 0.18, -uFluidPhase * 0.14, uFluidPhase * 0.12)) * 0.014;
    vec3 displaced = p * (1.0 + disp);

    vec4 worldPos = uModel * vec4(displaced, 1.0);
    vLocalPos = displaced;
    vWorldPos = worldPos.xyz;
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

uniform mat3 uCoreRotMat;
uniform float uFluidPhase;
uniform float uMeniscusPhase;
uniform float uCausticsPhase;
uniform float uFluidIntensity;
uniform float uCoreTransparency;
uniform float uOverallIntensity;
uniform float uHdrScale;

$GLSL_COMMON_MATH

float legacyInnerMeniscus(vec2 coreXZ, float meniscusPhase) {
    float r2 = dot(coreXZ, coreXZ);
    return 0.20 + 0.24 * r2 + 0.03 * sin(coreXZ.x * 2.4 + meniscusPhase * 0.45);
}

void main() {
    // Unified Core Frame for meniscus & zoning; Advected Frame for swirling fluid folds
    vec3 corePos = toCoreFrame(vLocalPos, uCoreRotMat);
    vec3 advPos = advectFluidPos(vLocalPos, uCoreRotMat, uFluidPhase);
    float swirl = fbm3(advPos * 1.75);
    float vortexArm = smoothstep(0.16, 0.84, 0.5 + 0.5 * sin(atan(advPos.z, advPos.x) * 3.0 + advPos.y * 2.4 + swirl * 2.8));

    float mY = legacyInnerMeniscus(corePos.xz, uMeniscusPhase);
    float belowLiquid = invSmoothstep(mY - 0.09, mY + 0.07, corePos.y);

    // Deep midnight sapphire + dark ink-emerald abyss with interior emerald-jade folds
    vec3 deepAbyss    = vec3(0.0012, 0.0135, 0.0255);
    vec3 deepSapphire = vec3(0.0032, 0.0440, 0.0980);
    vec3 richEmerald  = vec3(0.0085, 0.1850, 0.0560);
    vec3 brightJade   = vec3(0.0720, 0.4550, 0.1280);

    // Broad rotating emerald-jade liquid folds extending deep across the concave back wall
    float rotJadeLobe = smoothstep(-0.22, 0.68, sin(atan(advPos.z, advPos.x) * 2.0 + advPos.y * 1.85 + swirl * 1.7));
    float interiorSpread = 0.48 + 0.52 * smoothstep(0.10, 0.88, length(corePos.xz));
    float jadeCurtain = clamp(
        (0.34 + 0.66 * rotJadeLobe) * interiorSpread * invSmoothstep(-0.78, 0.52, corePos.y)
        * (0.44 + 0.56 * smoothstep(-0.26, 0.58, swirl)),
        0.0, 1.0
    );

    // Soft ambient underwater depth glow only (no duplicate sharp caustic web on back wall!)
    float ambientBelly = invSmoothstep(-0.90, 0.08, corePos.y)
                       * (0.55 + 0.45 * sin(uCausticsPhase * 0.25 + corePos.x * 2.2));
    vec3 softAmbientGlow = vec3(0.012, 0.115, 0.135) * ambientBelly * belowLiquid * 0.36 * uFluidIntensity;

    vec3 col = mix(deepAbyss, deepSapphire, (0.24 + 0.76 * vortexArm) * belowLiquid);
    col = mix(col, mix(richEmerald, brightJade, smoothstep(-0.10, 0.64, swirl)), jadeCurtain * 0.86 * belowLiquid);
    col += softAmbientGlow;

    // Keep upper air cavity nearly transparent so the upper crystal glass dome stays crisp & clear
    float cavityAlpha = mix(0.07, 0.80, belowLiquid);
    float alpha = clamp((cavityAlpha + jadeCurtain * 0.22 * belowLiquid) * (1.02 - uCoreTransparency * 0.24), 0.05, 0.86);

    col *= (0.85 + 0.18 * uOverallIntensity);
    fragColor = vec4(col * uHdrScale, alpha);
}
"""

  // -------------------------------------------------------------------------
  // 3. OUTER BLOWN CRYSTAL GLASS CORE WITH:
  //    - PIXEL-BOUNDED FBO REFRACTION (14-18px MAX AT EDGES, ~0px AT CENTER)
  //    - UNIFIED CORE-FRAME TILTED MENISCUS & TRANSPOSE NORMAL INVERSE
  //    - RICH EMERALD-JADE & DEEP SAPPHIRE 3D VOLUMETRIC CURRENTS
  //    - 3D FLOW DOMAIN WARPING IRREGULAR CURVED-WAVE UNDERWATER CAUSTICS
  //    - TIGHT CHAMPAGNE-GOLD & ICE-BLUE STUDIO CRYSTAL REFLECTIONS
  // -------------------------------------------------------------------------
  const val CORE_GLASS_VERT = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;

uniform mat4 uModel;
uniform mat4 uView;
uniform mat4 uProj;
uniform float uFluidPhase;

out vec3 vWorldPos;
out vec3 vWorldNormal;
out vec3 vViewNormal;
out vec3 vLocalPos;
out float vViewZ;

$GLSL_COMMON_MATH

vec3 displaceGlass(vec3 p, float phase) {
    // Subtle living blown-glass undulation (keeps outer shell optically steady & crisp)
    float n1 = snoise(p * 1.85 + vec3(phase * 0.16, -phase * 0.12, phase * 0.10));
    float n2 = snoise(p * 3.50 + vec3(-phase * 0.19, phase * 0.14, -phase * 0.12));
    float amp = 0.0085 * n1 + 0.0032 * n2;
    return p * (1.0 + amp);
}

void main() {
    vec3 p = normalize(aPos);
    vec3 displaced = displaceGlass(p, uFluidPhase);

    vec3 up = abs(p.y) < 0.92 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 tangent = normalize(cross(up, p));
    vec3 bitangent = normalize(cross(p, tangent));
    float eps = 0.014;
    vec3 pT = displaceGlass(normalize(p + tangent * eps), uFluidPhase);
    vec3 pB = displaceGlass(normalize(p + bitangent * eps), uFluidPhase);
    vec3 localNormal = normalize(cross(pT - displaced, pB - displaced));
    if (dot(localNormal, p) < 0.0) localNormal = -localNormal;

    vec4 worldPos = uModel * vec4(displaced, 1.0);
    vec4 viewPos = uView * worldPos;
    vec3 worldN = normalize(mat3(uModel) * localNormal);
    vec3 viewN = normalize(mat3(uView) * worldN);

    vWorldPos = worldPos.xyz;
    vWorldNormal = worldN;
    vViewNormal = viewN;
    vLocalPos = displaced;
    vViewZ = viewPos.z;
    gl_Position = uProj * viewPos;
}
"""

  const val CORE_GLASS_FRAG = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler3D;

in vec3 vWorldPos;
in vec3 vWorldNormal;
in vec3 vViewNormal;
in vec3 vLocalPos;
in float vViewZ;

out vec4 fragColor;

uniform sampler2D uRefractionTex;
uniform sampler3D uFluidVolume;
uniform sampler2D uFluidDetailMap;
uniform sampler2D uCausticsMap;
uniform sampler2D uGlassEnvMap;
uniform int uVolumeLoaded;
uniform int uFluidVolumeIsSrgb;
uniform int uGlassEnvIsSrgb;
uniform int uCausticsIsSrgb;
uniform int uFluidDetailIsSrgb;

uniform vec2 uViewportSize;
uniform mat4 uInvModel;
uniform mat3 uCoreRotMat;
uniform vec3 uCameraPos;
uniform float uFluidPhase;
uniform float uMeniscusPhase;
uniform float uCausticsPhase;
uniform float uFluidIntensity;
uniform float uDensityScale;
uniform float uCoreTransparency;
uniform float uRefraction;
uniform float uOverallIntensity;
uniform float uHdrScale;
uniform int uRaymarchSteps;
// 0=Full, 1=FluidDensity, 2=FluidFlow, 3=Meniscus, 4=CausticsOnly,
// 5=GlassOnly, 6=DepthDebug, 7=FluidVolumeOnly, 8=VolumeDepth, 9=CavityOnly
uniform int uDebugMode;
// P0-1: 32-sample master crystal-cavity meniscus profile auto-generated from REFERENCE_VISUAL_REGIONS.json
uniform float uReferenceMeniscusY[32];
// V3.2 Master-Fitted Candidate B Crystal Lens + 3D Fold Relief + Crystal Optics Uniforms
uniform vec3 uLensCenter;
uniform vec3 uLensRadii;
uniform vec4 uLensParams;  // x=wallThickness, y=cavityTintGain, z=cavityRimRefraction, w=upperGoldPenetration
uniform vec4 uFoldOptics;  // x=foldNormalPerturbation, y=foldSpecularGain, z=foldRefraction, w=volumeExposure
uniform vec4 uGlassOptics; // x=iorGlass, y=crownGoldStrength, z=upperHazeSuppress, w=causticIntensity
uniform vec3 uBeerLambert; // (R, G, B) fitted optical absorption coefficients
// P1 Tab 2 bright-background optics modulation (strictly 1.0 / 1.0 on Tab 1 ORB_ONLY -> zero Tab 1 regression!)
uniform float uTaskInteriorTransmission;
uniform float uTaskAbsorptionGain;

uniform vec3 uKeyLightDir;
uniform vec3 uKeyLightColor;
uniform vec3 uWarmRimDir;
uniform vec3 uWarmRimColor;
uniform vec3 uCoolRimDir;
uniform vec3 uCoolRimColor;
uniform vec3 uEmeraldFillColor;

$GLSL_COMMON_MATH

// P0-1: Overshoot-free clamped linear interpolation across the 32 master meniscus samples
float referenceMeniscusHeight(float x) {
    float t = clamp((x + 0.96875) / 1.9375, 0.0, 1.0) * 31.0;
    int i0 = clamp(int(floor(t)), 0, 30);
    int i1 = i0 + 1;
    float frac = clamp(t - float(i0), 0.0, 1.0);
    return mix(uReferenceMeniscusY[i0], uReferenceMeniscusY[i1], frac);
}

// P0-1: SOLE authoritative 3D Crystal Cavity & Liquid Interface SDF in rotated Core Frame.
// phi > 0 lies inside the clear upper crystal cavity; phi < 0 lies inside the submerged 3D liquid volume.
float cavityPhi(vec3 p, float phase) {
    // 3-tap smooth filter over the 32 master samples eliminates jagged V-notch while honoring master profile
    float baseProfileY = 0.25 * referenceMeniscusHeight(p.x - 0.08)
                       + 0.50 * referenceMeniscusHeight(p.x)
                       + 0.25 * referenceMeniscusHeight(p.x + 0.08);
    float r2 = dot(p.xz, p.xz);
    float domeDepthDip = 0.14 * p.z * p.z - 0.035 * p.z;
    float sideWallClimb = 0.048 * r2 * r2;
    float wave = 0.012 * sin(p.x * 2.4 + phase * 0.45 + p.z * 1.1)
               + 0.008 * cos(p.z * 2.8 - phase * 0.36 - p.x * 1.5);
    float surfaceY = baseProfileY + domeDepthDip + sideWallClimb + wave;
    return p.y - surfaceY;
}

// P0-1: Exact 3D central-difference Cavity SDF normal & tangent slope in Core Frame
vec3 cavityNormal(vec3 p, float phase, out vec2 slopeOut) {
    float e = 0.008;
    float gx = cavityPhi(p + vec3(e, 0.0, 0.0), phase) - cavityPhi(p - vec3(e, 0.0, 0.0), phase);
    float gy = cavityPhi(p + vec3(0.0, e, 0.0), phase) - cavityPhi(p - vec3(0.0, e, 0.0), phase);
    float gz = cavityPhi(p + vec3(0.0, 0.0, e), phase) - cavityPhi(p - vec3(0.0, 0.0, e), phase);
    slopeOut = vec2(-gx, -gz) / max(abs(gy), 1e-4);
    return normalize(vec3(gx, gy, gz));
}

// Compatibility wrappers strictly delegating to cavityPhi() / cavityNormal() (ZERO second formula!)
float meniscusHeight(vec2 coreXZ, float meniscusPhase) {
    return -cavityPhi(vec3(coreXZ.x, 0.0, coreXZ.y), meniscusPhase);
}

vec3 meniscusNormal(vec2 coreXZ, float meniscusPhase, out vec2 slopeOut) {
    float y = meniscusHeight(coreXZ, meniscusPhase);
    return cavityNormal(vec3(coreXZ.x, y, coreXZ.y), meniscusPhase, slopeOut);
}

// V3.2 Candidate B: Embedded Asymmetric Crystal Lens SDF in rotated Core Frame.
// phi < 0 lies inside the clear embedded crystal pocket; phi == 0 is the curved refractive lens wall.
float crystalLensPhi(vec3 p) {
    vec3 radii = max(uLensRadii, vec3(0.08));
    vec3 q = p - uLensCenter;
    // Slight organic top-dome asymmetry so the crystal pocket follows the master's curved crown
    q.y -= 0.032 * q.x * q.x + 0.018 * q.x;
    vec3 scaled = q / radii;
    float k0 = length(scaled);
    float k1 = length(scaled / radii);
    if (k0 < 1e-5) return -min(radii.x, min(radii.y, radii.z));
    return k0 * (k0 - 1.0) / max(k1, 1e-4);
}

vec3 crystalLensNormal(vec3 p) {
    float e = 0.008;
    float gx = crystalLensPhi(p + vec3(e, 0.0, 0.0)) - crystalLensPhi(p - vec3(e, 0.0, 0.0));
    float gy = crystalLensPhi(p + vec3(0.0, e, 0.0)) - crystalLensPhi(p - vec3(0.0, e, 0.0));
    float gz = crystalLensPhi(p + vec3(0.0, 0.0, e)) - crystalLensPhi(p - vec3(0.0, 0.0, e));
    return normalize(vec3(gx, gy, gz) + vec3(0.0, 1e-5, 0.0));
}

// Analytic ellipsoid ray intersection in Core Frame matching crystalLensPhi()
bool intersectCrystalLens(vec3 ro, vec3 rd, float tMax, out float tEnter, out float tExit, out float thickness) {
    vec3 radii = max(uLensRadii, vec3(0.08));
    vec3 oc = (ro - uLensCenter) / radii;
    vec3 d = rd / radii;
    float a = dot(d, d);
    float b = dot(oc, d);
    float c = dot(oc, oc) - 1.0;
    float disc = b * b - a * c;
    tEnter = 0.0;
    tExit = 0.0;
    thickness = 0.0;
    if (disc <= 0.0 || a < 1e-6) return false;
    float s = sqrt(disc);
    float t0 = (-b - s) / a;
    float t1 = (-b + s) / a;
    if (t1 <= 0.0 || t0 >= tMax) return false;
    tEnter = max(t0, 0.0);
    tExit = min(t1, tMax);
    thickness = max(tExit - tEnter, 0.0);
    return thickness > 1e-4;
}

// Active-surface analytical 3D fluid fold normal (evaluated ONLY on thin dense liquid sheets, ld > 0.14)
vec3 estimateFluidFoldNormal(vec3 samplePos, vec3 fallbackDir) {
    if (uVolumeLoaded <= 0) return fallbackDir;
    vec3 volUv = vec3(
        samplePos.x * 0.5 + 0.5,
        0.5 - samplePos.y * 0.5,
        samplePos.z * 0.5 + 0.5
    );
    float epsXY = 0.014;
    float epsZ = 0.032;
    float a0 = texture(uFluidVolume, clamp(volUv, vec3(0.004), vec3(0.996))).a;
    float ax = texture(uFluidVolume, clamp(volUv + vec3(epsXY, 0.0, 0.0), vec3(0.004), vec3(0.996))).a;
    float ay = texture(uFluidVolume, clamp(volUv - vec3(0.0, epsXY, 0.0), vec3(0.004), vec3(0.996))).a;
    float az = texture(uFluidVolume, clamp(volUv + vec3(0.0, 0.0, epsZ), vec3(0.004), vec3(0.996))).a;
    vec3 grad = vec3(a0 - ax, a0 - ay, a0 - az);
    float gLen = length(grad);
    return gLen > 1e-4 ? (grad / gLen) : fallbackDir;
}

// Bounded divergence-free 3D curl offset for continuous living fluid motion inside the rotating volume
vec3 computeVolumeCurl(vec3 p, float phase) {
    float rMask = invSmoothstep(0.65, 0.94, length(p));
    float f = 2.85;
    vec3 c = vec3(
        sin(p.y * f + phase * 0.58) * cos(p.z * f - phase * 0.42),
        sin(p.z * f - phase * 0.48) * cos(p.x * f + phase * 0.46),
        sin(p.x * f + phase * 0.52) * cos(p.y * f - phase * 0.36)
    );
    return c * (0.048 * rMask);
}

// Samples the V3 equirectangular WebP studio crystal environment map (high-contrast grazing reflections, ZERO dusty beige wash!)
vec3 sampleGlassEnv(vec3 R) {
    if (uVolumeLoaded > 0) {
        float phi = atan(R.x, R.z); // [-pi, +pi], 0 at +Z
        float theta = asin(clamp(R.y, -1.0, 1.0)); // [-pi/2, +pi/2]
        vec2 uv = vec2(phi / (2.0 * 3.14159265) + 0.5, 0.5 - theta / 3.14159265);
        vec3 envTex = texture(uGlassEnvMap, uv).rgb;
        if (uGlassEnvIsSrgb == 0) {
            envTex = pow(max(envTex, vec3(0.0)), vec3(2.2));
        }
        // Carve out dusty mid-tone beige haze so only razor-sharp studio crystal arcs reflect
        return pow(max(envTex, vec3(0.0)), vec3(1.75)) * 1.48;
    }

    // Procedural fallback matching glass_environment.webp if AssetManager unavailable
    vec3 env = vec3(0.0012, 0.0095, 0.0165);
    float grazingOnly = invSmoothstep(0.28, 0.78, R.z);
    float goldBand = exp(-pow((R.y - 0.22 * R.x - 0.14) * 18.0, 2.0)) * smoothstep(0.54, 0.94, R.x) * grazingOnly;
    env += vec3(1.42, 0.80, 0.16) * goldBand * 0.82;
    float cyanBand = exp(-pow((R.y + 0.38 + 0.18 * R.x) * 11.5, 2.0)) * invSmoothstep(-0.85, 0.12, R.x) * grazingOnly;
    env += vec3(0.14, 0.92, 1.28) * cyanBand * 0.82;
    return env;
}

// Samples 3D WebP FluidVolume(x,y,z) in rotated Core Frame with crisp glossy refractive silk folds from uFluidDetailMap
vec4 sampleFluidVolume3D(vec3 corePos, float fluidPhase, out vec3 flowDirOut) {
    // Gentle differential azimuthal vortex twist + bounded 3D curl preserving master spatial structure
    float rXZ = length(corePos.xz);
    float twist = 0.24 * (1.0 - clamp(rXZ, 0.0, 1.0)) * sin(corePos.y * 2.2 - fluidPhase * 0.48);
    float ct = cos(twist);
    float st = sin(twist);
    vec3 swirled = vec3(
        corePos.x * ct - corePos.z * st,
        corePos.y,
        corePos.x * st + corePos.z * ct
    );
    vec3 curl = computeVolumeCurl(swirled, fluidPhase);
    vec3 samplePos = swirled + curl;
    flowDirOut = normalize(curl + vec3(-corePos.z, 0.08 * sin(fluidPhase + corePos.x * 2.0), corePos.x) * 0.35 + vec3(1e-4));

    if (uVolumeLoaded > 0) {
        // Manifest convention: Row 0 of WebP slice = +Y (top), so volumeUv.y = 0.5 - samplePos.y * 0.5
        vec3 volUv = vec3(
            samplePos.x * 0.5 + 0.5,
            0.5 - samplePos.y * 0.5,
            samplePos.z * 0.5 + 0.5
        );
        vec4 vol = texture(uFluidVolume, clamp(volUv, vec3(0.004), vec3(0.996)));
        if (uFluidVolumeIsSrgb == 0) {
            vol.rgb = pow(max(vol.rgb, vec3(0.0)), vec3(2.2));
        }

        // Seamless 2D fine silk grain & refractive liquid-fold sharpening from maps/emerald_distribution.webp (LINEAR DATA)
        vec2 detailUvA = vec2(atan(samplePos.z, samplePos.x) / 6.2831853 + 0.5 + fluidPhase * 0.015, samplePos.y * 0.5 + 0.5);
        vec2 detailUvB = samplePos.xz * 0.64 + vec2(fluidPhase * 0.020, -fluidPhase * 0.016);
        vec4 detA = texture(uFluidDetailMap, detailUvA);
        vec4 detB = texture(uFluidDetailMap, detailUvB);
        if (uFluidDetailIsSrgb > 0) {
            detA.rgb = pow(max(detA.rgb, vec3(0.0)), vec3(1.0 / 2.2));
            detB.rgb = pow(max(detB.rgb, vec3(0.0)), vec3(1.0 / 2.2));
        }

        // Sharp liquid-silk fold ribs turn soft 3D voxel interpolation into crisp glossy translucent liquid sheets!
        float foldWave = sin(atan(samplePos.z, samplePos.x) * 5.0 + samplePos.y * 6.8 + detA.r * 3.8 + detB.g * 2.4 - fluidPhase * 0.55);
        float silkCrest = pow(clamp(0.5 + 0.5 * foldWave, 0.0, 1.0), 2.6);
        float silkValley = 0.34 + 0.66 * smoothstep(0.18, 0.88, mix(detA.g, detB.r, 0.48));

        // High-contrast deep obsidian-sapphire troughs vs vivid glowing emerald/aqua ridges
        vec3 deepContrastCol = pow(max(vol.rgb, vec3(0.0)), vec3(1.18)) * (0.62 + 0.72 * silkValley);
        float emeraldChroma = clamp((vol.g - vol.b * 0.82) * 3.0, 0.0, 1.0);
        float aquaChroma = clamp((vol.b + vol.g * 0.55 - vol.r * 1.4) * 1.8, 0.0, 1.0);
        vec3 boostedCol = deepContrastCol;
        boostedCol += vec3(0.032, 0.175, 0.044) * emeraldChroma * (0.35 + 0.95 * silkCrest) * detA.r * vol.a;
        boostedCol += vec3(0.016, 0.145, 0.215) * aquaChroma * silkCrest * detB.g * vol.a;

        // Carve crisp transparent voids between liquid curtains so interior stays crystal-clear, never milky smoke!
        float carvedDensity = clamp(vol.a * (0.45 + 0.72 * silkValley + 0.35 * silkCrest), 0.0, 1.0);
        return vec4(boostedCol, carvedDensity);
    }

    // Procedural fallback when WebP assets are absent (e.g. unit-test GL context without AssetManager)
    float r = length(samplePos);
    float sphereFade = invSmoothstep(0.84, 0.96, r);
    float nMain = fbm3(samplePos * 1.85);
    float nDet = fbm3(samplePos * 3.10 + vec3(2.1, -1.6, 1.4));
    float az = atan(samplePos.z, samplePos.x);
    float fold = 0.5 + 0.5 * sin(az * 2.0 + samplePos.y * 2.4 + nMain * 2.5);
    vec3 sapph = mix(vec3(0.002, 0.020, 0.058), vec3(0.010, 0.120, 0.230), fold);
    vec3 emer = mix(vec3(0.010, 0.190, 0.055), vec3(0.115, 0.500, 0.140), smoothstep(-0.2, 0.65, nDet));
    float emMask = smoothstep(-0.20, 0.68, -samplePos.x * 0.55 + fold * 0.65);
    vec3 col = mix(sapph, emer, emMask * 0.84);
    float dens = clamp((0.28 + 0.52 * fold) * sphereFade, 0.0, 1.0);
    return vec4(col, dens);
}

void main() {
    vec3 N = normalize(vWorldNormal);
    vec3 V = normalize(uCameraPos - vWorldPos);
    vec3 VN = normalize(vViewNormal);

    float micro = snoise(vLocalPos * 4.4 + vec3(uFluidPhase * 0.12, -uFluidPhase * 0.10, uFluidPhase * 0.08));
    vec3 pN = normalize(N + vec3(micro * 0.008, -micro * 0.007, micro * 0.008));
    float NdotV = clamp(dot(pN, V), 0.0, 1.0);

    // Debug Mode 6: View-space depth visualization
    if (uDebugMode == 6) {
        float depthVis = clamp((-vViewZ - 2.5) / 4.5, 0.0, 1.0);
        vec3 dCol = mix(vec3(0.15, 0.95, 1.0), vec3(0.03, 0.08, 0.25), depthVis);
        fragColor = vec4(dCol * uHdrScale, 1.0);
        return;
    }

    // -----------------------------------------------------------------------
    // 1. PIXEL-BOUNDED FBO REFRACTION (16px MAX AT EDGES, ~0px AT CENTER)
    //    + TIGHT GRAZING-ANGLE CAUCHY DISPERSION (ZERO RAINBOW SMEAR!)
    // -----------------------------------------------------------------------
    vec2 screenUv = gl_FragCoord.xy / max(uViewportSize, vec2(1.0));
    vec2 pxToUv = 1.0 / max(uViewportSize, vec2(1.0));
    float maxRefractPx = 16.0;
    float rimCurve = pow(1.0 - NdotV, 2.2);
    vec2 refractUvShift = VN.xy * (maxRefractPx * rimCurve * uRefraction) * pxToUv;
    float dispSplit = 0.032 * pow(1.0 - NdotV, 2.4) * (0.45 + 0.55 * uRefraction);

    vec2 uvR = clamp(screenUv - refractUvShift * (1.0 + dispSplit), vec2(0.002), vec2(0.998));
    vec2 uvG = clamp(screenUv - refractUvShift, vec2(0.002), vec2(0.998));
    vec2 uvB = clamp(screenUv - refractUvShift * (1.0 - dispSplit), vec2(0.002), vec2(0.998));

    float invHdr = 1.0 / max(uHdrScale, 1e-4);
    vec3 originalBg = texture(uRefractionTex, clamp(screenUv, vec2(0.002), vec2(0.998))).rgb * invHdr;
    vec3 refractedBack;
    refractedBack.r = texture(uRefractionTex, uvR).r * invHdr;
    refractedBack.g = texture(uRefractionTex, uvG).g * invHdr;
    refractedBack.b = texture(uRefractionTex, uvB).b * invHdr;

    // -----------------------------------------------------------------------
    // 2. V3 WEBP 3D VOLUME RAYMARCHING (FRONT-TO-BACK BEER-LAMBERT)
    //    + INDEPENDENT 3D CORE ROTATION (transpose(uCoreRotMat) * localPos)
    //    + BISECTED SCULPTED CONCAVE MENISCUS
    // -----------------------------------------------------------------------
    vec3 safeN = dot(pN, V) > 0.02 ? pN : normalize(pN + V * 0.08);
    vec3 refrWorld = refract(-V, safeN, 1.0 / 1.42);
    if (dot(refrWorld, refrWorld) < 1e-5) refrWorld = -V;
    vec3 rayDir = normalize(mat3(uInvModel) * refrWorld);
    vec3 rayOrg = vLocalPos * 0.945;

    float volRadius = 0.945;
    float bDot = dot(rayOrg, rayDir);
    float cRad = dot(rayOrg, rayOrg) - volRadius * volRadius;
    float disc = max(bDot * bDot - cRad, 0.0);
    float tExit = max(-bDot + sqrt(disc), 0.04);

    // Quality-adaptive V3 steps: LOW=16, MEDIUM=32, HIGH=48
    int steps = clamp(uRaymarchSteps, 12, 48);
    float stepLen = tExit / float(steps);

    // Subtle stochastic hash jitter eliminates any Z-slice wood-grain banding at grazing angles
    float jitter = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) * 0.55;

    float liquidPath = 0.0;
    float hitMeniscus = 0.0;
    float meniscusCrossingDir = 1.0;
    float meniscusHitT = 0.0;
    vec3 meniscusHitPos = vec3(0.0);
    vec3 meniscusHitCorePos = vec3(0.0);

    vec3 accumColor = vec3(0.0);
    float accumAlpha = 0.0;
    float opticalDepth = 0.0;
    vec3 debugDensityAcc = vec3(0.0);
    vec3 debugFlowSample = vec3(0.0);
    float debugFlowWeight = 0.0;

    // Unified inverse Core Frame matrix so rotating core rotates both meniscus & 3D WebP volume together!
    mat3 invCoreRot = transpose(uCoreRotMat);
    vec3 keyDirCore = normalize(invCoreRot * normalize(uKeyLightDir));
    vec3 rayOrgCore = toCoreFrame(rayOrg, invCoreRot);
    vec3 rayDirCore = normalize(invCoreRot * rayDir);

    // V3.2 Candidate B: Embedded Crystal Lens intersection in rotated Core Frame
    float lensEnterT = 0.0;
    float lensExitT = 0.0;
    float lensThickness = 0.0;
    bool hitCrystalLens = intersectCrystalLens(rayOrgCore, rayDirCore, tExit, lensEnterT, lensExitT, lensThickness);
    float lensPocketMask = hitCrystalLens ? smoothstep(0.02, max(uLensParams.x * 2.4, 0.09), lensThickness) : 0.0;

    float prevT = 0.0;
    vec3 prevCore = rayOrgCore;
    float prevDiff = cavityPhi(prevCore, uMeniscusPhase);
    float densityScale = clamp(uDensityScale, 1.20, 1.95) * (0.80 + 0.20 * uFluidIntensity);

    for (int i = 1; i <= 48; i++) {
        if (i > steps || accumAlpha > 0.96) break;

        float currT = (float(i) - jitter * 0.35) * stepLen;
        currT = clamp(currT, prevT + 1e-4, tExit);
        vec3 sp = rayOrg + rayDir * currT;
        vec3 coreSp = toCoreFrame(sp, invCoreRot);
        float currDiff = cavityPhi(coreSp, uMeniscusPhase);

        // Exact bracketed 3D Core-Frame cavityPhi intersection + 5-step binary bisection (P0-1)
        bool crossedDown = (prevDiff > 0.0 && currDiff <= 0.0);
        bool crossedUp   = (prevDiff < 0.0 && currDiff >= 0.0);
        if (hitMeniscus < 0.5 && (crossedDown || crossedUp)) {
            float tLo = prevT;
            float dLo = prevDiff;
            float tHi = currT;
            float dHi = currDiff;

            for (int b = 0; b < 5; b++) {
                float tMid = 0.5 * (tLo + tHi);
                vec3 pMid = rayOrg + rayDir * tMid;
                vec3 cMid = toCoreFrame(pMid, invCoreRot);
                float dMid = cavityPhi(cMid, uMeniscusPhase);
                if ((dLo > 0.0 && dMid > 0.0) || (dLo < 0.0 && dMid < 0.0)) {
                    tLo = tMid;
                    dLo = dMid;
                } else {
                    tHi = tMid;
                    dHi = dMid;
                }
            }

            float denom = dLo - dHi;
            float tHit = abs(denom) > 1e-6
                ? tLo + clamp(dLo / denom, 0.0, 1.0) * (tHi - tLo)
                : 0.5 * (tLo + tHi);

            meniscusHitPos = rayOrg + rayDir * tHit;
            meniscusHitCorePos = toCoreFrame(meniscusHitPos, invCoreRot);
            meniscusHitT = tHit;
            hitMeniscus = 1.0;
            meniscusCrossingDir = crossedDown ? 1.0 : -1.0;
        }

        // Front-to-Back Beer-Lambert Volume Integration strictly below cavityPhi == 0 (P0-1)
        float midT = 0.5 * (prevT + currT);
        vec3 midP = rayOrg + rayDir * midT;
        vec3 coreMidP = toCoreFrame(midP, invCoreRot);
        float midPhi = cavityPhi(coreMidP, uMeniscusPhase);
        float submerge = invSmoothstep(-0.015, 0.015, midPhi);

        if (submerge > 0.01) {
            // Smoothly carve crystal clarity inside Candidate B embedded lens pocket (zero hard horizontal line!)
            float lensPhi = crystalLensPhi(coreMidP);
            float outsideLens = smoothstep(-max(uLensParams.x * 0.65, 0.02), max(uLensParams.x * 1.15, 0.045), lensPhi);
            float segDt = (currT - prevT) * submerge * mix(0.22, 1.0, outsideLens);
            liquidPath += segDt;

            vec3 flowDir;
            vec4 volSample = sampleFluidVolume3D(coreMidP, uFluidPhase, flowDir);
            float localDensity = volSample.a * submerge * mix(0.18, 1.0, outsideLens);

            if (localDensity > 0.004) {
                float sigma = localDensity * densityScale * segDt;
                float stepAlpha = 1.0 - exp(-sigma);

                // High-contrast Beer-Lambert directional subsurface lighting + active-surface 3D fold normals!
                vec3 radialCore = normalize(coreMidP + vec3(1e-4));
                float keyPhase = clamp(dot(radialCore, keyDirCore) * 0.5 + 0.5, 0.0, 1.0);
                float depthAtten = exp(-opticalDepth * 0.78);
                float rimWallBoost = smoothstep(0.45, 0.92, length(midP.xz));

                // Evaluate analytical 3D fluid fold normal ONLY on active liquid sheets (ld > 0.14)
                float foldRelief = 0.0;
                float foldSpec = 0.0;
                if (localDensity > 0.14 && uFoldOptics.x > 0.01) {
                    vec3 foldN = normalize(mix(radialCore, estimateFluidFoldNormal(coreMidP, radialCore), clamp(uFoldOptics.x, 0.0, 0.75)));
                    float foldDiff = clamp(dot(foldN, keyDirCore), 0.0, 1.0);
                    vec3 foldHalf = normalize(keyDirCore - rayDirCore);
                    foldSpec = pow(max(dot(foldN, foldHalf), 0.0), 28.0) * uFoldOptics.y * depthAtten;
                    foldRelief = (foldDiff - 0.42) * uFoldOptics.x * 0.68 * depthAtten;
                }

                // Deep dark obsidian-sapphire core valleys + sunlit sculptural emerald/aqua liquid folds
                vec3 litSample = volSample.rgb * max(0.32, (0.48 + 0.88 * keyPhase * depthAtten + 0.26 * rimWallBoost + foldRelief)) * uFluidIntensity * uFoldOptics.w;
                litSample += mix(vec3(0.08, 0.48, 0.34), vec3(0.24, 0.76, 0.92), clamp(volSample.b * 1.4, 0.0, 1.0)) * foldSpec * localDensity;

                accumColor += (1.0 - accumAlpha) * litSample * stepAlpha;
                accumAlpha += (1.0 - accumAlpha) * stepAlpha;
                opticalDepth += sigma;

                debugDensityAcc += (1.0 - accumAlpha * 0.5) * vec3(volSample.g * 1.4, volSample.b * 1.1, localDensity) * segDt;
                if (debugFlowWeight < 0.01) {
                    debugFlowSample = flowDir * 0.5 + 0.5;
                    debugFlowWeight = 1.0;
                }
            }
        }

        prevT = currT;
        prevDiff = currDiff;
    }

    // -----------------------------------------------------------------------
    // 2B. HYBRID 3D FLOW DOMAIN-WARPED + WEBP UNDERWATER CAUSTICS
    //     Concentrated in lower-center/right belly (5-7 o'clock), ZERO honeycomb!
    // -----------------------------------------------------------------------
    vec3 floorPos = rayOrg + rayDir * (tExit * 0.74);
    vec3 floorCorePos = toCoreFrame(floorPos, invCoreRot);
    float floorPhi = cavityPhi(floorCorePos, uMeniscusPhase);
    float floorSubmerged = invSmoothstep(-0.08, 0.06, floorPhi);
    vec2 causticSlope = vec2(0.0);
    if (hitMeniscus > 0.5) {
        cavityNormal(meniscusHitCorePos, uMeniscusPhase, causticSlope);
    } else {
        cavityNormal(floorCorePos, uMeniscusPhase, causticSlope);
    }

    vec3 advFloor = advectFluidPos(floorPos, invCoreRot, uFluidPhase);
    float warpCausticNet = domainWarpCaustics(advFloor, floorCorePos, causticSlope, uCausticsPhase);

    // Layer crisp branching WebP caustic map (optics/caustics.webp, LINEAR DATA) in Core Frame
    float webpCaustics = 0.0;
    if (uVolumeLoaded > 0) {
        vec2 cUv1 = floorCorePos.xz * 0.64 + causticSlope * 0.12 + vec2(uCausticsPhase * 0.032, -uCausticsPhase * 0.024);
        vec2 cUv2 = vec2(floorCorePos.x * 0.50 - floorCorePos.z * 0.56, floorCorePos.x * 0.56 + floorCorePos.z * 0.50)
                  + vec2(-uCausticsPhase * 0.026, uCausticsPhase * 0.035);
        vec4 cTex1 = texture(uCausticsMap, cUv1);
        vec4 cTex2 = texture(uCausticsMap, cUv2);
        if (uCausticsIsSrgb > 0) {
            cTex1.rgb = pow(max(cTex1.rgb, vec3(0.0)), vec3(1.0 / 2.2));
            cTex2.rgb = pow(max(cTex2.rgb, vec3(0.0)), vec3(1.0 / 2.2));
        }
        float veins = (cTex1.r * 0.52 + cTex2.g * 0.48 + (cTex1.b + cTex2.b) * 0.24);
        float nodes = pow(clamp(cTex1.a * cTex2.a * 3.4, 0.0, 1.0), 1.35);
        float lowerMask = invSmoothstep(-0.88, -0.02, floorCorePos.y)
                        * smoothstep(-0.36, 0.64, floorCorePos.x * 0.48 - floorCorePos.y * 0.80);
        webpCaustics = (veins * 0.90 + nodes * 1.25) * lowerMask;
    }

    float combinedCaustics = mix(warpCausticNet, warpCausticNet * 0.60 + webpCaustics * 1.15, float(uVolumeLoaded));
    float causticStrength = combinedCaustics * floorSubmerged * smoothstep(0.04, 0.36, liquidPath);
    vec3 causticColor = mix(vec3(0.030, 0.780, 1.120), vec3(0.550, 1.520, 1.660), clamp(combinedCaustics * 0.68, 0.0, 1.0))
                      * causticStrength * 1.28 * uFluidIntensity;

    // Diagnostic Mode 1: 3D WebP Volume Fluid Density & Emerald/Sapphire Structure
    if (uDebugMode == 1) {
        fragColor = vec4(debugDensityAcc * 1.85 * uHdrScale, 1.0);
        return;
    }
    // Diagnostic Mode 2: 3D Core-Frame Curl Flow Direction Field
    if (uDebugMode == 2) {
        vec3 fCol = debugFlowWeight > 0.5 ? debugFlowSample : vec3(0.02, 0.05, 0.08);
        fragColor = vec4(fCol * uHdrScale, 1.0);
        return;
    }
    // Diagnostic Mode 3: Exact Bisected 3D Core-Frame Cavity SDF Normal
    if (uDebugMode == 3) {
        vec3 mVis = vec3(0.015, 0.04, 0.07);
        if (hitMeniscus > 0.5) {
            vec2 sl;
            vec3 mnCore = cavityNormal(meniscusHitCorePos, uMeniscusPhase, sl) * meniscusCrossingDir;
            vec3 mnLocal = normalize(uCoreRotMat * mnCore);
            mVis = mnLocal * 0.5 + 0.5;
        }
        fragColor = vec4(mVis * uHdrScale, 1.0);
        return;
    }
    // Diagnostic Mode 4: Isolated Underwater Caustics
    if (uDebugMode == 4) {
        fragColor = vec4((vec3(0.002, 0.014, 0.030) + causticColor * 1.55) * uHdrScale, 1.0);
        return;
    }
    // Diagnostic Mode 8: Beer-Lambert Accumulated Optical Depth Heatmap
    if (uDebugMode == 8) {
        float odNorm = clamp(opticalDepth / 2.6, 0.0, 1.0);
        vec3 depthHeat = mix(vec3(0.01, 0.05, 0.12), mix(vec3(0.04, 0.62, 0.54), vec3(0.95, 0.88, 0.35), smoothstep(0.45, 1.0, odNorm)), odNorm);
        fragColor = vec4(depthHeat * uHdrScale, 1.0);
        return;
    }

    // Diagnostic Mode 9: V3.2 Candidate B Crystal Lens + Cavity SDF Isolation (`CAVITY_ONLY`)
    if (uDebugMode == 9) {
        float edgeMask = smoothstep(0.0, 0.042, NdotV);
        float cavClear = invSmoothstep(0.02, 0.18, liquidPath);
        vec3 cavVis = mix(vec3(0.008, 0.045, 0.095), vec3(0.06, 0.36, 0.52), cavClear);
        if (hitCrystalLens) {
            vec3 lensHitP = rayOrgCore + rayDirCore * lensEnterT;
            vec3 lensN = crystalLensNormal(lensHitP);
            float lensGrazing = pow(1.0 - clamp(abs(dot(lensN, rayDirCore)), 0.0, 1.0), 2.4);
            cavVis = mix(cavVis, vec3(0.14, 0.64, 0.88), lensPocketMask * 0.55);
            cavVis += vec3(0.42, 0.96, 1.24) * lensGrazing * uLensParams.z * 0.65;
        }
        if (hitMeniscus > 0.5 && meniscusCrossingDir > 0.0) {
            float frontLip = invSmoothstep(0.10, 0.72, meniscusHitT);
            cavVis += vec3(0.95, 0.72, 0.24) * frontLip * 0.52;
        }
        fragColor = vec4(mix(originalBg, cavVis, edgeMask) * uHdrScale, 1.0);
        return;
    }

    // Beer-Lambert optical transmission of refracted background through submerged liquid volume
    float taskTrans = (uTaskInteriorTransmission > 0.01) ? uTaskInteriorTransmission : 1.0;
    float taskAbsorb = (uTaskAbsorptionGain > 0.01) ? uTaskAbsorptionGain : 1.0;
    vec3 waterTransmittance = exp(-liquidPath * uBeerLambert * (1.24 - uCoreTransparency * 0.42) * taskAbsorb);
    float bgWeight = (1.0 - accumAlpha * 0.86);
    vec3 interiorCol = refractedBack * waterTransmittance * bgWeight * taskTrans
                     + accumColor
                     + causticColor * (0.72 + 0.28 * (1.0 - accumAlpha * 0.35)) * clamp(uGlassOptics.w / 0.56, 0.45, 1.45);

    // P0-1: Subtle transparent crystal-water meniscus lip on front-wall top-entry crossings ONLY
    // (gates out deep back-wall water table hits so upper crystal dome stays pure dark transparent glass!)
    if (hitMeniscus > 0.5 && meniscusCrossingDir > 0.0) {
        float frontContact = invSmoothstep(0.10, 0.72, meniscusHitT);
        vec2 mSlope;
        vec3 mNCore = cavityNormal(meniscusHitCorePos, uMeniscusPhase, mSlope) * meniscusCrossingDir;
        vec3 mN = normalize(uCoreRotMat * mNCore);
        vec3 localV = normalize(-rayDir);
        float mNdotV = clamp(dot(mN, localV), 0.0, 1.0);
        float mFres = pow(1.0 - mNdotV, 5.4) * frontContact;
        float mLipRim = exp(-pow((mNdotV - 0.16) / 0.10, 2.0)) * frontContact;
        vec3 mHalf = normalize(localV + normalize(vec3(-0.52, 0.78, 0.36)));
        float mSpec = pow(max(dot(mN, mHalf), 0.0), 145.0) * frontContact;
        interiorCol *= (1.0 - 0.22 * mFres);
        interiorCol += vec3(0.015, 0.12, 0.22) * mLipRim * 0.14;
        interiorCol += vec3(0.42, 1.15, 1.38) * (mFres * 0.08 + mSpec * 0.40) * uFluidIntensity;
    }

    // P0-4 & V3.2 Candidate B: Crystal-clear transparent upper dome & embedded crystal lens pocket (ZERO frosted haze!)
    float airCavity = invSmoothstep(0.02, 0.16, liquidPath);
    float domeCyanSheen = smoothstep(0.12, 0.88, vLocalPos.y) * smoothstep(0.22, 0.82, NdotV);
    float cavityBgScale = mix(1.0, taskTrans, 0.72);
    vec3 clearCavityCol = refractedBack * vec3(0.92, 0.99, 1.02) * cavityBgScale
                        + vec3(0.001, 0.012, 0.021) * domeCyanSheen;
    interiorCol = mix(interiorCol, clearCavityCol, airCavity * 0.92);

    // Smooth Candidate B embedded crystal lens refraction rim + localized 10:30 sunlit golden penetration
    if (hitCrystalLens) {
        vec3 lensEnterPos = rayOrgCore + rayDirCore * lensEnterT;
        vec3 lensNCore = crystalLensNormal(lensEnterPos);
        float lensNdotV = clamp(abs(dot(lensNCore, rayDirCore)), 0.0, 1.0);
        float lensWallRing = exp(-pow((lensNdotV - 0.24) / 0.14, 2.0)) * smoothstep(0.015, 0.14, lensThickness);
        float lensClearBg = lensPocketMask * (0.42 + 0.32 * airCavity);
        vec3 lensCrystalTrans = refractedBack * vec3(0.88, 0.98, 1.06) * cavityBgScale
                              + vec3(0.004, 0.036, 0.068) * uLensParams.y * lensPocketMask;
        interiorCol = mix(interiorCol, lensCrystalTrans, clamp(lensClearBg * 0.58, 0.0, 0.68));
        // Curved aqua-crystal wall refraction + warm golden penetration on the 10:30 upper-left lens wall
        float goldWallSector = smoothstep(-0.35, 0.45, -lensEnterPos.x + lensEnterPos.y * 0.65);
        vec3 lensRimCol = mix(vec3(0.08, 0.52, 0.78), vec3(0.92, 0.68, 0.22), goldWallSector * clamp(uLensParams.w * 0.45, 0.0, 0.65));
        interiorCol += lensRimCol * lensWallRing * uLensParams.z * 0.22;
    }

    // Diagnostic Mode 7: Pure 3D WebP Fluid Volume Only (in-shader composited over background!)
    if (uDebugMode == 7) {
        float volEdge = smoothstep(0.0, 0.042, NdotV);
        vec3 volComp = mix(originalBg, interiorCol * (0.90 + 0.15 * uOverallIntensity), volEdge);
        fragColor = vec4(volComp * uHdrScale, 1.0);
        return;
    }

    // -----------------------------------------------------------------------
    // 3. P0-4 THICK DOUBLE-WALL CRYSTAL GLASS SHELL (ZERO UPPER-DOME HAZE)
    //    - Pure deep oceanic crystal transmission across upper hemisphere
    //    - Localized curved 10:30 golden refraction flare + branching sun caustics
    //    - Refined double-wall concentric rim weights + capped env reflection (<= 0.22)
    //    - Exact in-shader background coverage compositing outputting alpha = 1.0
    // -----------------------------------------------------------------------
    float fresnelOuter  = 0.012 + 0.988 * pow(1.0 - NdotV, 6.2);
    float razorOuterRim = pow(1.0 - NdotV, 11.5);
    float innerWallRing = exp(-pow((NdotV - 0.275) / 0.028, 2.0));
    float innerRimSharp = exp(-pow((NdotV - 0.315) / 0.016, 2.0));
    float glassGroove   = exp(-pow((NdotV - 0.145) / 0.044, 2.0));

    vec3 R = reflect(-V, pN);
    vec3 envRefl = sampleGlassEnv(R);

    // Grazing-only directional rim weights (never bleed dusty haze onto front center!)
    float grazingGate = pow(1.0 - NdotV, 3.4);
    float warmRimWeight = smoothstep(-0.16, 0.58, VN.x * 0.72 + VN.y * 0.68);
    float coolRimWeight = smoothstep(-0.12, 0.70, -VN.y * 0.76 - VN.x * 0.44);
    vec3 fieryGoldRim = vec3(1.75, 0.96, 0.22);
    vec3 champagneRim = vec3(1.92, 1.48, 0.76);
    vec3 iceCyanRim   = vec3(0.24, 1.12, 1.48);

    vec3 rimPalette = mix(iceCyanRim, mix(fieryGoldRim, champagneRim, razorOuterRim * 0.68), warmRimWeight);

    // Master PNG signature localized 10:30 curved golden crown refraction flare + branching golden caustic rays!
    vec2 vnDir = normalize(VN.xy + vec2(1e-5));
    float upperLeftSector = smoothstep(0.74, 0.985, dot(vnDir, normalize(vec2(-0.62, 0.785))));
    float crownRimArc = upperLeftSector * pow(1.0 - NdotV, 3.6) * (0.16 + 0.88 * innerWallRing + 1.18 * razorOuterRim);

    vec3 sunCore = toCoreFrame(vLocalPos, invCoreRot);
    vec3 sunAdv = advectFluidPos(vLocalPos, invCoreRot, uFluidPhase * 0.52);
    float sunCaustics = domainWarpCaustics(sunAdv * 1.22, vec3(sunCore.x, -0.45, sunCore.z), vec2(0.0), uCausticsPhase * 0.62)
                      * upperLeftSector * smoothstep(0.24, 0.84, 1.0 - NdotV) * 0.62;
    float upperRightArc = smoothstep(0.76, 0.98, dot(vnDir, normalize(vec2(0.45, 0.89))))
                        * innerRimSharp * 0.44;
    vec3 keyHighlight = vec3(1.88, 1.38, 0.58) * (crownRimArc * 1.02 + sunCaustics)
                      + vec3(1.10, 1.48, 1.68) * upperRightArc;

    // Grazing-edge directional rim glints ONLY
    vec3 Lwarm = normalize(uWarmRimDir);
    float grazingWarmRim = pow(max(dot(pN, normalize(V + Lwarm)), 0.0), 150.0) * max(dot(pN, Lwarm), 0.0) * grazingGate * 1.15;
    vec3 Lcool = normalize(uCoolRimDir);
    float grazingCoolRim = pow(max(dot(pN, normalize(V + Lcool)), 0.0), 125.0) * max(dot(pN, Lcool), 0.0) * grazingGate * 0.80;

    // Diagnostic Mode 5: Outer Crystal Glass Shell Only (Refraction + Env + Double-Wall Fresnel)
    vec3 baseInterior = (uDebugMode == 5) ? refractedBack : interiorCol;

    // Deep dark refractive wall groove creates unmistakable thick blown-glass double-wall look!
    vec3 col = baseInterior * (1.0 - glassGroove * 0.56);
    float envBlend = clamp(fresnelOuter * (0.58 - uCoreTransparency * 0.38), 0.003, 0.22);
    col = mix(col, envRefl, envBlend * 0.30);

    // P0-4 Refined double-wall crystal rim (clear oceanic glass, zero frosted upper-dome milkiness!)
    col += rimPalette * (fresnelOuter * 0.50 + razorOuterRim * 0.90);
    col += mix(iceCyanRim, champagneRim, warmRimWeight * 0.82) * (innerWallRing * 0.35 + innerRimSharp * 0.28);
    col += iceCyanRim * coolRimWeight * (innerWallRing * 0.28 + innerRimSharp * 0.22);
    col += keyHighlight + fieryGoldRim * grazingWarmRim + iceCyanRim * grazingCoolRim;

    col *= (0.90 + 0.16 * uOverallIntensity);

    // P0-3 Exact single-source in-shader background compositing (GL_BLEND disabled on this pass!)
    float edgeAlpha = smoothstep(0.0, 0.042, NdotV);
    vec3 finalComposited = mix(originalBg, col, edgeAlpha);
    fragColor = vec4(finalComposited * uHdrScale, 1.0);
}
"""
}
