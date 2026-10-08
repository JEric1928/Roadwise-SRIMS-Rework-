"""
CSJDM (City of San Jose del Monte) Geomorphological Parameter Registry & Synthetic Data Generator
Extracted from CSJDM Ecological Profile (EP-2024).
"""

import numpy as np

CSJDM_GEOMORPHIC_PROFILE = {
    "soils": {
        "Novaliches_Loam": {
            "description": "Stable volcanic adobe subgrade (Western Plains, 0-8% slopes)",
            "volatility_k": 1.00,
            "liquid_limit": 32.5,
            "gamma_decay_alpha": 0.05,
            "subgrade_modulus_mpa": 120.0
        },
        "Sibul_Clay": {
            "description": "High-expansion montmorillonite clay (Eastern Foothills, >18% slopes)",
            "volatility_k": 1.30,
            "liquid_limit": 68.2,
            "gamma_decay_alpha": 0.18,
            "subgrade_modulus_mpa": 45.0
        }
    },
    "hydrology": {
        "strahler_max_order": 4,
        "monsoon_august_peak_mm": 539.0,
        "merton_jump_intensity_lambda": 0.15,
        "base_pore_water_pressure_kpa": 12.0
    },
    "road_network": {
        "total_inventory_roads": 403,
        "default_carriageway_width_m": 6.10,
        "default_pavement_thickness_m": 0.23
    }
}


def get_soil_parameters(soil_type: str) -> dict:
    soils = CSJDM_GEOMORPHIC_PROFILE["soils"]
    if soil_type in soils:
        return soils[soil_type]
    # Default to Novaliches Loam if unknown
    return soils["Novaliches_Loam"]


def generate_synthetic_rainfall_series(days: int = 365, seed: int = 42) -> np.ndarray:
    """
    Synthesizes daily precipitation time series (mm) preserving Bulacan seasonal cycles
    and Hurst-Kolmogorov persistence with extreme typhoon bursts.
    """
    np.random.seed(seed)
    t = np.arange(days)
    # Cyclostationary seasonal mean (peaks around day 220, August)
    seasonal_mean = 5.0 + 15.0 * np.maximum(0, np.sin(2 * np.pi * (t - 120) / 365))

    # Gamma-distributed rain depth with Markovian persistence
    rain = np.zeros(days)
    state = 0  # 0: dry, 1: wet

    for i in range(days):
        p_transition = 0.3 if state == 0 else 0.7
        if np.random.rand() < p_transition:
            state = 1
        else:
            state = 0

        if state == 1:
            # Depth on wet day
            depth = np.random.gamma(shape=2.0, scale=seasonal_mean[i] / 2.0)
            # Occasional typhoon shock (Poisson-jump extreme event)
            if np.random.rand() < 0.05:
                depth += np.random.exponential(scale=80.0)
            rain[i] = depth

    return np.round(rain, 2)


def generate_synthetic_road_graph(num_nodes: int = 20, seed: int = 42) -> dict:
    """
    Generates a synthetic adjacency graph representing CSJDM road network segments.
    """
    np.random.seed(seed)
    nodes = [f"RD-{i+1:03d}" for i in range(num_nodes)]
    adj_matrix = np.zeros((num_nodes, num_nodes))

    # Build spatial chain / lattice with random cross-links
    for i in range(num_nodes - 1):
        adj_matrix[i, i + 1] = 1.0
        adj_matrix[i + 1, i] = 1.0
        if i + 2 < num_nodes and np.random.rand() > 0.5:
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
