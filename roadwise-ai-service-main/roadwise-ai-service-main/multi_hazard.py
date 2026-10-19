"""
CSJDM Multi-Hazard Composite Risk Routing (EP-2024) — ROADMAP SCAFFOLD.

Status: **not wired into the production ranking path.**

The Java ranking layer deliberately keeps its lexicographic budget queue immune to
calibrated coefficients (see ``PriorityRankingService``: "a ranking an LGU has to
defend in a budget hearing cannot shift because a regression moved a coefficient").
That invariant is not overridden here. This module exists so the composite index
has exactly one tested definition the moment hazard rasters land, instead of being
re-derived per consumer.

    R_b = w_F*F_b + w_L*L_b + w_S*S_b + w_E*E_b      with R_b in [0, 1]

Weights come from the CSJDM registry (``multi_hazard_weights``), whose sum is
asserted at import time in ``csjdm_parameters``. Inputs are normalised per-hazard
severity in [0, 1] — this module neither sources nor interpolates rasters; callers
supply already-normalised values.
"""

import numpy as np

from csjdm_parameters import CSJDM_GEOMORPHIC_PROFILE

HAZARD_KEYS = ("flood", "landslide", "seismic", "erosion")

WEIGHTS = dict(CSJDM_GEOMORPHIC_PROFILE["multi_hazard_weights"])

#: Set once hazard sourcing exists; surfaced on every response so a consumer can
#: never mistake this for a production risk score.
IMPLEMENTATION_STATUS = "ROADMAP"

NOTE = (
    "Scaffold only. Hazards are caller-supplied normalised severities, not derived "
    "from CSJDM rasters; the production priority queue is unaffected."
)


def composite_risk_index(
    flood: float = 0.0,
    landslide: float = 0.0,
    seismic: float = 0.0,
    erosion: float = 0.0,
) -> dict:
    """
    Weighted multi-hazard risk index for one road segment.

    Every input is clipped to [0, 1] before weighting, so out-of-range or hostile
    values cannot push the index outside its documented domain. Because the
    registry weights sum to 1.0, ``risk_index`` is automatically in [0, 1]; the
    final clip is defensive against a future weight edit.
    """
    supplied = {
        "flood": flood,
        "landslide": landslide,
        "seismic": seismic,
        "erosion": erosion,
    }

    normalised = {}
    for key in HAZARD_KEYS:
        value = supplied[key]
        if value is None or not np.isfinite(value):
            normalised[key] = 0.0
        else:
            normalised[key] = float(np.clip(float(value), 0.0, 1.0))

    weighted = {key: WEIGHTS[key] * normalised[key] for key in HAZARD_KEYS}
    risk_index = float(np.clip(sum(weighted.values()), 0.0, 1.0))

    return {
        "risk_index": round(risk_index, 4),
        "normalised_hazards": {k: round(v, 4) for k, v in normalised.items()},
        "weights": dict(WEIGHTS),
        "contributions": {k: round(v, 4) for k, v in weighted.items()},
        "implementation_status": IMPLEMENTATION_STATUS,
        "note": NOTE,
    }
