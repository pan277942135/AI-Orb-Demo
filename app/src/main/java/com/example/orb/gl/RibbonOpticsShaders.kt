package com.example.orb.gl

/**
 * V2 Full Visual Restoration GLSL ES 3.00 Shaders for:
 * - Sleek double-edged transparent liquid-glass orbital ribbons (style 0)
 * - Gossamer translucent olive-emerald botanical seaweed silk ribbons (style 1)
 * - Hair-fine fwidth anti-aliased optical glass filaments (style 2)
 * - Jewel-like pinpoint 4-point HDR starburst flares (never giant blown-out white circles!)
 * - Crisp 3D ray-shaded crystal water droplets & suspended micro-motes
 * - Linear HDR Bloom (threshold = 1.32) + Saturation-preserving ACES Filmic + True sRGB 2.2 OETF
 */
object RibbonOpticsShaders {

  private const val GLSL_INV_SMOOTHSTEP = """
float invSmoothstep(float lo, float hi, float x) {
    return 1.0 - smoothstep(lo, hi, x);
}
"""

  // -------------------------------------------------------------------------
  // 1. 3D ORBITING RIBBONS (CRYSTAL GLASS + GOSSAMER SEAWEED SILK + AA FILAMENTS)
  // -------------------------------------------------------------------------
  const val RIBBON_VERT = """#version 300 es
precision highp float;
layout(location = 0) in vec3 aCenter;
layout(location = 1) in vec3 aOffset;
layout(location = 2) in vec3 aNormal;
layout(location = 3) in vec2 aUv;

uniform mat4 uModel;
uniform mat4 uView;
uniform mat4 uProj;
uniform float uRibbonTime;
uniform float uRibbonWidth;
uniform float uWaveAmp;
uniform float uWaveFreq;
uniform float uRibbonPhase;

out vec3 vWorldPos;
out vec3 vViewPos;
out vec3 vWorldNormal;
out vec2 vUv;

void main() {
    float u = aUv.x;
    float t = uRibbonTime;

    float angle = u * 6.28318530718 * uWaveFreq;
    float waveN = sin(angle + t * 0.85 + uRibbonPhase) * uWaveAmp;
    float waveR = cos(angle * 0.5 - t * 0.58 + uRibbonPhase * 1.7) * (uWaveAmp * 0.50);

    vec3 radialDir = normalize(aCenter + vec3(0.0001));
    vec3 localPos = aCenter
                  + aOffset * uRibbonWidth
                  + normalize(aNormal) * waveN
                  + radialDir * waveR;

    vec4 worldPos = uModel * vec4(localPos, 1.0);
    vec4 viewPos = uView * worldPos;

    vWorldPos = worldPos.xyz;
    vViewPos = viewPos.xyz;
    vWorldNormal = normalize(mat3(uModel) * aNormal);
    vUv = aUv;
    gl_Position = uProj * viewPos;
}
"""

