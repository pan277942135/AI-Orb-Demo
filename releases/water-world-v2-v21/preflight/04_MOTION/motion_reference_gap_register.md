# Motion Reference Gap Register

| Gap | Required evidence | Affected elements | Current status |
|---|---|---|---|
| Raw master PNG bytes | Original downloadable PNG | all visual mapping | BLOCKED_MASTER_SOURCE
| Temporal reference clip or frame sequence | Stable camera, known timestamps, at least one full behavior cycle per moving class | fish, residents, plants, bubbles, water, core | NOT_PROVIDED
| Fish rig / separated body parts | Tail and fin masks, pivot/weights or rig mesh | all four fish | ASSET_NOT_AVAILABLE
| Individual plant leaves | leaf masks/mesh and pivots | both plants | ASSET_NOT_AVAILABLE
| Standalone core filaments | paths/geometry/width/depth | CORE_FILAMENT | ASSET_NOT_AVAILABLE
| Internal core particle positions | positions, velocity, lifetime, size | CORE_PARTICLES | ASSET_NOT_AVAILABLE
| Dynamic Water World values | angular speed, flow speed, amplitudes, frequencies, phase/loop modes | all animatable Water World elements | NEEDS_TEMPORAL_REFERENCE
| API36 hardware evidence | device GLES/vendor/renderer and capture | future GPU stage | NOT_RUN

Every missing numeric value remains null in motion_element_registry.json.
