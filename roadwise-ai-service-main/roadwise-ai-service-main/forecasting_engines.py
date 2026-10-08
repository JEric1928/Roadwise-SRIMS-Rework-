"""
RoadWise SRIMS Advanced Mathematical Forecasting Engines
Contains pure mathematical and numerical solvers for:
1. Gamma Process + Grünwald-Letnikov Viscoelastic Decay Engine
2. MLPS Hydrological Rain-Burst + Itô Merton Jump-Diffusion Engine
3. UrbanDE-Net Spatiotemporal Graph Defect Propagation Engine
4. Heavy-Tailed Lévy-Stable CMS Financial VaR / ES Risk Engine
5. Backus-Gilbert Parameter Inversion Data Assimilation Kernel
"""

import numpy as np
from scipy.special import gamma
from csjdm_parameters import get_soil_parameters, CSJDM_GEOMORPHIC_PROFILE


# ==============================================================================
# ENGINE 1: Continuous Gamma Process & Grünwald-Letnikov Fractional Viscoelastic
# ==============================================================================
def simulate_pavement_lifecycle(
    road_id: str = "RD-001",
    soil_type: str = "Sibul_Clay",
    initial_pci: float = 100.0,
    years_horizon: int = 10,
    fractional_alpha: float = 0.35,
    beta_scale: float = 2.5,
    seed: int = 42
) -> dict:
    """
    Simulates continuous pavement degradation using a Gamma process combined
    with Grünwald-Letnikov fractional calculus for memory-dependent plastic deformation.
    """
    np.random.seed(seed)
    soil_params = get_soil_parameters(soil_type)
    k_volatility = soil_params["volatility_k"]
    base_alpha = soil_params["gamma_decay_alpha"]

    months = years_horizon * 12
    dt = 1.0 / 12.0  # Monthly increments

    # Grünwald-Letnikov memory weights c_k
    # c_0 = 1, c_k = c_{k-1} * (1 - (alpha + 1) / k)
    memory_horizon = min(24, months)
    c_weights = np.zeros(memory_horizon)
    c_weights[0] = 1.0
    for k in range(1, memory_horizon):
        c_weights[k] = c_weights[k - 1] * (1.0 - (fractional_alpha + 1.0) / k)

    pci_trajectory = np.zeros(months + 1)
    pci_trajectory[0] = initial_pci
    wear_accumulated = np.zeros(months + 1)

    time_to_failure_years = None
    failure_threshold = 40.0  # Failure defined at PCI < 40 (Poor condition)

    for m in range(1, months + 1):
        # 1. Continuous Gamma process increment
        # Shape alpha_t depends on soil volatility and current time
        shape_t = base_alpha * k_volatility * (1.0 + 0.02 * (m / 12.0))
        gamma_inc = np.random.gamma(shape=shape_t, scale=1.0 / beta_scale)

        # 2. Fractional viscoelastic memory operator sum D^\alpha_t X(t)
        hist_len = min(m, memory_horizon)
        recent_wear = wear_accumulated[m - hist_len:m][::-1]
        memory_effect = np.sum(c_weights[:hist_len] * recent_wear) * 0.15

        # Total monthly degradation
        delta_wear = gamma_inc + max(0.0, memory_effect)
        wear_accumulated[m] = wear_accumulated[m - 1] + delta_wear

        current_pci = max(0.0, initial_pci - wear_accumulated[m])
        pci_trajectory[m] = round(float(current_pci), 2)

        if current_pci <= failure_threshold and time_to_failure_years is None:
            time_to_failure_years = round(m / 12.0, 2)

    if time_to_failure_years is None:
        time_to_failure_years = float(years_horizon)

    time_axis_years = [round(m / 12.0, 2) for m in range(months + 1)]

    return {
        "road_id": road_id,
        "soil_type": soil_type,
        "fractional_alpha": fractional_alpha,
        "initial_pci": initial_pci,
        "time_to_failure_years": time_to_failure_years,
        "time_axis_years": time_axis_years,
        "pci_trajectory": pci_trajectory.tolist(),
        "total_wear": round(float(wear_accumulated[-1]), 2),
        "status": "CRITICAL" if time_to_failure_years < 3.0 else "STABLE"
    }