  const val RIBBON_FRAG = """#version 300 es
precision highp float;

in vec3 vWorldPos;
in vec3 vViewPos;
in vec3 vWorldNormal;
in vec2 vUv;

out vec4 fragColor;

uniform vec3 uCameraPos;
uniform float uRibbonTime;
uniform float uRibbonTransparency;
uniform float uEnergyIntensity;
uniform float uOverallIntensity;
uniform float uHdrScale;
uniform int uRibbonStyle;      // 0 = Crystal Liquid-Glass, 1 = Gossamer Seaweed Silk, 2 = Hair-Fine AA Filament
uniform int uClipPass;         // 0 = Back Hemisphere (behind orb), 1 = Front Hemisphere (in front of orb)
uniform float uOrbCenterViewZ;
uniform vec3 uColorA;
uniform vec3 uColorB;
uniform float uFlowSpeed;
uniform float uPulseOffset;
uniform int uDepthDebug;       // 0 = Normal, 1 = Depth/Hemisphere occlusion visualization

$GLSL_INV_SMOOTHSTEP

float periodicWave(float u, float freq, float phase) {
    return 0.5 + 0.5 * sin(u * 6.28318530718 * freq + phase);
}

void main() {
    if (uClipPass == 0 && vViewPos.z >= uOrbCenterViewZ) discard;
    if (uClipPass == 1 && vViewPos.z <  uOrbCenterViewZ) discard;

    if (uDepthDebug == 1) {
        // Back hemisphere = cool blue, Front hemisphere = warm gold for immediate occlusion verification
        vec3 dbg = (uClipPass == 0) ? vec3(0.10, 0.45, 1.0) : vec3(1.0, 0.72, 0.15);
        fragColor = vec4(dbg * uHdrScale, 0.85);
        return;
    }

    float u = vUv.x;
    float v = vUv.y;
    float absV = abs(v);
    float aaWidth = max(fwidth(v) * 1.35, 0.035);

    vec3 N = normalize(vWorldNormal);
    vec3 V = normalize(uCameraPos - vWorldPos);
    if (dot(N, V) < 0.0) N = -N;
    float NdotV = clamp(dot(N, V), 0.0, 1.0);
    float fresnel = pow(1.0 - NdotV, 2.4);

    float t = uRibbonTime;

    // Spatial ice-cyan (left) -> molten champagne-gold (right) chromatic transition matching reference
    float spatialMix = smoothstep(-0.85, 0.82, vWorldPos.x + 0.22 * sin(u * 6.2831853 + t * 0.22 + uPulseOffset));
    vec3 baseColor = mix(uColorA, uColorB, spatialMix);

    float pulsePos1 = fract(t * uFlowSpeed * 0.11 + uPulseOffset);
    float pulsePos2 = fract(t * uFlowSpeed * 0.11 + uPulseOffset + 0.50);
    float d1 = min(abs(u - pulsePos1), 1.0 - abs(u - pulsePos1));
    float d2 = min(abs(u - pulsePos2), 1.0 - abs(u - pulsePos2));
    float pulse1 = exp(-d1 * d1 * 68.0);
    float pulse2 = exp(-d2 * d2 * 92.0);
    float energyPulse = (pulse1 + pulse2 * 0.65) * uEnergyIntensity;

    float streak1 = periodicWave(u, 3.0, -t * 1.25 + v * 3.2);
    float streak2 = periodicWave(u, 7.0,  t * 0.95 - v * 5.4);
    float flowTex = 0.56 + 0.28 * streak1 + 0.16 * streak2;

    vec3 col = vec3(0.0);
    float alpha = 0.0;

    if (uRibbonStyle == 0) {
        // Style 0: Sleek transparent double-edged blown liquid-glass ribbon
        float outerAA = invSmoothstep(1.0 - aaWidth, 1.0, absV);
        float twinWall = exp(-pow((absV - 0.80) / 0.105, 2.0)) * outerAA;
        float innerThread = exp(-pow((absV - 0.22) / 0.11, 2.0)) * (0.38 + 0.62 * streak1) * outerAA;
        float glassBody = invSmoothstep(0.54, 0.96, absV) * outerAA;

        vec3 darkGlassBody = baseColor * 0.16;
        vec3 luminousRim   = baseColor * 1.28;
        vec3 specularTint  = mix(baseColor * 1.35, vec3(1.18, 1.06, 0.84), 0.34);

        vec3 H1 = normalize(V + normalize(vec3(-0.56, 0.72, 0.58)));
        vec3 H2 = normalize(V + normalize(vec3(0.84, 0.22, 0.44)));
        float spec = pow(max(dot(N, H1), 0.0), 54.0) * 0.88 + pow(max(dot(N, H2), 0.0), 42.0) * 0.78;

        col = darkGlassBody * (0.48 + 0.52 * flowTex)
            + luminousRim * (twinWall * 0.96 + innerThread * 0.36 + fresnel * 0.26)
            + specularTint * (spec * 0.52 + twinWall * 0.20)
            + mix(baseColor * 1.55, vec3(1.42, 1.22, 0.84), 0.36) * energyPulse * (0.52 + 0.65 * twinWall);

        // Crystal-clear dark body, crisp bright twin glass walls (prevents muddy/opaque ribbon look)
        alpha = clamp(
            (glassBody * (0.14 + 0.18 * fresnel) + twinWall * 0.74 + innerThread * 0.24 + energyPulse * 0.36) * uRibbonTransparency,
            0.0, 0.90
        );
    } else if (uRibbonStyle == 1) {
        // Style 1: Gossamer translucent olive-emerald botanical seaweed silk ribbon (V10)
        float softEdge = invSmoothstep(0.18, 1.0, absV);
        float tipFade = smoothstep(0.0, 0.14, u) * invSmoothstep(0.82, 1.0, u);
        // Fine longitudinal botanical veins gently undulating along the translucent leaf blade
        float vein1 = 0.5 + 0.5 * sin(v * 22.0 + sin(u * 12.56637 + t * 0.55) * 1.4);
        float vein2 = 0.5 + 0.5 * sin(v * 38.0 - cos(u * 18.8495 - t * 0.42) * 1.1);
        float silkVeins = 0.46 + 0.32 * vein1 + 0.22 * vein2;
        float silkFold = 0.42 + 0.58 * smoothstep(-0.55, 0.75, sin(v * 3.2 + u * 6.28318 * 1.5 - t * 0.48));
        float delicateRim = exp(-pow((absV - 0.78) / 0.12, 2.0)) * softEdge;

        vec3 shadowMoss = baseColor * 0.26;
        vec3 backlitLeaf = mix(baseColor * 1.10, vec3(0.42, 0.66, 0.09), 0.46);

        col = mix(shadowMoss, backlitLeaf, silkFold * silkVeins) * (0.78 + 0.42 * fresnel);
        col += vec3(0.56, 0.76, 0.18) * (delicateRim * 0.34 + energyPulse * 0.18);

        // Whisper-sheer gossamer opacity (0.14..0.54) so background & crystal ribbons show through!
        alpha = clamp(
            softEdge * tipFade * (0.15 + 0.30 * silkVeins * silkFold + 0.18 * fresnel + 0.14 * delicateRim) * uRibbonTransparency,
            0.0, 0.58
        );
    } else {
        // Style 2: Hair-fine delicate secondary optical glass thread (never competes with 5 Residents!)
        float sigma = max(0.24, aaWidth * 1.25);
        float thread = exp(-pow(absV / sigma, 2.0)) * invSmoothstep(1.0 - aaWidth, 1.0, absV);
        col = mix(baseColor * 1.15, vec3(1.12, 1.04, 0.88), 0.26) * (0.72 + 0.28 * flowTex);
        col += mix(baseColor * 1.55, vec3(1.38, 1.20, 0.88), 0.35) * energyPulse * 0.62;
        alpha = clamp(thread * (0.34 + 0.30 * energyPulse) * uRibbonTransparency, 0.0, 0.72);
    }

    float depthFade = (uClipPass == 0) ? 0.74 : 1.0;
    col *= depthFade * (0.84 + 0.20 * uOverallIntensity);

    fragColor = vec4(col * uHdrScale, alpha);
}
"""

