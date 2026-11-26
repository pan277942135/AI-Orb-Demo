# GPU Diagnostics Contract

Stable modes: BACKGROUND_ONLY, RESIDENT_ONLY, SPHERE_MASK_ONLY, GLASS_BACK_ONLY, GLASS_FRONT_ONLY, WATER_ONLY, FISH_ONLY, PLANTS_ONLY, ROCKS_ONLY, LIGHTING_ONLY, CAUSTICS_ONLY, CORE_VOLUME_ONLY, CORE_FLUID_ONLY, CORE_FILAMENT_ONLY, CORE_PARTICLES_ONLY, DEPTH_VISUALIZATION, OCCLUSION_ID, NORMAL_VISUALIZATION, ALPHA_VISUALIZATION, FINAL_COMPOSITE.

Each capture reports mode, build/asset hashes, camera and motion snapshot IDs, viewport, GL vendor/renderer/version, FBO status, texture load states, GL errors, render size, and PNG dimensions/SHA. A mode backed by a missing asset reports ASSET_NOT_AVAILABLE; it must not draw a procedural substitute and claim parity. This preflight does not execute GPU rendering.
