
// ==============================================================================
// ADVANCED MATHEMATICAL FORECASTING ENGINES DASHBOARD INTEGRATION
// ==============================================================================
let pavementDecayChartInstance = null;

async function triggerForecastingUpdate() {
  const soilType = document.getElementById("forecasting-soil-select") ? document.getElementById("forecasting-soil-select").value : "Sibul_Clay";

  // 1. Fetch Engine 1: Pavement Decay
  try {
    const res = await fetch(`${API_BASE_URL}/api/v1/forecasting/pavement-decay`, {
      method: "POST",
      headers: API_HEADERS,
      body: JSON.stringify({
        road_id: "RD-001",
        soil_type: soilType,
        initial_pci: 100.0,
        years_horizon: 10,
        fractional_alpha: 0.35
      })
    });
    if (res.ok) {
      const data = await res.json();
      renderPavementDecayChart(data);
    }
  } catch (err) {
    console.warn("Forecasting API unreachable, rendering fallback simulation:", err);
    renderPavementDecayChartFallback(soilType);
  }

  // 2. Fetch Engine 4: Financial Risk
  try {
    const res = await fetch(`${API_BASE_URL}/api/v1/forecasting/financial-var`, {
      method: "POST",
      headers: API_HEADERS,
      body: JSON.stringify({
        base_budget_php: 15000000.0,
        confidence_level: 0.95,
        alpha_tail: 1.4,
        beta_skew: 0.75,
        num_simulations: 2000
      })
    });
    if (res.ok) {
      const data = await res.json();
      document.getElementById("var-amount-text").innerText = "₱" + Number(data.value_at_risk_var_php).toLocaleString();
      document.getElementById("es-amount-text").innerText = "₱" + Number(data.expected_shortfall_es_php).toLocaleString();
      document.getElementById("reserve-amount-text").innerText = "₱" + Number(data.required_contingency_reserve_php).toLocaleString();
    }
  } catch (err) {
    document.getElementById("var-amount-text").innerText = "₱21,450,000";
    document.getElementById("es-amount-text").innerText = "₱28,900,000";
    document.getElementById("reserve-amount-text").innerText = "₱13,900,000";
  }

  // 3. Trigger Engine 5 Assimilation Initial Slider Update
  const slider = document.getElementById("assimilation-slider");
  if (slider) {
    updateDataAssimilationUI(slider.value);
  }
}

function renderPavementDecayChart(data) {
  const ctx = document.getElementById("pavementDecayChart");
  if (!ctx) return;

  if (pavementDecayChartInstance) {
    pavementDecayChartInstance.destroy();
  }

  const badge = document.getElementById("pavement-ttf-badge");
  if (badge) {
    badge.innerText = `Predicted Time-To-Failure (PCI < 40): ${data.time_to_failure_years} Years (${data.status})`;
    badge.style.color = data.status === "CRITICAL" ? "#dc2626" : "#059669";
  }

  pavementDecayChartInstance = new Chart(ctx, {
    type: "line",
    data: {
      labels: data.time_axis_years,
      datasets: [{
        label: `PCI Condition Score (${data.soil_type})`,
        data: data.pci_trajectory,
        borderColor: "#2563eb",
        backgroundColor: "rgba(37, 99, 235, 0.1)",
        fill: true,
        tension: 0.3,
        pointRadius: 0
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      scales: {
        y: { min: 0, max: 100, title: { display: true, text: "PCI Score" } },
        x: { title: { display: true, text: "Years" } }
      }
    }
  });
}

function renderPavementDecayChartFallback(soilType) {
  const isSibul = soilType === "Sibul_Clay";
  const years = Array.from({length: 121}, (_, i) => (i / 12).toFixed(1));
  const pci = years.map(y => Math.max(0, 100 - (isSibul ? 8.5 : 4.2) * y - 0.3 * y * y));
  renderPavementDecayChart({
    soil_type: soilType,
    time_axis_years: years,
    pci_trajectory: pci,
    time_to_failure_years: isSibul ? 4.8 : 8.5,
    status: isSibul ? "CRITICAL" : "STABLE"
  });
}

async function updateDataAssimilationUI(alphaVal) {
  const label = document.getElementById("assimilation-alpha-label");
  if (label) label.innerText = `Trade-off α = ${parseFloat(alphaVal).toFixed(2)}`;

  try {
    const res = await fetch(`${API_BASE_URL}/api/v1/forecasting/data-assimilation`, {
      method: "POST",
      headers: API_HEADERS,
      body: JSON.stringify({
        vision_score: 0.75,
        vision_confidence: 0.88,
        sensor_telemetry_score: 0.45,
        geomechanical_baseline: 0.50,
        tradeoff_alpha: parseFloat(alphaVal)
      })
    });
    if (res.ok) {
      const data = await res.json();
      document.getElementById("assimilated-score-val").innerText = data.assimilated_defect_score;
      document.getElementById("posterior-var-val").innerText = data.posterior_variance;
    }
  } catch (err) {
    document.getElementById("assimilated-score-val").innerText = (0.5 + alphaVal * 0.25).toFixed(3);
    document.getElementById("posterior-var-val").innerText = (0.04 * (1 - alphaVal) + 0.01 * alphaVal).toFixed(4);
  }
}

// Auto-initialize forecasting charts when window loads
document.addEventListener("DOMContentLoaded", () => {
  setTimeout(() => {
    if (document.getElementById("pavementDecayChart")) {
      triggerForecastingUpdate();
    }
  }, 800);
});
