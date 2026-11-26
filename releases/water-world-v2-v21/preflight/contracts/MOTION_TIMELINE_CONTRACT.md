# Motion Timeline Contract

Status: schema frozen; all unmeasured Water World values remain null. This contract does not implement animation.

## Time model

- STATIC_REFERENCE_TIME = 0 seconds. It is the only approved static reconstruction and comparison instant.
- One monotonic global time source supplies globalTime and deltaTime. Renderers must not create independent clocks.
- Each element derives localTime = (globalTime - timeOrigin) * speedScale + phaseOffset. Values unavailable from a temporal reference remain null in the registry; the runtime must require explicit configuration before motion.
- Pause and resume preserve phase without a discontinuity. Reset returns to the element initial state at its time origin.
- Deterministic state requires a caller-supplied seed; no guessed production seed is frozen. The existing V2 bubble estimate seed is not validated and is not accepted as this contract default.
- Global speed scale and per-element speed scale multiply; default scale is 1 only after motion values are approved.
- Looping and interpolation are per-element contract fields; no loop mode is assumed when unknown.

## Required future interface

- evaluate(globalTime)
- evaluateLocalTime(elementId, globalTime)
- getInitialState(elementId)
- pause(elementId)
- resume(elementId)
- reset(elementId)
- setSpeedScale(elementId, scale)
- setGlobalSpeedScale(scale)

## Determinism and validation

Given identical element data, global time, time origin and seed, evaluate must return identical transforms, deformations and material phases. Unit tests must cover pause/resume continuity, reset, independent element phase, global/per-element speed scaling, out-of-order time requests and deterministic seeds. No tests or runtime implementation are added to the Android production application in this preflight.
