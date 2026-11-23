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
  // 1. 3D ORGANIC RESIDENT RIBBON SURFACES:
  //    - GPU Rodrigues local twist roll + progressive tip-weighted flexible aquatic bending
  //    - Exact spherical-front-surface depth partition for seamless FBO glass refraction
  //    - Style 0 (C01): Crystal-Clear Cyan Liquid-Glass Water Band (readable even with Bloom OFF)
  //    - Style 1 (G01/G02): Translucent Emerald Aquatic Kelp Silk (soft alpha, curved veins, backlit)
  //    - Style 2 (A01/A02): Warm Champagne-Gold Sunlit Stream (core + soft halo + traveling glint)
  // -------------------------------------------------------------------------
  const val RIBBON_VERT = """#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec3 aCenter;
layout(location = 1) in vec3 aOffset;
layout(location = 2) in vec3 aNormal;
layout(location = 3) in vec2 aUv;
layout(location = 4) in vec4 aTangentFlex; // xyz = unit spine tangent T(s), w = flexibility weight

uniform mat4 uModel;
uniform mat4 uView;
uniform mat4 uProj;
uniform float uRibbonTime;
uniform float uCyclePhase;
uniform float uRibbonWidth;
uniform float uWaveAmp;
uniform float uWaveFreq;
uniform float uTwistWaveAmp;
uniform float uWidthBreathAmp;
uniform float uRibbonPhase;

out vec3 vWorldPos;
out vec3 vViewPos;
out vec3 vWorldNormal;
out vec2 vUv;

// Exact Rodrigues rotation of vector v around unit axis k by angleRad (preserves orthogonality!)
vec3 rotateAroundAxis(vec3 v, vec3 k, float angleRad) {
    float c = cos(angleRad);
    float s = sin(angleRad);
    return v * c + cross(k, v) * s + k * dot(k, v) * (1.0 - c);
}

void main() {
    float u = aUv.x;
    float flexW = aTangentFlex.w;
    vec3 T = normalize(aTangentFlex.xyz + vec3(1e-5, 0.0, 0.0));
    vec3 baseN = normalize(aNormal + vec3(0.0, 1e-5, 0.0));
    vec3 crossB = normalize(cross(T, baseN) + vec3(0.0, 0.0, 1e-5));

    // 1. Progressive traveling aquatic bending waves (amplitude grows smoothly toward free kelp tips!)
    float waveArg1 = u * 6.28318530718 * uWaveFreq - uCyclePhase;
    float waveArg2 = u * 6.28318530718 * (uWaveFreq * 0.62) + uCyclePhase * 0.72 + uRibbonPhase;
    float bendN = (sin(waveArg1) * 0.72 + cos(waveArg2) * 0.28) * uWaveAmp * flexW;
    float bendB = (cos(waveArg1 * 0.85 + 0.9) * 0.65 + sin(waveArg2 * 1.15) * 0.35) * (uWaveAmp * 0.62) * flexW;

    // 2. Continuous local twist roll propagating smoothly along arc length u (Rodrigues around T!)
    float dynamicTwist = (sin(u * 6.28318530718 * 1.5 - uCyclePhase * 0.85 + uRibbonPhase) * 0.68
                        + cos(u * 6.28318530718 * 2.5 + uCyclePhase * 0.55) * 0.32) * uTwistWaveAmp * flexW;

    // 3. Subtle continuous local width breathing (zero sudden widening/narrowing!)
    float widthBreath = 1.0 + sin(u * 6.28318530718 * 2.0 - uCyclePhase * 0.9 + uRibbonPhase) * uWidthBreathAmp;

    vec3 twistedOffset = rotateAroundAxis(aOffset * (uRibbonWidth * widthBreath), T, dynamicTwist);
    vec3 twistedNormal = normalize(rotateAroundAxis(baseN, T, dynamicTwist));

    vec3 localPos = aCenter
                  + twistedOffset
                  + baseN * bendN
                  + crossB * bendB;

    vec4 worldPos = uModel * vec4(localPos, 1.0);
    vec4 viewPos = uView * worldPos;

    vWorldPos = worldPos.xyz;
    vViewPos = viewPos.xyz;
    vWorldNormal = normalize(mat3(uModel) * twistedNormal);
    vUv = aUv;
    gl_Position = uProj * viewPos;
}
"""

  const val RIBBON_FRAG = """#version 300 es
precision highp float;
precision highp int;

in vec3 vWorldPos;
in vec3 vViewPos;
in vec3 vWorldNormal;
in vec2 vUv;

out vec4 fragColor;

uniform vec3 uCameraPos;
uniform float uRibbonTime;
uniform float uCyclePhase;
uniform float uRibbonPhase;
uniform float uRibbonTransparency;
uniform float uEnergyIntensity;
uniform float uOverallIntensity;
uniform float uHdrScale;
uniform int uRibbonStyle;        // 0 = Cyan Liquid Glass (C01), 1 = Green Aquatic Silk (G01/G02), 2 = Champagne Gold Filament (A01/A02)
uniform int uClipPass;           // 0 = Behind Glass Front Surface (Refracted), 1 = In Front of Glass Front Surface
uniform vec3 uOrbCenterViewPos;
uniform float uCoreShellRadius;
uniform float uIsOpen;           // 1.0 for open tapered ribbons, 0.0 for seamless closed ribbons
uniform float uOpacityScale;
uniform vec3 uColorA;
uniform vec3 uColorB;
uniform float uFlowSpeed;
uniform float uPulseOffset;
uniform int uDepthDebug;         // 0 = Normal, 1 = Depth/Hemisphere occlusion visualization
uniform vec4 uGoldOptics;        // V3.2 fitted (goldWallsGain, coreStreamGain, secThreadGain, glassBellyGain)
uniform float uGoldSpecGain;     // V3.2 fitted gold specular gain

$GLSL_INV_SMOOTHSTEP

float periodicWave(float u, float freq, float phase) {
    return 0.5 + 0.5 * sin(u * 6.28318530718 * freq + phase);
}

void main() {
    // Exact spherical-front-surface partition:
    // - Inside projected glass disc: splits right on the curved front glass dome (zero rim gap, true FBO refraction!)
    // - Outside projected glass disc: splits cleanly at uOrbCenterViewPos.z (zero double-blending!)
    vec2 dxy = vViewPos.xy - uOrbCenterViewPos.xy;
    float r2 = dot(dxy, dxy);
    float coreR2 = uCoreShellRadius * uCoreShellRadius;
    float splitZ = uOrbCenterViewPos.z + sqrt(max(coreR2 - r2, 0.0));

    if (uClipPass == 0 && vViewPos.z >= splitZ) discard;
    if (uClipPass == 1 && vViewPos.z <  splitZ) discard;

    if (uDepthDebug == 1) {
        vec3 dbg = (uClipPass == 0) ? vec3(0.10, 0.45, 1.0) : vec3(1.0, 0.72, 0.15);
        fragColor = vec4(dbg * uHdrScale, 0.85);
        return;
    }

    float u = vUv.x;
    float v = vUv.y;
    float absV = abs(v);
    float aaWidth = max(fwidth(v) * 1.35, 0.032);

    vec3 N = normalize(vWorldNormal);
    vec3 V = normalize(uCameraPos - vWorldPos);
    float facingSign = dot(N, V) < 0.0 ? -1.0 : 1.0;
    N *= facingSign;
    float NdotV = clamp(dot(N, V), 0.0, 1.0);
    float fresnel = pow(1.0 - NdotV, 2.35);

    vec3 Lkey = normalize(vec3(-0.54, 0.72, 0.48));
    vec3 Lwarm = normalize(vec3(0.84, 0.18, 0.42));
    float NdotLKey = clamp(dot(N, Lkey), 0.0, 1.0);
    float NdotLWarm = clamp(dot(N, Lwarm), 0.0, 1.0);
    vec3 Hkey = normalize(V + Lkey);
    vec3 Hwarm = normalize(V + Lwarm);

    // Continuous period-locked internal material flow (seam-free on both open & closed curves)
    float flowDir = uFlowSpeed >= 0.0 ? 1.0 : -1.0;
    float cycleNorm = fract((uCyclePhase * flowDir) / 6.28318530718 + uPulseOffset);
    float dPulse1 = min(abs(u - cycleNorm), 1.0 - abs(u - cycleNorm));
    float cycleNorm2 = fract(cycleNorm + 0.52);
    float dPulse2 = min(abs(u - cycleNorm2), 1.0 - abs(u - cycleNorm2));
    float localGlint = (exp(-dPulse1 * dPulse1 * 56.0) + exp(-dPulse2 * dPulse2 * 84.0) * 0.55)
                     * (0.35 + 0.65 * uEnergyIntensity);

    float tipFade = mix(1.0, smoothstep(0.0, 0.13, u) * invSmoothstep(0.83, 1.0, u), uIsOpen);

    vec3 col = vec3(0.0);
    float alpha = 0.0;

    if (uRibbonStyle == 0) {
        // =========================================================================
        // STYLE 0 (C01): CRYSTAL-CLEAR MULTI-WALLED ICE-BLUE LIQUID-GLASS BAND
        // Ultra-clear glass belly + razor-sharp asymmetric refractive walls +
        // 3 braided electric-cyan caustic streams + subtle lower-right warm bounce
        // =========================================================================
        float outerAA = invSmoothstep(1.0 - max(aaWidth * 1.1, 0.028), 1.0, absV);

        // Razor-crisp asymmetric refractive glass walls (eliminates milky plastic tape look!)
        float leftLight  = 0.65 + 0.72 * NdotLKey + 0.48 * fresnel;
        float rightLight = 0.55 + 0.76 * NdotLWarm + 0.50 * fresnel;
        float wallOuterL = exp(-pow((v + 0.86) / 0.046, 2.0)) * leftLight * outerAA;
        float wallOuterR = exp(-pow((v - 0.86) / 0.044, 2.0)) * rightLight * outerAA;
        float wallInnerL = exp(-pow((v + 0.56) / 0.052, 2.0)) * (0.48 + 0.52 * NdotLKey) * outerAA;
        float wallInnerR = exp(-pow((v - 0.56) / 0.050, 2.0)) * (0.44 + 0.56 * NdotLWarm) * outerAA;
        float refrWalls  = wallOuterL + wallOuterR + (wallInnerL + wallInnerR) * 0.58;

        // Multi-speed braided high-contrast internal liquid caustic streams
        float s1Center =  0.24 * sin(u * 6.2831853 * 2.0 - uCyclePhase * 1.0 + 0.4);
        float s2Center = -0.32 * cos(u * 6.2831853 * 3.0 + uCyclePhase * 1.45 - 0.8);
        float s3Center =  0.10 * sin(u * 6.2831853 * 4.0 - uCyclePhase * 0.80 + 1.9);
        float stream1 = exp(-pow((v - s1Center) / 0.064, 2.0)) * (0.45 + 0.55 * periodicWave(u, 3.0, -uCyclePhase * 1.0));
        float stream2 = exp(-pow((v - s2Center) / 0.052, 2.0)) * (0.42 + 0.58 * periodicWave(u, 5.0,  uCyclePhase * 1.45));
        float stream3 = exp(-pow((v - s3Center) / 0.038, 2.0)) * (0.40 + 0.60 * periodicWave(u, 7.0, -uCyclePhase * 1.8));
        float innerStreams = clamp(stream1 * 0.78 + stream2 * 0.64 + stream3 * 0.52, 0.0, 1.55) * outerAA;

        // Ultra-transparent dark-clear sapphire glass belly (never clouds over the orb!)
        float waterBody = invSmoothstep(0.68, 0.98, absV) * outerAA;
        float specIce = pow(max(dot(N, Hkey), 0.0), 84.0) * 1.45 + pow(max(dot(N, Hwarm), 0.0), 58.0) * 0.62;

        // Subtle warm golden reflection pickup where C01 sweeps past A02 on lower-right
        float warmBounce = smoothstep(0.15, 0.95, vWorldPos.x) * invSmoothstep(-0.65, 0.15, vWorldPos.y) * 0.42;

        vec3 deepSapphire   = vec3(0.008, 0.160, 0.460);
        vec3 cyanRefractCol = mix(uColorA, uColorB, smoothstep(-0.65, 0.65, vWorldPos.x * 0.45 + v * 0.35));
        vec3 warmGoldTint   = vec3(1.65, 0.86, 0.14);
        vec3 glassTint      = mix(cyanRefractCol, warmGoldTint, warmBounce * 0.48);
        vec3 iceSpecCol     = vec3(1.28, 1.82, 2.12); // Crosses Bloom 1.32 cleanly ONLY on crisp ridges!

        col = deepSapphire * (0.45 + 0.55 * NdotV) * waterBody
            + glassTint * (refrWalls * 1.10 + innerStreams * 0.78 + fresnel * 0.32)
            + iceSpecCol * (specIce * (0.48 + 0.72 * refrWalls) + localGlint * (0.45 + 0.68 * innerStreams));

        // Ultra-clear belly (0.06..0.11), razor-crisp luminous refractive walls & streams
        alpha = clamp(
            tipFade * (waterBody * (0.065 + 0.11 * fresnel) + refrWalls * 0.68 + innerStreams * 0.44 + localGlint * 0.30)
            * uRibbonTransparency * uOpacityScale,
            0.0, 0.92
        );
    } else if (uRibbonStyle == 1) {
        // =========================================================================
        // STYLE 1 (G01 / G02): CRISP TRANSLUCENT WARM OLIVE-KELP BOTANICAL SILK
        // Crisp anti-aliased leaf silhouette (ZERO blurry smoke!) + golden backlit leaf rim
        // + curved longitudinal botanical silk veins + strong 3D sunlit-vs-shaded fold contrast
        // =========================================================================
        float leafMask = invSmoothstep(1.0 - max(aaWidth * 1.5, 0.065), 1.0, absV);

        // Delicate crisp golden-olive backlit leaf rim right along both blade margins!
        float rimLineL = exp(-pow((v + 0.86) / 0.056, 2.0)) * leafMask;
        float rimLineR = exp(-pow((v - 0.86) / 0.056, 2.0)) * leafMask;
        float backlitRim = (rimLineL * (0.65 + 0.55 * NdotLKey) + rimLineR * (0.55 + 0.65 * NdotLWarm));

        // Fine curved longitudinal botanical silk-rib veins flowing smoothly along the blade
        float veinWarp = sin(u * 6.2831853 * 2.0 - uCyclePhase * 0.75 + uRibbonPhase) * 0.28;
        float vCurved = v + veinWarp * (1.0 - absV * 0.50);
        float veinPrimary = 0.5 + 0.5 * sin(vCurved * 18.0 + u * 6.2831853 * 1.0 - uCyclePhase * 0.55);
        float veinFine    = 0.5 + 0.5 * cos(vCurved * 34.0 - u * 6.2831853 * 2.0 + uCyclePhase * 0.42);
        float botanicalVeins = 0.72 + 0.18 * veinPrimary + 0.10 * veinFine;

        // Strong 3D silk-fold shading across the curved kelp leaf (rich deep moss vs warm sunlit olive!)
        float silkFold = smoothstep(-0.68, 0.72, sin(v * 3.1 + u * 6.2831853 * 1.5 - uCyclePhase * 0.90));
        float litSide  = clamp(0.28 + 0.72 * (NdotLKey * 0.65 + NdotLWarm * 0.55), 0.0, 1.15);

        // Warm sunlit chartreuse-olive subsurface translucency (near-zero blue — NEVER neon mint!)
        float backlitSub = pow(clamp(dot(-V, Lkey) * 0.5 + 0.5, 0.0, 1.0), 1.45) * (0.45 + 0.55 * NdotLKey)
                         + 0.34 * NdotLWarm;
        float leafSpec = pow(max(dot(N, Hkey), 0.0), 38.0) * (0.35 + 0.65 * localGlint);

        vec3 deepMossShadow = uColorA * 0.45;
        vec3 warmOliveBody  = mix(uColorA * 1.35, uColorB, 0.42 + 0.58 * silkFold);
        vec3 sunlitChartreuse = vec3(0.76, 0.88, 0.085); // Sunlit warm golden-olive translucent rim & glow

        col = mix(deepMossShadow, warmOliveBody, clamp(0.32 + 0.68 * litSide * silkFold, 0.0, 1.0)) * botanicalVeins
            + sunlitChartreuse * (clamp(backlitSub, 0.0, 1.0) * (0.24 + 0.28 * silkFold) + backlitRim * 0.68 + leafSpec * 0.42);

        // Clean, crisp translucent botanical silk leaf (visible & layered, NOT fuzzy smoke!)
        float viewThick = 0.78 + 0.40 * fresnel;
        float bodyDensity = 0.34 + 0.30 * silkFold * botanicalVeins + 0.32 * backlitRim;
        alpha = clamp(
            leafMask * tipFade * bodyDensity * viewThick * uRibbonTransparency * uOpacityScale,
            0.0, 0.82
        );
    } else {
        // =========================================================================
        // STYLE 2 (A01 / A02): BROAD SCULPTURAL MOLTEN-AMBER & CHAMPAGNE-GOLD GLASS BAND
        // Upgraded from thin yellow wire to thick hollow refractive golden crystal ribbon:
        //   [1] Crisp asymmetric dual molten-amber refractive glass walls
        //   [2] Undulating luminous champagne-gold internal liquid core stream
        //   [3] Ultra-clear warm amber glass belly + localized traveling sunlit crest
        // =========================================================================
        float outerAA = invSmoothstep(1.0 - max(aaWidth * 1.15, 0.030), 1.0, absV);

        // Dual asymmetric golden refractive glass walls (gives thick crystal ribbon volume!)
        float wallL = exp(-pow((v + 0.82) / 0.062, 2.0)) * (0.62 + 0.68 * NdotLKey + 0.42 * fresnel) * outerAA;
        float wallR = exp(-pow((v - 0.82) / 0.060, 2.0)) * (0.58 + 0.72 * NdotLWarm + 0.45 * fresnel) * outerAA;
        float goldWalls = wallL + wallR;

        // Sculpted internal liquid-gold stream meandering gently inside the crystal band
        float streamOffset = 0.16 * sin(u * 6.2831853 * 2.0 - uCyclePhase * 1.0 + uRibbonPhase);
        float widthMod = 0.82 + 0.24 * periodicWave(u, 3.0, -uCyclePhase * 0.95 + uRibbonPhase);
        float coreSigma = max(0.17 * widthMod, aaWidth * 1.25);
        float coreStream = exp(-pow((v - streamOffset) / coreSigma, 2.0)) * outerAA;

        // Secondary delicate braided warm amber caustic thread inside the glass ribbon
        float secOffset = -0.26 * cos(u * 6.2831853 * 3.0 + uCyclePhase * 1.25);
        float secThread = exp(-pow((v - secOffset) / 0.065, 2.0)) * (0.45 + 0.55 * periodicWave(u, 5.0, -uCyclePhase * 1.4)) * outerAA;

        // Transparent amber glass interior belly + soft outer golden veil
        float glassBelly = invSmoothstep(0.66, 0.98, absV) * outerAA;
        float innerShimmer = 0.78 + 0.22 * periodicWave(u, 5.0, -uCyclePhase * 1.35 + v * 2.4);
        float goldSpec = pow(max(dot(N, Hwarm), 0.0), 54.0) * 0.85 + pow(max(dot(N, Hkey), 0.0), 68.0) * 0.65;

        vec3 moltenAmber    = uColorA;                    // Deep rich fiery amber-orange (near-zero blue!)
        vec3 sunlitGold     = uColorB;                    // Vibrant luminous gold
        vec3 champagneCrest = vec3(2.30, 1.56, 0.42);     // Warm champagne-gold HDR specular (no dead grey-white!)

        vec3 wallColor = mix(moltenAmber, sunlitGold, clamp(0.30 + 0.50 * NdotLWarm + 0.25 * fresnel, 0.0, 1.0));
        vec3 coreColor = mix(moltenAmber, sunlitGold, clamp(0.45 + 0.45 * coreStream, 0.0, 1.0));

        float wallsGain = uGoldOptics.x > 0.01 ? uGoldOptics.x : 0.74;
        float streamGain = uGoldOptics.y > 0.01 ? uGoldOptics.y : 0.66;
        float threadGain = uGoldOptics.z > 0.01 ? uGoldOptics.z : 0.395;
        float bellyGain = uGoldOptics.w > 0.01 ? uGoldOptics.w : 0.16;
        float specGain = uGoldSpecGain > 0.01 ? uGoldSpecGain : 0.305;

        col = moltenAmber * bellyGain * glassBelly
            + wallColor * (goldWalls * wallsGain + secThread * threadGain)
            + coreColor * (coreStream * streamGain * innerShimmer)
            + champagneCrest * (localGlint * (0.48 * coreStream + 0.38 * goldWalls) + goldSpec * specGain * (1.25 * goldWalls + 0.95 * coreStream));

        alpha = clamp(
            tipFade * (glassBelly * (0.09 + 0.12 * fresnel) + goldWalls * 0.64 + coreStream * 0.48 + secThread * 0.30 + localGlint * 0.24)
            * uRibbonTransparency * uOpacityScale,
            0.0, 0.90
        );
    }

    // Subtle atmospheric attenuation behind the orb (preserves crisp front-vs-back spatial depth!)
    float depthFade = (uClipPass == 0) ? 0.80 : 1.0;
    col *= depthFade * (0.88 + 0.18 * uOverallIntensity);

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
    float hotCore   = exp(-r * r * 125.0);
    float tightHalo = exp(-r * r * 18.0) * 0.78;
    float softAura  = pow(max(1.0 - r, 0.0), 3.1) * 0.34;

    float spikeH  = exp(-abs(pr.y) * 46.0) * exp(-abs(pr.x) * 2.6) * 1.08;
    float spikeV  = exp(-abs(pr.x) * 46.0) * exp(-abs(pr.y) * 2.6) * 1.08;
    vec2 pd = vec2(pr.x + pr.y, pr.x - pr.y) * 0.70710678;
    float spikeD1 = exp(-abs(pd.y) * 56.0) * exp(-abs(pd.x) * 4.4) * 0.52;
    float spikeD2 = exp(-abs(pd.x) * 56.0) * exp(-abs(pd.y) * 4.4) * 0.52;

    float shape = hotCore * 2.35 + tightHalo + softAura + spikeH + spikeV + spikeD1 + spikeD2;

    // Keep white strictly in the microscopic core; halo and spikes stay richly colored molten gold/ice-cyan!
    vec3 coreWhite = vec3(1.85, 1.72, 1.48);
    vec3 col = mix(uFlareColor * 1.55, coreWhite, clamp(hotCore * 0.90, 0.0, 1.0));
    col *= shape * uIntensity;

    float alpha = clamp(shape * uIntensity * 0.84, 0.0, 1.0);
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
