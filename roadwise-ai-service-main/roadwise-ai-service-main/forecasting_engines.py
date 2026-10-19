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


# --- shared engine limits -----------------------------------------------------
# Factor of safety is reported on a clipped band. The infinite-slope model
# divides by shear stress, which is floored at 0.01 kPa so a level slope cannot
# divide by zero — but that same floor lets FS reach ~1e3 on near-flat ground,
# and an unbounded FS distorts any downstream risk index it is multiplied into.
FS_MIN = 0.1
FS_MAX = 5.0
FS_RISK_SCALE = 1.5

# PCI below which a segment is reported CRITICAL regardless of extrapolation.
CRITICAL_PCI = 55.0


def calculate_true_ttf(
    initial_pci: float,
    wear_rate_monthly: float,
    threshold: float = 40.0,
) -> float:
    """
    Years until PCI reaches ``threshold`` at the observed mean wear rate.

    Deliberately independent of the forecast horizon. The horizon-bounded loop in
    ``simulate_pavement_lifecycle`` only observes crossings *inside* its window;
    at CSJDM wear rates (~0.02-0.11 PCI/month) a 60-PCI drop takes decades, so
    the old fallback assigned ``years_horizon`` as the failure date — which then
    drove ``status`` off the request parameter instead of the physical state.

    Returns ``inf`` when wear is zero or negative (no failure at any horizon).
    """
    if not np.isfinite(initial_pci) or not np.isfinite(wear_rate_monthly):
        return float("inf")
    remaining = float(initial_pci) - float(threshold)
    if remaining <= 0.0:
        return 0.0
    if wear_rate_monthly <= 0.0:
        return float("inf")
    return (remaining / wear_rate_monthly) / 12.0

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
    rng = np.random.default_rng(seed)
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
        gamma_inc = rng.gamma(shape=shape_t, scale=1.0 / beta_scale)

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

    # Mean wear observed over the window drives extrapolation past the horizon.
    wear_rate_monthly = float(wear_accumulated[-1] / months) if months > 0 else 0.0
    final_pci = float(pci_trajectory[-1])

    if time_to_failure_years is not None:
        ttf_basis = "OBSERVED"
    else:
        extrapolated = calculate_true_ttf(initial_pci, wear_rate_monthly, failure_threshold)
        if np.isfinite(extrapolated):
            time_to_failure_years = round(float(extrapolated), 2)
            ttf_basis = "EXTRAPOLATED"
        else:
            time_to_failure_years = None
            ttf_basis = "NONE"

    # Status follows physical state, not the requested horizon. The previous form
    # compared the horizon-bounded fallback against 3.0, so a 1-year forecast on
    # undamaged pavement reported CRITICAL while a 10-year forecast showing real
    # decay reported STABLE.
    critical = final_pci < CRITICAL_PCI or (
        time_to_failure_years is not None and time_to_failure_years < 3.0)
    status = "CRITICAL" if critical else "STABLE"

    time_axis_years = [round(m / 12.0, 2) for m in range(months + 1)]

    # Linear baseline comparison model (constant linear annual degradation of 5 PCI/yr)
    linear_baseline_pci = [max(0.0, round(initial_pci - 5.0 * t, 2)) for t in time_axis_years]

    return {
        "road_id": road_id,
        "soil_type": soil_type,
        "fractional_alpha": fractional_alpha,
        "initial_pci": initial_pci,
        "time_to_failure_years": time_to_failure_years,
        "time_axis_years": time_axis_years,
        "pci_trajectory": pci_trajectory.tolist(),
        "linear_baseline_pci": linear_baseline_pci,
        "total_wear": round(float(wear_accumulated[-1]), 2),
        "final_pci": round(final_pci, 2),
        "wear_rate_pci_per_month": round(wear_rate_monthly, 4),
        "time_to_failure_basis": ttf_basis,
        "status": status,
        "model_version": "pavement-decay-gamma-v2.1"
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
    rng = np.random.default_rng(seed)
    soil = get_soil_parameters(soil_type)

    # Base pore-water pressure (kPa)
    Q0 = CSJDM_GEOMORPHIC_PROFILE["hydrology"]["base_pore_water_pressure_kpa"]
    mu = 0.05  # Drift rate (gradual drainage / dissipation)
    sigma = 0.15  # Volatility from continuous soil moisture variation
    # Storm jump arrival rate, bound to the EP-2024 registry. The profile carries
    # 0.15 while this function hardcoded 0.12, leaving the registry entry dead.
    lambda_jump = float(CSJDM_GEOMORPHIC_PROFILE["hydrology"].get(
        "merton_jump_intensity_lambda", 0.15))

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
        dW = rng.normal(0, np.sqrt(dt))

        # Poisson jump process
        n_jumps = rng.poisson((lambda_jump + excess_rain / 500.0) * dt)
        jump_factor = 0.0
        if n_jumps > 0:
            # Log-normal jump magnitude
            jump_factor = np.sum(rng.lognormal(mean=jump_mean, sigma=0.2, size=n_jumps))

        # Euler-Maruyama step
        dQ = mu_rain * Q_t[i - 1] * dt + sigma * Q_t[i - 1] * dW + Q_t[i - 1] * jump_factor
        Q_t[i] = max(1.0, Q_t[i - 1] + dQ)

    max_pore_pressure = float(np.max(Q_t))

    # Factor of Safety (FS) slope stability equation (Infinite Slope Model)
    # FS = (c' + (gamma_s * z - Q_t) * cos^2(beta) * tan(phi')) / (gamma_s * z * sin(beta) * cos(beta))
    # Geotechnical strength comes from the CSJDM registry. Branching on the soil
    # string here gave every series except Sibul_Clay the stable cohesion/friction
    # pair, so an unregistered soil was silently treated as competent ground — and
    # adding a new series would have inherited that same default.
    cohesion = float(soil["cohesion_kpa"])        # kPa
    phi_deg = float(soil["friction_angle_deg"])   # Friction angle
    unit_weight = 18.5  # kN/m^3
    depth = 2.0  # m
    beta_rad = np.radians(slope_degrees)
    phi_rad = np.radians(phi_deg)

    effective_stress = max(0.1, unit_weight * depth - max_pore_pressure)
    shear_strength = cohesion + effective_stress * (np.cos(beta_rad) ** 2) * np.tan(phi_rad)
    shear_stress = unit_weight * depth * np.sin(beta_rad) * np.cos(beta_rad)

    raw_factor_of_safety = float(shear_strength / max(0.01, shear_stress))
    # Bound before anything downstream consumes it: the 0.01 kPa shear-stress
    # floor lets FS reach ~1e3 on near-level ground, which would dominate a
    # weighted risk sum without saying anything about actual stability.
    factor_of_safety = round(float(np.clip(raw_factor_of_safety, FS_MIN, FS_MAX)), 3)
    # Smooth 1 -> 0 ramp across the bounded band (FS 0.1 -> ~0.93, FS 5.0 -> ~0.003).
    factor_of_safety_risk = round(float(1.0 - np.tanh(factor_of_safety / FS_RISK_SCALE)), 4)

    # Slope failure probability P_f = P(FS < 1.0) via Monte Carlo perturbation
    fs_sims = []
    for _ in range(500):
        q_pert = max_pore_pressure * rng.normal(1.0, 0.15)
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
        "factor_of_safety_raw": round(raw_factor_of_safety, 3),
        "factor_of_safety_risk": factor_of_safety_risk,
        "failure_probability": failure_prob,
        "pressure_trajectory": np.round(Q_t, 2).tolist(),
        "time_axis_days": np.round(np.linspace(0, T, steps), 2).tolist(),
        "hazard_status": "HIGH_RISK" if failure_prob > 0.35 or factor_of_safety < 1.1 else "SAFE"
    }


