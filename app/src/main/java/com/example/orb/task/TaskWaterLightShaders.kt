package com.example.orb.task

/**
 * OpenGL ES 3.00 shaders for Tab 2 (`TASK_PERIMETER_DEMO`):
 *  1. `TASK_BG_VERT / TASK_BG_FRAG` — Luminous shallow-aqua studio background + focused pool caustics beneath Orb
 *  2. `PHOTO_QUAD_VERT / PHOTO_QUAD_FRAG` — Original fishing photo clipped by exact rounded-rect SDF + crisp white hairline
 *  3. `TASK_RIBBON_VERT / TASK_RIBBON_FRAG` — 4 independent Task Energy 3-layer liquid ribbons + screen-space refraction + G1 debug
 *  4. `PERIMETER_RIBBON_VERT / PERIMETER_RIBBON_FRAG` — Unified arc-length L1/L2/L3 liquid waterlight + screen-space refraction + G1 debug
 *  5. `TASK_SPARKLE_VERT / TASK_SPARKLE_FRAG` — KF02 contact glow, KF05 top-join glint, and L4 crystal droplets (GL_POINTS + quad fallback)
 *
 * Strictly enforces `#version 300 es`, `precision highp float`, `precision highp int` in BOTH vertex
 * and fragment stages, and zero inverted `smoothstep` calls.
 */
object TaskWaterLightShaders {

  const val TASK_BG_VERT = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
out vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

  const val TASK_BG_FRAG = """#version 300 es
precision highp float;
precision highp int;

in vec2 vUv;
out vec4 fragColor;

uniform float uTime;
uniform vec2 uResolution;
uniform vec2 uOrbCenterUv;
uniform float uOrbRadiusNormH;
uniform float uHdrScale;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// Gentle inverse-ACES pre-compensation so bright studio cyan survives ACES filmic tonemapping cleanly
// while leaving enough luminous headroom for crisp translucent liquid waterlight highlights!
vec3 studioSrgbToLinearPreComp(vec3 srgb) {
    vec3 lin = pow(clamp(srgb, vec3(0.0), vec3(0.98)), vec3(2.2));
    return lin * 1.12;
}

void main() {
    float aspect = uResolution.x / max(uResolution.y, 1.0);

    // 1. Airy light-cyan aquatic studio backdrop matching FROZEN_6KF_VISUAL_SPEC
    vec3 topIce     = vec3(0.81, 0.945, 0.975);
    vec3 midAqua    = vec3(0.62, 0.875, 0.935);
    vec3 lowCyan    = vec3(0.45, 0.815, 0.905);
    vec3 deepCorner = vec3(0.38, 0.760, 0.875);

    float yGrad = clamp(vUv.y, 0.0, 1.0); // 0 = bottom, 1 = top
    vec3 baseSrgb = mix(lowCyan, midAqua, smoothstep(0.08, 0.56, yGrad));
    baseSrgb = mix(baseSrgb, topIce, smoothstep(0.56, 0.96, yGrad));

    // Soft side luminance framing (brighter mist near top-left & center-bottom, gentle teal edge vignette)
    float sideDist = abs(vUv.x - 0.5) * 2.0;
    baseSrgb = mix(baseSrgb, deepCorner, smoothstep(0.56, 1.12, sideDist) * 0.26);

    // Soft diagonal sun-shaft veil across upper-left
    float shaft = exp(-pow(((vUv.x - 0.36) * 0.8 + (vUv.y - 0.62) * 0.55) * 2.3, 2.0));
    baseSrgb += vec3(0.06, 0.075, 0.07) * shaft;

    // 2. P1: Focused water-surface caustic reflection pool directly beneath the shared AI Orb.
    // Concentrates below Orb center, attenuates smoothly laterally, stays strictly below photo & Task stream!
    float poolCenterY = uOrbCenterUv.y - uOrbRadiusNormH * 0.44;
    float dxPool = (vUv.x - uOrbCenterUv.x) * aspect;
    float dyPool = (vUv.y - poolCenterY) * 2.65;
    float poolDist = length(vec2(dxPool * 1.15, dyPool));
    float lateralAtten = exp(-dxPool * dxPool * 8.5);
    float belowTaskGate = 1.0 - smoothstep(
        uOrbCenterUv.y + uOrbRadiusNormH * 0.15,
        uOrbCenterUv.y + uOrbRadiusNormH * 0.95,
        vUv.y
    );
    float poolMask = exp(-poolDist * poolDist * 8.2) * lateralAtten * belowTaskGate;

    vec2 cUv1 = vec2(vUv.x * 8.5 * aspect + uTime * 0.24, vUv.y * 16.0 - uTime * 0.19);
    vec2 cUv2 = vec2(vUv.x * 12.0 * aspect - uTime * 0.17, vUv.y * 22.0 + uTime * 0.23);
    float n1 = noise2(cUv1);
    float n2 = noise2(cUv2);
    float causticNet = pow(clamp(1.0 - abs(n1 - n2) * 2.75, 0.0, 1.0), 3.6);
    float rippleRing = 0.5 + 0.5 * sin(poolDist * 38.0 - uTime * 2.5 + n1 * 2.2);
    float ringGlow = pow(rippleRing, 3.4) * exp(-poolDist * 4.8);

    vec3 col = studioSrgbToLinearPreComp(baseSrgb);
    // Crisp shimmering pool caustics + delicate warm-gold specular floor reflection directly under Orb
    col += vec3(0.18, 0.66, 0.84) * causticNet * poolMask * 0.56;
    col += vec3(0.26, 0.76, 0.90) * ringGlow * poolMask * 0.34;
    float floorGold = exp(-pow(poolDist * 4.2, 2.0)) * lateralAtten * belowTaskGate;
    col += vec3(0.44, 0.34, 0.14) * floorGold * 0.26;

    // Keep strictly below Bloom bright-pass threshold (1.32) so studio background never hazes
    col = min(col, vec3(1.10));
    fragColor = vec4(col * uHdrScale, 1.0);
}
"""

