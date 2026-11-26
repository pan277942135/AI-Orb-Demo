# Core Motion GPU Execution Contract

## Resource inventory

See 04_MOTION/core_volume_asset_audit.md and JSON. AI Orb currently contains 32 spatial WebP slices (256x256x32), three 2D maps and runtime shader/geometry. It does not contain a temporal volume sequence. Independent filament path geometry and an internal particle dataset are absent.

The registry declares consumed `runtime_inputs` and the dependency graph declares produced edge `outputs`; the Validator checks the Core tokens match across both structures.

## Inputs and coordinate contracts

All core motion uses CORE_LOCAL_3D with one shared immutable snapshot per frame. Volume transform, fluid flow, density/normal, filament paths, particle attributes, caustics and optical response remain separate elements. The core can rotate independently from outer shell; do not rotate the whole Orb. Volume sampling, flow advection, caustics and glass refraction consume the same transform/time snapshot.

The AI Orb source texture domain uses `[0,0,0]` as its local origin. This is not its density-weighted centroid and is not a measured or approved Water World master pivot. Target pivots remain unresolved pending pixel geometry from the frozen master. The motion registry `runtime_inputs` and graph-edge `outputs` are cross-validated so every declared Core dependency output is consumed by the destination element.

## Runtime interface

Required future methods: evaluate(globalTime), evaluateLocalTime(elementId, globalTime), getInitialState(elementId), pause(elementId), resume(elementId), reset(elementId), setSpeedScale(elementId, scale), setGlobalSpeedScale(scale). This preflight defines signatures only.

## Current AI Orb source evidence

The existing AIOrbMotionController has a base angular velocity of 26 deg/s and normalized axis (0.14,0.98,-0.14). OrbFluidVolumeRenderer binds uCoreRotMat, uFluidPhase, uMeniscusPhase, uCausticsPhase and uVolumeLoaded through the volume manager. CoreFluidShaders consumes sampler3D uFluidVolume and performs advected 3D sampling. These values and names are evidence for the current source branch, not approved Water World material or target motion parameters.

## Reserved shader inputs

- Volume: sampler3D uCoreVolume; mat3 uCoreRotation; int uCoreVolumeReady; volume dimensions and density scale.
- Flow: sampler3D uFlowField; float uFluidPhase; vec3 uFlowScale; deformation boundary.
- Filaments: path/width/opacity/color/depth buffers and independent phase. Source asset currently unavailable.
- Particles: position/velocity/lifetime/size/color/depth buffers and deterministic seed. Source asset currently unavailable.
- Optics: density, normal, depth, caustics, IOR and refraction inputs from same snapshot.

## Required evidence before dynamic acceptance

Temporal master video, agreed angular/flow ranges, time origin, pivots, phase behavior, flow-field boundary, geometry for filaments/particles, API36 hardware capture and pause/resume deterministic checks. No values are guessed here.