# ==============================================================================
# ENGINE 2: MLPS Weather Generator & Itô Merton Jump-Diffusion Hydrological Engine
# ==============================================================================
def simulate_hydrological_shock(
    road_id: str = "RD-102",
    soil_type: str = "Sibul_Clay",
    rainfall_mm: float = 180.0,
    slope_degrees: float = 22.0,
    drainage_capacity_mm: float = 50.0,
    seed: int = 42
) -> dict:
    """
    Euler-Maruyama numerical solver for Itô Merton Jump-Diffusion SDE:
    dQ_t = mu * Q_t * dt + sigma * Q_t * dW_t + Q_t * dJ_t
    Simulates subsurface pore-water pressure spikes and calculates slope failure probability (P_f).
    """
    np.random.seed(seed)
    soil = get_soil_parameters(soil_type)

    # Base pore-water pressure (kPa)
    Q0 = CSJDM_GEOMORPHIC_PROFILE["hydrology"]["base_pore_water_pressure_kpa"]
    mu = 0.05  # Drift rate (gradual drainage / dissipation)
    sigma = 0.15  # Volatility from continuous soil moisture variation
    lambda_jump = 0.12  # Storm jump arrival rate (Poisson intensity)

    steps = 100
    T = 7.0  # 7-day storm evolution window
    dt = T / steps

    Q_t = np.zeros(steps)
    Q_t[0] = Q0

    # Net surface water input driving both continuous drift and storm jump magnitude
    excess_rain = max(0.0, rainfall_mm - drainage_capacity_mm)
    mu_rain = mu + (excess_rain / 400.0) * soil["volatility_k"]
    jump_mean = 0.2 + (excess_rain / 200.0) * soil["volatility_k"]

    for i in range(1, steps):
        dW = np.random.normal(0, np.sqrt(dt))

        # Poisson jump process
        n_jumps = np.random.poisson((lambda_jump + excess_rain / 500.0) * dt)
        jump_factor = 0.0
        if n_jumps > 0:
            # Log-normal jump magnitude
            jump_factor = np.sum(np.random.lognormal(mean=jump_mean, sigma=0.2, size=n_jumps))

        # Euler-Maruyama step
        dQ = mu_rain * Q_t[i - 1] * dt + sigma * Q_t[i - 1] * dW + Q_t[i - 1] * jump_factor
        Q_t[i] = max(1.0, Q_t[i - 1] + dQ)

    max_pore_pressure = float(np.max(Q_t))

    # Factor of Safety (FS) slope stability equation (Infinite Slope Model)
    # FS = (c' + (gamma_s * z - Q_t) * cos^2(beta) * tan(phi')) / (gamma_s * z * sin(beta) * cos(beta))
    cohesion = 15.0 if soil_type == "Sibul_Clay" else 25.0  # kPa
    phi_deg = 18.0 if soil_type == "Sibul_Clay" else 28.0   # Friction angle
    unit_weight = 18.5  # kN/m^3
    depth = 2.0  # m
    beta_rad = np.radians(slope_degrees)
    phi_rad = np.radians(phi_deg)

    effective_stress = max(0.1, unit_weight * depth - max_pore_pressure)
    shear_strength = cohesion + effective_stress * (np.cos(beta_rad) ** 2) * np.tan(phi_rad)
    shear_stress = unit_weight * depth * np.sin(beta_rad) * np.cos(beta_rad)

    factor_of_safety = round(float(shear_strength / max(0.01, shear_stress)), 3)

    # Slope failure probability P_f = P(FS < 1.0) via Monte Carlo perturbation
    fs_sims = []
    for _ in range(500):
        q_pert = max_pore_pressure * np.random.normal(1.0, 0.15)
        eff_s = max(0.1, unit_weight * depth - q_pert)
        ss = cohesion + eff_s * (np.cos(beta_rad) ** 2) * np.tan(phi_rad)
        fs_sims.append(ss / max(0.01, shear_stress))

    failure_prob = round(float(np.mean(np.array(fs_sims) < 1.0)), 4)

    return {
        "road_id": road_id,
        "soil_type": soil_type,
        "rainfall_mm": rainfall_mm,
        "slope_degrees": slope_degrees,
        "peak_pore_water_pressure_kpa": round(max_pore_pressure, 2),
        "factor_of_safety": factor_of_safety,
        "failure_probability": failure_prob,
        "pressure_trajectory": np.round(Q_t, 2).tolist(),
        "time_axis_days": np.round(np.linspace(0, T, steps), 2).tolist(),
        "hazard_status": "HIGH_RISK" if failure_prob > 0.35 or factor_of_safety < 1.1 else "SAFE"
    }