  const val PHOTO_QUAD_VERT = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec2 aPosNdc;
layout(location = 1) in vec2 aUv;
out vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(aPosNdc, 0.0, 1.0);
}
"""

  const val PHOTO_QUAD_FRAG = """#version 300 es
precision highp float;
precision highp int;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uPhotoTex;
uniform vec2 uPhotoSizePx;
uniform float uCornerRadiusPx;
uniform float uPadPx;
uniform float uHdrScale;
uniform int uDirectDisplaySrgb; // 0=legacy HDR+ACES FBO, 1=Tab 2 direct sRGB display framebuffer
uniform vec4 uPhotoUvRect;      // (uMin, vMin, uMax, vMax) in top-down Bitmap UV space

// Exact rounded-rectangle SDF in physical pixels relative to photo center
float roundedRectSdf(vec2 pPx, vec2 halfExtents, float radius) {
    vec2 q = abs(pPx) - (halfExtents - vec2(radius));
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
}

// Gentle inverse-ACES approximation so photo tones stay faithful & natural after ACES tonemapping
vec3 photoSrgbToPostCompLinear(vec3 srgb) {
    vec3 x = clamp(srgb, vec3(0.0), vec3(0.99));
    vec3 lin = pow(x, vec3(2.2));
    // Invert ACES compression curve mildly in [0..0.95], capped strictly < 1.24 so photo NEVER blooms!
    vec3 boosted = lin / max(vec3(0.28), vec3(1.0) - lin * 0.52);
    return min(boosted * 0.84, vec3(1.22));
}

