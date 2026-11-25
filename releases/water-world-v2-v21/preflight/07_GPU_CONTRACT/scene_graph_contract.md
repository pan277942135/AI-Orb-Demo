# Scene Graph Contract

V2 layer IDs/order are inherited verbatim and marked uncalibrated: background; resident_back; sphere_back_glass; water; plants; rocks; fish; glass_front; bubbles; resident_front. Existing placements and sphere geometry are visual estimates. The scene graph wrapper records this source and does not alter it before master analysis.

Each node declares stable identity, source asset, parent, coordinate space, transform, depth/occlusion role, material, blend mode and confidence. Master region links and calibrated transforms remain null until the raw master is accessible. Resident perimeter nodes remain isolated from Core Internal motion.
