# AI Orb Core Motion Contract

Scope: future core motion inputs only. This file does not implement the motion system. The V2.1 Water World master remains inaccessible as raw bytes, so Water World motion values are unvalidated.

## Audited resource identity

- CORE_VOLUME: 32 ordered 256x256 Lossless RGBA WebP spatial slices, 256x256x32, GL_TEXTURE_3D.
- CORE_FLUID: shared 3D volume RGB/alpha plus emerald_distribution detail map. It has no time-series texture frames.
- CORE_DENSITY: volume alpha channel declared linear density.
- CORE_COLOR: volume RGB declared sRGB.
- CORE_CAUSTICS: separate 2D caustics map plus shader-driven sampling; not a baked temporal sequence.
- CORE_GLASS: environment map and production shader path.
- CORE_FILAMENT: no independent geometry/path asset. A fine-filament channel exists in caustics.webp; it does not establish standalone filaments.
- CORE_PARTICLES: no dedicated internal particle asset. Existing AIOrbParticles draws a far-depth scene pass.

## Independent coordinate and motion interfaces

- Core volume transform: CORE_LOCAL_3D. The AI Orb source texture domain is centered at local origin `[0,0,0]`; that is a source-domain origin, not its density-weighted centroid and not an approved Water World master pivot. Water World pivot remains unresolved until measured from the frozen master.
- Fluid: independent FLOW_FIELD in CORE_LOCAL_3D; requires velocity, curl, boundary, density and normal inputs.
- Filaments: RIBBON_FLOW interface is reserved, but geometry, centerline, width and depth assets are unavailable.
- Particles: PARTICLE_SYSTEM interface is reserved; no internal particle positions or lifetimes are available.
- Glass: OPTICAL_RESPONSE consumes the transforms, fluid density/normal, caustics, depth and occlusion from the same timeline snapshot.

## Runtime input and dependency output contract

The registry's `runtime_inputs` list declares consumed data tokens; the dependency graph's `outputs` list declares the tokens produced by each edge. Preflight requires exact coverage for the Core paths: volume position/flow direction into fluid, density/color/light/refraction samples from volume, material/density/normal/refraction inputs from fluid, attenuation/absorption inputs from density, transmission color from color, internal caustic radiance from caustics, and filament/particle depth tokens into glass. These are interface names only; they do not imply that unavailable Water World temporal or geometry values exist.

## Source-backed current AI Orb behavior, not Water World target values

At the audited AI-Orb-Demo HEAD, AIOrbMotionController uses a base core rotation of 26 degrees per second around normalized axis (0.14, 0.98, -0.14). OrbFluidVolumeRenderer binds uCoreRotMat, uFluidPhase, uMeniscusPhase and uCausticsPhase; CoreFluidShaders uses the 3D volume sample and phase-driven advection. These are evidence of the current AI Orb path only. Do not copy them into a Water World profile without a separate approved reference and target design.

## Missing inputs

No temporal reference for the Water World master; no volume frames by time; no standalone filament mesh/path; no internal particle dataset; no approved rotation rate, fluid velocity/curl scale, amplitudes, frequencies, phase offsets or loop behavior. All such values remain null and NEEDS_TEMPORAL_REFERENCE.