void main() {
    // Expand quad by uPadPx on all sides so anti-aliased hairline + soft outer shadow never clip
    vec2 totalSize = uPhotoSizePx + vec2(uPadPx * 2.0);
    vec2 localPx = (vUv - vec2(0.5)) * totalSize;
    vec2 halfPhoto = uPhotoSizePx * 0.5;
    float sdf = roundedRectSdf(localPx, halfPhoto, uCornerRadiusPx);

    // Map expanded quad UV back into top-down photo [0..1] UV, then apply aspect-locked CENTER_CROP rect
    vec2 photoUv = (localPx + halfPhoto) / max(uPhotoSizePx, vec2(1.0));
    vec2 topDownUv = vec2(clamp(photoUv.x, 0.0, 1.0), clamp(1.0 - photoUv.y, 0.0, 1.0));
    vec2 sampleUv = clamp(mix(uPhotoUvRect.xy, uPhotoUvRect.zw, topDownUv), vec2(0.0), vec2(1.0));

    float insideAlpha = 1.0 - smoothstep(-0.75, 0.75, sdf);
    // Crisp 1.8px white-frost hairline border matching the frozen overview
    float hairline = exp(-pow((sdf - 0.35) / 1.15, 2.0)) * 0.76;
    // Soft ambient cyan-slate elevation shadow outside photo edge
    float outerShadow = (1.0 - smoothstep(0.0, uPadPx * 0.92, max(sdf, 0.0))) * (1.0 - insideAlpha) * 0.16;

    vec3 photoSrgb = texture(uPhotoTex, sampleUv).rgb;
    vec3 photoCol = (uDirectDisplaySrgb == 1) ? photoSrgb : photoSrgbToPostCompLinear(photoSrgb);

    vec3 hairlineCol = (uDirectDisplaySrgb == 1) ? vec3(0.94, 0.99, 1.0) : vec3(0.92, 0.98, 1.02);
    vec3 shadowCol = (uDirectDisplaySrgb == 1) ? vec3(0.05, 0.22, 0.30) : vec3(0.02, 0.14, 0.20);

    vec3 rgb = mix(shadowCol, photoCol, insideAlpha);
    float borderMix = (uDirectDisplaySrgb == 1)
        ? hairline * (1.0 - insideAlpha)
        : hairline * (1.0 - insideAlpha * 0.35);
    rgb = mix(rgb, hairlineCol, borderMix);
    float alpha = clamp(max(insideAlpha, max(hairline, outerShadow)), 0.0, 1.0);

    if (alpha < 0.004) discard;
    fragColor = vec4(rgb * uHdrScale, alpha);
}
"""

  const val TASK_RIBBON_VERT = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec2 aPosNdc;
layout(location = 1) in vec2 aUv;        // x = s in [0, 1], y = cross u in [0, 1]
layout(location = 2) in float aAlpha;
layout(location = 3) in float aTwist;
layout(location = 4) in vec2 aNormalDir; // Unit lateral ribbon normal in GL UV orientation

uniform int uStreamKind; // 0=MainIce, 1=LeftCyan, 2=RightFine, 3=EmeraldVeil
uniform float uFlowTime;
uniform float uStreamPhase;

out vec2 vUv;
out float vAlpha;
out float vTwist;
out vec2 vNormalDir;

void main() {
    vUv = aUv;
    vAlpha = aAlpha;
    vTwist = aTwist;
    vNormalDir = aNormalDir;

    // P0-2: Lateral sway is STRICTLY 0 at both anchors (s==0 Orb top & s==1 Photo bottom center) for main stream!
    float s = aUv.x;
    float swayEnv = sin(s * 3.14159265);
    if (uStreamKind == 0 && (s <= 0.001 || s >= 0.999)) {
        swayEnv = 0.0;
    }
    float wave = sin(s * 7.5 - uFlowTime * 2.8 + uStreamPhase) * 0.011
               + cos(s * 12.0 - uFlowTime * 4.1 - uStreamPhase) * 0.004;
    vec2 displaced = aPosNdc + vec2(wave * swayEnv, 0.0);
    gl_Position = vec4(displaced, 0.0, 1.0);
}
"""

  const val TASK_RIBBON_FRAG = """#version 300 es
precision highp float;
precision highp int;

in vec2 vUv;
in float vAlpha;
in float vTwist;
in vec2 vNormalDir;

out vec4 fragColor;

uniform int uStreamKind;      // 0=MainIce, 1=LeftCyan, 2=RightFine, 3=EmeraldVeil
uniform float uReveal;        // Effective front progress along this stream [0..1]
uniform float uFlowTime;
uniform float uFlowSpeed;
uniform float uHdrScale;
uniform sampler2D uTaskRefractionTex;
uniform int uHasRefractionTex;
uniform vec2 uFboSize;
uniform float uRefractionPx;
uniform float uRefractionStrength;
uniform int uTaskVisualDebug; // 0=NORMAL, 1=GEOMETRY_MAGENTA, 2=ALPHA_HEATMAP

vec3 alphaToHeatmap(float a) {
    float t = clamp(a / 0.85, 0.0, 1.0);
    vec3 c0 = vec3(0.05, 0.14, 0.65);
    vec3 c1 = vec3(0.05, 0.78, 0.92);
    vec3 c2 = vec3(0.96, 0.88, 0.14);
    vec3 c3 = vec3(0.98, 0.16, 0.12);
    if (t < 0.33) return mix(c0, c1, t / 0.33);
    if (t < 0.66) return mix(c1, c2, (t - 0.33) / 0.33);
    return mix(c2, c3, (t - 0.66) / 0.34);
}

void main() {
    float s = vUv.x;
    float u = vUv.y;

    // P0-2: Main stream stays visibly connected to Orb (s=0) & Photo Bottom Center (s=1) after KF02!
    // Secondary streams retain natural tapered root/tip fade.
    float revealMask;
    float rootFade;
    float endFade;
    if (uStreamKind == 0) {
        float settled = smoothstep(0.92, 0.995, uReveal);
        float travelEndFade = 1.0 - smoothstep(0.86, 1.0, s);
        float contactEndFade = 1.0 - 0.20 * smoothstep(0.92, 1.0, s);
        endFade = mix(travelEndFade, contactEndFade, settled);
        revealMask = (uReveal >= 0.995) ? 1.0 : (1.0 - smoothstep(uReveal - 0.095, uReveal + 0.015, s));
        rootFade = 0.55 + 0.45 * smoothstep(0.0, 0.045, s);
    } else {
        revealMask = 1.0 - smoothstep(uReveal - 0.095, uReveal + 0.015, s);
        rootFade = smoothstep(0.0, 0.09, s);
        endFade = 1.0 - smoothstep(0.86, 1.0, s);
    }
    if (revealMask < 0.004) discard;

    // G1 GEOMETRY_MAGENTA: proves real geometry rasterization while keeping real reveal progress clipping!
    if (uTaskVisualDebug == 1) {
        fragColor = vec4(vec3(1.0, 0.0, 1.0) * uHdrScale, 1.0);
        return;
    }

    // P0-3 Three-Layer Liquid Optical Cross-Section:
    // (A) Translucent liquid body + crisp refractive dark glass rim (0.16) for bright-background readability
    float distEdge = abs(u - 0.5) * 2.0; // 0 = center, 1 = outer edge
    float edgeMask = 1.0 - smoothstep(0.76, 1.0, distEdge);
    float fresnelRim = pow(clamp(distEdge, 0.0, 1.0), 2.3) * edgeMask;
    float coreBody = (1.0 - distEdge * distEdge) * edgeMask;
    float refractiveEdge = exp(-pow((distEdge - 0.84) / 0.11, 2.0)) * edgeMask;

    // (B) Undulating internal liquid specular crest
    float crestCenter = 0.5 + 0.12 * sin(s * 9.0 - uFlowTime * 1.6);
    float liquidCrest = exp(-pow((u - crestCenter) / 0.11, 2.0)) * edgeMask;

    // (C) Fine internal optical filaments + subtle warm-gold reflection
    float flowCoord = s * 9.5 - uFlowTime * uFlowSpeed * 2.4;
    float liquidSilk = 0.5 + 0.5 * sin(flowCoord + sin(u * 6.283 + uFlowTime * 1.4) * 1.2);
    float fineRipple = 0.5 + 0.5 * sin(s * 22.0 - uFlowTime * uFlowSpeed * 4.2 + u * 5.0);
    float fineFilament = exp(-pow((u - (0.5 - 0.18 * cos(s * 13.0 - uFlowTime * uFlowSpeed * 2.6))) / 0.075, 2.0))
                       * (0.40 + 0.60 * fineRipple) * edgeMask;
    float goldWaveU = 0.5 + 0.22 * sin(s * 6.5 - uFlowTime * 2.1 + vTwist);
    float goldThread = exp(-pow((u - goldWaveU) / 0.085, 2.0)) * (0.45 + 0.55 * fineRipple) * edgeMask;

    // Luminous leading front crescent while stream is advancing (softens once uReveal >= 0.99)
    float advancing = 1.0 - smoothstep(0.92, 1.0, uReveal);
    float frontTipGlow = exp(-pow((s - uReveal) / 0.065, 2.0)) * advancing;

    vec3 col;
    float alpha;
    if (uStreamKind == 3) {
        // Organic emerald-green aquatic kelp veil
        vec3 deepEmerald = vec3(0.02, 0.44, 0.25);
        vec3 jadeSheen   = vec3(0.22, 0.92, 0.58);
        vec3 goldAccent  = vec3(1.48, 1.16, 0.52);
        col = mix(deepEmerald, jadeSheen, 0.30 + 0.70 * liquidSilk);
        col = mix(col, vec3(0.01, 0.20, 0.14), refractiveEdge * 0.16);
        col += vec3(0.24, 0.88, 0.68) * (fresnelRim * 0.48 + liquidCrest * 0.28);
        col += vec3(0.48, 1.02, 1.35) * fineFilament * 0.20;
        col += goldAccent * goldThread * 0.24;
        alpha = (coreBody * 0.34 + fresnelRim * 0.54 + liquidCrest * 0.22 + goldThread * 0.18) * vAlpha;
    } else if (uStreamKind == 0) {
        // Wide translucent icy-blue main S-stream: sapphire depth + refractive dark rim + luminous crest + gold silk
        vec3 deepSapphire = vec3(0.06, 0.48, 0.88);
        vec3 iceBody      = vec3(0.14, 0.72, 1.04);
        vec3 crystalRim   = vec3(0.76, 1.28, 1.50);
        vec3 warmGold     = vec3(1.64, 1.28, 0.58);
        col = mix(deepSapphire, iceBody, 0.35 + 0.65 * liquidSilk);
        col = mix(col, crystalRim, 0.38 * fresnelRim + 0.28 * fineFilament);
        col = mix(col, vec3(0.02, 0.20, 0.42), refractiveEdge * 0.16);
        col += vec3(0.48, 1.02, 1.35) * liquidCrest * 0.30;
        col += warmGold * (goldThread * 0.40 + frontTipGlow * 0.55);
        alpha = (coreBody * 0.36 + fresnelRim * 0.62 + liquidCrest * 0.26 + refractiveEdge * 0.16 + goldThread * 0.22 + frontTipGlow * 0.34) * vAlpha;
    } else {
        // Accompanying icy-cyan left curl & fine right stream (delicate translucent liquid)
        vec3 cyanDeep  = vec3(0.08, 0.54, 0.90);
        vec3 edgeGlint = vec3(0.74, 1.26, 1.46);
        vec3 warmGold  = vec3(1.52, 1.18, 0.52);
        col = mix(cyanDeep, edgeGlint, 0.32 + 0.46 * fineRipple + 0.38 * fresnelRim);
        col = mix(col, vec3(0.02, 0.22, 0.42), refractiveEdge * 0.16);
        col += vec3(0.48, 1.02, 1.35) * liquidCrest * 0.30;
        col += warmGold * (goldThread * 0.28 + frontTipGlow * 0.35);
        alpha = (coreBody * 0.30 + fresnelRim * 0.56 + liquidCrest * 0.20 + goldThread * 0.18) * vAlpha;
    }

    alpha = clamp(alpha * revealMask * rootFade * endFade, 0.0, 0.82);

    // G1 ALPHA_HEATMAP: opaque visualization of real alpha after all reveal/fade/cap gating
    if (uTaskVisualDebug == 2) {
        fragColor = vec4(alphaToHeatmap(alpha) * uHdrScale, 1.0);
        return;
    }

    if (alpha < 0.002) discard;

    // P0-4: Screen-space liquid-film background refraction from separate refraction snapshot (ZERO feedback loop!)
    if (uHasRefractionTex > 0 && uRefractionStrength > 0.001) {
        vec2 safeFbo = max(uFboSize, vec2(1.0));
        vec2 baseUv = clamp(gl_FragCoord.xy / safeFbo, vec2(0.002), vec2(0.998));
        float crossLens = (u - 0.5) * 2.0;
        vec2 nDir = normalize(vNormalDir + vec2(1e-5, 0.0));
        vec2 refrUv = clamp(baseUv + nDir * (crossLens * uRefractionPx) / safeFbo, vec2(0.002), vec2(0.998));
        float invHdr = 1.0 / max(uHdrScale, 1e-4);
        vec3 baseBg = texture(uTaskRefractionTex, baseUv).rgb * invHdr;
        vec3 refrBg = texture(uTaskRefractionTex, refrUv).rgb * invHdr;
        vec3 refrDelta = (refrBg - baseBg) * uRefractionStrength;
        // Fold pure differential refraction into straight-alpha blend with zero double-background washout or NaN
        col = max(col + refrDelta / max(alpha, 0.08), vec3(0.0));
    }

    fragColor = vec4(col * uHdrScale, alpha);
}
"""

  const val PERIMETER_RIBBON_VERT = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec2 aPosNdc;
