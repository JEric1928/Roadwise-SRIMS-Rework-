"""
CSJDM (City of San Jose del Monte) Geomorphological Parameter Registry & Synthetic Data Generator
Extracted from CSJDM Ecological Profile (EP-2024).

Two invariants govern this module:

1. **Registered soils only.** ``get_soil_parameters`` raises ``ValueError`` on an
   unregistered key instead of silently falling back to stable parameters. The
   previous default resolved to Novaliches Loam (subgrade modulus 120 MPa), so a
   typo or an unregistered majority soil produced a *stable* answer for what may
   be expansive ground — a fail-open on risk.

2. **One source of truth.** Geotechnical qualities (cohesion, friction angle)
   reach the engines through this registry, never through string comparison at
   the call site.
"""

from enum import StrEnum

import numpy as np


class SoilType(StrEnum):
    """Registered CSJDM soil series. The wire format is the enum *value*."""

    NOVALICHES_LOAM = "Novaliches_Loam"
    NOVALICHES_CLAY_LOAM = "Novaliches_Clay_Loam"
    SIBUL_CLAY = "Sibul_Clay"


CSJDM_GEOMORPHIC_PROFILE = {
    "soils": {
        "Novaliches_Clay_Loam": {
            "description": "Dominant central/eastern expansive clay loam (66.77% coverage)",
            "coverage_pct": 66.77,
            "volatility_k": 1.25,
            "liquid_limit": 45.0,
            "gamma_decay_alpha": 0.14,
            "subgrade_modulus_mpa": 45.0,
            "shrink_swell_potential": "HIGH",
            "cohesion_kpa": 20.0,
            "friction_angle_deg": 22.0,
        },
        "Novaliches_Loam": {
            "description": "Stable volcanic adobe subgrade (Western Plains, 0-8% slopes)",
            "coverage_pct": 30.04,
            "volatility_k": 1.00,
            "liquid_limit": 32.5,
            "gamma_decay_alpha": 0.05,
            "subgrade_modulus_mpa": 120.0,
            "shrink_swell_potential": "LOW",
            "cohesion_kpa": 25.0,
            "friction_angle_deg": 28.0,
        },
        "Sibul_Clay": {
            "description": "High-expansion montmorillonite clay (Eastern Foothills, >18% slopes)",
            "coverage_pct": 3.19,
            "volatility_k": 1.30,
            "liquid_limit": 68.2,
            "gamma_decay_alpha": 0.18,
            "subgrade_modulus_mpa": 45.0,
            "shrink_swell_potential": "VERY_HIGH",
            "cohesion_kpa": 15.0,
            "friction_angle_deg": 18.0,
        },
    },
    "hydrology": {
        "strahler_max_order": 4,
        "monsoon_august_peak_mm": 539.0,
        "merton_jump_intensity_lambda": 0.15,
        "base_pore_water_pressure_kpa": 12.0,
    },
    "road_network": {
        "total_inventory_roads": 403,
        "default_carriageway_width_m": 6.10,
        "default_pavement_thickness_m": 0.23,
    },
    # EP-2024 multi-hazard composite weights. Sum is asserted in the test suite so
    # a future edit cannot silently emit a non-normalised index.
    "multi_hazard_weights": {
        "flood": 0.30,
        "landslide": 0.35,
        "seismic": 0.20,
        "erosion": 0.15,
    },
    # Self-organised-criticality exponent for the slope-area power law,
    # 1 - F(S) ~ S^-alpha. Retained as a profile constant for the roadmap item;
    # nothing consumes it yet.
    "slope_power_law_alpha": 1.8,
}


