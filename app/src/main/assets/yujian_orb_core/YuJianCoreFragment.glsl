#version 300 es
precision highp float;
in vec2 vNdc;
layout(location = 0) out vec4 fragColor;
uniform sampler2D uBackTex;
uniform float uTime;
uniform float uWake;
uniform vec2 uSphereCenter;
uniform vec2 uSphereRadii;
uniform float uHdrScale;
uniform float uCoreCenterViewZ;
uniform float uCoreRadiusView;
uniform vec4 uProjectionDepth;
uniform float uShowSun;
uniform float uShowCaustics;

vec2 rot2(vec2 p, float a) {
    float c = cos(a); float s = sin(a);
    return vec2(c * p.x - s * p.y, s * p.x + c * p.y);
}

float causticOctave(vec2 uv, float t, float fi) {
    vec2 p = rot2(uv, 0.42 + fi * 2.39996);
    for (int k = 0; k < 2; k++) {
        float fk = float(k);
        vec2 w = vec2(
            sin(p.y * (1.22 + fi * 0.19 + fk * 0.13) + p.x * 0.25 + t * (0.42 + fi * 0.06) + fi * 1.6),
            cos(p.x * (0.94 + fi * 0.16 + fk * 0.11) - p.y * 0.30 - t * (0.37 + fi * 0.05) + fi * 1.1)
        );
        p += w * (0.44 / (1.0 + fi * 0.16 + fk * 0.11));
    }
    vec2 d1 = vec2(1.0 + fi * 0.20, 0.34 + fi * 0.12);
    vec2 d2 = vec2(-0.50 - fi * 0.10, 1.10 + fi * 0.08);
    float ridge = sin(dot(p, d1) + t * 0.14) * sin(dot(p, d2) - t * 0.11);
    return 0.18 / (abs(ridge) * 5.2 + 0.20);
}

float causticField(vec2 uv, float t) {
    vec2 p = uv + vec2(sin(uv.y * 0.4 + t * 0.22) * 0.35, cos(uv.x * 0.35 - t * 0.18) * 0.35);
    float a = causticOctave(p, t, 0.0);
    float b = causticOctave(rot2(p * 1.618 + vec2(1.7, -1.2), 1.18), t * 0.80, 1.0);
    float c = causticOctave(rot2(p * 2.54 + vec2(2.3, -0.7), 1.54), t * 0.64, 2.0);
    return pow(clamp(max(a * 1.15, b * 0.82) + c * 0.38, 0.0, 2.1), 1.45);
}

// Adapted from the pinned OrbCoreRotationFieldV1.inverseSample (7 s source period, radial falloff 1.6).
// This source profile rotates interior optical coordinates; it is not a rigid 3D mesh spin.
vec2 rotateCoreInterior(vec2 p, float t) {
    float radius = clamp(length(p), 0.0, 1.0);
    if (radius <= 0.000001) return p;
    float radialWeight = 1.0 - smoothstep(0.0, 1.0, pow(radius, 1.6));
    float omega = 0.6462705 + (0.8975979 - 0.6462705) * radialWeight;
    float edgeFade = 1.0 - smoothstep(0.78, 1.0, radius);
    float angle = -max(t, 0.0) * omega * edgeFade;
    return rot2(p, angle);
}

