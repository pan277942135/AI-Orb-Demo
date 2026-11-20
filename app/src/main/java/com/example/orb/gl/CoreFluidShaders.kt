package com.example.orb.gl

/**
 * V2 Full Visual Restoration GLSL ES 3.00 Shaders:
 * - True Linear HDR pipeline paired with sRGB OETF in COMPOSITE_FRAG (eliminates crushed black backdrop)
 * - Zero inverted smoothstep calls (strictly uses `invSmoothstep(lo, hi, x)` with lo < hi)
 * - Unified tilted-axis 3D core rotation (`uCoreRotMat`) driving high-contrast swirling sapphire/emerald currents
 * - Sculpted asymmetric 3D concave meniscus solved via bracketed 3-step binary bisection + signed secant finish
 * - Razor-sharp dual-octave Voronoi cellular edge underwater caustics (`d2 - d1`) projected onto the
 *   rotating curved optical floor & concave inner bowl (fast 9-tap GPU evaluation, zero volume smearing)
 * - Crystal-clear upper glass dome, double-wall blown glass rim, localized upper-left golden key glint
 * - Production-pipeline `uDebugMode` visualization (0=Normal, 1=FluidDensity, 2=FluidFlow, 3=Meniscus,
 *   4=CausticsOnly, 5=GlassOnly, 6=DepthDebug)
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

// True 3D rotational advection + differential azimuthal vortex swirl + divergence-free curl perturbation.
// Carries clearly recognizable high-contrast liquid ribbons around the tilted 3D core axis continuously.
vec3 advectFluidPos(vec3 sp, mat3 coreRot, float fluidPhase) {
    vec3 rotated = coreRot * sp;
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

// Sculpted, asymmetric, gracefully dipped 3D concave liquid meniscus inside the unit sphere
float meniscusHeight(vec2 xz, float meniscusPhase) {
    float c = cos(meniscusPhase * 0.38);
    float s = sin(meniscusPhase * 0.38);
    vec2 r = vec2(xz.x * c - xz.y * s, xz.x * s + xz.y * c);

    float r2 = dot(xz, xz);
    float bowl = 0.24 * r2 - 0.11 * xz.x + 0.055 * xz.y;
    float swell = 0.072 * sin(r.x * 2.8 + meniscusPhase * 0.62 + r.y * 1.4);
    float crossWave = 0.044 * cos(r.y * 3.6 - meniscusPhase * 0.54 - r.x * 1.9);
    float capillary = 0.020 * sin((r.x * 1.3 - r.y * 1.1) * 4.8 + meniscusPhase * 0.92);
    return 0.02 + bowl + swell + crossWave + capillary;
}

vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

// Fast 9-tap Voronoi cellular-edge caustic filament extractor (F2 - F1 razor-sharp optical web)
float voronoiEdge2D(vec2 uv, float phase) {
    vec2 i = floor(uv);
    vec2 f = fract(uv);
    float d1 = 8.0;
    float d2 = 8.0;

    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 neighbor = vec2(float(x), float(y));
            vec2 h = hash22(i + neighbor);
            vec2 point = 0.5 + 0.38 * sin(phase * 0.62 + 6.2831853 * h);
            vec2 diff = neighbor + point - f;
            float dist = length(diff);
            if (dist < d1) {
                d2 = d1;
                d1 = dist;
            } else if (dist < d2) {
                d2 = dist;
            }
        }
    }
    float edge = max(d2 - d1, 0.0);
    return exp(-edge * 13.5);
}

// Dual-octave lacy underwater Voronoi caustic network coupled to core rotation & meniscus slope
float opticalCaustics(vec3 advectedPos, vec2 meniscusSlope, float causticsPhase) {
    // Project 3D rotating interior point onto curved optical caustic sheet with meniscus refraction warp
    vec2 projA = advectedPos.xz * 2.85 + vec2(advectedPos.y * 0.95, -advectedPos.y * 0.65)
               + meniscusSlope * 0.42;
    vec2 projB = vec2(
        projA.x * 0.78 - projA.y * 0.62,
        projA.x * 0.62 + projA.y * 0.78
    ) * 1.58 + vec2(3.7, -2.1);

    float web1 = voronoiEdge2D(projA, causticsPhase * 0.85);
    float web2 = voronoiEdge2D(projB, -causticsPhase * 1.05 + 1.9);
    // Bright caustic node intersections where two optical sheets cross
    float nodes = pow(clamp(web1 * web2 * 2.2, 0.0, 1.0), 1.35);
    return clamp(web1 * 0.88 + web2 * 0.62 + nodes * 1.15, 0.0, 2.25);
}
"""

  // -------------------------------------------------------------------------
  // 1. DEEP EMERALD-TEAL UNDERWATER SPACE + MULTI-DEPTH OLIVE-GOLD & TEAL BOKEH
  //    (Calibrated in True Linear Space for ACES + sRGB 2.2 OETF)
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

    // Rich deep emerald-teal underwater gradient (never crushed to pure black after sRGB OETF!)
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

    // Multi-depth soft underwater bokeh (warm olive-gold left/lower-left + cool teal upper/right)
    vec2 bPos[12];
    float bRad[12];
    vec3 bTint[12];

    bPos[0] = vec2(-0.42, -0.07) + vec2(sin(uTime * 0.09) * 0.012, cos(uTime * 0.08) * 0.010);
    bRad[0] = 0.060; bTint[0] = vec3(0.160, 0.130, 0.024) * 0.82;

    bPos[1] = vec2(-0.34, -0.34) + vec2(cos(uTime * 0.08) * 0.011, sin(uTime * 0.10) * 0.012);
    bRad[1] = 0.078; bTint[1] = vec3(0.115, 0.118, 0.022) * 0.64;

    bPos[2] = vec2(0.44, 0.08) + vec2(sin(uTime * 0.08 + 2.4) * 0.010, cos(uTime * 0.09) * 0.010);
    bRad[2] = 0.054; bTint[2] = vec3(0.145, 0.118, 0.024) * 0.68;

    bPos[3] = vec2(-0.47, 0.01) + vec2(sin(uTime * 0.11 + 1.5) * 0.008, cos(uTime * 0.09) * 0.008);
    bRad[3] = 0.030; bTint[3] = vec3(0.190, 0.185, 0.105) * 0.56;

    bPos[4] = vec2(-0.18, 0.45) + vec2(sin(uTime * 0.09 + 1.1) * 0.012, cos(uTime * 0.07) * 0.010);
    bRad[4] = 0.068; bTint[4] = vec3(0.024, 0.098, 0.088) * 0.68;

    bPos[5] = vec2(0.14, 0.46) + vec2(cos(uTime * 0.10 + 2.1) * 0.012, sin(uTime * 0.08) * 0.010);
    bRad[5] = 0.058; bTint[5] = vec3(0.052, 0.105, 0.064) * 0.58;

    bPos[6] = vec2(-0.38, 0.34) + vec2(sin(uTime * 0.10 + 3.8) * 0.011, cos(uTime * 0.08) * 0.010);
    bRad[6] = 0.082; bTint[6] = vec3(0.018, 0.086, 0.092) * 0.62;

    bPos[7] = vec2(0.35, -0.38) + vec2(cos(uTime * 0.08 + 0.7) * 0.011, sin(uTime * 0.09) * 0.011);
    bRad[7] = 0.076; bTint[7] = vec3(0.018, 0.082, 0.088) * 0.64;

    bPos[8] = vec2(0.05, -0.46) + vec2(cos(uTime * 0.07 + 1.9) * 0.011, sin(uTime * 0.10) * 0.010);
    bRad[8] = 0.058; bTint[8] = vec3(0.036, 0.092, 0.062) * 0.52;

    bPos[9] = vec2(0.40, -0.11) + vec2(sin(uTime * 0.10 + 4.5) * 0.009, cos(uTime * 0.09) * 0.009);
    bRad[9] = 0.040; bTint[9] = vec3(0.145, 0.118, 0.028) * 0.58;

    bPos[10] = vec2(-0.24, -0.44) + vec2(sin(uTime * 0.07 + 3.1) * 0.010, cos(uTime * 0.09) * 0.010);
    bRad[10] = 0.064; bTint[10] = vec3(0.092, 0.110, 0.026) * 0.50;

    bPos[11] = vec2(0.32, 0.36) + vec2(cos(uTime * 0.09 + 1.4) * 0.010, sin(uTime * 0.08) * 0.010);
    bRad[11] = 0.072; bTint[11] = vec3(0.020, 0.080, 0.076) * 0.55;

    vec2 bp = p + uParallax * 0.06;
    for (int i = 0; i < 12; i++) {
        col += bTint[i] * softBokeh(bp, bPos[i], bRad[i]);
    }

    col *= (0.85 + 0.15 * uOverallIntensity);
    fragColor = vec4(col * uHdrScale, 1.0);
}
"""

  // -------------------------------------------------------------------------
  // 2. CONCAVE INNER BACK-WALL LIQUID BOWL (LOCKED TO UNIFIED `uCoreRotMat`)
  //    Provides deep sapphire abyss floor, rotating translucent emerald-jade side folds,
  //    and razor-sharp back-wall Voronoi underwater caustic web!
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

void main() {
    // Unified rotating & differentially swirling 3D fluid coordinate frame
    vec3 advPos = advectFluidPos(vLocalPos, uCoreRotMat, uFluidPhase);
    float swirl = fbm3(advPos * 1.75);
    float vortexArm = smoothstep(0.18, 0.85, 0.5 + 0.5 * sin(atan(advPos.z, advPos.x) * 3.0 + advPos.y * 2.4 + swirl * 2.8));

    // Couple concave back-wall Voronoi caustics to meniscus surface slope & rotating fluid
    float e = 0.035;
    float mY = meniscusHeight(vLocalPos.xz, uMeniscusPhase);
    vec2 mSlope = vec2(
        meniscusHeight(vLocalPos.xz + vec2(e, 0.0), uMeniscusPhase) - mY,
        meniscusHeight(vLocalPos.xz + vec2(0.0, e), uMeniscusPhase) - mY
    ) / e;
    float caustic = opticalCaustics(advPos * 1.02, mSlope, uCausticsPhase);

    // Strictly ordered smoothstep bounds via invSmoothstep (ZERO GLSL UB)
    float belowLiquid = invSmoothstep(mY - 0.09, mY + 0.07, vLocalPos.y);

    // Deep sapphire abyss palette (dark enough that emerald folds & turquoise Voronoi caustics pop sharply!)
    vec3 deepAbyss    = vec3(0.0012, 0.0110, 0.0280);
    vec3 deepSapphire = vec3(0.0035, 0.0480, 0.1150);
    vec3 richEmerald  = vec3(0.0090, 0.1650, 0.0420);
    vec3 brightJade   = vec3(0.0880, 0.4200, 0.0680);
    vec3 causticAqua  = vec3(0.0550, 0.8200, 1.0500);

    // High-contrast rotating emerald-jade liquid folds hugging both sides & sweeping through the back wall
    float rotJadeLobe = smoothstep(-0.15, 0.72, sin(atan(advPos.z, advPos.x) * 2.0 + advPos.y * 1.85 + swirl * 1.6));
    float sideWallBias = smoothstep(0.24, 0.90, length(vLocalPos.xz));
    float jadeCurtain = clamp(
        (0.28 + 0.72 * rotJadeLobe) * sideWallBias * invSmoothstep(-0.76, 0.58, vLocalPos.y)
        * (0.42 + 0.58 * smoothstep(-0.25, 0.62, swirl)),
        0.0, 1.0
    );

    // Focused lower-center / lower-right underwater caustic pool
    float lowerBelly = invSmoothstep(-0.88, 0.04, vLocalPos.y)
                     * invSmoothstep(0.05, 0.92, length(vLocalPos.xz - vec2(0.12, -0.06)));

    vec3 col = mix(deepAbyss, deepSapphire, (0.25 + 0.75 * vortexArm) * belowLiquid);
    col = mix(col, mix(richEmerald, brightJade, smoothstep(0.0, 0.68, swirl)), jadeCurtain * 0.90);
    // Razor-sharp lacy turquoise Voronoi caustic web gleaming on the dark concave floor
    col += causticAqua * caustic * (0.08 + 1.05 * lowerBelly) * belowLiquid * 0.52 * uFluidIntensity;

    // Keep upper air cavity nearly transparent so the glass dome stays crystal-clear
    float cavityAlpha = mix(0.08, 0.82, belowLiquid);
    float alpha = clamp((cavityAlpha + jadeCurtain * 0.28) * (1.02 - uCoreTransparency * 0.24), 0.06, 0.88);

    col *= (0.85 + 0.18 * uOverallIntensity);
    fragColor = vec4(col * uHdrScale, alpha);
}
"""

  // -------------------------------------------------------------------------
  // 3. OUTER BLOWN CRYSTAL GLASS CORE WITH:
  //    - CRYSTAL-CLEAR UPPER DOME & DOUBLE-WALL BLOWN GLASS RIM
  //    - HIGH-CONTRAST ROTATING SAPPHIRE/EMERALD VOLUMETRIC CURRENTS
  //    - EXACT BISECTED ASYMMETRIC 3D MENISCUS SURFACE
  //    - RAZOR-SHARP DUAL-OCTAVE VORONOI CELLULAR UNDERWATER CAUSTICS
  //    - PRODUCTION GPU DIAGNOSTIC MODES (`uDebugMode`)
  // -------------------------------------------------------------------------
  const val CORE_GLASS_VERT = """#version 300 es
precision highp float;
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

in vec3 vWorldPos;
in vec3 vWorldNormal;
in vec3 vViewNormal;
in vec3 vLocalPos;
in float vViewZ;

out vec4 fragColor;

uniform sampler2D uRefractionTex;
uniform vec2 uViewportSize;
uniform mat4 uInvModel;
uniform mat3 uCoreRotMat;
uniform vec3 uCameraPos;
uniform float uFluidPhase;
uniform float uMeniscusPhase;
uniform float uCausticsPhase;
uniform float uFluidIntensity;
uniform float uCoreTransparency;
uniform float uRefraction;
uniform float uOverallIntensity;
uniform float uHdrScale;
uniform int uRaymarchSteps;
uniform int uDebugMode; // 0=Normal, 1=FluidDensity, 2=FluidFlow, 3=Meniscus, 4=CausticsOnly, 5=GlassOnly, 6=DepthDebug

uniform vec3 uKeyLightDir;
uniform vec3 uKeyLightColor;
uniform vec3 uWarmRimDir;
uniform vec3 uWarmRimColor;
uniform vec3 uCoolRimDir;
uniform vec3 uCoolRimColor;
uniform vec3 uEmeraldFillColor;

$GLSL_COMMON_MATH

// Exact central-difference 3D meniscus surface normal & slope
vec3 meniscusNormal(vec2 xz, float meniscusPhase, out vec2 slopeOut) {
    float e = 0.016;
    float hx = meniscusHeight(xz + vec2(e, 0.0), meniscusPhase) - meniscusHeight(xz - vec2(e, 0.0), meniscusPhase);
    float hz = meniscusHeight(xz + vec2(0.0, e), meniscusPhase) - meniscusHeight(xz - vec2(0.0, e), meniscusPhase);
    slopeOut = vec2(hx, hz) / (2.0 * e);
    return normalize(vec3(-hx, 2.0 * e, -hz));
}

// High-contrast dark-dominated studio crystal environment (strictly transparent center!)
vec3 sampleCrystalEnv(vec3 R) {
    vec3 env = vec3(0.0025, 0.0160, 0.0240);

    // Focused sunlit champagne-gold key reflection at upper-left (~10:30)
    vec3 keyAxis = normalize(vec3(-0.54, 0.72, 0.43));
    float keySpot = smoothstep(0.91, 0.992, dot(R, keyAxis));
    env += vec3(1.65, 1.24, 0.62) * keySpot;

    // Warm golden arc reflection along upper-right glass curvature
    float goldBand = exp(-pow((R.y - 0.20 * R.x - 0.10) * 8.0, 2.0)) * smoothstep(0.24, 0.90, R.x);
    env += vec3(1.38, 0.78, 0.16) * goldBand * 0.78;

    // Cool ice-cyan crescent reflection along lower-left glass curvature
    float cyanBand = exp(-pow((R.y + 0.34 + 0.22 * R.x) * 7.2, 2.0)) * invSmoothstep(-0.82, 0.08, R.x);
    env += vec3(0.14, 0.84, 1.18) * cyanBand * 0.72;

    return env;
}

void main() {
    vec3 N = normalize(vWorldNormal);
    vec3 V = normalize(uCameraPos - vWorldPos);
    vec3 VN = normalize(vViewNormal);

    float micro = snoise(vLocalPos * 4.6 + vec3(uFluidPhase * 0.14, -uFluidPhase * 0.12, uFluidPhase * 0.10));
    vec3 pN = normalize(N + vec3(micro * 0.010, -micro * 0.009, micro * 0.010));
    float NdotV = clamp(dot(pN, V), 0.0, 1.0);

    // Debug Mode 6: View-space depth visualization
    if (uDebugMode == 6) {
        float depthVis = clamp((-vViewZ - 2.5) / 4.5, 0.0, 1.0);
        vec3 dCol = mix(vec3(0.15, 0.95, 1.0), vec3(0.03, 0.08, 0.25), depthVis);
        fragColor = vec4(dCol * uHdrScale, 1.0);
        return;
    }

    // -----------------------------------------------------------------------
    // 1. REAL FBO REFRACTION + CAUCHY CHROMATIC DISPERSION (CENTER-CLEAR, RIM-PRISMATIC)
    // -----------------------------------------------------------------------
    vec2 screenUv = gl_FragCoord.xy / max(uViewportSize, vec2(1.0));
    float rimCurve = pow(1.0 - NdotV, 1.35);
    vec2 refractUvShift = VN.xy * (0.014 + 0.105 * rimCurve) * uRefraction;
    float dispSplit = 0.14 * (0.30 + 0.70 * pow(1.0 - NdotV, 1.5)) * (0.45 + 0.55 * uRefraction);

    vec2 uvR = clamp(screenUv - refractUvShift * (1.0 + dispSplit), vec2(0.002), vec2(0.998));
    vec2 uvG = clamp(screenUv - refractUvShift, vec2(0.002), vec2(0.998));
    vec2 uvB = clamp(screenUv - refractUvShift * (1.0 - dispSplit), vec2(0.002), vec2(0.998));

    float invHdr = 1.0 / max(uHdrScale, 1e-4);
    vec3 refractedBack;
    refractedBack.r = texture(uRefractionTex, uvR).r * invHdr;
    refractedBack.g = texture(uRefractionTex, uvG).g * invHdr;
    refractedBack.b = texture(uRefractionTex, uvB).b * invHdr;

    // -----------------------------------------------------------------------
    // 2. REFRACTED-RAY 3D ROTATING VORTEX VOLUME + EXACT BISECTED MENISCUS SOLVER
    // -----------------------------------------------------------------------
    vec3 safeN = dot(pN, V) > 0.02 ? pN : normalize(pN + V * 0.08);
    vec3 refrWorld = refract(-V, safeN, 1.0 / 1.42);
    if (dot(refrWorld, refrWorld) < 1e-5) refrWorld = -V;
    vec3 rayDir = normalize(mat3(uInvModel) * refrWorld);
    vec3 rayOrg = vLocalPos * 0.96;

    float bDot = dot(rayOrg, rayDir);
    float cRad = dot(rayOrg, rayOrg) - 0.945 * 0.945;
    float disc = max(bDot * bDot - cRad, 0.0);
    float tExit = max(-bDot + sqrt(disc), 0.05);

    int steps = clamp(uRaymarchSteps, 8, 24);
    float stepLen = tExit / float(steps);

    float liquidPath = 0.0;
    float hitMeniscus = 0.0;
    float meniscusCrossingDir = 1.0; // +1 air->water, -1 water->air
    vec3 meniscusHitPos = vec3(0.0);
    vec3 volumeGlow = vec3(0.0);
    vec3 debugDensityAcc = vec3(0.0);
    vec3 debugFlowSample = vec3(0.0);
    float debugFlowWeight = 0.0;
    float opticalTrans = 1.0;

    float prevT = 0.0;
    float prevDiff = rayOrg.y - meniscusHeight(rayOrg.xz, uMeniscusPhase);
    float absorptionDensity = 1.35 - uCoreTransparency * 0.48;

    for (int i = 1; i <= 24; i++) {
        if (i > steps || opticalTrans < 0.03) break;

        float currT = float(i) * stepLen;
        vec3 sp = rayOrg + rayDir * currT;
        float mH = meniscusHeight(sp.xz, uMeniscusPhase);
        float currDiff = sp.y - mH;

        // Exact bracketed meniscus detection + 3-step binary bisection + signed secant finish
        bool crossedDown = (prevDiff > 0.0 && currDiff <= 0.0);
        bool crossedUp   = (prevDiff < 0.0 && currDiff >= 0.0);
        if (hitMeniscus < 0.5 && (crossedDown || crossedUp)) {
            float tLo = prevT;
            float dLo = prevDiff;
            float tHi = currT;
            float dHi = currDiff;

            for (int b = 0; b < 3; b++) {
                float tMid = 0.5 * (tLo + tHi);
                vec3 pMid = rayOrg + rayDir * tMid;
                float dMid = pMid.y - meniscusHeight(pMid.xz, uMeniscusPhase);
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
            hitMeniscus = 1.0;
            meniscusCrossingDir = crossedDown ? 1.0 : -1.0;
        }

        // Crisp mid-segment liquid integration (tight +/- 0.015 meniscus transition eliminates flat band!)
        float midT = 0.5 * (prevT + currT);
        vec3 midP = rayOrg + rayDir * midT;
        float midM = meniscusHeight(midP.xz, uMeniscusPhase);
        float submerge = invSmoothstep(midM - 0.015, midM + 0.015, midP.y);

        if (submerge > 0.01) {
            float segLiquid = stepLen * submerge;
            liquidPath += segLiquid;

            // Transform sample point into unified rotating & swirling 3D liquid vortex frame!
            vec3 advP = advectFluidPos(midP, uCoreRotMat, uFluidPhase);
            float nMain = fbm3(advP * 1.75);
            float nDetail = fbm3(advP * 3.10 + vec3(2.4, -1.7, 1.9));

            // 3 distinct asymmetric rotating 3D spiral vortex plumes (visibly sweep across orb face!)
            float rotAz = atan(advP.z, advP.x);
            float spiralRibbon = smoothstep(0.18, 0.88, 0.5 + 0.5 * sin(rotAz * 3.0 + advP.y * 2.6 + nMain * 3.2));
            float filamentCrest = pow(clamp(1.0 - abs(nMain * 0.72 + nDetail * 0.28), 0.0, 1.0), 4.2);

            // Rich translucent emerald-jade side folds & inner swirling veins (Layer 3)
            float radialWall = smoothstep(0.24, 0.92, length(midP.xz));
            float jadeSpiral = smoothstep(-0.12, 0.78, sin(rotAz * 2.0 + advP.y * 2.1 + nMain * 1.9));
            float sidePresence = smoothstep(0.18, 0.82, abs(midP.x)) * 0.65 + 0.35;
            float jadeMask = clamp(
                radialWall * jadeSpiral * sidePresence * invSmoothstep(-0.82, 0.42, midP.y),
                0.0, 1.0
            );

            // Shallow cerulean highlight ONLY near the curved upper liquid surface (Layer 2),
            // leaving deep water dark sapphire so volumetric currents & Voronoi caustics have rich contrast!
            float depthBelowMeniscus = max(midM - midP.y, 0.0);
            float shallowLayer = exp(-depthBelowMeniscus * 3.2);

            vec3 deepSapphireCurrent = mix(vec3(0.002, 0.025, 0.068), vec3(0.012, 0.145, 0.285), spiralRibbon);
            vec3 ceruleanStreak      = vec3(0.024, 0.340, 0.560) * (filamentCrest * 0.75 + shallowLayer * 0.24);
            vec3 emeraldFold         = mix(vec3(0.008, 0.140, 0.036), vec3(0.105, 0.460, 0.072), smoothstep(-0.15, 0.65, nDetail));

            vec3 localEmission = deepSapphireCurrent * 0.22
                               + ceruleanStreak * (0.28 + 0.72 * spiralRibbon) * 0.46
                               + emeraldFold * jadeMask * (0.36 + 0.64 * spiralRibbon) * 0.62;

            volumeGlow += localEmission * segLiquid * opticalTrans;
            debugDensityAcc += vec3(emeraldFold.g * jadeMask * 1.4, spiralRibbon * 0.75, filamentCrest * 1.1) * segLiquid * opticalTrans;
            if (debugFlowWeight < 0.01) {
                debugFlowSample = normalize(advP - midP + vec3(1e-4)) * 0.5 + 0.5;
                debugFlowWeight = 1.0;
            }

            opticalTrans *= exp(-segLiquid * 1.15 * absorptionDensity);
        }

        prevT = currT;
        prevDiff = currDiff;
    }

    // -----------------------------------------------------------------------
    // 2B. RAZOR-SHARP PROJECTED DUAL-OCTAVE VORONOI UNDERWATER CAUSTICS (V05)
    //     Evaluated cleanly at the refracted optical floor (ZERO volume smearing!)
    // -----------------------------------------------------------------------
    vec3 floorPos = rayOrg + rayDir * (tExit * 0.76);
    float floorMeniscusY = meniscusHeight(floorPos.xz, uMeniscusPhase);
    float floorSubmerged = invSmoothstep(floorMeniscusY - 0.10, floorMeniscusY + 0.08, floorPos.y);
    vec2 causticSlope = vec2(0.0);
    if (hitMeniscus > 0.5) {
        meniscusNormal(meniscusHitPos.xz, uMeniscusPhase, causticSlope);
    } else {
        meniscusNormal(floorPos.xz, uMeniscusPhase, causticSlope);
    }

    vec3 advFloor = advectFluidPos(floorPos, uCoreRotMat, uFluidPhase);
    float voronoiNet = opticalCaustics(advFloor, causticSlope, uCausticsPhase);
    // Concentrate luminous turquoise caustic web in the lower-center / lower-right crystal water belly
    float bellyMask = invSmoothstep(-0.92, 0.10, floorPos.y)
                    * invSmoothstep(0.04, 0.94, length(floorPos.xz - vec2(0.12, -0.04)));
    float causticStrength = voronoiNet * floorSubmerged * (0.14 + 0.98 * bellyMask)
                          * smoothstep(0.04, 0.45, liquidPath);
    vec3 causticColor = mix(vec3(0.025, 0.560, 0.860), vec3(0.320, 1.250, 1.480), clamp(voronoiNet * 0.58, 0.0, 1.0))
                      * causticStrength * 0.72 * uFluidIntensity;

    // Debug Mode 1: Raw 3D Volumetric Fluid Density & Emerald/Sapphire Spiral Structure
    if (uDebugMode == 1) {
        fragColor = vec4(debugDensityAcc * 2.2 * uHdrScale, 1.0);
        return;
    }
    // Debug Mode 2: 3D Advected Flow Direction Field
    if (uDebugMode == 2) {
        vec3 fCol = debugFlowWeight > 0.5 ? debugFlowSample : vec3(0.02, 0.05, 0.08);
        fragColor = vec4(fCol * uHdrScale, 1.0);
        return;
    }
    // Debug Mode 3: Exact Bisected 3D Meniscus Normal & Curvature
    if (uDebugMode == 3) {
        vec3 mVis = vec3(0.015, 0.04, 0.07);
        if (hitMeniscus > 0.5) {
            vec2 sl;
            vec3 mn = meniscusNormal(meniscusHitPos.xz, uMeniscusPhase, sl) * meniscusCrossingDir;
            mVis = mn * 0.5 + 0.5;
        }
        fragColor = vec4(mVis * uHdrScale, 1.0);
        return;
    }
    // Debug Mode 4: Isolated Razor-Sharp Voronoi Underwater Caustics
    if (uDebugMode == 4) {
        fragColor = vec4((vec3(0.002, 0.015, 0.032) + causticColor * 1.45) * uHdrScale, 1.0);
        return;
    }

    // Exponential Beer-Lambert deep-water optical transmittance (rich oceanic depth!)
    vec3 waterTransmittance = exp(-liquidPath * vec3(2.15, 0.72, 0.36) * absorptionDensity);
    vec3 interiorCol = refractedBack * waterTransmittance
                     + (volumeGlow * uFluidIntensity)
                     + causticColor;

    // Crisp glossy 3D curved meniscus surface (dark TIR shadow lip + sharp silvery-cyan wave crest)
    if (hitMeniscus > 0.5) {
        vec2 mSlope;
        vec3 mN = meniscusNormal(meniscusHitPos.xz, uMeniscusPhase, mSlope) * meniscusCrossingDir;
        vec3 localV = normalize(-rayDir);
        float mNdotV = clamp(dot(mN, localV), 0.0, 1.0);
        float mFres = pow(1.0 - mNdotV, 3.6);
        vec3 mHalf = normalize(localV + normalize(vec3(-0.52, 0.78, 0.38)));
        float mSpec = pow(max(dot(mN, mHalf), 0.0), 72.0);
        // Dark internal refractive trough directly at the wave edge + thin bright crest glint
        interiorCol *= (1.0 - 0.52 * mFres);
        interiorCol += vec3(0.16, 0.78, 0.96) * (mFres * 0.26 + mSpec * 0.85) * uFluidIntensity;
    }

    // Clear upper glass dome subtle cool-sapphire cavity tint (keeps upper dome crisp & transparent!)
    float airCavity = invSmoothstep(0.02, 0.28, liquidPath);
    interiorCol = mix(interiorCol, refractedBack * vec3(0.76, 0.92, 1.04) + vec3(0.004, 0.028, 0.056), airCavity * 0.55);

    // -----------------------------------------------------------------------
    // 3. DOUBLE-WALL THICK BLOWN CRYSTAL GLASS SHELL (CRYSTAL-CLEAR CENTER, RAZOR RIM)
    // -----------------------------------------------------------------------
    float fresnelOuter  = 0.024 + 0.976 * pow(1.0 - NdotV, 5.2);
    float razorOuterRim = pow(1.0 - NdotV, 9.6);
    float innerWallRing = exp(-pow((NdotV - 0.25) / 0.052, 2.0));
    float glassGroove   = exp(-pow((NdotV - 0.14) / 0.038, 2.0));

    vec3 R = reflect(-V, pN);
    vec3 envRefl = sampleCrystalEnv(R);

    float warmRimWeight = smoothstep(-0.18, 0.58, VN.x * 0.72 + VN.y * 0.68);
    float coolRimWeight = smoothstep(-0.12, 0.72, -VN.y * 0.78 - VN.x * 0.44);
    vec3 fieryGoldRim = vec3(1.55, 0.88, 0.18);
    vec3 champagneRim = vec3(1.75, 1.28, 0.62);
    vec3 iceCyanRim   = vec3(0.20, 0.96, 1.28);

    vec3 rimPalette = mix(iceCyanRim, mix(fieryGoldRim, champagneRim, razorOuterRim * 0.60), warmRimWeight);

    // Tight localized sunlit golden caustic glint at upper-left (10:30) — ZERO milky left-hemisphere haze!
    vec3 Lkey = normalize(uKeyLightDir);
    float NdotLKey = max(dot(pN, Lkey), 0.0);
    vec3 Hkey = normalize(V + Lkey);
    float NdotHKey = max(dot(pN, Hkey), 0.0);
    float specKeyPinpoint = pow(NdotHKey, 240.0) * NdotLKey * 2.85;
    float specKeyTightArc = pow(NdotHKey, 64.0)  * NdotLKey * 0.28;

    float sunSpot = smoothstep(0.84, 0.985, dot(pN, Lkey));
    vec3 sunAdv = advectFluidPos(vLocalPos, uCoreRotMat, uFluidPhase * 0.6);
    float sunCaustics = opticalCaustics(sunAdv * 1.10, vec2(0.0), uCausticsPhase * 0.65) * sunSpot * 0.58;
    vec3 keyHighlight = vec3(1.65, 1.25, 0.62) * (specKeyPinpoint + specKeyTightArc + sunCaustics);

    vec3 Lwarm = normalize(uWarmRimDir);
    float specWarm = pow(max(dot(pN, normalize(V + Lwarm)), 0.0), 72.0) * max(dot(pN, Lwarm), 0.0) * 0.95;
    vec3 Lcool = normalize(uCoolRimDir);
    float specCool = pow(max(dot(pN, normalize(V + Lcool)), 0.0), 64.0) * max(dot(pN, Lcool), 0.0) * 0.62;

    // Debug Mode 5: Outer Glass Refraction + Double-Wall Fresnel + Speculars Only
    vec3 baseInterior = (uDebugMode == 5) ? refractedBack : interiorCol;

    vec3 col = baseInterior * (1.0 - glassGroove * 0.42);
    float envBlend = clamp(fresnelOuter * (1.04 - uCoreTransparency * 0.42), 0.020, 0.68);
    col = mix(col, envRefl, envBlend * 0.52);

    // Crisp blown-glass double wall (outer golden/cyan razor edge + recessed secondary crystal ring)
    col += rimPalette * (fresnelOuter * 0.58 + razorOuterRim * 1.25);
    col += mix(iceCyanRim, fieryGoldRim, warmRimWeight * 0.86) * innerWallRing * 0.34;
    col += iceCyanRim * coolRimWeight * innerWallRing * 0.22;
    col += keyHighlight + fieryGoldRim * specWarm + iceCyanRim * specCool;

    col *= (0.86 + 0.18 * uOverallIntensity);

    float edgeAlpha = smoothstep(0.0, 0.040, NdotV);
    fragColor = vec4(col * uHdrScale, edgeAlpha);
}
"""
}
