package com.example.waterworld

/**
 * Eight dedicated GLSL ES 3.0 shader programs implementing the Water World V2.1
 * 11-stage Render Graph and 5 independent optical effects (Work 1.6 / Work 2 / Work 3 Dynamic Runtime):
 *  1. OPAQUE           — Pass 01 background atmosphere & top glare suppression
 *  2. TRANSPARENT      — Pass 05/07/08 alpha-cutout plants (root-pinned sway), rocks (flowing caustics) & 4 fish (tail/fin deformation)
 *  3. RESIDENT_RIBBON  — Pass 02/10 multi-strand silk ribbons with directional arc-length flow, width & brightness pulse
 *  4. WATER            — Pass 04 dynamic tangent-space normal drift, wavy meniscus, AI Orb core fluid & basin scatter
 *  5. GLASS            — Pass 03/09 screen-space spherical refraction, dispersion, Fresnel & gentle optical response
 *  6. CAUSTICS         — Pass 06 volumetric golden god-rays & flowing underwater caustic lighting
 *  7. BLOOM            — Pass 11 bright-pass, separable Gaussian blur & luminance-preserving tone composite
 *  8. PARTICLE_BOKEH   — Pass 01/08/11 background/foreground bokeh orbs, sparkles & rising water droplets
 *
 * T=0 Regression Contract: when all Work 3 dynamic uniforms are set to their zero/identity defaults
 * (`uDeformMode=0`, `uFlowOffsetU=0`, `uBrightnessScale=1`, `uWaterNormalOffset=(0,0)`,
 * `uCausticsPhase=0`, `uLightingShimmer=1`, `uGlassRefractionScale=1`, `uGlassFresnelScale=1`,
 * `uCoreFluidParams=(0,0,0,0)`), shader evaluation is mathematically identical to Work 2 T=0.
 */
internal class WaterWorldShaders {

    val opaque = WaterWorldProgram(MESH_VERTEX, OPAQUE_FRAGMENT)
    val transparent = WaterWorldProgram(MESH_VERTEX, TRANSPARENT_FRAGMENT)
    val residentRibbon = WaterWorldProgram(RESIDENT_RIBBON_VERTEX, RESIDENT_RIBBON_FRAGMENT)
    val water = WaterWorldProgram(FULLSCREEN_VERTEX, WATER_FRAGMENT)
    val glass = WaterWorldProgram(FULLSCREEN_VERTEX, GLASS_FRAGMENT)
    val caustics = WaterWorldProgram(FULLSCREEN_VERTEX, CAUSTICS_FRAGMENT)
    val bloomBright = WaterWorldProgram(FULLSCREEN_VERTEX, BLOOM_BRIGHT_FRAGMENT)
    val bloomBlur = WaterWorldProgram(FULLSCREEN_VERTEX, BLOOM_BLUR_FRAGMENT)
    val bloomComposite = WaterWorldProgram(FULLSCREEN_VERTEX, BLOOM_COMPOSITE_FRAGMENT)
    val particleBokeh = WaterWorldProgram(MESH_VERTEX, PARTICLE_BOKEH_FRAGMENT)
    val bokehOrb = WaterWorldProgram(FULLSCREEN_VERTEX, BOKEH_ORB_FRAGMENT)
    val present = WaterWorldProgram(FULLSCREEN_VERTEX, PRESENT_FRAGMENT)

    fun allPrograms(): List<WaterWorldProgram> = listOf(
        opaque,
        transparent,
        residentRibbon,
        water,
        glass,
        caustics,
        bloomBright,
        bloomBlur,
        bloomComposite,
        particleBokeh,
        bokehOrb,
        present
    )

    fun release() {
        allPrograms().forEach { it.release() }
    }

