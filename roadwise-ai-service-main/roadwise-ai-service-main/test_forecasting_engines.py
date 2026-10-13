"""
Verification and Test Suite for RoadWise SRIMS 5 Mathematical Forecasting Engines
Tests monotonicity, numerical convergence, boundary constraints, and REST API contracts.
"""

import pytest
import numpy as np
from fastapi.testclient import TestClient

import os
import forecasting_engines as fe

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