  // -------------------------------------------------------------------------
  // 2. JEWEL-LIKE PINPOINT HDR ENERGY STARBURSTS (ZERO GIANT WHITE BALLS!)
  // -------------------------------------------------------------------------
  const val ENERGY_HIGHLIGHT_VERT = """#version 300 es
precision highp float;
layout(location = 0) in vec2 aQuadPos;
layout(location = 1) in vec2 aUv;

uniform mat4 uView;
uniform mat4 uProj;
uniform vec3 uWorldCenter;
uniform float uSize;

out vec2 vUv;

void main() {
    vec4 viewCenter = uView * vec4(uWorldCenter, 1.0);
    vec4 viewPos = viewCenter + vec4(aQuadPos * uSize, 0.0, 0.0);
    vUv = aUv;
    gl_Position = uProj * viewPos;
}
"""

  const val ENERGY_HIGHLIGHT_FRAG = """#version 300 es
precision highp float;

in vec2 vUv;
out vec4 fragColor;

uniform vec3 uFlareColor;
uniform float uIntensity;
uniform float uAngle;
uniform float uHdrScale;

void main() {
    vec2 p = (vUv - 0.5) * 2.0;
    float r = length(p);
    if (r > 1.0) discard;

    float ca = cos(uAngle);
    float sa = sin(uAngle);
    vec2 pr = vec2(p.x * ca - p.y * sa, p.x * sa + p.y * ca);

    // Pinpoint jewel-like center + razor-thin 4-point optical diffraction spikes (saturated hue halo!)
    float hotCore   = exp(-r * r * 115.0);
    float tightHalo = exp(-r * r * 16.0) * 0.62;
    float softAura  = pow(max(1.0 - r, 0.0), 3.4) * 0.22;

    float spikeH  = exp(-abs(pr.y) * 44.0) * exp(-abs(pr.x) * 3.0) * 0.88;
    float spikeV  = exp(-abs(pr.x) * 44.0) * exp(-abs(pr.y) * 3.0) * 0.88;
    vec2 pd = vec2(pr.x + pr.y, pr.x - pr.y) * 0.70710678;
    float spikeD1 = exp(-abs(pd.y) * 58.0) * exp(-abs(pd.x) * 5.2) * 0.38;
    float spikeD2 = exp(-abs(pd.x) * 58.0) * exp(-abs(pd.y) * 5.2) * 0.38;

    float shape = hotCore * 1.95 + tightHalo + softAura + spikeH + spikeV + spikeD1 + spikeD2;

    // Keep white strictly in the microscopic core; halo and spikes stay richly colored gold/ice-cyan!
    vec3 coreWhite = vec3(1.22, 1.14, 0.96);
    vec3 col = mix(uFlareColor * 1.38, coreWhite, clamp(hotCore * 0.92, 0.0, 1.0));
    col *= shape * uIntensity;

    float alpha = clamp(shape * uIntensity * 0.78, 0.0, 1.0);
    fragColor = vec4(col * uHdrScale, alpha);
}
"""

