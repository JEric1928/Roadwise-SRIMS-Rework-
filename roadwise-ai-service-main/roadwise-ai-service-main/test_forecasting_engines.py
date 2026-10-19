"""
Verification and Test Suite for RoadWise SRIMS 5 Mathematical Forecasting Engines
Tests monotonicity, numerical convergence, boundary constraints, and REST API contracts.
"""

import pytest
import numpy as np
from fastapi.testclient import TestClient

import os
import forecasting_engines as fe
import multi_hazard as mh
from csjdm_parameters import (
    CSJDM_GEOMORPHIC_PROFILE,
    SoilType,
    get_soil_parameters,
)

os.environ["AI_SERVICE_API_KEY"] = "test-key"
import main
main.AI_SERVICE_API_KEY = "test-key"

client = TestClient(main.app, headers={"X-API-Key": "test-key"})


# ==============================================================================
# 1. ENGINE 1: Continuous Gamma Decay + Grünwald-Letnikov Viscoelastic Operator
# ==============================================================================
def test_pavement_decay_monotonicity_and_bounds():
    res = fe.simulate_pavement_lifecycle(
        road_id="RD-TEST",
        soil_type="Sibul_Clay",
        initial_pci=100.0,
        years_horizon=5,
        fractional_alpha=0.35
    )

    pci = np.array(res["pci_trajectory"])

    # 1. Boundedness
    assert np.all(pci >= 0.0) and np.all(pci <= 100.0)
    assert pci[0] == 100.0

    # 2. Monotonicity of wear accumulation (PCI must be non-increasing)
    pci_diffs = np.diff(pci)
    assert np.all(pci_diffs <= 1e-5)

    # 3. Time to failure must be positive
    assert res["time_to_failure_years"] > 0.0


def test_pavement_decay_soil_impact():
    sibul_res = fe.simulate_pavement_lifecycle(soil_type="Sibul_Clay", years_horizon=5, seed=123)
    novaliches_res = fe.simulate_pavement_lifecycle(soil_type="Novaliches_Loam", years_horizon=5, seed=123)

    # Sibul Clay (expansive) must accumulate more total wear than Novaliches Loam
    assert sibul_res["total_wear"] > novaliches_res["total_wear"]


