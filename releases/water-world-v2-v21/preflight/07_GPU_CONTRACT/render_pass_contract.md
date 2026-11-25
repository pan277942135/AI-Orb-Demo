# Render Pass Contract

Static order: 1 Background; 2 Resident Back; 3 Sphere Back; 4 Water Interior; 5 Far Ecology; 6 Lighting; 7 Fish; 8 Near Ecology; 9 Sphere Front Glass; 10 Resident Front; 11 Final Composite. Each pass states color space, alpha convention, depth test/write, stencil/mask inputs, FBO and diagnostics. Exact state values remain pending master calibration and device tests.

Future dynamic update order: Core Volume Transform; Internal Fluid Update; Filament Update; Particle Update; Dynamic Lighting; Caustics Update; Glass Refraction Update; Final Composite. Each output is an immutable motion snapshot so all passes use the same time, transform and phase. This sequence is a dependency contract, not code.