  // -------------------------------------------------------------------------
  // 3. CRISP 3D RAY-SHADED CRYSTAL WATER DROPLETS & MICRO-MOTES (V13)
  // -------------------------------------------------------------------------
  const val BUBBLE_PARTICLE_VERT = """#version 300 es
precision highp float;
layout(location = 0) in vec2 aQuadPos;
layout(location = 1) in vec2 aUv;

uniform mat4 uView;
uniform mat4 uProj;
uniform vec3 uWorldCenter;
uniform float uSize;

out vec2 vUv;

void main() {
    vec4 viewCenter = uView * vec4(uWorldCenter, 1.0);
    vec4 viewPos = viewCenter + vec4(aQuadPos * uSize, 0.0, 0.0);
    vUv = aUv;
    gl_Position = uProj * viewPos;
}
"""

  const val BUBBLE_PARTICLE_FRAG = """#version 300 es
precision highp float;

in vec2 vUv;
out vec4 fragColor;

uniform int uMode;
uniform vec3 uTint;
uniform float uAlpha;
uniform float uHdrScale;

$GLSL_INV_SMOOTHSTEP

void main() {
    vec2 p = (vUv - 0.5) * 2.0;
    float r2 = dot(p, p);
    if (r2 > 1.0) discard;

    if (uMode == 0) {
        float nz = sqrt(max(1.0 - r2, 0.0));
        vec3 N = vec3(p.x, p.y, nz);
        vec3 V = vec3(0.0, 0.0, 1.0);
        float NdotV = clamp(N.z, 0.0, 1.0);
        float fresnel = pow(1.0 - NdotV, 2.6);
        float sharpRim = pow(1.0 - NdotV, 5.4);

        vec3 keyDir = normalize(vec3(-0.52, 0.68, 0.65));
        vec3 rimDir = normalize(vec3(0.62, -0.62, 0.46));
        float specPin = pow(max(dot(N, normalize(V + keyDir)), 0.0), 86.0);
        float specLow = pow(max(dot(N, normalize(V + rimDir)), 0.0), 18.0) * 0.58;

        // Dark transparent glass interior + crisp thin refractive rim + single bright specular pinpoint
        vec3 darkInterior = vec3(0.004, 0.026, 0.038);
        vec3 rimCol = mix(vec3(0.45, 0.96, 1.12), vec3(1.22, 0.92, 0.42), smoothstep(-0.3, 0.6, p.x + p.y));

        vec3 col = darkInterior * 0.65
                 + rimCol * (fresnel * 0.95 + sharpRim * 1.15)
                 + uTint * specLow * 0.72
                 + vec3(1.55, 1.48, 1.35) * specPin * 1.55;

        float edgeAA = invSmoothstep(0.84, 1.0, sqrt(r2));
        float alpha = clamp((0.36 + fresnel * 0.78 + specPin * 0.92) * edgeAA * uAlpha, 0.0, 0.96);
        fragColor = vec4(col * uHdrScale, alpha);
    } else {
        float r = sqrt(r2);
        float glow = exp(-r * r * 6.5);
        vec3 col = mix(uTint * 1.05, vec3(1.15, 1.10, 0.98), glow * 0.42) * glow * 1.10;
        fragColor = vec4(col * uHdrScale, glow * uAlpha);
    }
}
"""

