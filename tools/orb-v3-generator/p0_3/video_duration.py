"""Small strict parser for ffprobe duration output used by API36 preflight."""

from __future__ import annotations

import math


def parse_video_duration(raw: str) -> float:
    """Parse ffprobe duration text without treating N/A or non-finite values as evidence."""
    value = raw.strip()
    try:
        duration = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"PF04 motion video duration unavailable; ffprobe reported {value!r}") from exc
    if not math.isfinite(duration) or duration < 0:
        raise ValueError(f"PF04 motion video duration is invalid: {value!r}")
    return duration