def build_normalized_laplacian(adjacency) -> np.ndarray:
    """
    Symmetrically normalized graph Laplacian, ``L = I - D^-1/2 A D^-1/2``.

    Isolated (degree-0) nodes are handled by mapping their inverse square root to
    zero rather than inverting ``sqrt(0)``. The previous form called
    ``np.linalg.inv(np.sqrt(np.diag(deg)))``, which raises ``LinAlgError`` for any
    graph containing an isolated node — reachable from any caller that builds an
    adjacency matrix differently from the internal chain construction.

    With a zero row/column, such a node contributes nothing to diffusion and keeps
    a self-loop coefficient of 1, which is the correct limit (nothing flows in).
    """
    adj = np.asarray(adjacency, dtype=float)
    if adj.ndim != 2 or adj.shape[0] != adj.shape[1]:
        raise ValueError("adjacency must be a square matrix")

    n = adj.shape[0]
    deg = np.sum(adj, axis=1)
    safe_deg = np.where(deg > 0.0, deg, 1.0)
    inv_sqrt_deg = np.where(deg > 0.0, 1.0 / np.sqrt(safe_deg), 0.0)
    d_inv_sqrt = np.diag(inv_sqrt_deg)
    return np.eye(n) - d_inv_sqrt @ adj @ d_inv_sqrt


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
    rng = np.random.default_rng(seed)

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

    # Normalized Laplacian via the regularized helper, which tolerates degree-0
    # nodes instead of inverting sqrt(0).
    laplacian = build_normalized_laplacian(adj)

    # Initial defect levels (node 0 starts with severe defect, others minimal)
    p_t = np.zeros((timesteps + 1, num_nodes))
    p_t[0, 0] = 0.85  # Severe defect spike
    p_t[0, 1:] = rng.uniform(0.05, 0.2, size=num_nodes - 1)

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