  // -------------------------------------------------------------------------
  // 4. OPTICAL POST-PROCESSING (LINEAR HDR BLOOM -> ACES FILMIC -> sRGB 2.2 OETF)
  // -------------------------------------------------------------------------
  const val POST_QUAD_VERT = """#version 300 es
precision highp float;
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
out vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

  const val BLOOM_EXTRACT_FRAG = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uSceneTex;
uniform float uThreshold;
uniform float uKnee;
uniform float uHdrScale;

void main() {
    vec3 col = texture(uSceneTex, vUv).rgb / max(uHdrScale, 1e-4);
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    float soft = clamp((luma - uThreshold + uKnee) / max(2.0 * uKnee, 1e-4), 0.0, 1.0);
    soft = soft * soft * (3.0 - 2.0 * soft);
    float contrib = max(luma - uThreshold, 0.0) + soft * uKnee * 0.28;
    vec3 bright = col * (contrib / max(luma, 1e-4));
    fragColor = vec4(min(bright, vec3(3.2)) * uHdrScale, 1.0);
}
"""

  const val BLOOM_BLUR_FRAG = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uSourceTex;
uniform vec2 uTexelStep;

void main() {
    vec3 result = texture(uSourceTex, vUv).rgb * 0.22702703;
    result += texture(uSourceTex, vUv + uTexelStep * 1.38461538).rgb * 0.31621622;
    result += texture(uSourceTex, vUv - uTexelStep * 1.38461538).rgb * 0.31621622;
    result += texture(uSourceTex, vUv + uTexelStep * 3.23076923).rgb * 0.07027027;
    result += texture(uSourceTex, vUv - uTexelStep * 3.23076923).rgb * 0.07027027;
    fragColor = vec4(result, 1.0);
}
"""

  const val COMPOSITE_FRAG = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uSceneTex;
uniform sampler2D uBloomTex;
uniform float uBloomStrength;
uniform float uOverallIntensity;
uniform float uChromaticAmount;
uniform float uHdrScale;

$GLSL_INV_SMOOTHSTEP

vec3 acesFilm(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

void main() {
    vec2 centered = vUv - 0.5;
    float dist2 = dot(centered, centered);
    float invHdr = 1.0 / max(uHdrScale, 1e-4);

    vec2 caShift = centered * dist2 * uChromaticAmount;
    vec3 sceneCol;
    sceneCol.r = texture(uSceneTex, clamp(vUv + caShift, 0.0, 1.0)).r * invHdr;
    sceneCol.g = texture(uSceneTex, vUv).g * invHdr;
    sceneCol.b = texture(uSceneTex, clamp(vUv - caShift, 0.0, 1.0)).b * invHdr;

    vec3 bloomCol = texture(uBloomTex, vUv).rgb * invHdr;

    // Combine linear HDR scene + restrained high-threshold bloom
    vec3 hdr = (sceneCol + bloomCol * uBloomStrength * 0.72) * (0.90 + 0.18 * uOverallIntensity);

    // Filmic tone mapping in linear space
    vec3 mapped = acesFilm(hdr);

    // Subtle filmic color richness preservation
    float lum = dot(mapped, vec3(0.2126, 0.7152, 0.0722));
    mapped = clamp(mix(vec3(lum), mapped, 1.10), 0.0, 1.0);

    // Gentle optical vignette (preserves deep underwater teal corners instead of crushing to black)
    float vig = invSmoothstep(0.24, 0.96, dist2 * 1.45);
    mapped *= mix(0.78, 1.0, vig);

    // True Linear -> sRGB 2.2 OETF display encoding (eliminates black crush on Android displays!)
    vec3 srgb = pow(max(mapped, vec3(0.0)), vec3(1.0 / 2.2));

    fragColor = vec4(clamp(srgb, 0.0, 1.0), 1.0);
}
"""
}