layout(location = 1) in vec3 aArcU;      // x = side progress, y = crossU, z = closed-loop U
layout(location = 2) in vec2 aNormalNdc; // Outward unit normal scaled into NDC per px

uniform float uFlowTime;
uniform int uLayerKind;  // 0=L1 IceBlue, 1=L2 EmeraldVeil, 2=L3 GoldFilament
uniform float uSideSign; // -1.0 Left, +1.0 Right
uniform float uPhotoWidthPx;

out vec2 vArcU;
out float vLoopU;
out float vWave;
out vec2 vNormalDir;

void main() {
    vArcU = aArcU.xy;
    float s = aArcU.x;
    float u = aArcU.y;
    vLoopU = aArcU.z;

    // True outward unit normal in OpenGL UV orientation (+X right, +Y up) for P0-4 liquid refraction
    vNormalDir = normalize(aNormalNdc + vec2(1e-5, 0.0));

    // Continuous organic liquid undulation strictly OUTWARD (u == 0 inner edge stays locked on boundary!)
    float w1 = sin(s * 24.0 - uFlowTime * 2.35 + uSideSign * 0.85);
    float w2 = cos(s * 41.0 - uFlowTime * 3.40 - uSideSign * 1.40);
    float organicWave = 0.5 + 0.5 * (0.65 * w1 + 0.35 * w2);
    vWave = organicWave;

    float flutterPx = 0.0;
    if (uLayerKind == 0) {
        flutterPx = u * uPhotoWidthPx * 0.0036 * (w1 * 0.65 + w2 * 0.35);
    } else if (uLayerKind == 1) {
        flutterPx = u * uPhotoWidthPx * 0.0072 * (w1 * 0.72 + w2 * 0.28);
    } else {
        flutterPx = u * uPhotoWidthPx * 0.0024 * w2;
    }

    // Never push inward past the photo boundary (clamp negative outward flutter tightly)
    float safeOutPx = max(flutterPx, -u * 1.0);
    vec2 finalNdc = aPosNdc + aNormalNdc * safeOutPx;
    gl_Position = vec4(finalNdc, 0.0, 1.0);
}
"""

  const val PERIMETER_RIBBON_FRAG = """#version 300 es