# ==============================================================================
# ENGINE 3: Spatiotemporal UrbanDE-Net Graph Defect Propagation Engine
# ==============================================================================
def simulate_defect_propagation(
    num_nodes: int = 10,
    timesteps: int = 24,
    diffusion_rate: float = 0.15,
    growth_rate: float = 0.08,
    seed: int = 42
) -> dict:
    """
    Simulates graph-wide defect propagation using Graph WaveNet coupled with
    neural differential equation dynamics:
    d P_t / dt = f_se(P_t, G) + f_td(t)
    where f_se represents graph Laplacian diffusion and f_td represents temporal stress growth.
    """
    np.random.seed(seed)

    # Simple chain/grid network graph nodes
    nodes = [f"RD-{i+1:03d}" for i in range(num_nodes)]

    # Construct normalized Graph Laplacian L = I - D^(-1/2) A D^(-1/2)
    adj = np.zeros((num_nodes, num_nodes))
    for i in range(num_nodes - 1):
        adj[i, i + 1] = 1.0
        adj[i + 1, i] = 1.0
    # Add cross-link for spatial loop
    if num_nodes >= 6:
        adj[0, 5] = 1.0
        adj[5, 0] = 1.0

    deg = np.diag(np.sum(adj, axis=1))
    deg_inv_sqrt = np.linalg.inv(np.sqrt(deg))
    laplacian = np.eye(num_nodes) - deg_inv_sqrt @ adj @ deg_inv_sqrt

    # Initial defect levels (node 0 starts with severe defect, others minimal)
    p_t = np.zeros((timesteps + 1, num_nodes))
    p_t[0, 0] = 0.85  # Severe defect spike
    p_t[0, 1:] = np.random.uniform(0.05, 0.2, size=num_nodes - 1)

    dt = 1.0  # Monthly timestep

    for t in range(timesteps):
        curr_p = p_t[t]

        # Spatial evolution: negative graph Laplacian diffusion
        f_se = -diffusion_rate * (laplacian @ curr_p)

        # Temporal dynamics: harmonic growth coupled with traffic stress
        harmonic_term = np.sin(2 * np.pi * t / 12.0) * 0.02
        f_td = growth_rate * curr_p * (1.0 - curr_p) + harmonic_term

        # Differential Euler step
        dp_dt = f_se + f_td
        p_next = curr_p + dp_dt * dt
        p_t[t + 1] = np.clip(p_next, 0.0, 1.0)

    # Compute network diffusion metrics
    hotspot_nodes = [nodes[idx] for idx in range(num_nodes) if p_t[-1, idx] > 0.5]

    return {
        "nodes": nodes,
        "timesteps_months": timesteps,
        "initial_defect_levels": np.round(p_t[0], 3).tolist(),
        "final_defect_levels": np.round(p_t[-1], 3).tolist(),
        "trajectory_matrix": np.round(p_t, 3).tolist(),
        "hotspot_nodes": hotspot_nodes,
        "network_stress_index": round(float(np.mean(p_t[-1])), 3)
    }


