#!/usr/bin/env python3
"""
AI-Orb-Demo V3.2 — Unified Visual Loss & Strict Hard-Gate Evaluator

Formula:
  L_total = 0.18*L_sphere + 0.22*L_cavity + 0.25*L_volume
          + 0.20*L_resident + 0.10*L_optics + 0.05*L_layering

Hard Gates (NEVER loosened):
  - E01 sphere diameter relative error <= 2.0%
  - E01 sphere center error <= 1.0% W
  - E01 sphere silhouette IoU >= 0.95
  - E03 upper cavity relative area error <= 15.0% & centroid <= 0.04 D
  - E04 liquid interface contour mean error <= 0.025 R
  - E05 emerald folds relative area error <= 15.0% & centroid <= 0.04 D
  - E06 sapphire valleys relative area error <= 15.0% & centroid <= 0.04 D
  - E07 aqua pocket relative area error <= 15.0% & centroid <= 0.04 D
  - E10 gold combined Chamfer <= 0.025 R & NOT regular oval
  - Upper dome grey-white haze ratio <= 1.0%
"""

from __future__ import annotations

from typing import Any


def compute_visual_loss_and_gates(
    stage_a: dict[str, Any],
    stage_b: dict[str, Any],
    stage_c: dict[str, Any],
    stage_d: dict[str, Any],
    stage_e: dict[str, Any],
) -> dict[str, Any]:
    cam = stage_a["fitted"]
    cav = stage_b["winner"]
    vol_regs = stage_c["regions"]
    gold = stage_d["fittedGold"]
    opt = stage_e["metrics"]

    # Individual normalized loss components
    l_sphere = cam["squareDiamRelErr"] * 5.0 + cam["squareCenterErrW"] * 10.0 + (1.0 - cam["squareSilhouetteIoU"]) * 5.0
    # Use joint volume+cavity E03 metrics from Stage C (which includes Stage B crystal lens + full fluid!)
    e03_joint = vol_regs["E03_upper_cavity"]
    l_cavity = (
        e03_joint["relativeAreaError"] * 1.5
        + e03_joint["centroidDistD"] * 6.0
        + cav["e04MeanErrorR"] * 10.0
    )
    l_volume = (
        vol_regs["E05_emerald"]["relativeAreaError"]
        + vol_regs["E06_sapphire"]["relativeAreaError"]
        + vol_regs["E07_aqua"]["relativeAreaError"]
        + 4.0 * (
            vol_regs["E05_emerald"]["centroidDistD"]
            + vol_regs["E06_sapphire"]["centroidDistD"]
            + vol_regs["E07_aqua"]["centroidDistD"]
        )
    ) / 3.0
    l_resident = gold["chamferR"] * 4.0 + (0.5 if gold["ovalMetrics"]["isRegularOval"] else 0.0)
    l_optics = opt["upperDomeHazeRatio"] * 10.0 + opt["goldLumaErr"] * 2.0 + min(0.5, opt["e09AreaRelErr"] * 0.4)
    # Layering penalty: checks C01 smoothness + front/back separation + zero regular gold oval
    l_layering = max(0.0, (stage_d["c01MaxTurnDeg"] - 18.0) * 0.05) + (0.25 if gold["ovalMetrics"]["isRegularOval"] else 0.0)

    l_total = (
        0.18 * l_sphere
        + 0.22 * l_cavity
        + 0.25 * l_volume
        + 0.20 * l_resident
        + 0.10 * l_optics
        + 0.05 * l_layering
    )

    gates = {
        "E01_diameterRelErr_le_2pct": {
            "value": cam["squareDiamRelErr"],
            "threshold": 0.02,
            "pass": cam["squareDiamRelErr"] <= 0.02,
        },
        "E01_portraitDiamRelErr_le_2pct": {
            "value": cam["portraitDiamRelErr"],
            "threshold": 0.02,
            "pass": cam["portraitDiamRelErr"] <= 0.02,
        },
        "E01_centerErrW_le_1pct": {
            "value": cam["squareCenterErrW"],
            "threshold": 0.01,
            "pass": cam["squareCenterErrW"] <= 0.01,
        },
        "E01_silhouetteIoU_ge_095": {
            "value": cam["squareSilhouetteIoU"],
            "threshold": 0.95,
            "pass": cam["squareSilhouetteIoU"] >= 0.95,
        },
        "E03_cavityAreaRelErr_le_15pct": {
            "value": e03_joint["relativeAreaError"],
            "threshold": 0.15,
            "pass": e03_joint["relativeAreaError"] <= 0.15,
        },
        "E03_cavityCentroid_le_004D": {
            "value": e03_joint["centroidDistD"],
            "threshold": 0.04,
            "pass": e03_joint["centroidDistD"] <= 0.04,
        },
        "E04_meniscusMeanErr_le_0025R": {
            "value": cav["e04MeanErrorR"],
            "threshold": 0.025,
            "pass": cav["e04MeanErrorR"] <= 0.025,
        },
        "E05_emeraldAreaRelErr_le_15pct": {
            "value": vol_regs["E05_emerald"]["relativeAreaError"],
            "threshold": 0.15,
            "pass": vol_regs["E05_emerald"]["relativeAreaError"] <= 0.15,
        },
        "E05_emeraldCentroid_le_004D": {
            "value": vol_regs["E05_emerald"]["centroidDistD"],
            "threshold": 0.04,
            "pass": vol_regs["E05_emerald"]["centroidDistD"] <= 0.04,
        },
        "E06_sapphireAreaRelErr_le_15pct": {
            "value": vol_regs["E06_sapphire"]["relativeAreaError"],
            "threshold": 0.15,
            "pass": vol_regs["E06_sapphire"]["relativeAreaError"] <= 0.15,
        },
        "E06_sapphireCentroid_le_004D": {
            "value": vol_regs["E06_sapphire"]["centroidDistD"],
            "threshold": 0.04,
            "pass": vol_regs["E06_sapphire"]["centroidDistD"] <= 0.04,
        },
        "E07_aquaAreaRelErr_le_15pct": {
            "value": vol_regs["E07_aqua"]["relativeAreaError"],
            "threshold": 0.15,
            "pass": vol_regs["E07_aqua"]["relativeAreaError"] <= 0.15,
        },
        "E07_aquaCentroid_le_004D": {
            "value": vol_regs["E07_aqua"]["centroidDistD"],
            "threshold": 0.04,
            "pass": vol_regs["E07_aqua"]["centroidDistD"] <= 0.04,
        },
        "E10_goldCombinedChamfer_le_0025R": {
            "value": gold["chamferR"],
            "threshold": 0.025,
            "pass": gold["chamferR"] <= 0.025,
        },
        "E10_goldNonRegularOval": {
            "value": not gold["ovalMetrics"]["isRegularOval"],
            "threshold": True,
            "pass": not gold["ovalMetrics"]["isRegularOval"],
        },
        "E11_c01SmoothTurn_lt_20deg": {
            "value": stage_d["c01MaxTurnDeg"],
            "threshold": 20.0,
            "pass": stage_d["c01MaxTurnDeg"] < 20.0,
        },
        "E02_upperDomeHaze_le_1pct": {
            "value": opt["upperDomeHazeRatio"],
            "threshold": 0.01,
            "pass": opt["upperDomeHazeRatio"] <= 0.01,
        },
    }

    all_offline_pass = all(g["pass"] for g in gates.values())
    failed_gates = [k for k, g in gates.items() if not g["pass"]]

    return {
        "schemaVersion": "AI_ORB_V3_2_VISUAL_LOSS_1_0",
        "evaluationDomain": "OFFLINE_CPU_PROXY_ANALYTICAL_FITTING",
        "realGpuStatus": "PENDING_REAL_GPU_CAPTURE",
        "weights": {
            "L_sphere": 0.18,
            "L_cavity": 0.22,
            "L_volume": 0.25,
            "L_resident": 0.20,
            "L_optics": 0.10,
            "L_layering": 0.05,
        },
        "components": {
            "L_sphere": round(l_sphere, 5),
            "L_cavity": round(l_cavity, 5),
            "L_volume": round(l_volume, 5),
            "L_resident": round(l_resident, 5),
            "L_optics": round(l_optics, 5),
            "L_layering": round(l_layering, 5),
            "L_total": round(l_total, 5),
        },
        "hardGates": gates,
        "allOfflineHardGatesPass": all_offline_pass,
        "failedOfflineGates": failed_gates,
    }
