# Shader Uniform Contract

Uniform interfaces are semantic placeholders pending Work 2 shader design; do not copy AI Orb V3 numbers as Water World values.

- Camera: uView, uProjection, uViewport, uMasterToWorld; matrix/color spaces documented at binding.
- Texture resources: uBackground, uWaterColor, uWaterNormal, uWaterDepth, uSphereMask, uGlassBackMask, uGlassFrontMask, uResidentAtlas, uEcology textures; exact sampler targets/formats pending GLES validation.
- Static layer: uTransform, uOpacity, uDepthRole, uMasterRegion, uColorSpaceMode, uAlphaMode.
- Dynamic reserved: uGlobalTime, uLocalTime, uSpeedScale, uPhase; uCoreRotation; uFlowField; uDeformation; uFilamentPath; uParticleBuffer; uCausticsPhase; uRefractionSource.
- Output: linear premultiplied HDR scene color plus explicit coverage alpha, composited once before output encoding.

Every uniform must have one producer, declared coordinate space and units, shader consumer, fallback/error behavior and diagnostic visualization.
