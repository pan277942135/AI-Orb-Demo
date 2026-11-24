#!/usr/bin/env python3
"""Validate the pinned YuJian Core inputs and regenerate their provenance manifest."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE_COMMIT = "03db3692669847084d7e48fc3128f85cbf832af9"
EXPECTED_WEBP_SHA = "daa85a1fc13fac4a7ca562959c2d19172ee0f1e94277c3eeb32ef1f09a1a7b09"
EXPECTED_SPHERE_FS_SHA = "eff0af75967117b501be6409009dfa62d9fb336065e458464a19d3e5221bbc05"
EXPECTED_QUAD_VS_SHA = "e6aaef05da273580ec1133edbe112a6ef6f9847abaa931fd31aeaa1bcb682bd8"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_sha(path: Path) -> str:
    return sha256(path.read_bytes())


def main() -> None:
    assets = ROOT / "app/src/main/assets/yujian_orb_core"
    webp_path = assets / "processing_water_core_v1.webp"
    fragment_path = assets / "YuJianCoreFragment.glsl"
    vertex_path = assets / "YuJianCoreVertex.glsl"
    source_fragment_path = ROOT / "docs/yujian_hybrid/source_reference/YUJIAN_SPHere_FS_SOURCE.glsl"
    webp_bytes = webp_path.read_bytes()
    fragment_bytes = fragment_path.read_bytes()
    vertex_bytes = vertex_path.read_bytes()
    source_fragment_bytes = source_fragment_path.read_bytes()
    assert file_sha(webp_path) == EXPECTED_WEBP_SHA, "YuJian WebP binary differs from pinned source"
    assert sha256(source_fragment_bytes) == EXPECTED_SPHERE_FS_SHA, "Extracted source SPHERE_FS provenance mismatch"
    assert file_sha(vertex_path) != EXPECTED_QUAD_VS_SHA, "Target vertex must be adapted to Demo projection inputs"

    fragment = fragment_bytes.decode("utf-8")
    vertex = vertex_bytes.decode("utf-8")
    assert fragment.startswith("#version 300 es\n") and vertex.startswith("#version 300 es\n")
    assert "uniform sampler2D uBackTex" in fragment
    assert "rotateCoreInterior(p, uTime)" in fragment
    assert "float causticField(" in fragment and "float meniscusY =" in fragment
    assert "uProjectionDepth" in fragment and "gl_FragDepth" in fragment
    assert "uQuadExtent" in vertex and "vNdc = uSphereCenter + aPosition * uQuadExtent" in vertex
    assert "texture2D(" not in fragment and "gl_FragColor" not in fragment
    assert "uSphereRadius" not in fragment

    with Image.open(webp_path) as image:
        assert image.format == "WEBP", f"Expected WebP, got {image.format}"
        assert image.size == (384, 384), f"Unexpected WebP size {image.size}"
        assert image.mode == "RGBA", f"Expected RGBA, got {image.mode}"
        rgba = image.convert("RGBA").tobytes()
    alpha = rgba[3::4]
    alpha_nonzero = sum(value > 0 for value in alpha)
    alpha_opaque = sum(value == 255 for value in alpha)

    source_file_hashes = {
        "app/src/main/java/com/yujian/ai/ui/designsystem/components/aiorb/AIOrbCore.kt": "44ee4f2ec39df7651b2bc3d6e6102c29a019aa24c63fa009555bf2a75655767a",
        "app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbCrystalWakeGlOverlay.kt": "91b7503522338c65a33d2390385808e3b1985733be925f1e7dfa2141d331ad17",
        "app/src/main/java/com/yujian/ai/ui/waterlight/renderer/WaterLightGlSupport.kt": "8dc5ae12735ba723654e4b62c8491f52777cdd8c9d4f0c67da0369a1b744a277",
        "app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbFluidRefractionProgramV1.kt": "d23c5f9be1e95d9b19b2f99e003c457461a00fec8e7a0a783cc6c8a124bfaf70",
        "app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbResidentSystemsV1.kt": "1e2d0459936b15a98e06cff99a92c9b831fba50a7dc93dee3e765e6517a38129",
        "app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbCausticsAdapter.kt": "d338fa3f5509e8c96c8834e9a79eb1b20ae25dcc171df8185ac70ae763388615",
        "app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbDropletRenderer.kt": "7826a94f522b24e0e0445f1dbdfc9c87416a2a7f24f5c9fdc4b7897a4a6eae23",
        "app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbFilamentRenderer.kt": "6ae8b8603301862ff5c8bfe33e5bf0f57f3c68280eb6bc85b1e2b3a7527e26c1",
        "app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbRuntimeProfileV1.kt": "7e0f549ab297c934072c5e83323562b039d58c3044a4df954936b6597fec9ae6",
        "app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbGrassMotionProfileV11.kt": "481db889e1b7c41d1f80c39876aba2084259eebb703505571914d436aabffeaf",
        "app/src/main/assets/orb_fluid_v1/orb_fluid_refraction_v1.agsl": "909395f21c7be87b4d73c62a2406c4b34eeb3d2fd2b7a3b2ba8060f3d1421011",
        "app/src/main/res/drawable-nodpi/processing_water_core_v1.webp": EXPECTED_WEBP_SHA,
    }

    targets = {
        "app/src/main/assets/yujian_orb_core/YuJianCoreFragment.glsl": file_sha(fragment_path),
        "app/src/main/assets/yujian_orb_core/YuJianCoreVertex.glsl": file_sha(vertex_path),
        "app/src/main/assets/yujian_orb_core/processing_water_core_v1.webp": file_sha(webp_path),
    }
    manifest = {
        "schema": "YUJIAN_ORB_CORE_SOURCE_MANIFEST_1_0",
        "sourceCommit": SOURCE_COMMIT,
        "sourceMainObserved": SOURCE_COMMIT,
        "demoBaseCommit": "a8e9249da74784fdf89cb08ec089ae9b676eb45a",
        "demoBranch": "codex/yujian-orb-third-tab-hybrid-v1",
        "items": [
            {
                "sourcePath": "app/src/main/java/com/yujian/ai/ui/designsystem/components/aiorb/AIOrbCore.kt",
                "sourceSha256": source_file_hashes["app/src/main/java/com/yujian/ai/ui/designsystem/components/aiorb/AIOrbCore.kt"],
                "sourceSymbol": "AIOrbCore routing: WebP+AGSL and optional GL candidate",
                "targetPath": None,
                "targetSha256": None,
                "dependencyRole": "Production route and fallback audit",
                "migrationStatus": "AUDITED_NOT_MIGRATED",
            },
            {
                "sourcePath": "app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbCrystalWakeGlOverlay.kt",
                "sourceSha256": source_file_hashes["app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbCrystalWakeGlOverlay.kt"],
                "sourceSymbol": "SPHERE_FS",
                "sourceSymbolSha256": EXPECTED_SPHERE_FS_SHA,
                "targetPath": "app/src/main/assets/yujian_orb_core/YuJianCoreFragment.glsl",
                "targetSha256": targets["app/src/main/assets/yujian_orb_core/YuJianCoreFragment.glsl"],
                "dependencyRole": "Core body, chromatic background refraction, meniscus, caustics, sunlight and Fresnel",
                "migrationStatus": "EXTRACTED_AND_ADAPTED_TO_GLES3_AND_SHARED_DEMO_PROJECTION",
            },
            {
                "sourcePath": "app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbCrystalWakeGlOverlay.kt",
                "sourceSha256": source_file_hashes["app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbCrystalWakeGlOverlay.kt"],
                "sourceSymbol": "QUAD_VS",
                "sourceSymbolSha256": EXPECTED_QUAD_VS_SHA,
                "targetPath": "app/src/main/assets/yujian_orb_core/YuJianCoreVertex.glsl",
                "targetSha256": targets["app/src/main/assets/yujian_orb_core/YuJianCoreVertex.glsl"],
                "dependencyRole": "Source quad mapped to projected Core bounds through Demo matrices",
                "migrationStatus": "ADAPTED_TO_GLES3_PROJECTED_BOUNDS",
            },
            {
                "sourcePath": "app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbResidentSystemsV1.kt",
                "sourceSha256": source_file_hashes["app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbResidentSystemsV1.kt"],
                "sourceSymbol": "OrbCoreRotationFieldV1.inverseSample",
                "targetPath": "app/src/main/assets/yujian_orb_core/YuJianCoreFragment.glsl",
                "targetSha256": targets["app/src/main/assets/yujian_orb_core/YuJianCoreFragment.glsl"],
                "dependencyRole": "Frozen 7-second radial 2D interior optical coordinate rotation; not rigid mesh rotation",
                "migrationStatus": "PROFILE_ADAPTED_TO_SOURCE_GL_INTERIOR_OPTICAL_COORDINATES",
            },
            {
                "sourcePath": "app/src/main/res/drawable-nodpi/processing_water_core_v1.webp",
                "sourceSha256": EXPECTED_WEBP_SHA,
                "targetPath": "app/src/main/assets/yujian_orb_core/processing_water_core_v1.webp",
                "targetSha256": targets["app/src/main/assets/yujian_orb_core/processing_water_core_v1.webp"],
                "dependencyRole": "Pinned original source Core image for YuJian's image/AGSL route; SPHERE_FS does not sample it",
                "migrationStatus": "COPIED_BIT_IDENTICALLY_PACKAGED_NOT_SAMPLED_BY_EXTRACTED_GL_SHADER",
            },
        ] + [
            {
                "sourcePath": path,
                "sourceSha256": digest,
                "targetPath": None,
                "targetSha256": None,
                "dependencyRole": role,
                "migrationStatus": status,
            }
            for path, digest, role, status in [
                ("app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbFluidRefractionProgramV1.kt", source_file_hashes["app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbFluidRefractionProgramV1.kt"], "Audited WebP+AGSL Core path; not reused as a second Compose/RuntimeShader host", "AUDITED_NOT_MIGRATED"),
                ("app/src/main/assets/orb_fluid_v1/orb_fluid_refraction_v1.agsl", source_file_hashes["app/src/main/assets/orb_fluid_v1/orb_fluid_refraction_v1.agsl"], "Pinned AGSL fluid image path; not used by the selected GLES SPHERE_FS route", "AUDITED_NOT_MIGRATED"),
                ("app/src/main/java/com/yujian/ai/ui/waterlight/renderer/WaterLightGlSupport.kt", source_file_hashes["app/src/main/java/com/yujian/ai/ui/waterlight/renderer/WaterLightGlSupport.kt"], "Compose TextureView/EGL support; second EGL host is excluded", "EXCLUDED_SECOND_EGL_HOST"),
                ("app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbCausticsAdapter.kt", source_file_hashes["app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbCausticsAdapter.kt"], "Source-app caustics adapter; Core caustics are already inside SPHERE_FS", "EXCLUDED_OUTSIDE_CORE_SHADER"),
                ("app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbDropletRenderer.kt", source_file_hashes["app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbDropletRenderer.kt"], "Source outer droplet system; Demo owns outer particles", "EXCLUDED_OUTER_DUPLICATE"),
                ("app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbFilamentRenderer.kt", source_file_hashes["app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbFilamentRenderer.kt"], "Source Resident paths; Tab 3 must use Demo's five frozen Residents", "EXCLUDED_OUTER_DUPLICATE"),
                ("app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbRuntimeProfileV1.kt", source_file_hashes["app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbRuntimeProfileV1.kt"], "Source scene/stage runtime profile; no recognition business is imported", "AUDITED_NOT_MIGRATED"),
                ("app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbGrassMotionProfileV11.kt", source_file_hashes["app/src/main/java/com/yujian/ai/ui/waterlight/renderer/OrbGrassMotionProfileV11.kt"], "Source grass motion; grass is excluded from Tab 3", "EXCLUDED_OUTER_DUPLICATE"),
            ]
        ],
    }
    (ROOT / "docs/yujian_hybrid/YUJIAN_ORB_SOURCE_MANIFEST.json").write_text(json.dumps(manifest, indent=2) + "\n")
    validation = {
        "schema": "YUJIAN_TAB3_SOURCE_ASSET_PREFLIGHT_1_0",
        "sourceCommit": SOURCE_COMMIT,
        "webp": {
            "binarySha256": file_sha(webp_path),
            "width": 384,
            "height": 384,
            "decodedMode": "RGBA",
            "decodedRgbaSha256": sha256(rgba),
            "alphaMin": min(alpha),
            "alphaMax": max(alpha),
            "alphaNonzeroPixels": alpha_nonzero,
            "alphaOpaquePixels": alpha_opaque,
            "status": "PASS",
        },
        "sourceSphereShaderSha256": sha256(source_fragment_bytes),
        "adaptedFragmentSha256": file_sha(fragment_path),
        "adaptedVertexSha256": file_sha(vertex_path),
        "runtimeShaderSamplesSourceWebp": False,
        "runtimeShaderWebpStatus": "PACKAGED_NOT_SAMPLED_BY_SOURCE_SPHERE_FS",
        "status": "PASS",
    }
    (ROOT / "docs/yujian_hybrid/YUJIAN_SOURCE_ASSET_VALIDATION.json").write_text(json.dumps(validation, indent=2) + "\n")
    print(json.dumps(validation, indent=2))


if __name__ == "__main__":
    main()