    companion object {
        val SHADER_PROGRAM_NAMES: List<String> = listOf(
            "OPAQUE",
            "TRANSPARENT",
            "RESIDENT_RIBBON",
            "WATER",
            "GLASS",
            "CAUSTICS",
            "BLOOM",
            "PARTICLE_BOKEH"
        )

        val MESH_VERTEX = """#version 300 es
            precision highp float;
            layout(location=0) in vec3 aPosition;
            layout(location=1) in vec3 aNormal;
            layout(location=2) in vec2 aUv;
            uniform mat4 uModel;
            uniform vec2 uCanvas;
            // Work 3 dynamic vertex deformation (0=none/T=0, 1=fish_tail_fin, 2=plant_sway, 3=resident_ribbon_wave)
            uniform int uDeformMode;
            uniform vec4 uDeformParams;
            out vec2 vUv;
            out vec2 vScreenUv;
            out vec3 vNormal;
            void main() {
                vec3 localPos = aPosition;
                if (uDeformMode == 1) {
                    // Fish swimming deformation: u=0 is tail tip, u=1 is nose/head.
                    // Head stays rigid; tail sweeps smoothly with traveling sinusoidal wave; dorsal/pelvic fins flutter.
                    float tailAmp = uDeformParams.x;
                    float tailPhase = uDeformParams.y;
                    float finAmp = uDeformParams.z;
                    float finPhase = uDeformParams.w;
                    float tailMask = pow(clamp(1.0 - aUv.x / 0.58, 0.0, 1.0), 1.75);
                    float tailWave = sin(tailPhase + (1.0 - aUv.x) * 3.8);
                    float dyTail = tailMask * tailAmp * tailWave;
                    float dxTail = abs(dyTail) * 0.14 * tailMask;
                    float bodySpan = smoothstep(0.18, 0.42, aUv.x) * (1.0 - smoothstep(0.68, 0.88, aUv.x));
                    float finEdge = (1.0 - smoothstep(0.0, 0.30, aUv.y)) + smoothstep(0.70, 1.0, aUv.y);
                    float finWave = sin(finPhase + aUv.x * 5.2 + aUv.y * 2.4);
                    float dyFin = bodySpan * finEdge * finAmp * finWave;
                    localPos.x += dxTail;
                    localPos.y += dyTail + dyFin;
                } else if (uDeformMode == 2) {
                    // Aquatic plant sway: v=1.0 is root (strictly pinned), v=0.0 is leaf tip.
                    float swayAmp = uDeformParams.x;
                    float swayPhase = uDeformParams.y;
                    float flutterAmp = uDeformParams.z;
                    float flutterPhase = uDeformParams.w;
                    float bendWeight = pow(clamp(1.0 - aUv.y, 0.0, 1.0), 1.75);
                    float dx = bendWeight * (
                        swayAmp * sin(swayPhase + aUv.y * 1.65) +
                        flutterAmp * sin(flutterPhase + aUv.x * 4.6 + aUv.y * 3.2)
                    );
                    localPos.x += dx;
                    localPos.y += abs(dx) * 0.08 * bendWeight;
                } else if (uDeformMode == 3) {
                    // Resident silk ribbon: subtle non-uniform lateral wave & width breathing along local cross-axis.
                    float widthPulse = uDeformParams.x - 1.0;
                    float waveAmp = uDeformParams.y;
                    float wavePhase = uDeformParams.z;
                    float endAnchor = smoothstep(0.0, 0.12, aUv.x) * smoothstep(0.0, 0.12, 1.0 - aUv.x);
                    vec2 crossDir = aNormal.xy;
                    float cLen = length(crossDir);
                    crossDir = (cLen > 0.001) ? (crossDir / cLen) : vec2(0.0, 1.0);
                    float acrossSigned = aUv.y * 2.0 - 1.0;
                    float wave = sin(wavePhase + aUv.x * 9.0 + acrossSigned * 0.8) * 0.65
                               + cos(wavePhase * 0.72 - aUv.x * 5.2) * 0.35;
                    localPos.xy += crossDir * (
                        waveAmp * wave * endAnchor +
                        widthPulse * acrossSigned * 18.0 * endAnchor
                    );
                }
                vec4 p = uModel * vec4(localPos, 1.0);
                gl_Position = vec4(p.x / uCanvas.x * 2.0 - 1.0, 1.0 - p.y / uCanvas.y * 2.0, p.z / 2048.0, 1.0);
                vNormal = normalize(transpose(inverse(mat3(uModel))) * aNormal);
                vUv = aUv;
                vScreenUv = p.xy / uCanvas;
            }
        """.trimIndent()

        val FULLSCREEN_VERTEX = """#version 300 es
            precision highp float;
            out vec2 vUv;
            void main() {
                vec2 pos = vec2((gl_VertexID == 1) ? 3.0 : -1.0, (gl_VertexID == 2) ? 3.0 : -1.0);
                gl_Position = vec4(pos, 0.0, 1.0);
                // Top-left UV origin matching image canvas coordinates (0,0 top-left -> 1,1 bottom-right)
                vUv = vec2(pos.x * 0.5 + 0.5, 0.5 - pos.y * 0.5);
            }
        """.trimIndent()

        val OPAQUE_FRAGMENT = """#version 300 es
            precision highp float;
            in vec2 vUv;
            in vec2 vScreenUv;
            in vec3 vNormal;
            uniform sampler2D uTexture;
            uniform vec2 uSphereCenter;
            uniform vec2 uCanvas;
            uniform float uTopGlareSuppression;
            uniform float uVignetteStrength;
            uniform vec3 uDeepTint;
            uniform vec2 uHaloCenter;
            uniform float uHaloRadius;
            uniform vec3 uHaloColor;
            uniform float uHaloIntensity;
            uniform float uSphereBackdropRadius;
            out vec4 fragColor;
            void main() {
                vec4 bg = texture(uTexture, vUv);
                float topMask = 1.0 - smoothstep(0.0, 0.24, vUv.y);
                float gSup = 1.0 - topMask * uTopGlareSuppression;
                vec3 col = bg.rgb * gSup * vec3(0.55, 0.62, 0.62) + uDeepTint * vec3(0.55, 0.60, 0.60);
                vec2 px = vScreenUv * uCanvas;
                vec2 d = (px - uSphereCenter) / (uCanvas * 0.58);
                float vig = 1.0 - smoothstep(0.55, 1.32, length(d)) * uVignetteStrength;
                col *= vig;
                float hd = length(vUv - uHaloCenter) / max(uHaloRadius, 0.001);
                float halo = exp(-hd * hd * 1.6) * uHaloIntensity;
                col += uHaloColor * halo;
                float bgMask = 1.0;
                if (uSphereBackdropRadius > 0.5) {
                    float sr = length(px - uSphereCenter);
                    bgMask = 1.0 - smoothstep(uSphereBackdropRadius - 12.0, uSphereBackdropRadius + 4.0, sr);
                }
                fragColor = vec4(col * bgMask, 1.0);
            }
        """.trimIndent()

        val RESIDENT_RIBBON_VERTEX = """#version 300 es
            precision highp float;
            layout(location=0) in vec3 aPosition;
            layout(location=1) in vec3 aNormal;
            layout(location=2) in vec2 aUv;
            layout(location=3) in float aLocalHalfWidthPx;
            uniform mat4 uModel;
            uniform vec2 uCanvas;
            // Five Resident Filaments V1 vertex deformation: 0 = exact T=0 static identity, 3 = V1 dynamic wave + relative width breathing
            uniform int uDeformMode;
            // x = widthAmp (relative fraction), y = maxLateralWavePx, z = wavePhaseRad, w = widthTimePhaseRad
            uniform vec4 uDeformParams;
            // x = segmentArcLengthStart, y = segmentArcLengthEnd, z = localUvDirection (+1/-1), w = localFlowPhaseOffset
            uniform vec4 uSegmentArcRange;
            // Work 3.2 true 3D Core-local rigid orbital motion: 0 = exact T=0 static bypass, 1 = active 3D orbit around Core center
            uniform mat3 uResidentOrbitMat;
            uniform vec3 uCoreCenterPx;
            uniform float uCoreRadiusPx;
            uniform int uResidentOrbitEnabled;
            out vec2 vUv;
            out vec2 vScreenUv;
            out vec3 vNormal;
            out float vCanonicalS;
            void main() {
                float directedU = (uSegmentArcRange.z >= 0.0) ? aUv.x : (1.0 - aUv.x);
                float canonicalS = mix(uSegmentArcRange.x, uSegmentArcRange.y, directedU) + uSegmentArcRange.w;
                vec3 localPos = aPosition;
                if (uDeformMode == 3) {
                    float widthAmp = uDeformParams.x;
                    float waveAmpPx = uDeformParams.y;
                    float wavePhase = uDeformParams.z;
                    float widthTimePhase = uDeformParams.w;
                    float endAnchor = smoothstep(0.0, 0.09, aUv.x) * smoothstep(0.0, 0.09, 1.0 - aUv.x);

                    // Outward cross-section direction from centerline toward this edge vertex (verified on all 13 frozen strips)
                    vec2 outwardDir = aNormal.xy;
                    float cLen = length(outwardDir);
                    outwardDir = (cLen > 0.001) ? (outwardDir / cLen) : vec2(0.0, 1.0);
                    float sideSign = (aUv.y < 0.5) ? 1.0 : -1.0;
                    vec2 ribbonNormal = outwardDir * sideSign;

                    // 1) Continuous local lateral wave along canonical arc-length s (strictly bounded within ±waveAmpPx)
                    float lateralWave = sin(wavePhase - canonicalS * 12.5663706) * 0.68
                                      + cos(wavePhase * 0.73 + canonicalS * 6.2831853) * 0.32;
                    localPos.xy += ribbonNormal * (waveAmpPx * lateralWave * endAnchor);

                    // 2) True relative cross-section width breathing proportional to exact local ribbon half-width (zero hardcoded 18px)
                    float widthWave = sin(widthTimePhase - canonicalS * 9.424778) * 0.72
                                    + cos(widthTimePhase * 0.65 + canonicalS * 6.2831853) * 0.28;
                    float localWidthDelta = widthAmp * widthWave * endAnchor;
                    localPos.xy += outwardDir * (aLocalHalfWidthPx * localWidthDelta);
                }
                vec4 p = uModel * vec4(localPos, 1.0);
                vec3 worldNormal = normalize(transpose(inverse(mat3(uModel))) * aNormal);
                if (uResidentOrbitEnabled == 1) {
                    // Core-local normalized 3D rigid orbital rotation: P'(t) = C + R(axis, theta(t)) * (P - C)
                    float safeCoreRadius = max(uCoreRadiusPx, 1.0);
                    vec3 coreLocalNorm = (p.xyz - uCoreCenterPx) / safeCoreRadius;
                    vec3 rotatedNorm = uResidentOrbitMat * coreLocalNorm;
                    p.xyz = uCoreCenterPx + rotatedNorm * safeCoreRadius;
                    worldNormal = normalize(uResidentOrbitMat * worldNormal);
                }
                gl_Position = vec4(p.x / uCanvas.x * 2.0 - 1.0, 1.0 - p.y / uCanvas.y * 2.0, p.z / 2048.0, 1.0);
                vNormal = worldNormal;
                vUv = aUv;
                vScreenUv = p.xy / uCanvas;
                vCanonicalS = canonicalS;
            }
        """.trimIndent()

        val RESIDENT_RIBBON_FRAGMENT = """#version 300 es
            precision highp float;
            in vec2 vUv;
            in vec2 vScreenUv;
            in vec3 vNormal;
            in float vCanonicalS;
            uniform vec4 uTint;
            uniform vec3 uCoreColor;
            uniform float uEmission;
            uniform float uRibbonStyle;
            uniform float uFlowOffsetU;
            uniform float uBrightnessScale;
            // x = flowActive (0 at T=0, 1 at T>0), y = brightnessAmp, z = brightnessTimePhaseRad, w = flowDirection (+1/-1)
            uniform vec4 uResidentFlowParams;
            out vec4 fragColor;
            void main() {
                float u = vUv.x;
                float v = vUv.y;
                float across = abs(v * 2.0 - 1.0);
                float endFade = smoothstep(0.0, 0.11, u) * smoothstep(0.0, 0.13, 1.0 - u);

                float flowActive = clamp(uResidentFlowParams.x, 0.0, 1.0);
                float brightAmp = uResidentFlowParams.y;
                float brightPhase = uResidentFlowParams.z;
                float flowDir = (uResidentFlowParams.w >= 0.0) ? 1.0 : -1.0;

                // At T=0 (flowActive == 0, uFlowOffsetU == 0): exact Work 2 T=0 pixel-identical evaluation.
                // At T>0: silk internal stream & moving highlight band travel continuously along canonical s in [0, 1].
                float uFlow = mix(u, vCanonicalS * 2.4 - uFlowOffsetU * 2.8, flowActive);
                float twist = 0.82 + 0.18 * sin(uFlow * 10.5 + uRibbonStyle * 2.1);
                float wave = 0.5 + 0.5 * cos((v * 2.0 - 1.0) * 10.5 + uFlow * 6.8);
                float streak = 0.56 + 0.44 * pow(wave, 1.4);

                float body;
                float rim;
                float core;
                if (uRibbonStyle < 0.5) {
                    body = (1.0 - smoothstep(0.16, 1.0, across)) * 0.52 * streak;
                    rim = exp(-28.0 * pow(across - 0.64, 2.0)) * 0.62;
                    core = exp(-24.0 * across * across) * 0.60;
                } else if (uRibbonStyle < 1.5) {
                    body = exp(-2.9 * across * across) * 0.54 * streak;
                    rim = exp(-26.0 * pow(across - 0.52, 2.0)) * 0.40;
                    core = exp(-34.0 * across * across) * 1.06;
                } else {
                    body = (1.0 - smoothstep(0.15, 1.0, across)) * 0.50 * streak;
                    rim = exp(-26.0 * pow(across - 0.58, 2.0)) * 0.50;
                    core = exp(-30.0 * across * across) * 0.94;
                }

                // Moving energy highlight band along canonical s with soft leading edge and smooth fading tail (seamless cyclic wrap)
                float phaseHead1 = fract((vCanonicalS - uFlowOffsetU) * flowDir);
                float leadEnv1 = exp(-pow(phaseHead1 / 0.068, 2.0));
                float tailEnv1 = exp(-pow((1.0 - phaseHead1) / 0.20, 2.0));
                float primaryBand = max(leadEnv1, tailEnv1);

                float phaseHead2 = fract(phaseHead1 + 0.52);
                float leadEnv2 = exp(-pow(phaseHead2 / 0.062, 2.0));
                float tailEnv2 = exp(-pow((1.0 - phaseHead2) / 0.17, 2.0));
                float secondaryBand = max(leadEnv2, tailEnv2);

                float energyBand = (primaryBand * 0.36 + secondaryBand * 0.16) * flowActive;
                float coreFocus = exp(-7.5 * across * across);

                // Non-uniform spatially-phased brightness breathing strictly bounded to 1 ± brightnessAmp (zero whole-line strobe)
                float brightWave = sin(brightPhase - vCanonicalS * 6.2831853) * 0.70
                                 + cos(brightPhase * 0.62 + vCanonicalS * 9.424778) * 0.30;
                float spatialBrightness = 1.0 + brightAmp * brightWave * flowActive;
                float finalBrightness = mix(1.0, spatialBrightness * 0.72 + uBrightnessScale * 0.28, flowActive);

                float alpha = clamp((body + rim * 0.60 + core * 0.68) * uTint.a * endFade * twist, 0.0, 1.0);
                if (alpha < 0.002) discard;

                vec3 baseRgb = uTint.rgb * (body + rim) + uCoreColor * core;
                vec3 highlightRgb = (uTint.rgb * 0.35 + uCoreColor * 0.65) * energyBand * (0.36 + 0.64 * coreFocus) * (body + core * 0.88);
                vec3 rgb = (baseRgb + highlightRgb) * uEmission * finalBrightness;
                fragColor = vec4(rgb * alpha, alpha);
            }
        """.trimIndent()

        val TRANSPARENT_FRAGMENT = """#version 300 es
            precision highp float;
            in vec2 vUv;
            in vec2 vScreenUv;
            in vec3 vNormal;
            uniform sampler2D uTexture;
            uniform sampler2D uCausticsTex;
            uniform sampler2D uWaterNormalTex;
            uniform vec4 uTint;
            uniform vec3 uKeyColor;
            uniform float uAmbient;
            uniform float uAlphaCutoff;
            uniform float uSphereClip;
            uniform vec2 uSphereCenter;
            uniform float uSphereInnerRadius;
            uniform vec2 uCanvas;
            uniform int uMaterialMode; // 0=fish, 1=plant, 2=rock, 3=rock_moss
            uniform float uFarHaze;
            uniform vec2 uWaterNormalOffset;
            uniform float uCausticsPhase;
            uniform float uCausticsGain;
            out vec4 fragColor;
            void main() {
                float clipAlpha = 1.0;
                if (uSphereClip > 0.5) {
                    float sr = length(vScreenUv * uCanvas - uSphereCenter) / (uSphereInnerRadius + 6.0);
                    if (sr >= 1.0) discard;
                    clipAlpha = 1.0 - smoothstep(0.93, 1.0, sr);
                }
                vec4 tex = texture(uTexture, vUv);
                float alpha = tex.a * uTint.a * clipAlpha;
                if (alpha <= 0.005 || (uAlphaCutoff > 0.0 && alpha < uAlphaCutoff)) discard;

                vec3 col = tex.rgb * uTint.rgb;
                float su = vScreenUv.x;
                float sv = vScreenUv.y;
                float topLight = clamp(1.12 - sv * 0.52 + (0.5 - su) * 0.20, 0.52, 1.20);

                if (uMaterialMode == 0) {
                    // 4 Fish: sunlit golden-cream dorsal back + crisp aquatic turquoise belly bounce
                    float dorsal = 1.0 - smoothstep(0.15, 0.72, vUv.y);
                    col = col * vec3(uAmbient + 0.44 * topLight, uAmbient + 0.43 * topLight, uAmbient + 0.39 * topLight);
                    col += vec3(dorsal * 0.15 * uKeyColor.r, dorsal * 0.12 * uKeyColor.g, (1.0 - dorsal) * 0.055);
                } else if (uMaterialMode == 1) {
                    // Plants: natural rich emerald stems with warm sunlit golden-chartreuse upper tips
                    float tipSun = (1.0 - smoothstep(0.05, 0.68, vUv.y)) * (0.62 + 0.38 * (1.0 - su));
                    col = col * vec3(uAmbient + 0.36 * topLight + tipSun * 0.44,
                                     uAmbient + 0.43 * topLight + tipSun * 0.34,
                                     uAmbient + 0.24 * topLight + tipSun * 0.04);
                    if (uFarHaze > 0.01) col = mix(col, vec3(0.022, 0.26, 0.28), uFarHaze);
                } else {
                    // Rocks: physically stationary with dynamically flowing water-warped golden-white caustics
                    vec3 wn = texture(uWaterNormalTex, fract(vScreenUv * 2.2 + uWaterNormalOffset * 1.4)).rgb;
                    vec2 cauUv = fract(vec2(su * 1.75 + (wn.r - 0.5) * 0.24 + 0.15 + uCausticsPhase * 0.032,
                                            sv * 2.05 + (wn.g - 0.5) * 0.24 + 0.09 - uCausticsPhase * 0.022));
                    float cSample = texture(uCausticsTex, cauUv).r;
                    float topSurface = 1.0 - smoothstep(0.04, 0.66, vUv.y);
                    float ridge = pow(clamp(cSample, 0.0, 1.0), 4.0);
                    float cGain = ((uMaterialMode == 2) ? 1.48 : 0.92) * uCausticsGain;
                    float cGlow = ridge * topSurface * cGain;
                    float mossBoost = (uMaterialMode == 3) ? pow(topSurface, 1.4) * 0.36 : 0.0;
                    col = col * vec3(uAmbient + 0.40 * topLight, uAmbient + 0.44 * topLight, uAmbient + 0.42 * topLight);
                    col += vec3(cGlow * 1.02 + mossBoost * 0.88,
                                cGlow * 0.96 + mossBoost * 1.00,
                                cGlow * 0.68 + mossBoost * 0.16);
                    if (uFarHaze > 0.01) col = mix(col, vec3(0.022, 0.26, 0.29), uFarHaze);
                }
                fragColor = vec4(col, alpha);
            }
        """.trimIndent()

        val WATER_FRAGMENT = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uSceneSnapshot;
            uniform sampler2D uWaterbedTex;
            uniform sampler2D uWaterNormalTex;
            uniform sampler2D uWaterDepthTex;
            uniform sampler2D uCausticsTex;
            uniform vec2 uCanvas;
            uniform vec2 uSphereCenter;
            uniform float uSphereInnerRadius;
            uniform float uNormalStrength;
            uniform float uMeniscusBaseY;
            uniform float uMeniscusAmp;
            uniform float uMeniscusThick;
            uniform vec3 uAirDomeTop;
            uniform vec3 uAirDomeWarm;
            uniform vec3 uWaterShallow;
            uniform vec3 uWaterMid;
            uniform vec3 uWaterDeep;
            uniform float uWaterbedBlend;
            uniform int uDiagnosticOnly;
            // Work 3.1 dynamic water surface/normal/meniscus uniforms (zero at T=0).
            // Internal Core 3D fluid motion is owned exclusively by OrbFluidVolumeRenderer (32 Z-slice GL_TEXTURE_3D),
            // never faked by 2D sin/cos swirl in WATER_FRAGMENT.
            uniform vec2 uWaterNormalOffset;
            uniform vec2 uMeniscusPhases;
            out vec4 fragColor;
            void main() {
                vec2 px = vUv * uCanvas;
                vec2 d = (px - uSphereCenter) / (uSphereInnerRadius + 8.0);
                float r2 = dot(d, d);
                if (r2 > 1.0) discard;
                float r = sqrt(r2);
                float edgeSoft = 1.0 - smoothstep(0.960, 1.0, r);
                vec3 wn = texture(uWaterNormalTex, fract(vUv * 2.0 + uWaterNormalOffset)).rgb;
                float tnx = wn.r * 2.0 - 1.0;
                float tny = wn.g * 2.0 - 1.0;
                float relDepth = texture(uWaterDepthTex, vUv).r;
                vec2 disp = vec2(tnx, tny) * uNormalStrength * (0.35 + 0.65 * relDepth);
                vec2 sampleUv = clamp(vUv + disp, vec2(0.0), vec2(1.0));
                vec3 preS = texture(uSceneSnapshot, vec2(sampleUv.x, 1.0 - sampleUv.y)).rgb;
                vec3 wbed = texture(uWaterbedTex, clamp(vUv + disp * 1.2, vec2(0.0), vec2(1.0))).rgb;
                float cau = texture(uCausticsTex, fract((vUv + disp * 1.5) * 1.8 + uWaterNormalOffset * 1.35)).r;

                float waveY = uMeniscusBaseY
                    + uMeniscusAmp * sin((vUv.x - 0.36) * 13.5 + uMeniscusPhases.x)
                    + uMeniscusAmp * 0.38 * cos((vUv.x - 0.54) * 24.0 - uMeniscusPhases.y)
                    - 0.026 * smoothstep(0.56, 0.72, vUv.x)
                    + 0.024 * smoothstep(0.70, 0.84, vUv.x)
                    + tny * 0.005;
                float signedY = vUv.y - waveY;
                float menLine = exp(-(signedY * signedY) / (uMeniscusThick * uMeniscusThick * 0.35));

                if (uDiagnosticOnly == 1) {
                    fragColor = vec4(clamp(0.5 + tnx * 0.5, 0.0, 1.0) * edgeSoft,
                                     clamp(0.5 + tny * 0.5, 0.0, 1.0) * edgeSoft,
                                     clamp(relDepth * 0.7 + menLine * 0.6, 0.0, 1.0) * edgeSoft, 1.0);
                    return;
                }

                vec3 col;
                if (signedY < 0.0) {
                    float airFrac = clamp(-signedY / 0.10, 0.0, 1.0);
                    float sunSide = 1.0 - smoothstep(0.25, 0.58, vUv.x);
                    float skyRef = exp(-pow((vUv.x - 0.51) / 0.17, 2.0)) * (1.0 - airFrac * 0.52);
                    col = mix(uAirDomeTop, uAirDomeWarm, vec3(sunSide * 0.86, sunSide * 0.74, sunSide * 0.44))
                        + vec3(0.18, 0.40, 0.48) * skyRef;
                    float topDark = smoothstep(0.68, 0.98, r) * (1.0 - sunSide * 0.52);
                    col *= (1.0 - topDark * vec3(0.54, 0.44, 0.32));
                    col = mix(preS, col, vec3(0.79, 0.81, 0.82));
                } else {
                    float depthT = clamp(signedY / 0.58, 0.0, 1.0);
                    float rightDeep = smoothstep(0.45, 0.82, vUv.x);
                    vec3 c0 = mix(uWaterShallow, uWaterMid, smoothstep(0.0, 0.40, depthT));
                    c0 = mix(c0, uWaterDeep, smoothstep(0.42, 1.0, depthT));
                    c0 *= (1.0 - rightDeep * vec3(0.35, 0.24, 0.10));
                    float wbFactor = uWaterbedBlend * smoothstep(0.30, 0.90, depthT);
                    col = mix(c0, wbed * vec3(0.46, 0.62, 0.64), wbFactor);
                    float wallShade = 1.0 - smoothstep(0.62, 0.99, r) * 0.44;
                    col *= wallShade;
                    float basinD = length(vec2((vUv.x - 0.54) / 0.22, (vUv.y - 0.62) / 0.16));
                    float basinGlow = exp(-basinD * basinD * 1.4) * 0.26;
                    col += vec3(0.04, 0.74, 0.78) * basinGlow;
                    // Real 32 Z-slice GL_TEXTURE_3D Core Volume is composited immediately after Water Base
                    // inside Pass 04_WATER_INTERIOR via OrbFluidVolumeRenderer.drawVolumeOnly().
                    col = mix(preS, col, 0.91);
                }

                float underRipple = (signedY > 0.0 && signedY < 0.090)
                    ? exp(-pow((signedY - 0.025) / 0.032, 2.0)) * (0.38 + 0.62 * cau) : 0.0;
                float sunWarmth = 1.0 - smoothstep(0.25, 0.60, vUv.x);
                vec3 menCol = mix(vec3(0.20, 0.88, 0.96), vec3(1.00, 0.93, 0.52), sunWarmth);
                col = mix(col, menCol * vec3(0.90 + 0.28 * cau, 0.91 + 0.24 * cau, 0.92 + 0.20 * cau), menLine * 0.88)
                    + mix(vec3(0.08, 0.50, 0.60), vec3(0.68, 0.58, 0.22), sunWarmth) * underRipple * 0.52;
                fragColor = vec4(col, edgeSoft);
            }
        """.trimIndent()

        val GLASS_FRAGMENT = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uSceneSnapshot;
            uniform sampler2D uGlassShellMask;
            uniform vec2 uCanvas;
            uniform vec2 uSphereCenter;
            uniform float uSphereOuterRadius;
            uniform int uPassSide; // 0 = back shell (Pass 03), 1 = front shell (Pass 09)
            uniform float uRefractionStrength;
            uniform float uDispersion;
            uniform float uFresnelPower;
            uniform vec3 uRimCool;
            uniform vec3 uRimWarm;
            uniform vec3 uAbsorption;
            uniform vec2 uTopLeftFlare;
            uniform float uTopLeftRadius;
            uniform vec3 uTopLeftColor;
            uniform float uTopLeftIntensity;
            uniform vec2 uBottomRightFlare;
            uniform float uBottomRightRadius;
            uniform vec3 uBottomRightColor;
            uniform float uBottomRightIntensity;
            uniform vec2 uRightRimArc;
            uniform float uRightRimIntensity;
            uniform int uDiagnosticOnly;
            // Work 3 subtle optical modulation (identity 1.0, 1.0, 0.0 at T=0; sphere never rotates!)
            uniform float uGlassRefractionScale;
            uniform float uGlassFresnelScale;
            uniform float uGlassOpticalPhase;
            out vec4 fragColor;

            vec3 sampleSnap(vec2 uv) {
                vec2 c = clamp(uv, vec2(0.0), vec2(1.0));
                return texture(uSceneSnapshot, vec2(c.x, 1.0 - c.y)).rgb;
            }

            void main() {
                vec2 px = vUv * uCanvas;
                vec2 d = (px - uSphereCenter) / uSphereOuterRadius;
                float r2 = dot(d, d);
                vec3 base = sampleSnap(vUv);
                float effRefract = uRefractionStrength * uGlassRefractionScale;

                if (uPassSide == 0) {
                    if (r2 > 1.0) discard;
                    float r = sqrt(r2);
                    float nz = sqrt(max(0.0001, 1.0 - r2));
                    float mask = smoothstep(0.55, 1.0, r);
                    vec2 ruv = vUv - d * effRefract * mask;
                    vec3 s = sampleSnap(ruv);
                    float shellM = texture(uGlassShellMask, vUv).r;
                    float fresnel = pow(1.0 - nz, uFresnelPower);
                    float rimGlow = (fresnel * 0.20 + shellM * 0.08) * uGlassFresnelScale;
                    if (uDiagnosticOnly == 1) {
                        fragColor = vec4(abs(ruv.x - vUv.x) * 18.0 + uRimCool.r * rimGlow,
                                         abs(ruv.y - vUv.y) * 18.0 + uRimCool.g * rimGlow,
                                         fresnel * 0.8 + uRimCool.b * rimGlow, 1.0);
                        return;
                    }
                    vec3 col = mix(s, uAbsorption, vec3(0.14 + fresnel * 0.12, 0.16 + fresnel * 0.14, 0.18 + fresnel * 0.16))
                        + uRimCool * rimGlow * vec3(0.25, 0.30, 0.35);
                    fragColor = vec4(col, 1.0);
                    return;
                }

                // Front glass pass (Pass 09): screen-space refraction + crystal bevel + flares
                vec3 outCol = base;
                if (r2 <= 1.006) {
                    float r = sqrt(min(1.0, r2));
                    float nz = sqrt(max(0.0001, 1.0 - min(0.9999, r2)));
                    float wallFactor = smoothstep(0.84, 0.995, r);
                    float dispMask = smoothstep(0.91, 0.995, r);
                    float off = effRefract * wallFactor * (1.0 - nz * 0.55);
                    float sR = sampleSnap(vUv - d * (off + uDispersion * dispMask)).r;
                    float sG = sampleSnap(vUv - d * off).g;
                    float sB = sampleSnap(vUv - d * (off - uDispersion * dispMask)).b;
                    vec3 col = vec3(sR, sG, sB);

                    float fresnel = pow(1.0 - nz, uFresnelPower);
                    float outerBevel = exp(-pow((r - 0.990) / 0.011, 2.0));
                    float innerDarkBand = exp(-pow((r - 0.958) / 0.013, 2.0));
                    float innerBrightRing = exp(-pow((r - 0.936) / 0.014, 2.0));
                    float shellM = texture(uGlassShellMask, vUv).r;

                    col *= (1.0 - innerDarkBand * vec3(0.28, 0.20, 0.14));

                    float ulSunGlass = exp(-pow(d.x + 0.72, 2.0) * 1.45 - pow(d.y + 0.42, 2.0) * 1.45);
                    float lrSunGlass = exp(-pow(d.x - 0.56, 2.0) * 1.55 - pow(d.y - 0.54, 2.0) * 1.55);
                    float warmWeight = clamp(ulSunGlass + lrSunGlass * 0.95, 0.0, 1.0);
                    vec3 rimTint = mix(uRimCool, uRimWarm, warmWeight);

                    float rimSpec = (outerBevel * 1.05 + innerBrightRing * 0.62 + fresnel * 0.30 + shellM * 0.10) * uGlassFresnelScale;
                    float rightCrescent = exp(-pow((vUv.x - uRightRimArc.x) / 0.068, 2.0)
                                              -pow((vUv.y - uRightRimArc.y) / 0.22, 2.0)) * wallFactor * uRightRimIntensity * uGlassFresnelScale;
                    float topArch = smoothstep(0.25, 0.88, -d.y) * (outerBevel * 0.80 + innerBrightRing * 0.58) * uGlassFresnelScale;
                    float blAqua = smoothstep(0.05, 0.80, d.y) * smoothstep(0.15, 0.85, -d.x)
                                   * (outerBevel * 0.55 + innerBrightRing * 0.52 + fresnel * 0.28);
                    float sunWallGlow = (ulSunGlass * 0.58 + lrSunGlass * 0.52) * smoothstep(0.76, 0.99, r);

                    col += rimTint * rimSpec * vec3(0.88, 0.92, 0.95)
                        + vec3(0.88, 0.97, 1.00) * rightCrescent
                        + vec3(0.76, 0.96, 1.00) * topArch * vec3(0.45, 0.52, 0.55)
                        + vec3(0.18, 0.86, 1.00) * blAqua
                        + vec3(1.00, 0.82, 0.42) * sunWallGlow;

                    if (uDiagnosticOnly == 1) {
                        fragColor = vec4(clamp(outerBevel * 0.85 + abs(d.x * off) * 32.0, 0.0, 1.0),
                                         clamp(innerBrightRing * 0.85 + abs(d.y * off) * 32.0, 0.0, 1.0),
                                         clamp(fresnel * 0.9 + rightCrescent, 0.0, 1.0), 1.0);
                        return;
                    }
                    float aaEdge = 1.0 - smoothstep(0.993, 1.005, sqrt(r2));
                    outCol = mix(base, col, aaEdge);
                } else if (uDiagnosticOnly == 1) {
                    fragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }

                float flareBreath = 1.0 + 0.025 * sin(uGlassOpticalPhase);
                float dTL = length(vUv - uTopLeftFlare) / max(uTopLeftRadius, 0.001);
                float flareTL = (exp(-dTL * dTL * 3.4) * 0.62 + exp(-dTL * dTL * 26.0) * 1.38) * uTopLeftIntensity * flareBreath;
                float dBR = length(vUv - uBottomRightFlare) / max(uBottomRightRadius, 0.001);
                float flareBR = (exp(-dBR * dBR * 3.4) * 0.60 + exp(-dBR * dBR * 24.0) * 1.32) * uBottomRightIntensity * flareBreath;
                outCol += uTopLeftColor * flareTL + uBottomRightColor * flareBR;
                fragColor = vec4(outCol, 1.0);
            }
        """.trimIndent()

        val CAUSTICS_FRAGMENT = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uWaterNormalTex;
            uniform sampler2D uCausticsTex;
            uniform vec2 uCanvas;
            uniform vec2 uSphereCenter;
            uniform float uSphereInnerRadius;
            uniform vec2 uSunOrigin;
            uniform float uGodRayIntensity;
            uniform vec3 uGodRayColor;
            uniform vec3 uBeams[6]; // x=angleDeg, y=widthDeg, z=strength
            uniform float uSurfaceCausticStrength;
            uniform float uBedCausticStrength;
            uniform vec3 uBedCausticColor;
            uniform int uDiagnosticOnly;
            // Work 3 dynamic caustics & gentle god-ray shimmer (zero / 1.0 at T=0)
            uniform vec2 uWaterNormalOffset;
            uniform float uCausticsPhase;
            uniform float uLightingShimmer;
            out vec4 fragColor;
            void main() {
                vec2 px = vUv * uCanvas;
                vec2 d = (px - uSphereCenter) / (uSphereInnerRadius + 4.0);
                float r2 = dot(d, d);
                if (r2 > 1.0) discard;
                float sphereFade = 1.0 - smoothstep(0.90, 1.0, sqrt(r2));

                vec2 sd = vUv - uSunOrigin;
                float dist = length(sd);
                float raySum = 0.0;
                float beamMotionGate = smoothstep(0.0, 0.05, abs(uCausticsPhase));
                if (sd.y > 0.01 && dist < 0.44) {
                    float angDeg = degrees(atan(sd.y, sd.x));
                    for (int i = 0; i < 6; i++) {
                        float swayDeg = sin(uCausticsPhase * 1.15 + float(i) * 1.3) * 0.85 * beamMotionGate;
                        float da = angDeg - (uBeams[i].x + swayDeg);
                        raySum += uBeams[i].z * exp(-(da * da) / (2.0 * uBeams[i].y * uBeams[i].y));
                    }
                    float lenFade = smoothstep(0.02, 0.08, dist) * (1.0 - smoothstep(0.16, 0.40, dist));
                    raySum = clamp(raySum, 0.0, 1.05) * lenFade * uGodRayIntensity * uLightingShimmer;
                }

                vec3 wn = texture(uWaterNormalTex, fract(vUv * 1.8 + uWaterNormalOffset * 1.25)).rgb;
                vec2 cauUv = fract(vUv * 1.35 + (wn.rg - 0.5) * 0.12 + vec2(uCausticsPhase * 0.026, -uCausticsPhase * 0.019));
                float cVal = pow(clamp(texture(uCausticsTex, cauUv).r, 0.0, 1.0), 2.1);

                float upperBand = exp(-pow((vUv.y - 0.255) / 0.042, 2.0));
                float lowerBed = smoothstep(0.60, 0.74, vUv.y) * (1.0 - smoothstep(0.78, 0.86, vUv.y));
                vec3 addCol = uGodRayColor * raySum
                    + vec3(uGodRayColor.r * 0.35, uGodRayColor.g * 0.40, 0.78 * 0.36) * cVal * upperBand * uSurfaceCausticStrength
                    + uBedCausticColor * vec3(0.34, 0.38, 0.26) * cVal * lowerBed * uBedCausticStrength;
                addCol *= sphereFade;
                if (uDiagnosticOnly == 1) {
                    fragColor = vec4(clamp(addCol * 2.2, 0.0, 1.0), 1.0);
                } else {
                    fragColor = vec4(addCol, 1.0);
                }
            }
        """.trimIndent()

        val BLOOM_BRIGHT_FRAGMENT = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uSceneTex;
            uniform float uThreshold;
            uniform float uSoftKnee;
            out vec4 fragColor;
            void main() {
                vec3 col = texture(uSceneTex, vec2(vUv.x, 1.0 - vUv.y)).rgb;
                float lum = dot(col, vec3(0.2126, 0.7152, 0.0722));
                float rq = clamp(lum - uThreshold + uSoftKnee, 0.0, 2.0 * uSoftKnee);
                rq = (rq * rq) / (4.0 * uSoftKnee + 1e-5);
                float contrib = max(rq, lum - uThreshold) / max(lum, 1e-5);
                fragColor = vec4(col * contrib, 1.0);
            }
        """.trimIndent()

        val BLOOM_BLUR_FRAGMENT = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uInputTex;
            uniform vec2 uTexelStep;
            out vec4 fragColor;
            void main() {
                vec2 uv = vec2(vUv.x, 1.0 - vUv.y);
                const float weights[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);
                vec3 sum = texture(uInputTex, uv).rgb * weights[0];
                for (int i = 1; i < 5; i++) {
                    vec2 off = uTexelStep * float(i) * 1.75;
                    sum += texture(uInputTex, clamp(uv + off, vec2(0.0), vec2(1.0))).rgb * weights[i];
                    sum += texture(uInputTex, clamp(uv - off, vec2(0.0), vec2(1.0))).rgb * weights[i];
                }
                fragColor = vec4(sum, 1.0);
            }
        """.trimIndent()

        val BLOOM_COMPOSITE_FRAGMENT = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uSceneTex;
            uniform sampler2D uBloomHalfTex;
            uniform sampler2D uBloomQuarterTex;
            uniform float uBloomStrength;
            uniform float uMaxContribution;
            uniform int uDiagnosticOnly;
            out vec4 fragColor;
            void main() {
                vec2 uv = vec2(vUv.x, 1.0 - vUv.y);
                vec3 scene = texture(uSceneTex, uv).rgb;
                vec3 b2 = texture(uBloomHalfTex, uv).rgb;
                vec3 b4 = texture(uBloomQuarterTex, uv).rgb;
                vec3 bloom = min(vec3(uMaxContribution), (b4 * 0.65 + b2 * 0.55) * uBloomStrength);
                if (uDiagnosticOnly == 1) {
                    fragColor = vec4(clamp(bloom * 2.0, 0.0, 1.0), 1.0);
                    return;
                }
                vec3 c = max(vec3(0.0), scene + bloom);
                float maxC = max(c.r, max(c.g, c.b));
                if (maxC > 0.82) {
                    float targetMax = 0.82 + 0.18 * tanh((maxC - 0.82) * 1.45);
                    float scale = targetMax / maxC;
                    float hot = clamp((maxC - 1.55) * 0.25, 0.0, 0.35);
                    c = clamp(mix(c * scale, vec3(targetMax), hot), 0.0, 1.0);
                }
                fragColor = vec4(c, 1.0);
            }
        """.trimIndent()

        val PARTICLE_BOKEH_FRAGMENT = """#version 300 es
            precision highp float;
            in vec2 vUv;
            in vec2 vScreenUv;
            in vec3 vNormal;
            uniform sampler2D uTexture;
            uniform vec4 uTint;
            uniform float uSphereClip;
            uniform vec2 uSphereCenter;
            uniform float uSphereInnerRadius;
            uniform vec2 uCanvas;
            uniform int uIsOuterDroplet;
            uniform float uAlphaScale;
            out vec4 fragColor;
            void main() {
                float clipAlpha = 1.0;
                if (uSphereClip > 0.5) {
                    float sr = length(vScreenUv * uCanvas - uSphereCenter) / (uSphereInnerRadius + 6.0);
                    if (sr >= 1.0) discard;
                    clipAlpha = 1.0 - smoothstep(0.93, 1.0, sr);
                }
                vec2 b = vUv * 2.0 - 1.0;
                float br = length(b);
                if (br > 1.0) discard;
                vec4 tex = texture(uTexture, vUv);
                if (uIsOuterDroplet == 1) {
                    float rim = smoothstep(0.62, 0.94, br) * (1.0 - smoothstep(0.94, 1.0, br));
                    float spec1 = exp(-dot(b + vec2(0.35), b + vec2(0.35)) * 18.0);
                    float spec2 = exp(-dot(b - vec2(0.32, 0.35), b - vec2(0.32, 0.35)) * 14.0) * 0.45;
                    vec3 rgb = tex.rgb * vec3(0.45, 0.55, 0.62) + rim * vec3(0.72, 0.90, 0.96)
                        + vec3(spec1 * 1.15) + spec2 * vec3(0.65, 0.85, 0.95);
                    float a = clamp((tex.a * 0.45 + rim * 0.72 + spec1 * 0.92 + spec2 * 0.45) * uTint.a * uAlphaScale, 0.0, 1.0);
                    if (a < 0.002) discard;
                    fragColor = vec4(rgb, a);
                } else {
                    float rim = smoothstep(0.55, 0.92, br) * (1.0 - smoothstep(0.92, 1.0, br));
                    float spec = exp(-dot(b + vec2(0.34), b + vec2(0.34)) * 16.0);
                    vec3 rgb = tex.rgb * uTint.rgb * vec3(0.70, 0.80, 0.85) + rim * vec3(0.65, 0.85, 0.95) + vec3(spec * 1.1);
                    float a = clamp((tex.a * 0.65 + spec * 0.85 + rim * 0.50) * uTint.a * clipAlpha * uAlphaScale, 0.0, 1.0);
                    if (a < 0.002) discard;
                    fragColor = vec4(rgb, a);
                }
            }
        """.trimIndent()

        val BOKEH_ORB_FRAGMENT = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform vec2 uCenter;
            uniform float uRadius;
            uniform vec3 uColor;
            uniform float uAlpha;
            uniform float uSoftness;
            out vec4 fragColor;
            void main() {
                float d = length(vUv - uCenter) / max(uRadius, 0.0005);
                if (d > 1.35) discard;
                float falloff = (uSoftness > 0.80)
                    ? (exp(-d * d * 4.5) * 0.55 + exp(-d * d * 24.0) * 0.95)
                    : (1.0 - smoothstep(1.0 - uSoftness, 1.05, d));
                float a = falloff * uAlpha;
                if (a < 0.001) discard;
                fragColor = vec4(uColor * a, 1.0);
            }
        """.trimIndent()

        val PRESENT_FRAGMENT = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform sampler2D uTexture;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTexture, vec2(vUv.x, 1.0 - vUv.y));
            }
        """.trimIndent()
    }
}
