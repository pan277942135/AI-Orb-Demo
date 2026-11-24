# V4.1 Six-Frame Visual Acceptance

## Status

**PENDING_REAL_GPU_CAPTURE** and **REAL_DEVICE_QA_PENDING**. This report keeps software validation separate from physical-device visual acceptance. No new V4.1 GPU or Window screenshots are claimed here.

The frozen comparison source is `public/assets/aistudio/AI_Orb_KF01-KF06_________________01802259.png`, with regions in `FROZEN_6KF_REGIONS.json`. Historical CPU proxy images have been retained under `offline_cpu_proxy/` and are explicitly not acceptance evidence.

## Frame checklist

| Frame | Frozen state | GPU PNG | Window PNG | Visual result |
|---|---|---|---|---|
| KF01 | ORB_RELEASE; perimeter inactive | Pending device capture | Pending device capture | Not assessed |
| KF02 | BOTTOM_CONTACT; local center contact only | Pending device capture | Pending device capture | Not assessed |
| KF03 | BOTTOM_SPLIT; bottom propagation only | Pending device capture | Pending device capture | Not assessed |
| KF04 | SIDE_ASCEND; both sides near 60%, top inactive | Pending device capture | Pending device capture | Not assessed |
| KF05 | TOP_JOIN; brief center join highlight | Pending device capture | Pending device capture | Not assessed |
| KF06 | PERIMETER_LOOP; all four sides continuous | Pending device capture | Pending device capture | Not assessed |

## Checks ready for device run

- V4.1 layout and capture settings are centralized in `TASK_6KF_VISUAL_LOCK_V41.json`.
- Capture code exports GPU-only layer diagnostics and paired Android Window captures; it rejects software-renderer results as hardware GPU evidence.
- Each capture is designed to record the locked snapshot, FBO/readback state, image dimensions and SHA256 before advancing.
- The uploaded background source must be available at `app/src/main/assets/task_demo/background_lake_glass_v41.png` before the six frames can be accepted.
- A physical Android device is required for KF01–KF06 visual acceptance and the 20-second KF06 run.

## Required completion evidence

Populate the table only from the six GPU PNGs and six matching Window PNGs in `AI_ORB_DEMO_V41_FINAL_EVIDENCE/`, along with their capture manifest, runtime telemetry, build fingerprint and SHA256 list. Do not promote the historical CPU proxy renders to GPU evidence.