precision highp float;
precision highp int;

in vec2 vArcU;
in float vLoopU;
in float vWave;
in vec2 vNormalDir;

out vec4 fragColor;

uniform int uLayerKind;         // 0=L1 IceBlue, 1=L2 EmeraldVeil, 2=L3 GoldFilament
uniform float uRevealProgress;  // [0..1] along this side (0=bottom center, 1=top center)
uniform float uFlowTime;
uniform float uSideSign;
uniform float uCoverageComplete; // Strictly driven from real snapshot.coverageComplete (left>=0.999 && right>=0.999)
uniform float uLayerAlphaScale;  // Bound from PerimeterWaterLightMaterial per layer
uniform float uHdrScale;
uniform sampler2D uTaskRefractionTex;
uniform int uHasRefractionTex;
uniform vec2 uFboSize;
uniform float uRefractionPx;
uniform float uRefractionStrength;
uniform int uTaskVisualDebug;     // 0=NORMAL, 1=GEOMETRY_MAGENTA, 2=ALPHA_HEATMAP
uniform float uLayerAlphaCapOverride; // <=0 uses frozen default cap; >0 tightens cap for Tab 2 V2.2
uniform int uDirectDisplayLdr;    // 0=legacy HDR FBO, 1=Tab 2 direct sRGB LDR display soft tone-fit
uniform int uUseV2Material;
uniform float uLayerSpeed;
uniform float uLoopElapsedSec;
uniform sampler2D uLayerRgbaTex;
uniform sampler2D uLayerNormalTex;
uniform sampler2D uLayerFlowTex;

vec3 alphaToHeatmap(float a) {
    float t = clamp(a / 0.52, 0.0, 1.0);
    vec3 c0 = vec3(0.05, 0.14, 0.65);
    vec3 c1 = vec3(0.05, 0.78, 0.92);
    vec3 c2 = vec3(0.96, 0.88, 0.14);
    vec3 c3 = vec3(0.98, 0.16, 0.12);
    if (t < 0.33) return mix(c0, c1, t / 0.33);
    if (t < 0.66) return mix(c1, c2, (t - 0.33) / 0.33);
    return mix(c2, c3, (t - 0.66) / 0.34);
}