void main() {
    vec2 baseUv = vNdc * 0.5 + 0.5;
    vec4 backSample = texture(uBackTex, baseUv);
    vec2 p = (vNdc - uSphereCenter) / max(uSphereRadii, vec2(0.001));
    float r2 = dot(p, p);
    float r = sqrt(r2);
    if (r >= 1.01) discard;
    float surfaceViewZ = uCoreCenterViewZ + uCoreRadiusView * sqrt(max(0.0, 1.0 - min(r2, 1.0)));
    float surfaceClipZ = uProjectionDepth.x * surfaceViewZ + uProjectionDepth.y;
    float surfaceClipW = uProjectionDepth.z * surfaceViewZ + uProjectionDepth.w;
    gl_FragDepth = clamp((surfaceClipZ / max(abs(surfaceClipW), 0.0001)) * 0.5 + 0.5, 0.0, 1.0);
    vec2 opticalP = rotateCoreInterior(p, uTime);
    float sphereAA = 1.0 - smoothstep(0.975, 1.01, r);
    float nz = sqrt(max(0.0, 1.0 - min(r2, 1.0)));
    vec3 N = normalize(vec3(p.x, p.y, nz));

    // 1. Chromatic glass refraction sampling backFbo (bends back grass & back ribbons!)
    vec2 refractShift = N.xy * (0.28 * uSphereRadii * (0.55 + 0.45 * (1.0 - nz)));
    vec2 uvR = clamp(baseUv - refractShift * 1.08, 0.0, 1.0);
    vec2 uvG = clamp(baseUv - refractShift * 1.00, 0.0, 1.0);
    vec2 uvB = clamp(baseUv - refractShift * 0.91, 0.0, 1.0);
    vec4 backR = texture(uBackTex, uvR);
    vec4 backG = texture(uBackTex, uvG);
    vec4 backB = texture(uBackTex, uvB);
    vec3 refractedBg = vec3(backR.r, backG.g, backB.b);
    float refractedBgA = max(backG.a, max(backR.a, backB.a));

    // 2. Deep oceanic emerald-teal water volume + curved S-meniscus wave surface
    float meniscusY = 0.09 * sin(opticalP.x * 3.1 + uTime * 1.75) +
        0.045 * cos(opticalP.x * 5.4 - uTime * 1.25) - 0.10 * opticalP.x + 0.06;
    float inLowerLiquid = 1.0 - smoothstep(meniscusY - 0.12, meniscusY + 0.10, opticalP.y);
    float meniscusCrest = exp(-pow((opticalP.y - meniscusY) * 18.0, 2.0)) * (1.0 - smoothstep(0.75, 0.98, r));

    vec3 deepTeal = vec3(0.02, 0.18, 0.24);
    vec3 midAqua = vec3(0.04, 0.44, 0.50);
    vec3 upperGlassTint = vec3(0.10, 0.40, 0.48);
    vec3 waterBody = mix(upperGlassTint, mix(deepTeal, midAqua, smoothstep(-0.85, 0.35, opticalP.x - opticalP.y * 0.4)), inLowerLiquid);

    // Sphere optical body density (rich translucent glass center, deeper color at liquid bottom)
    float bodyDensity = mix(0.62, 0.88, inLowerLiquid * 0.72 + (1.0 - nz) * 0.35);
    vec3 interior = waterBody * bodyDensity + refractedBg * (0.92 - bodyDensity * 0.42);
    interior += vec3(0.45, 0.96, 1.0) * meniscusCrest * 0.58 * uWake;

    // 3. GPU multi-octave caustics brightly lighting the lower-right basin & upper-left entry
    if (uShowCaustics > 0.5) {
        vec2 cUv = opticalP * 2.15 + N.xy * 0.35;
        float cVal = causticField(cUv, uTime * 0.85);
        float veins = smoothstep(0.22, 1.28, cVal);
        float lowerBasin = (1.0 - smoothstep(-0.75, 0.45, opticalP.y)) * smoothstep(-0.85, 0.75, opticalP.x * 0.6 - opticalP.y * 0.5);
        vec3 cyanCaustic = vec3(0.26, 0.96, 0.94);
        interior += cyanCaustic * veins * (0.28 + 0.72 * lowerBasin) * uWake * 0.88;
    }

    // 4. Upper-left golden solar entry flare & diagonal refractive rays
    if (uShowSun > 0.5) {
        vec2 sunPos = vec2(-0.42, 0.44);
        float dSun = length(opticalP - sunPos);
        float sunCore = exp(-dSun * dSun * 14.0);
        float sunHalo = exp(-dSun * 3.1) * 0.62;
        // Diagonal rays streaming toward lower-right interior
        vec2 rayDir = normalize(vec2(0.76, -0.65));
        vec2 rel = opticalP - sunPos;
        float proj = dot(rel, rayDir);
        float perp = abs(rel.x * rayDir.y - rel.y * rayDir.x);
        float rayMask = smoothstep(0.0, 0.3, proj) * (1.0 - smoothstep(0.15, 1.35, proj)) * exp(-perp * 7.5);
        vec3 goldSun = vec3(1.0, 0.88, 0.52);
        vec3 whiteHot = vec3(1.0, 0.99, 0.92);
        interior += goldSun * (sunHalo + rayMask * 0.38) * uWake;
        interior += whiteHot * sunCore * 0.95 * uWake;
    }

    // 5. Double Spherical Fresnel Rim Shell (gold top-left/right -> electric cyan bottom-left/right)
    float fresnel = pow(1.0 - nz, 3.6);
    float innerThicknessRim = smoothstep(0.78, 0.90, r) * (1.0 - smoothstep(0.92, 0.985, r));
    float sunBias = clamp(dot(normalize(p + vec2(0.0001)), normalize(vec2(-0.6, 0.7))) * 0.5 + 0.5, 0.0, 1.0);
    vec3 rimCol = mix(vec3(0.38, 0.95, 1.0), vec3(1.0, 0.88, 0.54), sunBias);
    vec3 specularTop = vec3(0.92, 0.99, 1.0) * exp(-length(p - vec2(0.02, 0.62)) * 4.5) * 0.45;

    vec3 sphereRgb = interior + rimCol * (fresnel * 0.95 + innerThicknessRim * 0.34) * (0.55 + 0.45 * uWake) + specularTop;
    float sphereAlpha = clamp(max(bodyDensity, max(fresnel * 0.95, refractedBgA)), 0.0, 0.98);
    vec4 spherePremul = vec4(sphereRgb * (sphereAlpha * uHdrScale), sphereAlpha);

    // Anti-aliased blend at sphere outer edge against backFbo
    fragColor = mix(backSample, spherePremul, sphereAA);
}
