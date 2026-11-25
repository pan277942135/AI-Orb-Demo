# Renderer Architecture Contract

Status: design contract only; no renderer created or production code changed.

The future Water World path is isolated from AI Orb V4.1 and photo-water/task-energy code: WaterWorldStaticRenderer owns static reconstruction. CoreMotionRendererInterface is reserved for a later dynamic stage. Proposed responsibilities are AssetLoader, SceneGraph, Camera, BackgroundRenderer, ResidentRenderer, SphereRenderer, WaterRenderer, EcologyRenderer, LightingRenderer, CoreMotionInterface and OpticalCompositor. These are logical modules, not current production classes.

AI Orb remains a read-only technical reference: GLES 3.0, GL_TEXTURE_3D volume upload, FBO scene/refraction capture, shader program compilation, HDR/post-processing, context-bound GL resources and recovery. Work 2 must implement its own scene and asset bindings without changing the AI Orb runtime.
