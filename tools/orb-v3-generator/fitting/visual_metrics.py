"""Shared offline visual classifiers used by the staged fitters."""

from __future__ import annotations


UPPER_DOME_MIN_Y = 0.18
UPPER_DOME_HAZE_LUMA_MIN = 135.0
UPPER_DOME_HAZE_MAX_SATURATION = 0.22


def is_upper_dome_grey_white_haze(
    ny: float,
    sr: int,
    sg: int,
    sb: int,
    is_localized_flare: bool,
) -> bool:
    """Return the single frozen Stage B/Stage E upper-dome haze pixel classification."""
    if ny <= UPPER_DOME_MIN_Y or is_localized_flare:
        return False
    luma = 0.2126 * sr + 0.7152 * sg + 0.0722 * sb
    c_max = max(sr, sg, sb)
    c_min = min(sr, sg, sb)
    saturation = (c_max - c_min) / max(1.0, float(c_max))
    return luma > UPPER_DOME_HAZE_LUMA_MIN and saturation < UPPER_DOME_HAZE_MAX_SATURATION