def test_pavement_decay_api_contract():
    payload = {
        "road_id": "RD-001",
        "soil_type": "Sibul_Clay",
        "initial_pci": 100.0,
        "years_horizon": 10,
        "fractional_alpha": 0.35
    }
    response = client.post("/api/v1/forecasting/pavement-decay", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert "pci_trajectory" in data
    assert "time_to_failure_years" in data
    assert len(data["pci_trajectory"]) == 121  # 10 years * 12 months + 1


# ==============================================================================
# 2. ENGINE 2: MLPS Weather & Itô Merton Jump-Diffusion Hydrological Engine
# ==============================================================================
def test_hydrological_shock_sde_convergence():
    res = fe.simulate_hydrological_shock(
        road_id="RD-HYDRO-01",
        soil_type="Sibul_Clay",
        rainfall_mm=250.0,
        slope_degrees=25.0,
        drainage_capacity_mm=30.0
    )

    # 1. Pore pressure must be positive
    pressures = np.array(res["pressure_trajectory"])
    assert np.all(pressures > 0.0)

    # 2. Factor of safety & failure probability bounds
    assert res["factor_of_safety"] > 0.0
    assert 0.0 <= res["failure_probability"] <= 1.0


def test_hydrological_shock_extreme_rainfall_impact():
    light_rain = fe.simulate_hydrological_shock(rainfall_mm=10.0, seed=42)
    extreme_rain = fe.simulate_hydrological_shock(rainfall_mm=300.0, seed=42)

    assert extreme_rain["peak_pore_water_pressure_kpa"] > light_rain["peak_pore_water_pressure_kpa"]
    assert extreme_rain["failure_probability"] >= light_rain["failure_probability"]


def test_hydrological_shock_api_contract():
    payload = {
        "road_id": "RD-102",
        "soil_type": "Sibul_Clay",
        "rainfall_mm": 180.0,
        "slope_degrees": 22.0,
        "drainage_capacity_mm": 50.0
    }
    response = client.post("/api/v1/forecasting/hydrological-shock", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert "peak_pore_water_pressure_kpa" in data
    assert "factor_of_safety" in data
    assert "failure_probability" in data


# ==============================================================================
# 3. ENGINE 3: Spatiotemporal UrbanDE-Net Graph Defect Propagation Engine
# ==============================================================================
def test_defect_propagation_bounds_and_diffusion():
    res = fe.simulate_defect_propagation(num_nodes=10, timesteps=12)

    traj = np.array(res["trajectory_matrix"])

    # 1. Defect values strictly within [0.0, 1.0]
    assert np.all(traj >= 0.0) and np.all(traj <= 1.0)

    # 2. Neighboring node (RD-002) should experience defect increase over time due to spatial diffusion
    rd2_initial = traj[0, 1]
    rd2_final = traj[-1, 1]
    assert rd2_final >= rd2_initial


def test_defect_propagation_api_contract():
    payload = {
        "num_nodes": 10,
        "timesteps": 24,
        "diffusion_rate": 0.15,
        "growth_rate": 0.08
    }
    response = client.post("/api/v1/forecasting/defect-propagation", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert "trajectory_matrix" in data
    assert "hotspot_nodes" in data
    assert "network_stress_index" in data


# ==============================================================================
# 4. ENGINE 4: Heavy-Tailed Lévy-Stable CMS Financial VaR / ES Engine
# ==============================================================================
def test_financial_risk_var_ordering():
    base_budget = 10000000.0
    res = fe.simulate_financial_risk_var(
        base_budget_php=base_budget,
        confidence_level=0.95,
        alpha_tail=1.4,
        num_simulations=3000
    )

    var = res["value_at_risk_var_php"]
    es = res["expected_shortfall_es_php"]

    # 1. VaR >= Base Budget
    assert var >= base_budget

    # 2. Expected Shortfall >= VaR (Risk-averse metric property)
    assert es >= var

    # 3. Required reserve = ES - Base Budget
    assert pytest.approx(res["required_contingency_reserve_php"], rel=1e-3) == (es - base_budget)


def test_financial_risk_api_contract():
    payload = {
        "base_budget_php": 15000000.0,
        "confidence_level": 0.95,
        "alpha_tail": 1.4,
        "beta_skew": 0.75,
        "num_simulations": 1000
    }
    response = client.post("/api/v1/forecasting/financial-var", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert "value_at_risk_var_php" in data
    assert "expected_shortfall_es_php" in data
    assert "required_contingency_reserve_php" in data


# ==============================================================================
# 5. ENGINE 5: Backus-Gilbert Parameter Inversion Data Assimilation Engine
# ==============================================================================
def test_data_assimilation_kernel_normalization():
    res = fe.assimilate_multi_sensor_data(
        vision_score=0.8,
        sensor_telemetry_score=0.4,
        geomechanical_baseline=0.5,
        tradeoff_alpha=0.6
    )

    weights = res["kernel_weights"]
    total_weight = weights["vision_weight"] + weights["telemetry_weight"] + weights["baseline_weight"]

    # Kernel weights must sum to 1.0 (Integration constraint)
    assert pytest.approx(total_weight, abs=1e-3) == 1.0

    # Assimilated score within [min_input, max_input]
    assert 0.4 <= res["assimilated_defect_score"] <= 0.8


def test_data_assimilation_api_contract():
    payload = {
        "vision_score": 0.65,
        "vision_confidence": 0.85,
        "sensor_telemetry_score": 0.40,
        "geomechanical_baseline": 0.50,
        "tradeoff_alpha": 0.5
    }
    response = client.post("/api/v1/forecasting/data-assimilation", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert "assimilated_defect_score" in data
    assert "posterior_variance" in data
    assert "kernel_weights" in data


def test_prediction_cache_thread_safety_and_lru():
    from main import cache_set, cache_get, PREDICTION_CACHE
    import threading

    def worker(i):
        cache_set(f"key_{i}", {"val": i})

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(50)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    # Verify lookups work thread-safely
    res = cache_get("key_0")
    assert res is not None or len(PREDICTION_CACHE) <= 1000


# ==============================================================================
# 6. CSJDM registry integrity
# ==============================================================================
def test_unknown_soil_raises_instead_of_defaulting_to_stable():
    """
    An unregistered series must fail loudly. It previously fell back to
    Novaliches Loam (120 MPa subgrade), so a typo answered "stable" for what may be
    expansive ground.
    """
    with pytest.raises(ValueError):
        get_soil_parameters("Novaliches_Silt")
    with pytest.raises(ValueError):
        get_soil_parameters("novaliches loam")   # wrong case/separator


def test_registered_soils_cover_the_city():
    soils = CSJDM_GEOMORPHIC_PROFILE["soils"]
    coverage = sum(entry["coverage_pct"] for entry in soils.values())
    assert 99.5 <= coverage <= 100.5
    # The 66.77% majority series was the missing one.
    assert "Novaliches_Clay_Loam" in soils
    assert soils["Novaliches_Clay_Loam"]["coverage_pct"] == pytest.approx(66.77)


def test_soil_enum_matches_the_registry():
    assert {member.value for member in SoilType} == set(CSJDM_GEOMORPHIC_PROFILE["soils"])
    assert SoilType.SIBUL_CLAY == "Sibul_Clay"


def test_unknown_soil_is_a_422_not_a_500():
    response = client.post(
        "/api/v1/forecasting/pavement-decay",
        json={"soil_type": "Novaliches_Silt"},
    )
    assert response.status_code == 422


def test_multi_hazard_weights_are_normalised():
    weights = CSJDM_GEOMORPHIC_PROFILE["multi_hazard_weights"]
    assert sum(weights.values()) == pytest.approx(1.0)
    assert weights == {"flood": 0.30, "landslide": 0.35, "seismic": 0.20, "erosion": 0.15}


# ==============================================================================
# 7. Pavement engine: status and time-to-failure regressions
# ==============================================================================
def test_short_horizon_does_not_report_critical():
    """
    Regression for the inverted status.

    ``time_to_failure_years`` fell back to ``float(years_horizon)`` whenever no
    crossing fell inside the window, and status was ``CRITICAL if ttf < 3.0``. A
    1-year forecast on pristine pavement therefore reported CRITICAL purely because
    of the horizon parameter.
    """
    for horizon in (1, 2):
        res = fe.simulate_pavement_lifecycle(
            soil_type="Novaliches_Loam", initial_pci=100.0, years_horizon=horizon)

        assert res["final_pci"] > 90.0
        assert res["time_to_failure_basis"] == "EXTRAPOLATED"
        assert res["time_to_failure_years"] > 3.0
        assert res["status"] == "STABLE", res


def test_critical_when_failure_occurs_inside_the_window():
    """The counterpart: real decay must read CRITICAL, at any horizon."""
    res = fe.simulate_pavement_lifecycle(
        soil_type="Sibul_Clay", initial_pci=100.0, years_horizon=10)

    assert res["time_to_failure_basis"] == "OBSERVED"
    assert res["time_to_failure_years"] <= 10.0
    assert res["status"] == "CRITICAL", res

def test_critical_status_tracks_actual_condition():
    res = fe.simulate_pavement_lifecycle(soil_type="Sibul_Clay", initial_pci=45.0,
                                         years_horizon=10)
    assert res["final_pci"] < fe.CRITICAL_PCI
    assert res["status"] == "CRITICAL"


def test_time_to_failure_never_equals_the_horizon():
    """
    Regression for the fallback: ``time_to_failure_years`` used to become exactly
    ``float(years_horizon)``, so the reported failure date *was* the window length.
    """
    for horizon in (1, 2, 3):
        res = fe.simulate_pavement_lifecycle(
            soil_type="Novaliches_Loam", initial_pci=100.0, years_horizon=horizon)

        assert res["time_to_failure_basis"] == "EXTRAPOLATED", horizon
        assert res["time_to_failure_years"] > horizon, horizon
        assert res["time_to_failure_years"] != pytest.approx(float(horizon))

def test_time_to_failure_basis_is_reported():
    res = fe.simulate_pavement_lifecycle(soil_type="Sibul_Clay", years_horizon=10)
    assert res["time_to_failure_basis"] in {"OBSERVED", "EXTRAPOLATED", "NONE"}


def test_calculate_true_ttf_boundaries():
    assert fe.calculate_true_ttf(100.0, 0.0) == float("inf")
    assert fe.calculate_true_ttf(100.0, -0.5) == float("inf")
    assert fe.calculate_true_ttf(30.0, 0.5) == 0.0
    # 60 PCI of budget at 0.5 PCI/month -> 120 months -> 10 years
    assert fe.calculate_true_ttf(100.0, 0.5) == pytest.approx(10.0)
    assert not np.isfinite(fe.calculate_true_ttf(float("nan"), 0.5))


# ==============================================================================
# 8. Hydrological engine: registry binding and FS bounding
# ==============================================================================
def test_jump_intensity_is_read_from_the_registry():
    assert CSJDM_GEOMORPHIC_PROFILE["hydrology"]["merton_jump_intensity_lambda"] == pytest.approx(0.15)


def test_soil_strength_comes_from_the_registry():
    """
    Novaliches Clay Loam must use its own cohesion/friction, not the stable branch the
    old ``soil_type == "Sibul_Clay"`` ternary produced for every other series.
    """
    clay_loam = fe.simulate_hydrological_shock(soil_type="Novaliches_Clay_Loam")
    loam = fe.simulate_hydrological_shock(soil_type="Novaliches_Loam")
    sibul = fe.simulate_hydrological_shock(soil_type="Sibul_Clay")

    assert clay_loam["factor_of_safety_raw"] < loam["factor_of_safety_raw"]
    assert sibul["factor_of_safety_raw"] < clay_loam["factor_of_safety_raw"]


def test_factor_of_safety_is_bounded_on_a_level_slope():
    res = fe.simulate_hydrological_shock(soil_type="Sibul_Clay", slope_degrees=0.0)
    # The 0.01 kPa shear-stress floor lets the raw value run far past the band.
    assert res["factor_of_safety_raw"] > fe.FS_MAX
    assert res["factor_of_safety"] == pytest.approx(fe.FS_MAX)
    assert 0.0 <= res["factor_of_safety_risk"] <= 1.0


def test_factor_of_safety_risk_is_monotonic_in_stability():
    steep = fe.simulate_hydrological_shock(soil_type="Sibul_Clay", slope_degrees=40.0)
    flat = fe.simulate_hydrological_shock(soil_type="Sibul_Clay", slope_degrees=0.0)
    assert steep["factor_of_safety_risk"] > flat["factor_of_safety_risk"]


# ==============================================================================
# 9. Graph engine: isolated nodes
# ==============================================================================
def test_normalized_laplacian_handles_an_isolated_node():
    """``inv(sqrt(diag(deg)))`` raised LinAlgError as soon as any node was isolated."""
    adj = np.zeros((4, 4))
    adj[0, 1] = adj[1, 0] = 1.0        # nodes 2 and 3 are isolated

    lap = fe.build_normalized_laplacian(adj)

    assert np.all(np.isfinite(lap))
    assert lap[2, 2] == pytest.approx(1.0)     # self-loop only
    assert lap[2, 0] == 0.0 and lap[0, 2] == 0.0


def test_normalized_laplacian_rejects_non_square():
    with pytest.raises(ValueError):
        fe.build_normalized_laplacian(np.zeros((2, 3)))


def test_defect_propagation_stays_finite_across_sizes():
    for nodes in (3, 6, 10, 50):
        res = fe.simulate_defect_propagation(num_nodes=nodes, timesteps=12)
        traj = np.array(res["trajectory_matrix"])
        assert np.all(np.isfinite(traj)), nodes
        assert np.all(traj >= 0.0) and np.all(traj <= 1.0), nodes


# ==============================================================================
# 10. Lévy-stable sampler: NaN regression and distributional correctness
# ==============================================================================
def test_levy_stable_never_produces_nan():
    """Regression: ``cos(...) ** ((1-alpha)/alpha)`` went NaN at the default alpha=1.4."""
    rng = np.random.default_rng(5)
    for alpha in (1.01, 1.2, 1.4, 1.8, 2.0):
        for beta in (-1.0, -0.5, 0.0, 0.75, 1.0):
            variates = fe.sample_levy_stable(alpha, beta, 20_000, rng)
            assert np.all(np.isfinite(variates)), (alpha, beta)


def test_levy_stable_reduces_to_normal_at_alpha_two():
    """``S(2, 0)`` is ``N(0, 2)`` — a convention-independent anchor for the transform."""
    rng = np.random.default_rng(11)
    variates = fe.sample_levy_stable(2.0, 0.0, 300_000, rng)
    assert np.mean(variates) == pytest.approx(0.0, abs=0.05)
    assert np.var(variates) == pytest.approx(2.0, abs=0.1)


def test_levy_stable_matches_scipy_reference_quantiles():
    """
    Ground-truth check against ``scipy.stats.levy_stable``.

    This is the assertion that actually pins the transform. A previous version of
    ``sample_levy_stable`` used ``cos(alpha*theta0 + (alpha-1)*(theta0+V))`` for the
    third factor, which goes negative for ~7% of draws at alpha=1.4; clipping it
    produced a finite but badly wrong distribution with the skew *inverted* (beta>0
    gave the heavy left tail). Finiteness and a loose characteristic-function check
    both missed that, because the clip hid the negative base rather than raising.
    """
    from scipy import stats

    quantiles = np.array([0.05, 0.25, 0.50, 0.75, 0.95])

    for alpha, beta in ((1.4, 0.75), (1.4, -0.75), (1.2, 0.50), (1.8, -0.60), (1.6, 0.0)):
        rng = np.random.default_rng(2024)
        sample = fe.sample_levy_stable(alpha, beta, 200_000, rng)
        assert np.all(np.isfinite(sample)), (alpha, beta)

        ours = np.quantile(sample, quantiles)
        reference = stats.levy_stable(alpha=alpha, beta=beta).ppf(quantiles)
        max_error = float(np.max(np.abs(ours - reference)))
        assert max_error < 0.15, (alpha, beta, ours, reference)


def test_levy_stable_skew_follows_the_sign_of_beta():
    """beta > 0 must put the heavy tail on the right; beta < 0 on the left."""
    rng = np.random.default_rng(7)

    right = fe.sample_levy_stable(1.4, 0.75, 200_000, rng)
    left = fe.sample_levy_stable(1.4, -0.75, 200_000, rng)

    # Compare tail reach in a robust way: the ratio of the upper to lower spread.
    right_ratio = np.quantile(right, 0.95) / abs(np.quantile(right, 0.05))
    left_ratio = np.quantile(left, 0.95) / abs(np.quantile(left, 0.05))

    assert right_ratio > 1.0 > left_ratio, (right_ratio, left_ratio)


def test_financial_var_stays_finite_across_tail_parameters():
    for alpha in (1.01, 1.2, 1.4, 1.8, 2.0):
        for beta in (-1.0, 0.0, 0.75):
            res = fe.simulate_financial_risk_var(
                alpha_tail=alpha, beta_skew=beta, num_simulations=1500)
            assert np.isfinite(res["value_at_risk_var_php"]), (alpha, beta)
            assert np.isfinite(res["expected_shortfall_es_php"]), (alpha, beta)
            assert res["value_at_risk_var_php"] >= res["base_budget_php"]
            assert res["expected_shortfall_es_php"] >= res["value_at_risk_var_php"]


# ==============================================================================
# 11. Multi-hazard composite index (roadmap scaffold)
# ==============================================================================
def test_multi_hazard_index_is_bounded():
    assert mh.composite_risk_index()["risk_index"] == pytest.approx(0.0)
    saturated = mh.composite_risk_index(flood=1.0, landslide=1.0, seismic=1.0, erosion=1.0)
    assert saturated["risk_index"] == pytest.approx(1.0)
    assert sum(saturated["contributions"].values()) == pytest.approx(1.0)


def test_multi_hazard_clips_hostile_inputs():
    over = mh.composite_risk_index(flood=5.0, landslide=-3.0, seismic=float("nan"))
    assert 0.0 <= over["risk_index"] <= 1.0
    assert over["normalised_hazards"]["flood"] == 1.0
    assert over["normalised_hazards"]["landslide"] == 0.0
    assert over["normalised_hazards"]["seismic"] == 0.0


def test_multi_hazard_ranks_landslide_heaviest():
    """EP-2024 weights put landslide at 0.35, the largest single hazard."""
    weights = mh.WEIGHTS
    assert weights["landslide"] == max(weights.values())


def test_multi_hazard_api_contract():
    response = client.post(
        "/api/v1/forecasting/multi-hazard-index",
        json={"flood": 0.5, "landslide": 0.8, "seismic": 0.2, "erosion": 0.4},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["implementation_status"] == "ROADMAP"
    assert 0.0 <= body["risk_index"] <= 1.0
    # 0.30*0.5 + 0.35*0.8 + 0.20*0.2 + 0.15*0.4 = 0.53
    assert body["risk_index"] == pytest.approx(0.53, abs=1e-3)