def _validate_registry() -> None:
    """Fail at import, not at request time, on an inconsistent registry."""
    required = {
        "coverage_pct",
        "volatility_k",
        "liquid_limit",
        "gamma_decay_alpha",
        "subgrade_modulus_mpa",
        "cohesion_kpa",
        "friction_angle_deg",
    }
    for name, params in CSJDM_GEOMORPHIC_PROFILE["soils"].items():
        missing = required - params.keys()
        if missing:
            raise ValueError(f"soil '{name}' is missing required keys: {sorted(missing)}")
        if not 0.0 <= params["coverage_pct"] <= 100.0:
            raise ValueError(f"soil '{name}' coverage_pct outside [0, 100]")
        if params["cohesion_kpa"] <= 0.0 or params["friction_angle_deg"] <= 0.0:
            raise ValueError(f"soil '{name}' has non-positive strength parameters")

    coverage = sum(p["coverage_pct"] for p in CSJDM_GEOMORPHIC_PROFILE["soils"].values())
    # EP-2024 maps three series covering the city; allow rounding slack only.
    if not 99.5 <= coverage <= 100.5:
        raise ValueError(f"soil coverage_pct sums to {coverage}, expected ~100")

    weights = CSJDM_GEOMORPHIC_PROFILE["multi_hazard_weights"]
    if not 0.0 < sum(weights.values()) <= 1.0 + 1e-9:
        raise ValueError(f"multi-hazard weights sum to {sum(weights.values())}, expected 1.0")


_validate_registry()


def get_soil_parameters(soil_type: str) -> dict:
    """
    Look up a registered soil series.

    Raises ``ValueError`` for an unregistered series. Callers are expected to map
    that onto a 4xx response: an unknown soil is a *client* error, and answering
    it with the stable-soil parameters understates slope and subgrade risk.
    """
    soils = CSJDM_GEOMORPHIC_PROFILE["soils"]

    key = soil_type.value if isinstance(soil_type, SoilType) else soil_type
    if key in soils:
        return soils[key]

    # Accept the enum *name* (NOVALICHES_CLAY_LOAM) as well as its value.
    try:
        return soils[SoilType[str(key)].value]
    except KeyError:
        pass

    raise ValueError(
        f"unknown soil_type {soil_type!r}; registered series are "
        f"{sorted(soils)}"
    )


def get_hydrology_parameters() -> dict:
    """CSJDM hydrology constants, read through one accessor."""
    return CSJDM_GEOMORPHIC_PROFILE["hydrology"]


def generate_synthetic_rainfall_series(days: int = 365, seed: int = 42) -> np.ndarray:
    """
    Synthesizes daily precipitation time series (mm) preserving Bulacan seasonal cycles
    and Hurst-Kolmogorov persistence with extreme typhoon bursts.
    """
    rng = np.random.default_rng(seed)
    t = np.arange(days)
    # Cyclostationary seasonal mean (peaks around day 220, August)
    seasonal_mean = 5.0 + 15.0 * np.maximum(0, np.sin(2 * np.pi * (t - 120) / 365))

    # Gamma-distributed rain depth with Markovian persistence
    rain = np.zeros(days)
    state = 0  # 0: dry, 1: wet

    for i in range(days):
        p_transition = 0.3 if state == 0 else 0.7
        if rng.random() < p_transition:
            state = 1
        else:
            state = 0

        if state == 1:
            # Depth on wet day
            depth = rng.gamma(shape=2.0, scale=seasonal_mean[i] / 2.0)
            # Occasional typhoon shock (Poisson-jump extreme event)
            if rng.random() < 0.05:
                depth += rng.exponential(scale=80.0)
            rain[i] = depth

    return np.round(rain, 2)


def generate_synthetic_road_graph(num_nodes: int = 20, seed: int = 42) -> dict:
    """
    Generates a synthetic adjacency graph representing CSJDM road network segments.
    """
    rng = np.random.default_rng(seed)
    nodes = [f"RD-{i+1:03d}" for i in range(num_nodes)]
    adj_matrix = np.zeros((num_nodes, num_nodes))

    # Build spatial chain / lattice with random cross-links
    for i in range(num_nodes - 1):
        adj_matrix[i, i + 1] = 1.0
        adj_matrix[i + 1, i] = 1.0
        if i + 2 < num_nodes and rng.random() > 0.5:
            adj_matrix[i, i + 2] = 1.0
            adj_matrix[i + 2, i] = 1.0

    # Row normalize
    deg = np.sum(adj_matrix, axis=1, keepdims=True)
    deg[deg == 0] = 1.0
    norm_adj = adj_matrix / deg

    return {
        "nodes": nodes,
        "adjacency": norm_adj.tolist()
    }
