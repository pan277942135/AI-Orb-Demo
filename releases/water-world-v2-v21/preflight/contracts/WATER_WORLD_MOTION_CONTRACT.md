# Water World Motion Contract

All values are interfaces; no animation is implemented. Movement identities stay independent.

- Four fish: one identity each, with separate body, tail and fin child identities. Swim path, orientation, depth track, tail frequency/amplitude and fin offsets remain null. Existing cutouts and meshes do not provide a verified animated rig.
- Five Residents G01, G02, A01, A02 and C01: independent SPLINE_PATH/RIBBON_FLOW instances. Keep their archived estimated source paths as references; do not promote estimated curve/width to calibrated truth. Speed, phase, flow direction, width pulse and brightness pulse remain null.
- Plants: left/right root anchors exist as placement estimates. Individual leaf geometry/pivots are not available; leaf motion interface is reserved with null deformation weights and sway rates.
- Water and caustics: normal/depth/height maps exist; no temporal sequence. Flow field, normal disturbance, UV velocity and caustic phase remain null.
- Bubbles B01-B09: sprite and placement estimates exist. Per-bubble position/depth/velocity/lifetime/radius/opacity are unvalidated. The old placement file contains provisional speed bounds and seed; these are recorded as untrusted estimates and are not frozen values.
- Rocks and background: STATIC.

Each animated identity resolves time through MotionTimelineContract. No shared path or phase may be silently applied across identities.
