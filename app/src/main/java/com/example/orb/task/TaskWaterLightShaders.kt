package com.example.orb.task

/**
 * OpenGL ES 3.00 shaders for Tab 2 (`TASK_PERIMETER_DEMO`):
 *  1. `TASK_BG_VERT / TASK_BG_FRAG` — Luminous shallow-aqua studio background + animated pool caustics beneath Orb
 *  2. `PHOTO_QUAD_VERT / PHOTO_QUAD_FRAG` — Original fishing photo clipped by exact rounded-rect SDF + crisp white hairline
 *  3. `TASK_RIBBON_VERT / TASK_RIBBON_FRAG` — 4 independent Task Energy liquid ribbons (icy-blue main + 3 flanking wisps)
 *  4. `PERIMETER_RIBBON_VERT / PERIMETER_RIBBON_FRAG` — Unified arc-length L1/L2/L3 perimeter liquid waterlight
 *  5. `TASK_SPARKLE_VERT / TASK_SPARKLE_FRAG` — KF02 contact glow, KF05 top-join glint, and L4 sparse crystal droplets
 *
 * Strictly enforces `#version 300 es`, `precision highp float`, and zero inverted `smoothstep` calls.
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
vec3 studioSrgbToLinearPreComp(vec3 srgb) {
    vec3 lin = pow(clamp(srgb, vec3(0.0), vec3(0.98)), vec3(2.2));
    // Boost upper studio tones gently below bloom threshold (1.32) so daylight aqua stays crisp & airy
    return lin * 1.18;
}

void main() {
    float aspect = uResolution.x / max(uResolution.y, 1.0);

    // 1. Airy light-cyan aquatic studio backdrop matching FROZEN_6KF_VISUAL_SPEC
    vec3 topIce     = vec3(0.84, 0.965, 0.985);
    vec3 midAqua    = vec3(0.66, 0.895, 0.945);
    vec3 lowCyan    = vec3(0.48, 0.835, 0.915);
    vec3 deepCorner = vec3(0.42, 0.785, 0.885);

    float yGrad = clamp(vUv.y, 0.0, 1.0); // 0 = bottom, 1 = top
    vec3 baseSrgb = mix(lowCyan, midAqua, smoothstep(0.08, 0.56, yGrad));
    baseSrgb = mix(baseSrgb, topIce, smoothstep(0.56, 0.96, yGrad));

    // Soft side luminance framing (brighter mist near top-left & center-bottom, gentle teal edge vignette)
    float sideDist = abs(vUv.x - 0.5) * 2.0;
    baseSrgb = mix(baseSrgb, deepCorner, smoothstep(0.58, 1.15, sideDist) * 0.24);

    // Soft diagonal sun-shaft veil across upper-left and behind the photo
    float shaft = exp(-pow(((vUv.x - 0.36) * 0.8 + (vUv.y - 0.62) * 0.55) * 2.3, 2.0));
    baseSrgb += vec3(0.08, 0.09, 0.08) * shaft;

    // 2. Animated water-surface caustic reflection pool directly beneath the shared AI Orb
    vec2 poolDelta = vec2((vUv.x - uOrbCenterUv.x) * aspect, (vUv.y - (uOrbCenterUv.y - uOrbRadiusNormH * 0.38)) * 2.35);
    float poolDist = length(poolDelta);
    float poolMask = exp(-poolDist * poolDist * 7.5) * (1.0 - smoothstep(0.28, 0.55, vUv.y));

    vec2 cUv1 = vec2(vUv.x * 7.5 * aspect + uTime * 0.22, vUv.y * 14.0 - uTime * 0.18);
    vec2 cUv2 = vec2(vUv.x * 10.5 * aspect - uTime * 0.16, vUv.y * 19.0 + uTime * 0.21);
    float n1 = noise2(cUv1);
    float n2 = noise2(cUv2);
    float causticNet = pow(clamp(1.0 - abs(n1 - n2) * 2.4, 0.0, 1.0), 3.2);
    float rippleRing = 0.5 + 0.5 * sin(poolDist * 34.0 - uTime * 2.4 + n1 * 2.5);
    float ringGlow = pow(rippleRing, 3.0) * exp(-poolDist * 4.2);

    vec3 col = studioSrgbToLinearPreComp(baseSrgb);
    // Crisp shimmering pool caustics + delicate warm-gold specular floor reflection directly under Orb
    col += vec3(0.22, 0.68, 0.82) * causticNet * poolMask * 0.52;
    col += vec3(0.32, 0.78, 0.88) * ringGlow * poolMask * 0.34;
    float floorGold = exp(-pow(poolDist * 3.8, 2.0)) * (1.0 - smoothstep(0.18, 0.36, vUv.y));
    col += vec3(0.42, 0.34, 0.14) * floorGold * 0.26;

    // Keep strictly below Bloom bright-pass threshold (1.32) so studio background never hazes
    col = min(col, vec3(1.18));
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

    // Map expanded quad UV back into exact photo [0..1] UV (OpenGL V=0 bottom -> flip Y for Android top-down texture)
    vec2 photoUv = (localPx + halfPhoto) / max(uPhotoSizePx, vec2(1.0));
    vec2 sampleUv = vec2(clamp(photoUv.x, 0.0, 1.0), clamp(1.0 - photoUv.y, 0.0, 1.0));

    float insideAlpha = 1.0 - smoothstep(-0.75, 0.75, sdf);
    // Crisp 1.8px white-frost hairline border matching the frozen overview
    float hairline = exp(-pow((sdf - 0.35) / 1.15, 2.0)) * 0.76;
    // Soft ambient cyan-slate elevation shadow outside photo edge
    float outerShadow = (1.0 - smoothstep(0.0, uPadPx * 0.92, max(sdf, 0.0))) * (1.0 - insideAlpha) * 0.16;

    vec3 photoSrgb = texture(uPhotoTex, sampleUv).rgb;
    vec3 photoLin = photoSrgbToPostCompLinear(photoSrgb);

    vec3 hairlineCol = vec3(0.92, 0.98, 1.02);
    vec3 shadowCol = vec3(0.02, 0.14, 0.20);

    vec3 rgb = mix(shadowCol, photoLin, insideAlpha);
    rgb = mix(rgb, hairlineCol, hairline * (1.0 - insideAlpha * 0.35));
    float alpha = clamp(max(insideAlpha, max(hairline, outerShadow)), 0.0, 1.0);

    if (alpha < 0.004) discard;
    fragColor = vec4(rgb * uHdrScale, alpha);
}
"""

  const val TASK_RIBBON_VERT = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec2 aPosNdc;
layout(location = 1) in vec2 aUv;       // x = s in [0, 1], y = cross u in [0, 1]
layout(location = 2) in float aAlpha;
layout(location = 3) in float aTwist;

uniform float uFlowTime;
uniform float uStreamPhase;

out vec2 vUv;
out float vAlpha;
out float vTwist;

void main() {
    vUv = aUv;
    vAlpha = aAlpha;
    vTwist = aTwist;

    // Gentle living lateral aquatic sway zeroed at Orb base (s=0) and Photo Bottom Center (s=1)
    float swayEnv = sin(aUv.x * 3.14159265);
    float wave = sin(aUv.x * 7.5 - uFlowTime * 2.8 + uStreamPhase) * 0.011
               + cos(aUv.x * 12.0 - uFlowTime * 4.1 - uStreamPhase) * 0.004;
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

out vec4 fragColor;

uniform int uStreamKind;      // 0=MainIce, 1=LeftCyan, 2=RightFine, 3=EmeraldVeil
uniform float uReveal;        // Effective front progress along this stream [0..1]
uniform float uFlowTime;
uniform float uFlowSpeed;
uniform float uHdrScale;

void main() {
    float s = vUv.x;
    float u = vUv.y;

    // Progressive smooth reveal from Orb top anchor upward to stream frontier
    float tipSoft = 0.095;
    float revealMask = 1.0 - smoothstep(uReveal - tipSoft, uReveal + 0.015, s);
    if (revealMask < 0.004) discard;

    // Smooth root fade at Orb exit and graceful taper at trailing/advancing tip
    float rootFade = smoothstep(0.0, 0.09, s);
    float endFade = 1.0 - smoothstep(0.86, 1.0, s);

    // Cross-section optical refraction profile: translucent liquid core + crisp Fresnel bright edges!
    float distEdge = abs(u - 0.5) * 2.0; // 0 = center, 1 = outer edge
    float edgeMask = 1.0 - smoothstep(0.76, 1.0, distEdge);
    float fresnelRim = pow(clamp(distEdge, 0.0, 1.0), 2.3) * edgeMask;
    float coreBody = (1.0 - distEdge * distEdge) * edgeMask;

    // Continuous internal liquid flow bands & champagne-gold micro-filament
    float flowCoord = s * 9.5 - uFlowTime * uFlowSpeed * 2.4;
    float liquidSilk = 0.5 + 0.5 * sin(flowCoord + sin(u * 6.283 + uFlowTime * 1.4) * 1.2);
    float fineRipple = 0.5 + 0.5 * sin(s * 22.0 - uFlowTime * uFlowSpeed * 4.2 + u * 5.0);
    float goldWaveU = 0.5 + 0.24 * sin(s * 6.5 - uFlowTime * 2.1 + vTwist);
    float goldThread = exp(-pow((u - goldWaveU) / 0.095, 2.0)) * (0.45 + 0.55 * fineRipple);

    // Luminous leading front crescent while stream is advancing (softens once uReveal >= 0.99)
    float advancing = 1.0 - smoothstep(0.92, 1.0, uReveal);
    float frontTipGlow = exp(-pow((s - uReveal) / 0.065, 2.0)) * advancing;

    vec3 col;
    float alpha;
    if (uStreamKind == 3) {
        // Organic emerald-green aquatic kelp veil
        vec3 deepEmerald = vec3(0.06, 0.56, 0.36);
        vec3 jadeSheen   = vec3(0.32, 0.92, 0.64);
        vec3 goldAccent  = vec3(1.45, 1.18, 0.58);
        col = mix(deepEmerald, jadeSheen, 0.35 + 0.65 * liquidSilk);
        col += vec3(0.28, 0.85, 0.72) * fresnelRim * 0.55;
        col += goldAccent * goldThread * 0.22;
        alpha = (coreBody * 0.32 + fresnelRim * 0.56 + goldThread * 0.18) * vAlpha;
    } else if (uStreamKind == 0) {
        // Wide translucent icy-blue main stream with refractive crystal edges + delicate gold silk
        vec3 iceBody   = vec3(0.18, 0.78, 1.02);
        vec3 crystalRim = vec3(0.76, 1.24, 1.44);
        vec3 warmGold  = vec3(1.62, 1.28, 0.62);
        col = mix(iceBody, crystalRim, 0.30 + 0.50 * liquidSilk + 0.45 * fresnelRim);
        col += warmGold * (goldThread * 0.42 + frontTipGlow * 0.55);
        alpha = (coreBody * 0.34 + fresnelRim * 0.68 + goldThread * 0.24 + frontTipGlow * 0.35) * vAlpha;
    } else {
        // Accompanying icy-cyan left curl & fine right stream (delicate translucent liquid)
        vec3 cyanBody = vec3(0.20, 0.82, 1.05);
        vec3 edgeGlint = vec3(0.78, 1.26, 1.42);
        vec3 warmGold = vec3(1.50, 1.20, 0.56);
        col = mix(cyanBody, edgeGlint, 0.35 + 0.50 * fineRipple + 0.40 * fresnelRim);
        col += warmGold * (goldThread * 0.30 + frontTipGlow * 0.35);
        alpha = (coreBody * 0.28 + fresnelRim * 0.60 + goldThread * 0.20) * vAlpha;
    }

    alpha = clamp(alpha * revealMask * rootFade * endFade, 0.0, 0.82);
    if (alpha < 0.004) discard;
    fragColor = vec4(col * uHdrScale, alpha);
}
"""

  const val PERIMETER_RIBBON_VERT = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec2 aPosNdc;
layout(location = 1) in vec2 aArcU;      // x = sNorm in [0,1], y = crossU in [0,1] (0=inner photo edge, 1=outer)
layout(location = 2) in vec2 aNormalNdc; // Outward unit normal scaled into NDC per px

uniform float uFlowTime;
uniform int uLayerKind;  // 0=L1 IceBlue, 1=L2 EmeraldVeil, 2=L3 GoldFilament
uniform float uSideSign; // -1.0 Left, +1.0 Right
uniform float uPhotoWidthPx;

out vec2 vArcU;
out float vWave;

void main() {
    vArcU = aArcU;
    float s = aArcU.x;
    float u = aArcU.y;

    // Continuous organic liquid undulation strictly OUTWARD (u == 0 inner edge stays locked on boundary!)
    // Zero mechanical uniform-width breathing; multi-frequency traveling waves keep KF06 alive!
    float w1 = sin(s * 24.0 - uFlowTime * 2.35 + uSideSign * 0.85);
    float w2 = cos(s * 41.0 - uFlowTime * 3.40 - uSideSign * 1.40);
    float organicWave = 0.5 + 0.5 * (0.65 * w1 + 0.35 * w2);
    vWave = organicWave;

    float flutterPx = 0.0;
    if (uLayerKind == 0) {
        flutterPx = u * uPhotoWidthPx * 0.0048 * (w1 * 0.65 + w2 * 0.35);
    } else if (uLayerKind == 1) {
        flutterPx = u * uPhotoWidthPx * 0.0085 * (w1 * 0.72 + w2 * 0.28);
    } else {
        flutterPx = u * uPhotoWidthPx * 0.0032 * w2;
    }

    // Never push inward past the photo boundary (clamp negative outward flutter tightly)
    float safeOutPx = max(flutterPx, -u * 1.2);
    vec2 finalNdc = aPosNdc + aNormalNdc * safeOutPx;
    gl_Position = vec4(finalNdc, 0.0, 1.0);
}
"""

  const val PERIMETER_RIBBON_FRAG = """#version 300 es
precision highp float;
precision highp int;

in vec2 vArcU;
in float vWave;

out vec4 fragColor;

uniform int uLayerKind;       // 0=L1 IceBlue, 1=L2 EmeraldVeil, 2=L3 GoldFilament
uniform float uRevealProgress; // [0..1] along this side (0=bottom center, 1=top center)
uniform float uFlowTime;
uniform float uSideSign;
uniform float uCoverageComplete;
uniform float uHdrScale;

void main() {
    float s = vArcU.x;
    float u = vArcU.y;

    if (uRevealProgress <= 0.001) discard;

    // Strict arc-length reveal gate: NEVER activates ahead of uRevealProgress!
    float frontSoft = 0.028;
    float revealMask = 1.0 - smoothstep(uRevealProgress - frontSoft, uRevealProgress + 0.006, s);
    if (revealMask < 0.004) discard;

    // Advancing frontier local highlight (active during KF02-KF05, smoothly vanishes once coverage == 1.0)
    float isAdvancing = (1.0 - smoothstep(0.96, 1.0, uRevealProgress)) * (1.0 - uCoverageComplete * 0.9);
    float frontBoost = exp(-pow((s - uRevealProgress) / 0.024, 2.0)) * isAdvancing;

    // Cross-section transparency: crisp liquid glass rim + airy translucent body (KF04 restrained standard!)
    float innerSoft = smoothstep(0.0, 0.14, u);
    float outerSoft = 1.0 - smoothstep(0.72, 1.0, u);
    float bodyEnv = innerSoft * outerSoft;
    float liquidEdge = exp(-pow((u - 0.18) / 0.12, 2.0)) + exp(-pow((u - 0.78) / 0.13, 2.0)) * 0.75;

    // Continuous non-uniform internal water ripples along arc length (decoupled from revealProgress!)
    float flowA = 0.5 + 0.5 * sin(s * 36.0 - uFlowTime * 3.1 + uSideSign * 0.5 + u * 3.8);
    float flowB = 0.5 + 0.5 * sin(s * 62.0 - uFlowTime * 4.6 - u * 4.5);
    float shimmer = 0.45 * flowA + 0.55 * flowB;

    vec3 col;
    float alpha;
    if (uLayerKind == 0) {
        // L1: Translucent icy-blue liquid body (alpha 0.12..0.38 restrained KF04 standard)
        vec3 deepIce   = vec3(0.14, 0.76, 1.02);
        vec3 brightIce = vec3(0.74, 1.24, 1.42);
        vec3 frontGold = vec3(1.58, 1.26, 0.62);
        col = mix(deepIce, brightIce, 0.25 + 0.50 * shimmer + 0.35 * liquidEdge);
        col += frontGold * frontBoost * 0.65;
        alpha = (0.14 + 0.22 * shimmer) * bodyEnv + 0.24 * liquidEdge + frontBoost * 0.32;
        alpha = clamp(alpha, 0.0, 0.48);
    } else if (uLayerKind == 1) {
        // L2: Emerald-green aquatic kelp-like veil (alpha 0.08..0.24, organic intermittent plumes!)
        float plumeGate = smoothstep(0.28, 0.82, 0.5 + 0.5 * sin(s * 14.0 - uFlowTime * 1.15 + uSideSign * 1.7));
        vec3 emeraldDeep = vec3(0.05, 0.58, 0.34);
        vec3 jadeLight   = vec3(0.30, 0.94, 0.60);
        col = mix(emeraldDeep, jadeLight, 0.30 + 0.70 * flowA);
        alpha = (0.09 + 0.15 * vWave) * bodyEnv * (0.45 + 0.55 * plumeGate);
        alpha = clamp(alpha, 0.0, 0.28);
    } else {
        // L3: Traveling champagne-gold refractive filament (alpha 0.15..0.50, sparse luminous streaks!)
        float travelPacket = pow(clamp(0.5 + 0.5 * sin(s * 18.0 - uFlowTime * 2.55 + uSideSign * 1.2), 0.0, 1.0), 3.0);
        float fineCore = exp(-pow((u - (0.48 + 0.16 * sin(s * 28.0 - uFlowTime * 2.2))) / 0.19, 2.0));
        vec3 goldCore = vec3(1.68, 1.34, 0.68);
        vec3 champagne = vec3(1.92, 1.66, 1.12);
        col = mix(goldCore, champagne, flowB);
        alpha = fineCore * (0.14 + 0.34 * travelPacket + 0.36 * frontBoost);
        alpha = clamp(alpha, 0.0, 0.54);
    }

    alpha *= revealMask;
    if (alpha < 0.004) discard;
    fragColor = vec4(col * uHdrScale, alpha);
}
"""

  const val TASK_SPARKLE_VERT = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec2 aPosNdc;
layout(location = 1) in float aSizePx;
layout(location = 2) in float aAlpha;
layout(location = 3) in float aKind; // 0=CrystalDroplet, 1=WarmGoldStarburst, 2=CyanRippleHalo

out float vAlpha;
out float vKind;

void main() {
    vAlpha = aAlpha;
    vKind = aKind;
    gl_Position = vec4(aPosNdc, 0.0, 1.0);
    gl_PointSize = aSizePx;
}
"""

  const val TASK_SPARKLE_FRAG = """#version 300 es
precision highp float;
precision highp int;

in float vAlpha;
in float vKind;

out vec4 fragColor;

uniform float uHdrScale;

void main() {
    if (vAlpha < 0.005) discard;
    vec2 p = gl_PointCoord * 2.0 - 1.0;
    float r = length(p);
    if (r > 1.0) discard;

    vec3 col;
    float alpha;
    if (vKind < 0.5) {
        // L4: Translucent crystal water droplet (refractive rim + tiny top-left glint)
        float rim = exp(-pow((r - 0.72) / 0.16, 2.0));
        float body = (1.0 - smoothstep(0.0, 0.85, r)) * 0.24;
        float glint = exp(-pow(length(p - vec2(-0.26, -0.28)) / 0.22, 2.0));
        col = vec3(0.42, 0.96, 1.18) * (body + rim * 0.72) + vec3(1.55, 1.65, 1.72) * glint * 0.85;
        alpha = clamp((body + rim * 0.65 + glint * 0.78) * vAlpha, 0.0, 0.82);
    } else if (vKind < 1.5) {
        // KF02 Contact / KF05 Top-Join localized warm champagne-gold optical starburst
        float core = exp(-r * r * 11.0);
        float halo = exp(-r * r * 3.2) * 0.42;
        float crossH = exp(-abs(p.y) * 14.0) * exp(-abs(p.x) * 3.0);
        float crossV = exp(-abs(p.x) * 14.0) * exp(-abs(p.y) * 3.0);
        float rays = (crossH + crossV) * 0.58;
        col = vec3(1.92, 1.54, 0.82) * (core + rays) + vec3(1.42, 0.98, 0.36) * halo;
        alpha = clamp((core + halo + rays * 0.72) * vAlpha, 0.0, 0.92);
    } else {
        // Delicate cyan aquatic contact ripple ring
        float ring = exp(-pow((r - 0.64) / 0.18, 2.0));
        float mist = exp(-r * r * 3.6) * 0.32;
        col = vec3(0.28, 0.96, 1.24) * (ring + mist);
        alpha = clamp((ring * 0.56 + mist) * vAlpha, 0.0, 0.55);
    }

    if (alpha < 0.005) discard;
    fragColor = vec4(col * uHdrScale, alpha);
}
"""
}
