// ==============================================================================
// ADVANCED MATHEMATICAL FORECASTING ENGINES DASHBOARD INTEGRATION
// ==============================================================================
let pavementDecayChartInstance = null;

function getFreshnessTimestamp() {
  return new Date().toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

async function fetchPavementDecay(soilType) {
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
      renderPavementDecayChart(data, false);
    } else {
      renderPavementDecayChartFallback(soilType);
    }
  } catch (err) {
    console.warn("Forecasting API unreachable, rendering fallback simulation:", err);
    renderPavementDecayChartFallback(soilType);
  }
}

async function fetchFinancialRisk() {
  const timestamp = getFreshnessTimestamp();
  const statusEl = document.getElementById("financial-var-status-label");

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
      const varEl = document.getElementById("var-amount-text");
      const esEl = document.getElementById("es-amount-text");
      const reserveEl = document.getElementById("reserve-amount-text");
      if (varEl) varEl.innerText = "₱" + Number(data.value_at_risk_var_php).toLocaleString();
      if (esEl) esEl.innerText = "₱" + Number(data.expected_shortfall_es_php).toLocaleString();
      if (reserveEl) reserveEl.innerText = "₱" + Number(data.required_contingency_reserve_php).toLocaleString();
      if (statusEl) statusEl.innerText = `Live API • Updated at ${timestamp}`;
    } else {
      throw new Error("API returned non-200 response");
    }
  } catch (err) {
    const varEl = document.getElementById("var-amount-text");
    const esEl = document.getElementById("es-amount-text");
    const reserveEl = document.getElementById("reserve-amount-text");
    if (varEl) varEl.innerText = "₱21,450,000 (Demo)";
    if (esEl) esEl.innerText = "₱28,900,000 (Demo)";
    if (reserveEl) reserveEl.innerText = "₱13,900,000 (Demo)";
    if (statusEl) statusEl.innerText = `Forecast unavailable (Demo Simulation) • Updated ${timestamp}`;
  }
}

async function triggerForecastingUpdate() {
  const soilType = document.getElementById("forecasting-soil-select") ? document.getElementById("forecasting-soil-select").value : "Sibul_Clay";

  await Promise.all([
    fetchPavementDecay(soilType),
    fetchFinancialRisk()
  ]);

  const slider = document.getElementById("assimilation-slider");
  if (slider) {
    updateDataAssimilationUI(slider.value);
  }
}

function renderPavementDecayChart(data, isFallback = false) {
  const ctx = document.getElementById("pavementDecayChart");
  if (!ctx) return;

  const timestamp = getFreshnessTimestamp();
  const badge = document.getElementById("pavement-ttf-badge");
  if (badge) {
    const modePrefix = isFallback ? "[Demo Fallback - API Offline]" : "[Live Model]";
    badge.innerText = `${modePrefix} Predicted Time-To-Failure (PCI < 40): ${data.time_to_failure_years} Years (${data.status}) • Updated at ${timestamp}`;
    badge.style.color = isFallback ? "#d97706" : (data.status === "CRITICAL" ? "#dc2626" : "#059669");
  }

  if (pavementDecayChartInstance) {
    pavementDecayChartInstance.data.labels = data.time_axis_years;
    pavementDecayChartInstance.data.datasets[0].label = `PCI Condition Score (${data.soil_type})${isFallback ? ' - Simulated' : ''}`;
    pavementDecayChartInstance.data.datasets[0].data = data.pci_trajectory;
    pavementDecayChartInstance.update();
    return;
  }

  pavementDecayChartInstance = new Chart(ctx, {
    type: "line",
    data: {
      labels: data.time_axis_years,
      datasets: [{
        label: `PCI Condition Score (${data.soil_type})${isFallback ? ' - Simulated' : ''}`,
        data: data.pci_trajectory,
        borderColor: isFallback ? "#d97706" : "#2563eb",
        backgroundColor: isFallback ? "rgba(217, 119, 6, 0.1)" : "rgba(37, 99, 235, 0.1)",
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
  }, true);
}

const debouncedFetchDataAssimilation = (typeof debounce === 'function') ? debounce(async (alphaVal) => {
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
      const scoreEl = document.getElementById("assimilated-score-val");
      const varEl = document.getElementById("posterior-var-val");
      if (scoreEl) scoreEl.innerText = data.assimilated_defect_score;
      if (varEl) varEl.innerText = data.posterior_variance;
    } else {
      throw new Error("API non-200");
    }
  } catch (err) {
    const scoreEl = document.getElementById("assimilated-score-val");
    const varEl = document.getElementById("posterior-var-val");
    if (scoreEl) scoreEl.innerText = `${(0.5 + alphaVal * 0.25).toFixed(3)} (Demo)`;
    if (varEl) varEl.innerText = `${(0.04 * (1 - alphaVal) + 0.01 * alphaVal).toFixed(4)} (Demo)`;
  }
}, 300) : null;

async function updateDataAssimilationUI(alphaVal) {
  const label = document.getElementById("assimilation-alpha-label");
  if (label) label.innerText = `Trade-off α = ${parseFloat(alphaVal).toFixed(2)}`;

  if (debouncedFetchDataAssimilation) {
    debouncedFetchDataAssimilation(alphaVal);
  } else {
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
        const scoreEl = document.getElementById("assimilated-score-val");
        const varEl = document.getElementById("posterior-var-val");
        if (scoreEl) scoreEl.innerText = data.assimilated_defect_score;
        if (varEl) varEl.innerText = data.posterior_variance;
      } else {
        throw new Error("API non-200");
      }
    } catch (err) {
      const scoreEl = document.getElementById("assimilated-score-val");
      const varEl = document.getElementById("posterior-var-val");
      if (scoreEl) scoreEl.innerText = `${(0.5 + alphaVal * 0.25).toFixed(3)} (Demo)`;
      if (varEl) varEl.innerText = `${(0.04 * (1 - alphaVal) + 0.01 * alphaVal).toFixed(4)} (Demo)`;
    }
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