void main() {
    float s = vArcU.x;
    float u = vArcU.y;

    if (uRevealProgress <= 0.001 && uCoverageComplete < 0.5) discard;

    // P0-1: When real coverageComplete is true (left>=0.999 && right>=0.999), revealMask = 1.0 everywhere
    // so KF06 has ZERO top-center endpoint clipping! During KF02-KF05 keep exact smooth progressive front.
    float revealMask;
    if (uCoverageComplete > 0.5) {
        revealMask = 1.0;
    } else {
        float frontSoft = 0.028;
        revealMask = 1.0 - smoothstep(uRevealProgress - frontSoft, uRevealProgress + 0.006, s);
    }
    if (revealMask < 0.004) discard;

    // G1 GEOMETRY_MAGENTA: opaque magenta proves real GPU perimeter geometry coverage with real reveal gating!
    if (uTaskVisualDebug == 1) {
        fragColor = vec4(vec3(1.0, 0.0, 1.0) * uHdrScale, 1.0);
        return;
    }

    // Advancing frontier local highlight (active during KF02-KF05, smoothly vanishes once coverage == 1.0)
    float isAdvancing = (1.0 - smoothstep(0.96, 1.0, uRevealProgress)) * (1.0 - uCoverageComplete * 0.9);
    float frontBoost = exp(-pow((s - uRevealProgress) / 0.024, 2.0)) * isAdvancing;

    // Cross-section optical liquid film profile: inner meniscus dark rim + translucent body + outer refractive edge
    float innerSoft = smoothstep(0.0, 0.12, u);
    float outerSoft = 1.0 - smoothstep(0.74, 1.0, u);
    float bodyEnv = innerSoft * outerSoft;
    float liquidEdge = exp(-pow((u - 0.16) / 0.11, 2.0)) + exp(-pow((u - 0.80) / 0.12, 2.0)) * 0.80;
    float darkMeniscusRim = exp(-pow((u - 0.08) / 0.08, 2.0)) * 0.70 + exp(-pow((u - 0.86) / 0.09, 2.0)) * 0.55;

    // Asynchronous non-uniform internal water ripples along arc length (independent of revealProgress!)
    float flowA = 0.5 + 0.5 * sin(s * 36.0 - uFlowTime * 3.1 + uSideSign * 0.5 + u * 3.8);
    float flowB = 0.5 + 0.5 * sin(s * 62.0 - uFlowTime * 4.6 - u * 4.5);
    float shimmer = 0.45 * flowA + 0.55 * flowB;
    float crestU = 0.42 + 0.14 * sin(s * 18.0 - uFlowTime * 2.1 + uSideSign * 0.9);
    float liquidCrest = exp(-pow((u - crestU) / 0.14, 2.0)) * bodyEnv;

    vec3 col;
    float alpha;
    float alphaCap;
    if (uUseV2Material == 1) {
        // V2 textures are authored across one closed photo perimeter; each half-strip carries
        // its runtime-derived closed-loop coordinate, never the locked master's pixel geometry.
        vec2 baseUv = vec2(vLoopU, u);
        vec2 flowVector = texture(uLayerFlowTex, baseUv).rg * 2.0 - 1.0;
        vec3 normalVector = texture(uLayerNormalTex, baseUv).rgb * 2.0 - 1.0;
        normalVector = normalize(vec3(normalVector.xy * 0.38, max(normalVector.z, 0.24)));
        float layerTime = uFlowTime * uLayerSpeed;
        vec2 animatedUv = vec2(
            fract(vLoopU - layerTime * 0.018 + flowVector.x * 0.012),
            clamp(u + flowVector.y * 0.018 + sin(layerTime + vLoopU * 18.8496) * 0.004, 0.0, 1.0)
        );
        vec4 source = texture(uLayerRgbaTex, animatedUv);
        vec3 lightDir = normalize(vec3(-0.32, 0.48, 0.82));
        float normalLight = max(dot(normalVector, lightDir), 0.0);
        float specular = pow(normalLight, 18.0) * (0.28 + 0.72 * shimmer);

        // Two fronts split from the real top-center join and keep advancing in opposite
        // directions after KF06. Wrapped distance keeps the positions continuous at q=0/1.
        float frontA = fract(0.5 + uLoopElapsedSec * uLayerSpeed * 0.035);
        float frontB = fract(0.5 - uLoopElapsedSec * uLayerSpeed * 0.035);
        float distA = abs(fract(vLoopU - frontA + 0.5) - 0.5);
        float distB = abs(fract(vLoopU - frontB + 0.5) - 0.5);
        float loopPulse = max(exp(-pow(distA / 0.012, 2.0)), exp(-pow(distB / 0.012, 2.0)))
            * uCoverageComplete;

        if (uLayerKind == 0) {
            col = source.rgb * (0.88 + 0.18 * normalLight)
                + vec3(0.42, 0.78, 1.0) * (specular * 0.34 + loopPulse * 0.14)
                + vec3(0.48, 0.74, 0.90) * frontBoost * 0.10;
            alpha = source.a;
            alphaCap = 0.30;
        } else if (uLayerKind == 1) {
            float plume = 0.72 + 0.28 * (0.5 + 0.5 * sin(vLoopU * 37.6991 - layerTime * 1.1 + flowVector.x * 3.0));
            col = source.rgb * (0.82 + 0.22 * normalLight)
                + vec3(0.16, 0.46, 0.28) * (specular * 0.20 + loopPulse * 0.08);
            alpha = source.a * plume;
            alphaCap = 0.16;
        } else {
            // L3 keeps the sparse V2 gold marks and adds two narrow, asynchronous, gated
            // strands whose changing separation creates local branch, merge and fade behavior.
            float branchA = vLoopU * 25.1327 - layerTime * 1.55 + flowVector.x * 2.0;
            float branchB = vLoopU * 37.6991 + layerTime * 1.08 - flowVector.y * 2.0;
            float strandA = exp(-pow((u - (0.34 + 0.18 * sin(branchA))) / 0.024, 2.0));
            float strandB = exp(-pow((u - (0.66 + 0.15 * sin(branchB))) / 0.022, 2.0));
            float branchGate = smoothstep(-0.25, 0.55, sin(vLoopU * 25.1327 - layerTime * 1.3));
            float branchAlpha = max(strandA, strandB) * branchGate * 0.15;
            alpha = max(source.a, branchAlpha);
            alphaCap = 0.28;
            col = mix(vec3(0.96, 0.70, 0.36), source.rgb, step(0.012, source.a));
            col += vec3(0.48, 0.32, 0.12) * (specular * 0.25 + loopPulse * 0.11 + frontBoost * 0.08);
        }
    } else if (uLayerKind == 0) {
        // L1: High-contrast translucent sapphire-ice liquid film (strict frozen cap <= 0.38!)
        // Deep sapphire-cyan core + dark meniscus rim + bright specular liquid crest ensures crisp daylight visibility!
        vec3 deepSapphire = vec3(0.04, 0.44, 0.90);
        vec3 brightIce    = vec3(0.68, 1.32, 1.62);
        vec3 darkRefract  = vec3(0.01, 0.18, 0.38);
        vec3 frontGold    = vec3(1.62, 1.26, 0.54);
        col = mix(deepSapphire, brightIce, 0.24 + 0.42 * shimmer + 0.44 * liquidCrest);
        col = mix(col, darkRefract, clamp(darkMeniscusRim * 0.28, 0.0, 0.32));
        col += vec3(0.46, 1.06, 1.42) * liquidEdge * 0.34;
        col += frontGold * frontBoost * 0.72;
        alpha = (0.16 + 0.20 * shimmer) * bodyEnv + 0.22 * liquidEdge + 0.14 * liquidCrest + frontBoost * 0.28;
        alphaCap = 0.38;
    } else if (uLayerKind == 1) {
        // L2: Rich emerald-green aquatic kelp veil (strict frozen cap <= 0.24, asynchronous slow plumes!)
        float plumeGate = smoothstep(0.24, 0.82, 0.5 + 0.5 * sin(s * 14.0 - uFlowTime * 1.15 + uSideSign * 1.7));
        vec3 emeraldDeep = vec3(0.015, 0.42, 0.22);
        vec3 jadeLight   = vec3(0.18, 0.92, 0.52);
        col = mix(emeraldDeep, jadeLight, 0.26 + 0.74 * flowA);
        col = mix(col, vec3(0.008, 0.18, 0.12), clamp(darkMeniscusRim * 0.22, 0.0, 0.25));
        alpha = (0.10 + 0.14 * vWave) * bodyEnv * (0.48 + 0.52 * plumeGate);
        alphaCap = 0.24;
    } else {
        // L3: Slender traveling champagne-gold refractive filament (strict frozen cap <= 0.50!)
        float travelPacket = pow(clamp(0.5 + 0.5 * sin(s * 18.0 - uFlowTime * 2.55 + uSideSign * 1.2), 0.0, 1.0), 2.6);
        float fineCore = exp(-pow((u - (0.48 + 0.16 * sin(s * 28.0 - uFlowTime * 2.2))) / 0.20, 2.0));
        vec3 amberGold = vec3(1.56, 1.08, 0.30);
        vec3 champagne = vec3(2.08, 1.72, 0.96);
        col = mix(amberGold, champagne, 0.35 + 0.65 * flowB);
        alpha = fineCore * (0.18 + 0.34 * travelPacket + 0.34 * frontBoost);
        alphaCap = 0.50;
    }

    // P0-6: Apply revealMask and PerimeterWaterLightMaterial scale, then strictly enforce frozen alphaCap!
    alpha = clamp(alpha * revealMask * uLayerAlphaScale, 0.0, alphaCap);
    if (uLayerAlphaCapOverride > 0.0) {
        alpha = min(alpha, uLayerAlphaCapOverride);
    }

    // G1 ALPHA_HEATMAP: opaque visualization of real per-layer alpha after cap enforcement
    if (uTaskVisualDebug == 2) {
        fragColor = vec4(alphaToHeatmap(alpha) * uHdrScale, 1.0);
        return;
    }

    if (alpha < 0.002) discard;

    // P0-4: Screen-space liquid-film refraction sampling separate refraction snapshot (photo border + background!)
    if (uHasRefractionTex > 0 && uRefractionStrength > 0.001) {
        vec2 safeFbo = max(uFboSize, vec2(1.0));
        vec2 baseUv = clamp(gl_FragCoord.xy / safeFbo, vec2(0.002), vec2(0.998));
        // Refract inward near inner photo edge and outward near outer crest for genuine curved meniscus lens feel
        float lensProfile = (u - 0.35) * 2.0;
        vec2 nDir = normalize(vNormalDir + vec2(1e-5, 0.0));
        if (uUseV2Material == 1) {
            vec2 materialUv = vec2(vLoopU, u);
            vec3 materialNormal = texture(uLayerNormalTex, materialUv).rgb * 2.0 - 1.0;
            nDir = normalize(nDir + materialNormal.xy * 0.18);
        }
        vec2 refrUv = clamp(baseUv + nDir * (lensProfile * uRefractionPx) / safeFbo, vec2(0.002), vec2(0.998));
        float invHdr = 1.0 / max(uHdrScale, 1e-4);
        vec3 baseBg = texture(uTaskRefractionTex, baseUv).rgb * invHdr;
        vec3 refrBg = texture(uTaskRefractionTex, refrUv).rgb * invHdr;
        vec3 refrDelta = (refrBg - baseBg) * uRefractionStrength;
        col = max(col + refrDelta / max(alpha, 0.08), vec3(0.0));
    }

    vec3 outRgb = col * uHdrScale;
    if (uDirectDisplayLdr == 1) {
        // Soft display-referred highlight fit into [0, 1] sRGB without harsh channel clipping
        outRgb = clamp(outRgb / (vec3(1.0) + outRgb * 0.45), vec3(0.0), vec3(1.0));
    }
    fragColor = vec4(outRgb, alpha);
}
"""

  const val TASK_SPARKLE_VERT = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec2 aPosNdc;
layout(location = 1) in float aSizePx;
layout(location = 2) in float aAlpha;
layout(location = 3) in float aKind;   // 0=CrystalDroplet, 1=WarmGoldStarburst, 2=CyanRippleHalo
layout(location = 4) in float aSpriteIndex;
layout(location = 5) in vec2 aQuadUv; // Used strictly when uUseQuad == 1

uniform int uUseQuad;

out float vAlpha;
out float vKind;
out float vSpriteIndex;
out vec2 vQuadUv;

void main() {
    vAlpha = aAlpha;
    vKind = aKind;
    vSpriteIndex = aSpriteIndex;
    vQuadUv = aQuadUv;
    gl_Position = vec4(aPosNdc, 0.0, 1.0);
    gl_PointSize = (uUseQuad == 0) ? aSizePx : 1.0;
}
"""

  const val TASK_SPARKLE_FRAG = """#version 300 es
precision highp float;
precision highp int;

in float vAlpha;
in float vKind;
in float vSpriteIndex;
in vec2 vQuadUv;

out vec4 fragColor;

uniform int uUseQuad;
uniform float uHdrScale;
uniform int uTaskVisualDebug; // 0=NORMAL, 1=GEOMETRY_MAGENTA, 2=ALPHA_HEATMAP
uniform int uDirectDisplayLdr; // 0=legacy HDR FBO, 1=Tab 2 direct sRGB LDR display soft tone-fit
uniform int uUseDropletAtlas;
uniform sampler2D uDropletAtlas;

vec3 alphaToHeatmap(float a) {
    float t = clamp(a / 0.90, 0.0, 1.0);
    vec3 c0 = vec3(0.05, 0.14, 0.65);
    vec3 c1 = vec3(0.05, 0.78, 0.92);
    vec3 c2 = vec3(0.96, 0.88, 0.14);
    vec3 c3 = vec3(0.98, 0.16, 0.12);
    if (t < 0.33) return mix(c0, c1, t / 0.33);
    if (t < 0.66) return mix(c1, c2, (t - 0.33) / 0.33);
    return mix(c2, c3, (t - 0.66) / 0.34);
}

void main() {
    if (vAlpha < 0.005) discard;
    vec2 uv = (uUseQuad == 1) ? vQuadUv : gl_PointCoord;
    vec2 p = uv * 2.0 - 1.0;
    float r = length(p);
    if (r > 1.0) discard;

    if (uTaskVisualDebug == 1) {
        fragColor = vec4(vec3(1.0, 0.0, 1.0) * uHdrScale, 1.0);
        return;
    }

    vec3 col;
    float alpha;
    if (vKind < 0.5) {
        if (uUseDropletAtlas == 1 && vSpriteIndex >= 0.0) {
            // V2 atlas is a 4x4 RGBA sprite sheet; inset UVs prevent neighboring-cell bleed.
            float cellIndex = clamp(floor(vSpriteIndex + 0.5), 0.0, 15.0);
            vec2 cell = vec2(mod(cellIndex, 4.0), floor(cellIndex / 4.0));
            vec2 localUv = mix(vec2(0.035), vec2(0.965), uv);
            vec2 atlasUv = (cell + localUv) / 4.0;
            vec4 droplet = texture(uDropletAtlas, atlasUv);
            col = droplet.rgb;
            alpha = droplet.a * vAlpha;
        } else {
            // Legacy non-V2 crystal droplet material, retained for the separate frozen Orb scene.
            float rim = exp(-pow((r - 0.72) / 0.15, 2.0));
            float darkOutline = exp(-pow((r - 0.84) / 0.10, 2.0));
            float body = (1.0 - smoothstep(0.0, 0.85, r)) * 0.22;
            float glint = exp(-pow(length(p - vec2(-0.26, -0.28)) / 0.20, 2.0));
            col = vec3(0.16, 0.74, 1.06) * (body + rim * 0.78)
                + vec3(1.62, 1.72, 1.80) * glint * 0.88;
            col = mix(col, vec3(0.02, 0.22, 0.40), darkOutline * 0.30);
            alpha = clamp((body + rim * 0.68 + glint * 0.82) * vAlpha, 0.0, 0.82);
        }
    } else if (vKind < 1.5) {
        // P0-7: Compact KF02 Contact / KF05 Top-Join localized warm champagne-gold optical focus
        float core = exp(-r * r * 14.0);
        float halo = exp(-r * r * 4.2) * 0.38;
        float crossH = exp(-abs(p.y) * 16.0) * exp(-abs(p.x) * 3.4);
        float crossV = exp(-abs(p.x) * 16.0) * exp(-abs(p.y) * 3.4);
        float rays = (crossH + crossV) * 0.62;
        col = vec3(1.98, 1.56, 0.78) * (core + rays) + vec3(1.48, 0.98, 0.32) * halo;
        alpha = clamp((core + halo + rays * 0.74) * vAlpha, 0.0, 0.92);
    } else {
        // Compact cyan aquatic contact ripple ring
        float ring = exp(-pow((r - 0.66) / 0.16, 2.0));
        float mist = exp(-r * r * 4.2) * 0.28;
        col = vec3(0.16, 0.84, 1.24) * (ring + mist);
        alpha = clamp((ring * 0.58 + mist) * vAlpha, 0.0, 0.55);
    }

    if (uTaskVisualDebug == 2) {
        fragColor = vec4(alphaToHeatmap(alpha) * uHdrScale, 1.0);
        return;
    }

    if (alpha < 0.005) discard;
    vec3 outRgb = col * uHdrScale;
    if (uDirectDisplayLdr == 1) {
        outRgb = clamp(outRgb / (vec3(1.0) + outRgb * 0.45), vec3(0.0), vec3(1.0));
    }
    fragColor = vec4(outRgb, alpha);
}
"""
}