def sample_levy_stable(alpha, beta, size, rng) -> np.ndarray:
    """
    Chambers-Mallows-Stuck sampler for an alpha-stable variate, ``S(alpha, beta)``.

    Uses Nolan's S1 parameterisation:

    .. code-block:: text

        zeta   = beta * tan(pi*alpha/2)
        theta0 = arctan(zeta) / alpha
        X = (1+zeta^2)^(1/(2*alpha))
            * sin(alpha*(V+theta0)) / cos(V)^(1/alpha)
            * (cos(alpha*theta0 + (alpha-1)*V) / W)^((1-alpha)/alpha)

    with ``V ~ U(-pi/2, pi/2)`` and ``W ~ Exp(1)``. Verified against the closed-form
    characteristic function ``exp(-|t|^alpha * (1 - i*beta*sign(t)*tan(pi*alpha/2)))``
    in the test suite.

    The third factor's base is positive on ``alpha in (1, 2)``; the ``clip`` only
    absorbs floating-point noise at the interval edges. The previous implementation
    used ``cos(u - alpha*(u + theta0))`` — a different theta0 coefficient — whose base
    goes negative, so raising it to a fractional power produced NaN at the default
    ``alpha=1.4``.
    """
    a = float(np.clip(alpha, 1.01, 2.0))
    b = float(np.clip(beta, -1.0, 1.0))

    v = rng.uniform(-np.pi / 2.0, np.pi / 2.0, size=size)
    w = rng.exponential(scale=1.0, size=size)

    zeta = b * np.tan(np.pi * a / 2.0)
    theta0 = np.arctan(zeta) / a

    cos_v = np.cos(v)
    cos_v = np.where(np.abs(cos_v) < 1e-12, 1e-12, cos_v)

    # Third factor. The argument is alpha*theta0 + (alpha-1)*V — NOT
    # alpha*theta0 + (alpha-1)*(theta0+V), which yields a negative base for roughly
    # 7% of draws at alpha=1.4 and pushes the whole distribution's mass into one
    # spurious tail. Verified against scipy.stats.levy_stable in the test suite.
    base = np.cos(a * theta0 + (a - 1.0) * v) / w
    base = np.where(base > 0.0, base, np.finfo(float).tiny)

    term1 = (1.0 + zeta ** 2) ** (1.0 / (2.0 * a))
    term2 = np.sin(a * (v + theta0)) / np.power(np.abs(cos_v), 1.0 / a)
    term3 = np.power(base, (1.0 - a) / a)

    variates = term1 * term2 * term3
    # A non-finite variate would poison np.percentile and silently return NaN as VaR.
    return np.where(np.isfinite(variates), variates, 0.0)


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
    rng = np.random.default_rng(seed)

    # 1. CMS Algorithm for Lévy-Stable variates (see sample_levy_stable).
    levy_variates = sample_levy_stable(alpha_tail, beta_skew, num_simulations, rng)

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
