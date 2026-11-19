package com.example.orb.gl

/**
 * V2 High-Fidelity GLSL ES 3.00 Shaders for:
 * - Alpha-blended refractive liquid-glass Ice-Cyan & Fiery Amber-Gold ribbons (zero cyan+orange white-out!)
 * - Silky feathered jade-emerald / yellow-green drapery ribbons
 * - Screen-space anti-aliased (`fwidth`) gossamer crystal filaments
 * - High-impact HDR travelling starburst flares (white-hot core + warm amber/cyan halo + diffraction spikes)
 * - Crisp 3D ray-shaded water-droplet glass bubbles
 * - Linear HDR-decoded high-threshold Bloom + saturation-preserving ACES Filmic Composite
 */
object RibbonOpticsShaders {

  // -------------------------------------------------------------------------
  // 1. 3D ORBITING RIBBONS (REFRACTIVE CRYSTAL GLASS + SILKY JADE + AA FILAMENTS)
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
uniform float uTime;
uniform float uRibbonWidth;
uniform float uWaveAmp;
uniform float uWaveFreq;
uniform float uRibbonSpeed;
uniform float uRibbonPhase;

out vec3 vWorldPos;
out vec3 vViewPos;
out vec3 vWorldNormal;
out vec2 vUv;

void main() {
    float u = aUv.x;
    float t = uTime * uRibbonSpeed;

    float angle = u * 6.28318530718 * uWaveFreq;
    float waveN = sin(angle + t * 0.90 + uRibbonPhase) * uWaveAmp;
    float waveR = cos(angle * 0.5 - t * 0.62 + uRibbonPhase * 1.7) * (uWaveAmp * 0.52);

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
uniform float uTime;
uniform float uRibbonSpeed;
uniform float uRibbonTransparency;
uniform float uEnergyIntensity;
uniform float uOverallIntensity;
uniform float uHdrScale;
uniform int uRibbonStyle;      // 0 = Crystal Glass Ice/Amber, 1 = Silky Jade-Emerald, 2 = AA Glass Filament
uniform int uClipPass;         // 0 = Back Hemisphere (behind orb), 1 = Front Hemisphere (in front of orb)
uniform float uOrbCenterViewZ;
uniform vec3 uColorA;
uniform vec3 uColorB;
uniform float uFlowSpeed;
uniform float uPulseOffset;

float periodicWave(float u, float freq, float phase) {
    return 0.5 + 0.5 * sin(u * 6.28318530718 * freq + phase);
}

void main() {
    if (uClipPass == 0 && vViewPos.z >= uOrbCenterViewZ) discard;
    if (uClipPass == 1 && vViewPos.z <  uOrbCenterViewZ) discard;

    float u = vUv.x;
    float v = vUv.y;       // -1..1 across width
    float absV = abs(v);
    float aaWidth = max(fwidth(v) * 1.35, 0.04);

    vec3 N = normalize(vWorldNormal);
    vec3 V = normalize(uCameraPos - vWorldPos);
    if (dot(N, V) < 0.0) N = -N;
    float NdotV = clamp(dot(N, V), 0.0, 1.0);
    float fresnel = pow(1.0 - NdotV, 2.2);

    float t = uTime * uRibbonSpeed;

    // Spatial + smoothly animated color transition (e.g. Ice-Blue on left -> Fiery Amber-Gold on right)
    float spatialMix = smoothstep(-0.95, 0.85, vWorldPos.x + 0.25 * sin(u * 6.2831853 + t * 0.24 + uPulseOffset));
    vec3 baseColor = mix(uColorA, uColorB, spatialMix);

    // Travelling localized HDR energy pulse along the ribbon arc
    float pulsePos1 = fract(t * uFlowSpeed * 0.13 + uPulseOffset);
    float pulsePos2 = fract(t * uFlowSpeed * 0.13 + uPulseOffset + 0.50);
    float d1 = min(abs(u - pulsePos1), 1.0 - abs(u - pulsePos1));
    float d2 = min(abs(u - pulsePos2), 1.0 - abs(u - pulsePos2));
    float pulse1 = exp(-d1 * d1 * 54.0);
    float pulse2 = exp(-d2 * d2 * 76.0);
    float energyPulse = (pulse1 + pulse2 * 0.72) * uEnergyIntensity;

    // Flowing longitudinal liquid glass refraction streaks
    float streak1 = periodicWave(u, 3.0, -t * 1.35 + v * 3.4);
    float streak2 = periodicWave(u, 7.0,  t * 1.05 - v * 5.8);
    float flowTex = 0.58 + 0.26 * streak1 + 0.16 * streak2;

    vec3 col = vec3(0.0);
    float alpha = 0.0;

    if (uRibbonStyle == 0) {
        // Style 0: Thick Sculpted Liquid-Glass Ribbon (alpha-blended so Amber Gold NEVER bleaches to white!)
        float outerAA = 1.0 - smoothstep(1.0 - aaWidth, 1.0, absV);
        // Hollow refractive glass tube cross-section: translucent dark-tinted glass core + sharp twin glass walls
        float twinWall = exp(-pow((absV - 0.78) / 0.13, 2.0)) * outerAA;
        float innerThread = exp(-pow((absV - 0.24) / 0.14, 2.0)) * (0.45 + 0.55 * streak1) * outerAA;
        float glassBody = smoothstep(0.95, 0.50, absV) * outerAA;

        // Deep jewel-tinted glass body prevents cyan bleed-through from washing out warm amber gold
        vec3 deepGlassBody = baseColor * 0.36;
        vec3 luminousRim   = baseColor * 1.42;
        vec3 specularTint  = mix(baseColor * 1.35, vec3(1.15, 1.06, 0.88), 0.38);

        vec3 H1 = normalize(V + normalize(vec3(-0.56, 0.72, 0.58)));
        vec3 H2 = normalize(V + normalize(vec3(0.84, 0.22, 0.44)));
        float spec = pow(max(dot(N, H1), 0.0), 42.0) * 0.95 + pow(max(dot(N, H2), 0.0), 34.0) * 0.85;

        col = deepGlassBody * (0.55 + 0.45 * flowTex)
            + luminousRim * (twinWall * 0.95 + innerThread * 0.44 + fresnel * 0.32)
            + specularTint * (spec * 0.58 + twinWall * 0.26)
            + mix(baseColor * 1.65, vec3(1.45, 1.28, 0.95), 0.45) * energyPulse * (0.68 + 0.75 * twinWall);

        // Alpha is high along glass walls & energy pulses, gracefully translucent across hollow center
        alpha = clamp(
            (glassBody * (0.28 + 0.22 * fresnel) + twinWall * 0.78 + innerThread * 0.32 + energyPulse * 0.48) * uRibbonTransparency,
            0.0, 0.95
        );
    } else if (uRibbonStyle == 1) {
        // Style 1: Feathered Translucent Silky Jade-Emerald / Yellow-Green Ribbon
        float softEdge = smoothstep(1.0, 0.34, absV);
        // Fine longitudinal silk fibers with gentle organic wave
        float fiber1 = 0.5 + 0.5 * sin(v * 26.0 + sin(u * 12.56637 + t * 0.65) * 1.6);
        float fiber2 = 0.5 + 0.5 * sin(v * 44.0 - cos(u * 18.8495 - t * 0.50) * 1.2);
        float silkFibers = 0.52 + 0.30 * fiber1 + 0.18 * fiber2;
        float silkFold = 0.55 + 0.45 * smoothstep(-0.6, 0.8, sin(v * 3.8 + u * 6.28318 * 2.0 - t * 0.55));
        float edgeSheen = exp(-pow((absV - 0.72) / 0.16, 2.0)) * softEdge;

        vec3 shadowJade = baseColor * 0.42;
        vec3 sunlitSilk = mix(baseColor, vec3(0.76, 0.88, 0.24), 0.48);

        col = mix(shadowJade, sunlitSilk, silkFold * silkFibers) * (0.85 + 0.35 * fresnel);
        col += vec3(0.84, 0.95, 0.42) * (edgeSheen * 0.36 + energyPulse * 0.38);

        alpha = clamp(softEdge * (0.26 + 0.40 * silkFibers * silkFold + 0.20 * fresnel) * uRibbonTransparency, 0.0, 0.80);
    } else {
        // Style 2: Screen-Space Anti-Aliased Fine Crystalline Filament (zero jagged stair-stepping!)
        float sigma = max(0.32, aaWidth * 1.4);
        float thread = exp(-pow(absV / sigma, 2.0)) * (1.0 - smoothstep(1.0 - aaWidth, 1.0, absV));
        col = mix(baseColor * 1.25, vec3(1.18, 1.12, 0.98), 0.32) * (0.80 + 0.35 * flowTex);
        col += mix(baseColor * 1.8, vec3(1.5, 1.35, 1.05), 0.45) * energyPulse * 0.95;
        alpha = clamp(thread * (0.52 + 0.44 * energyPulse) * uRibbonTransparency, 0.0, 0.90);
    }

    float depthFade = (uClipPass == 0) ? 0.78 : 1.0;
    col *= depthFade * (0.82 + 0.24 * uOverallIntensity);

    fragColor = vec4(col * uHdrScale, alpha);
}
"""

  // -------------------------------------------------------------------------
  // 2. HIGH-IMPACT TRAVELLING HDR ENERGY STARBURSTS (WARM AMBER-GOLD & ICE-CYAN)
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

    // Dazzling compact white-hot optical core + fiery saturated inner glow + wide soft halo
    float hotCore   = exp(-r * r * 42.0);
    float richHalo  = exp(-r * r * 8.5) * 0.85;
    float wideAura  = pow(max(1.0 - r, 0.0), 2.4) * 0.36;

    // Crisp 4-point primary + diagonal optical lens diffraction spikes
    float spikeH  = exp(-abs(pr.y) * 26.0) * exp(-abs(pr.x) * 2.6) * 0.92;
    float spikeV  = exp(-abs(pr.x) * 26.0) * exp(-abs(pr.y) * 2.6) * 0.92;
    vec2 pd = vec2(pr.x + pr.y, pr.x - pr.y) * 0.70710678;
    float spikeD1 = exp(-abs(pd.y) * 36.0) * exp(-abs(pd.x) * 4.2) * 0.52;
    float spikeD2 = exp(-abs(pd.x) * 36.0) * exp(-abs(pd.y) * 4.2) * 0.52;

    float shape = hotCore * 2.15 + richHalo + wideAura + spikeH + spikeV + spikeD1 + spikeD2;

    // Preserve fiery amber-gold / ice-cyan saturation in the halo while keeping center white-hot
    vec3 coreWhite = vec3(1.15, 1.08, 0.96);
    vec3 col = mix(uFlareColor * 1.45, coreWhite, clamp(hotCore * 0.95 + (spikeH + spikeV) * 0.22, 0.0, 1.0));
    col *= shape * uIntensity;

    float alpha = clamp(shape * uIntensity * 0.85, 0.0, 1.0);
    fragColor = vec4(col * uHdrScale, alpha);
}
"""

  // -------------------------------------------------------------------------
  // 3. CRISP 3D RAY-SHADED WATER-DROPLET BUBBLES & LUMINOUS MICRO-MOTES
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

uniform int uMode; // 0 = 3D Crystal Water Droplet Bubble, 1 = Soft Luminous Micro-Mote
uniform vec3 uTint;
uniform float uAlpha;
uniform float uHdrScale;

void main() {
    vec2 p = (vUv - 0.5) * 2.0;
    float r2 = dot(p, p);
    if (r2 > 1.0) discard;

    if (uMode == 0) {
        float nz = sqrt(max(1.0 - r2, 0.0));
        vec3 N = vec3(p.x, p.y, nz);
        vec3 V = vec3(0.0, 0.0, 1.0);
        float NdotV = clamp(N.z, 0.0, 1.0);
        float fresnel = pow(1.0 - NdotV, 2.2);
        float sharpRim = pow(1.0 - NdotV, 4.5);

        // Dark refractive droplet belly + crisp bright crescent Fresnel rim + razor specular pinpoint
        vec3 keyDir = normalize(vec3(-0.52, 0.68, 0.65));
        vec3 rimDir = normalize(vec3(0.62, -0.62, 0.46));
        float specPin = pow(max(dot(N, normalize(V + keyDir)), 0.0), 64.0);
        float specLow = pow(max(dot(N, normalize(V + rimDir)), 0.0), 14.0) * 0.68;

        vec3 darkInterior = vec3(0.015, 0.075, 0.105);
        vec3 rimCol = mix(vec3(0.62, 0.96, 1.05), vec3(1.15, 0.94, 0.58), smoothstep(-0.3, 0.6, p.x + p.y));

        vec3 col = darkInterior * 0.72
                 + rimCol * (fresnel * 1.15 + sharpRim * 1.25)
                 + uTint * specLow * 0.85
                 + vec3(1.65, 1.58, 1.48) * specPin * 1.65;

        float edgeAA = smoothstep(1.0, 0.84, sqrt(r2));
        float alpha = clamp((0.46 + fresnel * 0.75 + specPin * 0.95) * edgeAA * uAlpha, 0.0, 0.98);
        fragColor = vec4(col * uHdrScale, alpha);
    } else {
        float r = sqrt(r2);
        float glow = exp(-r * r * 5.2);
        vec3 col = mix(uTint * 1.2, vec3(1.2, 1.16, 1.08), glow * 0.52) * glow * 1.45;
        fragColor = vec4(col * uHdrScale, glow * uAlpha);
    }
}
"""

  // -------------------------------------------------------------------------
  // 4. OPTICAL POST-PROCESSING (LINEAR HDR DECODE + HIGH-THRESHOLD BLOOM + ACES)
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
    // Decode linear HDR before thresholding so RGBA8 fallback behaves identically to RGBA16F
    vec3 col = texture(uSceneTex, vUv).rgb / max(uHdrScale, 1e-4);
    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    float soft = clamp((luma - uThreshold + uKnee) / max(2.0 * uKnee, 1e-4), 0.0, 1.0);
    soft = soft * soft * (3.0 - 2.0 * soft);
    float contrib = max(luma - uThreshold, 0.0) + soft * uKnee * 0.35;
    vec3 bright = col * (contrib / max(luma, 1e-4));
    fragColor = vec4(min(bright, vec3(3.8)) * uHdrScale, 1.0);
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

// Narkowicz ACES Filmic Tone Mapping with subtle jewel-saturation preservation
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

    // Subtle optical radial chromatic dispersion toward outer field
    vec2 caShift = centered * dist2 * uChromaticAmount;
    vec3 sceneCol;
    sceneCol.r = texture(uSceneTex, clamp(vUv + caShift, 0.0, 1.0)).r * invHdr;
    sceneCol.g = texture(uSceneTex, vUv).g * invHdr;
    sceneCol.b = texture(uSceneTex, clamp(vUv - caShift, 0.0, 1.0)).b * invHdr;

    vec3 bloomCol = texture(uBloomTex, vUv).rgb * invHdr;

    vec3 hdr = (sceneCol + bloomCol * uBloomStrength * 0.88) * (0.88 + 0.24 * uOverallIntensity);

    // Filmic tone mapping
    vec3 mapped = acesFilm(hdr);

    // Gentle jewel vibrance boost so amber-gold, emerald-jade & turquoise stay lush like studio photography
    float lum = dot(mapped, vec3(0.2126, 0.7152, 0.0722));
    mapped = mix(vec3(lum), mapped, 1.14);

    // Subtle atmospheric vignette framing the orb
    float vig = smoothstep(0.92, 0.20, dist2 * 1.65);
    mapped *= mix(0.64, 1.0, vig);

    fragColor = vec4(clamp(mapped, 0.0, 1.0), 1.0);
}
"""
}