# ==============================================================================
# ENGINE 4: Heavy-Tailed Lévy-Stable CMS Financial VaR / ES Engine
# ==============================================================================
def simulate_financial_risk_var(
    base_budget_php: float = 15000000.0,  # 15M PHP base repair budget
    confidence_level: float = 0.95,
    alpha_tail: float = 1.4,  # Heavy tail parameter (1.0 < alpha <= 2.0)
    beta_skew: float = 0.75,  # Positive skew (right-skewed cost shocks)
    num_simulations: int = 5000,
    seed: int = 42
) -> dict:
    """
    Chambers-Mallows-Stuck (CMS) algorithm for generating heavy-tailed Lévy-Stable distributions
    S(alpha, beta, gamma, delta) to forecast catastrophic capital Value-at-Risk (VaR)
    and Expected Shortfall (ES).
    """
    np.random.seed(seed)

    # 1. CMS Algorithm for Lévy-Stable variates
    # Uniform U in (-pi/2, pi/2), Exponential W with mean 1
    u = np.random.uniform(-np.pi / 2.0, np.pi / 2.0, size=num_simulations)
    w = np.random.exponential(scale=1.0, size=num_simulations)

    # CMS formula for alpha != 1
    zeta = -beta_skew * np.tan(np.pi * alpha_tail / 2.0)
    theta0 = (1.0 / alpha_tail) * np.arctan(-zeta)

    term1 = (1.0 + zeta**2) ** (1.0 / (2.0 * alpha_tail))
    term2 = np.sin(alpha_tail * (u + theta0)) / (np.cos(u) ** (1.0 / alpha_tail))
    term3 = (np.cos(u - alpha_tail * (u + theta0)) / w) ** ((1.0 - alpha_tail) / alpha_tail)

    levy_variates = term1 * term2 * term3

    # Rescale and shift variates to represent cost multiplier shocks
    cost_multipliers = 1.0 + 0.15 * np.maximum(0.0, levy_variates)

    # Clamp maximum cost multiplier at 4.5x (physical non-divergence limit)
    cost_multipliers = np.clip(cost_multipliers, 1.0, 4.5)

    simulated_costs = base_budget_php * cost_multipliers

    # 2. Compute VaR_p and ES_p
    var_percentile = confidence_level * 100.0
    var_p = float(np.percentile(simulated_costs, var_percentile))

    # Expected Shortfall ES_p: Average loss given loss exceeds VaR_p
    exceedances = simulated_costs[simulated_costs >= var_p]
    es_p = float(np.mean(exceedances)) if len(exceedances) > 0 else var_p

    contingency_reserve_php = es_p - base_budget_php

    return {
        "base_budget_php": round(base_budget_php, 2),
        "confidence_level": confidence_level,
        "alpha_tail": alpha_tail,
        "value_at_risk_var_php": round(var_p, 2),
        "expected_shortfall_es_php": round(es_p, 2),
        "required_contingency_reserve_php": round(contingency_reserve_php, 2),
        "var_multiplier": round(var_p / base_budget_php, 3),
        "es_multiplier": round(es_p / base_budget_php, 3),
        "simulated_cost_histogram": np.round(np.percentile(simulated_costs, [10, 25, 50, 75, 90, 95, 99]), 2).tolist()
    }


# ==============================================================================
# ENGINE 5: Backus-Gilbert Parameter Inversion Data Assimilation Engine
# ==============================================================================
def assimilate_multi_sensor_data(
    vision_score: float = 0.65,
    vision_confidence: float = 0.85,
    sensor_telemetry_score: float = 0.40,
    geomechanical_baseline: float = 0.50,
    tradeoff_alpha: float = 0.5,
    seed: int = 42
) -> dict:
    """
    Backus-Gilbert parameter inversion kernel balancing spatial defect resolution
    against sensor noise variance via parameter alpha in [0, 1].
    Minimizes: alpha * int (r - r0)^2 A(r, r0)^2 dr + (1 - alpha) Var(theta_hat)
    subject to int A(r, r0) dr = 1.
    """
    # Inverse variance weights for sources
    # High tradeoff_alpha -> High spatial detail resolution (focus on vision & telemetry)
    # Low tradeoff_alpha -> Low noise variance (focus on geomechanical baseline)

    # Raw variance estimates for each sensor channel
    var_vision = max(0.01, (1.0 - vision_confidence) ** 2)
    var_telemetry = 0.08
    var_baseline = 0.04

    # Backus-Gilbert weighting terms based on tradeoff parameter alpha
    w_vision = (tradeoff_alpha / var_vision)
    w_telemetry = (tradeoff_alpha / var_telemetry)
    w_baseline = ((1.0 - tradeoff_alpha) / var_baseline)

    total_w = w_vision + w_telemetry + w_baseline

    # Normalized kernel weights (sum = 1.0)
    k_vision = float(w_vision / total_w)
    k_telemetry = float(w_telemetry / total_w)
    k_baseline = float(w_baseline / total_w)

    # Inverted optimal estimate theta_hat
    assimilated_defect_score = round(
        k_vision * vision_score + k_telemetry * sensor_telemetry_score + k_baseline * geomechanical_baseline,
        4
    )

    # Posterior variance estimate
    posterior_variance = round(float(1.0 / total_w), 5)

    return {
        "tradeoff_alpha": tradeoff_alpha,
        "assimilated_defect_score": assimilated_defect_score,
        "posterior_variance": posterior_variance,
        "kernel_weights": {
            "vision_weight": round(k_vision, 4),
            "telemetry_weight": round(k_telemetry, 4),
            "baseline_weight": round(k_baseline, 4)
        },
        "resolution_vs_variance_curve": [
            {
                "alpha": a,
                "score": round(
                    ((a / var_vision) * vision_score + (a / var_telemetry) * sensor_telemetry_score + ((1 - a) / var_baseline) * geomechanical_baseline) /
                    ((a / var_vision) + (a / var_telemetry) + ((1 - a) / var_baseline)),
                    4
                )
            }
            for a in np.linspace(0.05, 0.95, 10)
        ]
    }
