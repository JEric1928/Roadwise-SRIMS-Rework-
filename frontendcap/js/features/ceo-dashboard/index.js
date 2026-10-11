/**
 * CEO dashboard engine: data loader, progress/status charts, tables and
 * filters, manage modal with map, schedule-extension request, batch defer,
 * repair completion, and the CEO tab watchdogs lifted from the main app.js
 * listener.
 *
 * Extracted verbatim from app.js. The watchdogs register in their own
 * DOMContentLoaded listener at the top, preserving their original position
 * ahead of the engine own DOMContentLoaded boot.
 *
 * Bridges kept (all pre-existing window.* assignments moved with the block):
 * loadCEODashboardData, renderCEOTable, filterCEODashTable, toggleCEOTimeline,
 * openCEOManageModal, toggleCeoExtensionForm, submitCeoExtensionRequest,
 * jumpToCEOMasterlistAndManage, deferringSingleId, closeBatchDeferModal,
 * markAsPendingBudget, openBatchDeferModal, submitBatchDefer,
 * executeBatchDeferral, toggleBatchActionBar, toggleAllCheckboxes,
 * submitCEOCompletion, and the ceoManage* map state.
 *
 * External reads stay unchanged: window.filterCEOReports (search-filters),
 * window.loadReportTimeline (barangay-dashboard), loadSecureImage
 * (secure-image), and the live-updates/navigation reload guards.
 */
import { apiFetch, API_BASE_URL } from '../../core/api.js';
import { showToast } from '../../components/toast.js';
import { calculateEstimatedDays } from '../../domain/repair-estimates.js';

// ==========================================
// CEO DASHBOARD WATCHDOGS (lifted from the main app.js listener)
// ==========================================
// Registers in its own DOMContentLoaded listener ahead of the engine boot,
// preserving the original main-listener ordering.
document.addEventListener('DOMContentLoaded', () => {
  // ==========================================
  // 🚀 CEO DASHBOARD: THE "ADMIN MAGIC" WATCHDOG
  // ==========================================
  // 1. Initial Load Check for CEO Dashboard
  if (document.getElementById('view-dashboard') && document.getElementById('ceo-metric-total')) {
    if (typeof loadCEODashboardData === 'function') {
      loadCEODashboardData();
    }
  }
});

// ==========================================
// 🚀 CEO DASHBOARD: REAL DATA ENGINE & RENDERER
// ==========================================
let rawAllCEOReports = [];
let rawActiveCEORows = [];
let ceoStatusChartInstance = null;

// Global variables for Modal Map
let currentCEOProjectID = null;
let currentCEOLat = 0;
let currentCEOLng = 0;
let ceoManageMap = null;
let ceoManageMarker = null;

// ==========================================
// 1. DATA LOADER (MAIN BRAIN)
// ==========================================
window.loadCEODashboardData = function() {
  apiFetch(`/api/reports`, { cache: 'no-store' })
    .catch(err => {
      console.error("🚨 [CEO API] Failed to fetch reports:", err);
      return [];
    })
    .then(reports => {
      const data = Array.isArray(reports) ? reports : [];

      // Include all actionable and finished work orders for this cycle (exclude past archived)
      rawAllCEOReports = data.filter(r => {
        const s = String(r.status || '').trim().toLowerCase();
        return s === 'dispatched to ceo' || s === 'in progress' || s === 'completed' || s === 'repaired' || s === 'pending budget';
      });

      // 🧠 DECISION TREE & TIER SCORING
      rawAllCEOReports.forEach(report => {
        const severity = String(report.severity || 'Unassessed').toLowerCase();
        const importance = String(report.roadImportance || '').toLowerCase();
        const status = String(report.status || '').trim().toLowerCase();
        const hasRework = Boolean(report.adminRemarks && report.adminRemarks.trim() !== '');

        // --- STEP 1: CEO WORKFLOW STATUS TIERS ---
        if (status === 'in progress' && hasRework) {
          report.statusScore = 5; // URGENT: Returned by Admin for Rework (TOP)
        } else if (status === 'in progress') {
          report.statusScore = 4; // ACTIVE: Currently being repaired on-site
        } else if (status === 'dispatched to ceo') {
          report.statusScore = 3; // NEW: Needs scheduling
        } else if (status === 'pending budget') {
          report.statusScore = 2; // DEFERRED: Awaiting Admin acknowledgment
        } else {
          report.statusScore = 1; // COMPLETED / REPAIRED: Waiting for QA
        }

        // --- STEP 2: PRIORITY BADGE & TIER COLOR (UNASSESSED UNIFIED) ---
        report.tierScore = 0;
        report.tierLabel = 'UNASSESSED';
        report.tierColor = '#ecfdf5';            // Light mint background
        report.tierTextColor = '#047857';        // Dark green text
        report.tierBorder = '1px solid #34d399'; // Green outline border

        if (severity === 'high') {
          report.tierScore = 3;
          report.tierLabel = 'HIGH';
          report.tierColor = '#dc2626';     // Red
          report.tierTextColor = '#ffffff'; // White text
          report.tierBorder = '1px solid #dc2626';
        } else if (severity === 'medium') {
          if (importance.includes('core')) {
            report.tierScore = 3;
            report.tierLabel = 'HIGH';
            report.tierColor = '#dc2626';
            report.tierTextColor = '#ffffff';
            report.tierBorder = '1px solid #dc2626';
          } else {
            report.tierScore = 2;
            report.tierLabel = 'MEDIUM';
            report.tierColor = '#ffc107';     // Amber/Yellow
            report.tierTextColor = '#000000'; // Dark text
            report.tierBorder = '1px solid #eab308';
          }
        } else if (severity === 'low') {
          if (importance.includes('core')) {
            report.tierScore = 2;
            report.tierLabel = 'MEDIUM';
            report.tierColor = '#ffc107';
            report.tierTextColor = '#000000';
            report.tierBorder = '1px solid #eab308';
          } else {
            report.tierScore = 1;
            report.tierLabel = 'LOW';
            report.tierColor = '#16a34a';     // Solid Green
            report.tierTextColor = '#ffffff'; // White text
            report.tierBorder = '1px solid #16a34a';
          }
        }

        // --- STEP 3: AREA SCORE ---
        const dLength = parseFloat(report.damageLength) || 0;
        const dWidth = parseFloat(report.damageWidth) || 0;
        report.areaScore = dLength * dWidth;
      });

      // --- HYBRID CEO SORTING ALGORITHM ---
      rawAllCEOReports.sort((a, b) => {
        // RULE 1: Completed items go to bottom
        const aIsCompleted = a.statusScore === 1;
        const bIsCompleted = b.statusScore === 1;
        if (aIsCompleted && !bIsCompleted) return 1;
        if (!aIsCompleted && bIsCompleted) return -1;

        // RULE 2: Reworks go to the top
        const aIsRework = a.statusScore === 5;
        const bIsRework = b.statusScore === 5;
        if (aIsRework && !bIsRework) return -1;
        if (!aIsRework && bIsRework) return 1;

        // RULE 3: Severity Wins
        if (b.tierScore !== a.tierScore) {
          return b.tierScore - a.tierScore;
        }

        // RULE 4: Dispatched before In Progress if severity tied
        if (b.statusScore !== a.statusScore) {
          return b.statusScore - a.statusScore;
        }

        // RULE 5: Largest Area
        if (b.areaScore !== a.areaScore) {
          return b.areaScore - a.areaScore;
        }

        // RULE 6: Oldest Date
        const dateA = new Date(a.date_submitted || a.dateSubmitted || 0);
        const dateB = new Date(b.date_submitted || b.dateSubmitted || 0);
        return dateA - dateB;
      });

      // Active Queue items (Exclude Completed from dashboard active table)
      rawActiveCEORows = rawAllCEOReports.filter(r => {
        const s = String(r.status || '').trim().toLowerCase();
        return s === 'dispatched to ceo' || s === 'in progress' || s === 'pending budget';
      });

      // KPI Metric Counts
      let countDispatched = 0;
      let countInProgress = 0;
      let countRework = 0;
      let countPendingBudget = 0;
      let countCompleted = 0;

      rawAllCEOReports.forEach(r => {
        const s = String(r.status || '').trim().toLowerCase();
        const hasRework = Boolean(r.adminRemarks && r.adminRemarks.trim() !== '');

        if (s === 'dispatched to ceo') {
          countDispatched++;
        } else if (s === 'in progress') {
          if (hasRework) countRework++;
          else countInProgress++;
        } else if (s === 'pending budget') {
          countPendingBudget++;
        } else if (s === 'completed' || s === 'repaired' || s === 'closed' || s === 'resolved') {
          countCompleted++;
        }
      });

      // Update KPI Cards
      const totalEl = document.getElementById('ceo-metric-total');
      const actEl = document.getElementById('ceo-metric-active');
      const rewEl = document.getElementById('ceo-metric-rework');
      const budEl = document.getElementById('ceo-metric-budget');

      if (totalEl) totalEl.innerText = countDispatched;
      if (actEl) actEl.innerText = countInProgress;
      if (rewEl) rewEl.innerText = countRework;
      if (budEl) budEl.innerText = countPendingBudget;

      // Update Annual Progress Accomplishment
      updateCEOProgressBarUI(countCompleted, rawAllCEOReports.length);

      // Update Status Chart
      updateCEOStatusChart([countInProgress, countRework, countPendingBudget, countCompleted]);

      // Render Tables
      renderCEOTable(rawActiveCEORows, 'ultimate-ceo-dash-table', true);
      renderCEOTable(rawAllCEOReports, 'deploy-master-table', false);
    });
};

// ==========================================
// 2. ACCOMPLISHMENT PROGRESS BAR (ANNUAL)
// ==========================================
function updateCEOProgressBarUI(completedCount, totalAssigned) {
  const safeTotal = totalAssigned > 0 ? totalAssigned : Math.max(completedCount, 1);
  let percentage = Math.round((completedCount / safeTotal) * 100);
  if (percentage > 100) percentage = 100;

  const progressText = document.getElementById('ceo-progress-text');
  const progressPercent = document.getElementById('ceo-progress-percentage');
  const barFill = document.getElementById('ceo-progress-bar-fill');

  if (progressText && progressPercent && barFill) {
    progressText.innerHTML = `<strong>${completedCount}</strong> of <strong>${totalAssigned}</strong> assigned road projects completed.`;
    progressPercent.innerText = `${percentage}%`;
    barFill.style.width = `${percentage}%`;

    if (percentage === 100) {
      barFill.style.background = 'linear-gradient(90deg, #16a34a, #22c55e)';
      progressPercent.style.color = '#16a34a';
    } else {
      barFill.style.background = 'linear-gradient(90deg, #0284c7, #38bdf8)';
      progressPercent.style.color = '#0284c7';
    }
  }
}

// ==========================================
// 3. WORK ORDER LIFECYCLE DOUGHNUT CHART
// ==========================================
function updateCEOStatusChart(dataArray) {
  const canvasId = 'ceoStatusChart';
  const ctx = document.getElementById(canvasId);
  if (!ctx) return;

  if (ceoStatusChartInstance) {
    ceoStatusChartInstance.destroy();
  }

  let existingChart = Chart.getChart(canvasId);
  if (existingChart) existingChart.destroy();

  const total = dataArray.reduce((a, b) => a + b, 0);
  const isEmpty = total === 0;

  ceoStatusChartInstance = new Chart(ctx, {
    type: 'doughnut',
    data: {
      labels: isEmpty ? ['No Active Projects'] : ['In Progress', 'Rework Required', 'Pending Budget', 'Completed'],
      datasets: [{
        data: isEmpty ? [1] : dataArray,
        backgroundColor: isEmpty ? ['#e2e8f0'] : ['#0284c7', '#dc2626', '#f59e0b', '#16a34a'],
        borderWidth: 2,
        borderColor: '#ffffff'
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: {
          position: 'bottom',
          labels: {
            boxWidth: 10,
            font: { size: 10.5, weight: '600' },
            padding: 8
          }
        },
        tooltip: { enabled: !isEmpty }
      },
      cutout: '70%'
    }
  });
}

// ==========================================
// 4. REUSABLE TABLE GENERATOR (CEO ENGINEER THEME)
// ==========================================
window.renderCEOTable = function(dataArray, tbodyId, isDashboard) {
  const tbody = document.getElementById(tbodyId);
  if (!tbody) return;

  tbody.innerHTML = '';

  if (dataArray.length === 0) {
    const colCount = isDashboard ? "7" : "7";
    tbody.innerHTML = `<tr><td colspan="${colCount}" style="text-align: center; padding: 35px; color: #94a3b8; font-style: italic;">No active projects matching criteria.</td></tr>`;
    return;
  }

  dataArray.forEach((report) => {
    const formatId = `#PRJ-${String(report.id).padStart(4, '0')}`;
    const formatBrgy = (report.barangay && report.barangay.barangayName) ? report.barangay.barangayName : 'Unknown';
    const formatName = report.cityRoadName || 'Unnamed Road';
    const damageType = report.damageType || 'Road Damage';

    const dLen = parseFloat(report.damageLength) || 0;
    const dWid = parseFloat(report.damageWidth) || 0;
    const area = dLen * dWid;
    const formatArea = area > 0 ? `${area.toFixed(1)} sq.m` : 'Unknown';

    const status = String(report.status || '').toLowerCase();
    const hasRework = Boolean(report.adminRemarks && report.adminRemarks.trim() !== '');
    const onClickAction = isDashboard ? `jumpToCEOMasterlistAndManage(${report.id})` : `openCEOManageModal(${report.id})`;

    // Status Badge & Action Button Configuration
    let statusHtml = `<span style="background: #e0f2fe; color: #0369a1; padding: 4px 9px; border-radius: 20px; font-size: 11px; font-weight: 700; border: 1px solid #bae6fd; white-space: nowrap;">Dispatched</span>`;

    // 🚀 UPDATED: CEO Theme Primary Orange (#ea580c) with smooth hover and shadow
    let btnHtml = `<button onclick="${onClickAction}" style="background-color: #ea580c; color: white; border: none; padding: 6px 14px; border-radius: 6px; cursor: pointer; font-weight: 700; font-size: 12px; box-shadow: 0 2px 6px rgba(234, 88, 12, 0.28); transition: background-color 0.2s;" onmouseover="this.style.backgroundColor='#c2410c'" onmouseout="this.style.backgroundColor='#ea580c'">Manage</button>`;

    if (status === 'in progress') {
      if (hasRework) {
        statusHtml = `<span style="background-color: #fef2f2; color: #dc2626; padding: 4px 9px; border-radius: 20px; font-size: 11px; font-weight: 700; border: 1px solid #fecaca; white-space: nowrap;">⚠️ Rework Required</span>`;
      } else {
        statusHtml = `<span style="background-color: #ffedd5; color: #c2410c; padding: 4px 9px; border-radius: 20px; font-size: 11px; font-weight: 700; border: 1px solid #fed7aa; white-space: nowrap;">In Progress</span>`;
      }
    } else if (status === 'pending budget') {
      statusHtml = `<span style="background-color: #fef3c7; color: #d97706; padding: 4px 9px; border-radius: 20px; font-size: 11px; font-weight: 700; border: 1px solid #fde68a; white-space: nowrap;">⏳ Pending Budget</span>`;
    } else if (status.includes('complet') || status.includes('repair')) {
      statusHtml = `<span style="background-color: #dcfce7; color: #15803d; padding: 4px 9px; border-radius: 20px; font-size: 11px; font-weight: 700; border: 1px solid #bbf7d0; white-space: nowrap;">✅ Completed</span>`;
      btnHtml = `<button onclick="${onClickAction}" style="background-color: #16a34a; color: white; border: none; padding: 6px 14px; border-radius: 6px; cursor: pointer; font-weight: 700; font-size: 12px; box-shadow: 0 2px 6px rgba(22, 163, 74, 0.28); transition: background-color 0.2s;" onmouseover="this.style.backgroundColor='#15803d'" onmouseout="this.style.backgroundColor='#16a34a'">View Proof</button>`;
    }

    // Checkbox logic for Masterlist view only
    let checkboxHtml = '';
    if (!isDashboard) {
      if (status === 'dispatched to ceo') {
        checkboxHtml = `
          <td style="text-align: center; padding: 12px;">
            <input type="checkbox" class="defer-checkbox" value="${report.id}" onchange="toggleBatchActionBar()" style="cursor: pointer; width: 16px; height: 16px;">
          </td>`;
      } else {
        checkboxHtml = `<td style="padding: 12px;"></td>`;
      }
    }

    const tr = document.createElement('tr');
    tr.style.borderBottom = "1px solid #f1f5f9";
    tr.style.transition = "background-color 0.15s";
    tr.onmouseover = () => tr.style.backgroundColor = "#f8fafc";
    tr.onmouseout = () => tr.style.backgroundColor = "transparent";

    tr.innerHTML = `
      ${checkboxHtml}
      <td style="padding: 12px 15px; border-left: 4px solid ${report.tierColor}; white-space: nowrap;">
        <strong style="font-family: monospace; font-size: 12.5px; color: #0f172a;">${formatId}</strong>
      </td>
      <td style="padding: 12px 15px; font-size: 12.5px; color: #475569;">${formatBrgy}</td>
      <td style="padding: 12px 15px;">
        <div style="font-weight: 700; color: #0f172a; font-size: 13.5px;">${formatName}</div>
        <div style="font-size: 11.5px; color: #64748b; margin-top: 1px;">🛠️ ${damageType} ${dLen > 0 ? `(${dLen}m × ${dWid}m)` : ''}</div>
      </td>
      <td style="padding: 12px 15px; color: #475569; font-weight: 600; white-space: nowrap;">${formatArea}</td>
      <td style="padding: 12px 15px; text-align: center; white-space: nowrap;">
  <span style="background-color: ${report.tierColor || '#ecfdf5'}; color: ${report.tierTextColor || '#047857'}; border: ${report.tierBorder || '1px solid #34d399'}; padding: 3px 9px; border-radius: 5px; font-size: 11px; font-weight: 800; display: inline-block; letter-spacing: 0.3px; white-space: nowrap;">
    ${report.tierLabel || 'UNASSESSED'}
  </span>
</td>
      <td style="padding: 12px 15px; text-align: center;">
        ${statusHtml}
      </td>
      <td style="padding: 12px 15px; text-align: center;">
        ${btnHtml}
      </td>
    `;
    tbody.appendChild(tr);
  });

  if (!isDashboard && typeof window.filterCEOReports === 'function') {
    window.filterCEOReports();
  }
};

// ==========================================
// 5. DASHBOARD TABLE SEARCH & FILTER HANDLER
// ==========================================
window.filterCEODashTable = function() {
  const query = (document.getElementById('ceo-dash-search')?.value || '').toLowerCase().trim();
  const filterVal = document.getElementById('ceo-dash-filter')?.value || 'ALL';

  const filtered = rawActiveCEORows.filter(report => {
    const prjId = `#prj-${String(report.id).padStart(4, '0')}`.toLowerCase();
    const road = String(report.cityRoadName || '').toLowerCase();
    const brgy = String(report.barangay?.barangayName || '').toLowerCase();
    const damage = String(report.damageType || '').toLowerCase();
    const status = String(report.status || '').toLowerCase();
    const hasRework = Boolean(report.adminRemarks && report.adminRemarks.trim() !== '');

    const matchesQuery = !query || prjId.includes(query) || road.includes(query) || brgy.includes(query) || damage.includes(query);

    let matchesFilter = true;
    if (filterVal === 'REWORK') {
      matchesFilter = (status === 'in progress' && hasRework);
    } else if (filterVal === 'PROGRESS') {
      matchesFilter = (status === 'in progress' && !hasRework);
    } else if (filterVal === 'DISPATCHED') {
      matchesFilter = (status === 'dispatched to ceo');
    } else if (filterVal === 'BUDGET') {
      matchesFilter = (status === 'pending budget');
    }

    return matchesQuery && matchesFilter;
  });

  renderCEOTable(filtered, 'ultimate-ceo-dash-table', true);
};


// ==========================================
// 🕒 CEO TIMELINE TOGGLE CONTROLLER
// ==========================================
window.toggleCEOTimeline = function() {
  const container = document.getElementById('ceo-manage-timeline-container');
  const arrow = document.getElementById('ceo-timeline-arrow');
  if (!container) return;

  const isHidden = container.classList.contains('hidden') || container.style.display === 'none';
  if (isHidden) {
    container.classList.remove('hidden');
    container.style.display = 'block';
    if (arrow) arrow.innerText = '▲';

    // Always scroll to top upon expanding
    container.scrollTop = 0;
    requestAnimationFrame(() => { container.scrollTop = 0; });
  } else {
    container.classList.add('hidden');
    container.style.display = 'none';
    if (arrow) arrow.innerText = '▼';
  }
};

// ==========================================
// 6. MODAL & MAP CONTROLLERS (CEO MANAGE MODAL)
// ==========================================
window.openCEOManageModal = function(reportId) {
  currentCEOProjectID = reportId;

  const dashboardView = document.getElementById('view-dashboard');
  const repairView = document.getElementById('view-repair');

  if (dashboardView && repairView && !dashboardView.classList.contains('hidden')) {
    dashboardView.classList.add('hidden');
    repairView.classList.remove('hidden');
  }

  const mapContainer = document.getElementById('ceo-manage-map-container');
  if (mapContainer) mapContainer.style.display = 'none';

  const modal = document.getElementById('manage-modal');
  if (!modal) return;

  modal.classList.remove('hidden');
  const modalBody = modal.querySelector('.modal-body');
  if (modalBody) modalBody.scrollTop = 0;

  // 🕒 1. RESET AND LOAD LIFECYCLE AUDIT TRAIL TIMELINE
  const tlContainer = document.getElementById('ceo-manage-timeline-container');
  const tlArrow = document.getElementById('ceo-timeline-arrow');
  if (tlContainer) {
    tlContainer.classList.add('hidden');
    tlContainer.style.display = 'none';
    tlContainer.scrollTop = 0;
  }
  if (tlArrow) tlArrow.innerText = '▼';

  if (typeof loadReportTimeline === 'function') {
    loadReportTimeline(reportId, 'ceo-timeline-items');
  }

  document.getElementById('ceo-modal-prj-id').innerText = `#PRJ-${String(reportId).padStart(4, '0')} (Loading...)`;

  // 🔄 INSTANT UI RESETS
  const dispatchSection = document.getElementById('ceo-dispatch-section');
  if (dispatchSection) dispatchSection.style.display = 'block';

  const deferredAlert = document.getElementById('ceo-deferred-alert');
  if (deferredAlert) deferredAlert.style.display = 'none';

  const targetDateContainer = document.getElementById('ceo-target-date-container');
  if (targetDateContainer) targetDateContainer.style.display = 'none';

  const countdownBadge = document.getElementById('ceo-target-countdown-badge');
  if (countdownBadge) countdownBadge.style.display = 'none';

  // Reset Extension Sections
  const extSection = document.getElementById('ceo-extension-section');
  if (extSection) extSection.style.display = 'none';
  const extPendingCard = document.getElementById('ceo-extension-pending-card');
  if (extPendingCard) extPendingCard.style.display = 'none';
  const extRejectedCard = document.getElementById('ceo-extension-rejected-card');
  if (extRejectedCard) extRejectedCard.style.display = 'none';
  if (typeof toggleCeoExtensionForm === 'function') {
    toggleCeoExtensionForm(false);
  }

  const btnStartRepair = document.getElementById('ceo-btn-start-repair');
  if (btnStartRepair) {
    btnStartRepair.style.display = 'inline-block';
    btnStartRepair.disabled = true;
    btnStartRepair.style.pointerEvents = "none";
    btnStartRepair.style.opacity = "0.6";
    btnStartRepair.style.cursor = "wait";
    btnStartRepair.style.backgroundColor = "#64748b";
    btnStartRepair.innerHTML = `<span class="icon">⏳</span> Loading...`;
  }

  const btnPendingBudget = document.getElementById('btn-pending-budget');
  if (btnPendingBudget) {
    btnPendingBudget.style.display = 'none';
  }

  // Reset inputs & previews
  const dmgImg = document.getElementById('ceo-modal-image');
  if (dmgImg) dmgImg.src = '';
  const proofImg = document.getElementById('ceo-modal-proof-image');
  if (proofImg) proofImg.src = '';
  const proofInput = document.getElementById('ceo-repair-image-upload');
  if (proofInput) proofInput.value = '';
  const remarksInput = document.getElementById('ceo-repair-remarks');
  if (remarksInput) remarksInput.value = '';
  const fileNameDisplay = document.getElementById('ceo-repair-file-name');
  if (fileNameDisplay) fileNameDisplay.innerText = '';
  const previewImgTag = document.getElementById('ceo-preview-img');
  if (previewImgTag) previewImgTag.src = '';
  const previewContainer = document.getElementById('ceo-dropzone-preview');
  if (previewContainer) previewContainer.style.display = 'none';
  const defaultDropzone = document.getElementById('ceo-dropzone-default');
  if (defaultDropzone) defaultDropzone.style.display = 'block';

  apiFetch(`/api/reports/${reportId}`, { cache: 'no-store' })
    .then(report => {
      // 🛡️ RACE CONDITION GUARD: Ignore superseded response if user opened another report
      if (currentCEOProjectID !== reportId) return;

      currentCEOLat = report.latitude;
      currentCEOLng = report.longitude;

      // Extract unified status once
      const status = String(report.status || '');
      const currentStatus = status.toLowerCase();

      document.getElementById('ceo-modal-prj-id').innerText = `#PRJ-${String(report.id).padStart(4, '0')}`;
      document.getElementById('ceo-modal-brgy').innerText = report.barangay ? report.barangay.barangayName : 'Unknown';
      document.getElementById('ceo-modal-road-name').innerText = report.cityRoadName || 'Unnamed Road';

      document.getElementById('ceo-modal-road-id').innerText = report.cityRoadId || 'N/A';
      document.getElementById('ceo-modal-importance').innerText = report.roadImportance || 'N/A';
      document.getElementById('ceo-modal-terrain').innerText = report.terrainType || 'N/A';
      document.getElementById('ceo-modal-road-type').innerText = report.roadType || 'N/A';
      document.getElementById('ceo-modal-length').innerText = report.length || 0;
      document.getElementById('ceo-modal-width').innerText = report.width || 0;
      document.getElementById('ceo-modal-culverts').innerText = report.lengthOfCulverts || 0;
      document.getElementById('ceo-modal-bridges').innerText = report.numberOfBridges || 0;

      document.getElementById('ceo-modal-damage-type').innerText = report.damageType || 'None';

      const damageLen = parseFloat(report.damageLength) || 0;
      const damageWid = parseFloat(report.damageWidth) || 0;
      const damageArea = damageLen * damageWid;

      document.getElementById('ceo-modal-damage-length').innerText = damageLen;
      document.getElementById('ceo-modal-damage-width').innerText = damageWid;
      document.getElementById('ceo-modal-damage-area').innerText = damageArea > 0 ? `${damageArea.toFixed(1)} sq.m` : '0 sq.m';

      document.getElementById('ceo-modal-gps').innerText = (report.latitude && report.longitude) ? `${report.latitude}°, ${report.longitude}°` : 'No GPS data';

      let ceoSubmitterText = `Barangay Official (${report.barangay?.barangayName || 'Unknown'})`;
      if (report.user && report.user.firstName && report.user.lastName) {
        ceoSubmitterText = `${report.user.firstName} ${report.user.lastName} (${report.barangay?.barangayName || 'Unknown'})`;
      } else if (report.reportedBy) {
        ceoSubmitterText = report.reportedBy;
      }
      const ceoSubmitterEl = document.getElementById('ceo-modal-submitter-name');
      if (ceoSubmitterEl) ceoSubmitterEl.innerText = ceoSubmitterText;
      document.getElementById('ceo-modal-description').innerText = report.damageDescription || 'No description provided.';

      // ========================================================
      // 🧠 SOLID AI SEVERITY & CONFIDENCE BADGE
      // ========================================================
      const rawSev = String(report.severity || '').trim().toLowerCase();
      const confScore = report.cvConfidenceScore || report.cvconfidenceScore;
      const confText = confScore ? ` (${confScore}%)` : '';
      const priorityBadge = document.getElementById('ceo-modal-priority');

      if (priorityBadge) {
        if (rawSev === 'high') {
          priorityBadge.innerText = `HIGH${confText}`;
          priorityBadge.style.cssText = 'background-color: #dc2626; color: #ffffff; border: 1px solid #b91c1c; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11px; display: inline-block; letter-spacing: 0.4px;';
        } else if (rawSev === 'medium') {
          priorityBadge.innerText = `MEDIUM${confText}`;
          priorityBadge.style.cssText = 'background-color: #f59e0b; color: #ffffff; border: 1px solid #d97706; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11px; display: inline-block; letter-spacing: 0.4px;';
        } else if (rawSev === 'low') {
          priorityBadge.innerText = `LOW${confText}`;
          priorityBadge.style.cssText = 'background-color: #16a34a; color: #ffffff; border: 1px solid #15803d; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11px; display: inline-block; letter-spacing: 0.4px;';
        } else {
          priorityBadge.innerText = 'UNASSESSED';
          priorityBadge.style.cssText = 'background-color: #ecfdf5; color: #047857; border: 1.5px solid #34d399; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11px; display: inline-block; letter-spacing: 0.4px;';
        }
      }

      // ==========================================
      // ⏱️ UPDATE EXTENSION STATUS IN CEO MODAL
      // ==========================================
      const extSectionEl = document.getElementById('ceo-extension-section');
      const extPendingCardEl = document.getElementById('ceo-extension-pending-card');
      const extRejectedCardEl = document.getElementById('ceo-extension-rejected-card');
      const extBtnContainerEl = document.getElementById('ceo-extension-btn-container');
      const extDisplayDateEl = document.getElementById('ceo-extension-display-date');
      const extDisplayReasonEl = document.getElementById('ceo-extension-display-reason');
      const extDateInputEl = document.getElementById('ceo-extension-target-date');

      // Only display extension controls if project is currently In Progress
      if (currentStatus.includes('progress')) {
        if (extSectionEl) extSectionEl.style.display = 'block';

        // Set min date to tomorrow or existing target date + 1 day
        if (extDateInputEl) {
          const baseDate = report.targetCompletionDate ? new Date(report.targetCompletionDate) : new Date();
          baseDate.setDate(baseDate.getDate() + 1);
          extDateInputEl.min = baseDate.toISOString().split('T')[0];
        }

        if (report.extensionStatus === 'PENDING') {
          if (extPendingCardEl) extPendingCardEl.style.display = 'block';
          if (extRejectedCardEl) extRejectedCardEl.style.display = 'none';
          if (extBtnContainerEl) extBtnContainerEl.style.display = 'none';
          if (extDisplayDateEl) extDisplayDateEl.textContent = report.extensionTargetDate || 'Pending';
          if (extDisplayReasonEl) extDisplayReasonEl.textContent = report.extensionReason || '-';
          toggleCeoExtensionForm(false);
        } else if (report.extensionStatus === 'REJECTED') {
          if (extPendingCardEl) extPendingCardEl.style.display = 'none';
          if (extRejectedCardEl) extRejectedCardEl.style.display = 'block';
          if (extBtnContainerEl) extBtnContainerEl.style.display = 'flex';
          toggleCeoExtensionForm(false);
        } else {
          if (extPendingCardEl) extPendingCardEl.style.display = 'none';
          if (extRejectedCardEl) extRejectedCardEl.style.display = 'none';
          if (extBtnContainerEl) extBtnContainerEl.style.display = 'flex';
          toggleCeoExtensionForm(false);
        }
      } else {
        if (extSectionEl) extSectionEl.style.display = 'none';
      }

      // ========================================================
      // 🏷️ STATUS BADGE STYLING
      // ========================================================
      const statusBadge = document.getElementById('ceo-modal-current-status');
      if (statusBadge) {
        statusBadge.innerText = status;
        if (currentStatus === 'pending budget' || currentStatus.includes('defer')) {
          statusBadge.style.cssText = "background-color: #fef3c7; color: #92400e; border: 1px solid #f59e0b; padding: 4px 8px; border-radius: 4px; font-weight: bold;";
        } else if (currentStatus === 'in progress') {
          statusBadge.style.cssText = "background-color: #cce5ff; color: #004085; padding: 4px 8px; border-radius: 4px; font-weight: bold;";
        } else if (currentStatus.includes('complet') || currentStatus.includes('repair')) {
          statusBadge.style.cssText = "background-color: #d4edda; color: #155724; padding: 4px 8px; border-radius: 4px; font-weight: bold;";
        } else {
          statusBadge.style.cssText = "background-color: #e2e8f0; color: #334155; padding: 4px 8px; border-radius: 4px; font-weight: bold;";
        }
      }

      // ========================================================
      // ⚠️ REWORK ALERT CHECK
      // ========================================================
      const reworkAlert = document.getElementById('ceo-rework-alert');
      const reworkText = document.getElementById('ceo-modal-admin-remarks');
      const isRework = currentStatus === 'in progress' && report.adminRemarks && report.adminRemarks.trim() !== '';

      if (isRework) {
        if (reworkText) reworkText.innerText = report.adminRemarks;
        if (reworkAlert) reworkAlert.style.display = 'block';
      } else {
        if (reworkAlert) reworkAlert.style.display = 'none';
      }

      // Image Loading
      const placeholderEl = document.getElementById('ceo-modal-image-placeholder-text');
      if (placeholderEl) placeholderEl.style.display = 'none';
      if (typeof loadSecureImage === 'function') {
        loadSecureImage('ceo-modal-image', report.damageImage);
      }

      // ========================================================
      // 🎯 BUTTONS, DEFER & RESOLUTION LOGIC
      // ========================================================
      const completionForm = document.getElementById('ceo-completion-form');
      const completedEvidence = document.getElementById('ceo-completed-evidence-section');
      const proofRemarks = document.getElementById('ceo-modal-proof-remarks');
      const evidenceTitle = completedEvidence ? completedEvidence.querySelector('.section-title') : null;
      const targetDateInput = document.getElementById('ceo-target-date-input');
      const targetDateHint = document.getElementById('ceo-target-date-hint');

      // Countdown Helper for In Progress projects
      const renderCountdown = () => {
        if (!countdownBadge) return;
        if (!report.targetCompletionDate) {
          countdownBadge.style.display = 'none';
          return;
        }

        countdownBadge.style.display = 'inline-block';
        const target = new Date(report.targetCompletionDate + 'T23:59:59');
        const now = new Date();
        const diffMs = target - now;
        const diffDays = Math.ceil(diffMs / (1000 * 60 * 60 * 24));

        if (diffDays > 1) {
          countdownBadge.innerText = `⏳ Target: ${report.targetCompletionDate} (${diffDays} days left)`;
          countdownBadge.style.cssText = "background: #e0f2fe; color: #0369a1; border: 1px solid #7dd3fc; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 700; display: inline-block;";
        } else if (diffDays === 1) {
          countdownBadge.innerText = `⏳ Target: Due Tomorrow (${report.targetCompletionDate})`;
          countdownBadge.style.cssText = "background: #fef3c7; color: #b45309; border: 1px solid #fde047; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 700; display: inline-block;";
        } else if (diffDays === 0) {
          countdownBadge.innerText = `⚠️ Target: Due Today (${report.targetCompletionDate})`;
          countdownBadge.style.cssText = "background: #fef3c7; color: #b45309; border: 1px solid #fde047; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 700; display: inline-block;";
        } else {
          const overdueDays = Math.abs(diffDays);
          countdownBadge.innerText = `🚨 Overdue by ${overdueDays} day${overdueDays > 1 ? 's' : ''} (${report.targetCompletionDate})`;
          countdownBadge.style.cssText = "background: #fee2e2; color: #b91c1c; border: 1px solid #fca5a5; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 700; display: inline-block;";
        }
      };

      if (currentStatus === 'pending budget' || currentStatus.includes('defer')) {
        // STATE 1: PENDING BUDGET / DEFERRED (DISPATCH CREW COMPLETELY REMOVED)
        if (dispatchSection) dispatchSection.style.display = 'none';

        if (deferredAlert) {
          deferredAlert.style.display = 'block';
          const remarksEl = document.getElementById('ceo-deferred-remarks');
          if (remarksEl) {
            remarksEl.innerText = report.adminRemarks || report.repairRemarks || 'Project deferred pending budget allocation.';
          }
        }

        if (completionForm) completionForm.style.display = 'none';
        if (completedEvidence) completedEvidence.style.display = 'none';

      } else if (currentStatus.includes('complet') || currentStatus.includes('repair')) {
        // STATE 2: COMPLETED (DISPATCH CREW REMOVED & PROOF RENDERED)
        if (dispatchSection) dispatchSection.style.display = 'none';
        if (deferredAlert) deferredAlert.style.display = 'none';

        if (completionForm) completionForm.style.display = 'none';
        if (completedEvidence) {
          completedEvidence.style.display = 'block';
          completedEvidence.style.backgroundColor = '#f0fdf4';
          completedEvidence.style.borderColor = '#bbf7d0';
        }
        if (evidenceTitle) {
          evidenceTitle.innerHTML = `<span class="icon">✅</span> Repair Completed`;
          evidenceTitle.style.color = '#16a34a';
        }

        if (typeof loadSecureImage === 'function') {
          loadSecureImage('ceo-modal-proof-image', report.proofOfRepairImage);
        }
        if (proofRemarks) proofRemarks.innerText = report.repairRemarks || "No official remarks provided.";

      } else if (isRework) {
        // STATE 3: REWORK REQUIRED (HISTORICAL PROOF + NEW UPLOAD FORM)
        if (dispatchSection) dispatchSection.style.display = 'block';
        if (deferredAlert) deferredAlert.style.display = 'none';
        if (targetDateContainer) targetDateContainer.style.display = 'none';
        renderCountdown();

        if (btnStartRepair) {
          btnStartRepair.style.display = 'inline-block';
          btnStartRepair.innerHTML = `<span class="icon">⚠️</span> Rework Underway`;
          btnStartRepair.style.backgroundColor = "#dc2626";
          btnStartRepair.style.cursor = "not-allowed";
          btnStartRepair.style.pointerEvents = "none";
          btnStartRepair.style.opacity = "0.85";
          btnStartRepair.disabled = true;
        }
        if (btnPendingBudget) btnPendingBudget.style.display = 'none';

        // 📸 Display previous rejected CEO submission
        if (completedEvidence) {
          completedEvidence.style.display = 'block';
          completedEvidence.style.backgroundColor = '#fffbeb';
          completedEvidence.style.borderColor = '#fef3c7';
        }
        if (evidenceTitle) {
          evidenceTitle.innerHTML = `<span class="icon">🔍</span> Previously Rejected Proof & Remarks`;
          evidenceTitle.style.color = '#b45309';
        }
        if (typeof loadSecureImage === 'function') {
          loadSecureImage('ceo-modal-proof-image', report.proofOfRepairImage);
        }
        if (proofRemarks) proofRemarks.innerText = report.repairRemarks || "No previous remarks provided.";

        // New upload dropzone
        if (completionForm) completionForm.style.display = 'block';

      } else if (currentStatus.includes('progress')) {
        // STATE 4: STANDARD IN PROGRESS
        if (dispatchSection) dispatchSection.style.display = 'block';
        if (deferredAlert) deferredAlert.style.display = 'none';
        if (targetDateContainer) targetDateContainer.style.display = 'none';
        renderCountdown();

        if (btnStartRepair) {
          btnStartRepair.style.display = 'inline-block';
          btnStartRepair.innerHTML = `<span class="icon">⚡</span> Repairs Underway`;
          btnStartRepair.style.backgroundColor = "#64748b";
          btnStartRepair.style.cursor = "not-allowed";
          btnStartRepair.style.pointerEvents = "none";
          btnStartRepair.style.opacity = "0.75";
          btnStartRepair.disabled = true;
        }
        if (btnPendingBudget) btnPendingBudget.style.display = 'none';

        if (completionForm) completionForm.style.display = 'block';
        if (completedEvidence) completedEvidence.style.display = 'none';

      } else {
        // STATE 5: DISPATCHED / READY TO QUEUE (SMART TIMELINE CALCULATOR ACTIVE)
        if (dispatchSection) dispatchSection.style.display = 'block';
        if (deferredAlert) deferredAlert.style.display = 'none';
        if (countdownBadge) countdownBadge.style.display = 'none';

        if (btnStartRepair) {
          btnStartRepair.style.display = 'inline-block';
          btnStartRepair.innerHTML = `<span class="icon">👷</span> Mark as "In Progress"`;
          btnStartRepair.style.backgroundColor = "#ea580c";
          btnStartRepair.style.cursor = "pointer";
          btnStartRepair.style.pointerEvents = "auto";
          btnStartRepair.style.opacity = "1";
          btnStartRepair.disabled = false;
        }
        if (btnPendingBudget) {
          btnPendingBudget.style.display = 'inline-block';
          btnPendingBudget.disabled = false;
        }

        // 🧠 Auto-Compute Estimated Target Completion Date
        const est = calculateEstimatedDays(report.damageType, report.severity, damageArea);
        const estDate = new Date();
        estDate.setDate(estDate.getDate() + est.days);
        const dateStr = estDate.toISOString().split('T')[0];
        const todayStr = new Date().toISOString().split('T')[0];

        if (targetDateContainer) targetDateContainer.style.display = 'block';
        if (targetDateInput) {
          targetDateInput.min = todayStr;
          targetDateInput.value = dateStr;
        }
        if (targetDateHint) {
          targetDateHint.innerText = `💡 Suggested: ${est.label}`;
        }

        if (completionForm) completionForm.style.display = 'none';
        if (completedEvidence) completedEvidence.style.display = 'none';
      }
    })
    .catch(err => {
      console.error("Error populating CEO modal:", err);
      document.getElementById('ceo-modal-prj-id').innerText = "Database Error!";
    });
};

// ==========================================
// ⏱️ CEO: TOGGLE EXTENSION FORM
// ==========================================
window.toggleCeoExtensionForm = function(show) {
  const form = document.getElementById('ceo-extension-form');
  const btnContainer = document.getElementById('ceo-extension-btn-container');
  const dateInput = document.getElementById('ceo-extension-target-date');
  const reasonInput = document.getElementById('ceo-extension-reason-input');

  if (show) {
    if (form) form.classList.remove('hidden');
    if (btnContainer) btnContainer.style.display = 'none';
    if (dateInput) dateInput.focus();
  } else {
    if (form) form.classList.add('hidden');
    if (btnContainer) btnContainer.style.display = 'flex';
    if (dateInput) dateInput.value = '';
    if (reasonInput) reasonInput.value = '';
  }
};

// ==========================================
// ⏱️ CEO: SUBMIT EXTENSION REQUEST
// ==========================================
window.submitCeoExtensionRequest = function() {
  if (!currentCEOProjectID) {
    if (typeof showToast === 'function') showToast("No active report selected.", "error");
    return;
  }

  const dateInput = document.getElementById('ceo-extension-target-date');
  const reasonInput = document.getElementById('ceo-extension-reason-input');
  const btnSubmit = document.getElementById('btn-submit-extension');

  const extensionTargetDate = dateInput ? dateInput.value : '';
  const extensionReason = reasonInput ? reasonInput.value.trim() : '';

  if (!extensionTargetDate) {
    if (typeof showToast === 'function') showToast("Please select a proposed new target date.", "error");
    return;
  }

  if (!extensionReason) {
    if (typeof showToast === 'function') showToast("Please provide a justification for the extension request.", "error");
    return;
  }

  const originalText = btnSubmit ? btnSubmit.innerHTML : "Submit to CPDO";
  if (btnSubmit) {
    btnSubmit.innerHTML = "⏳ Submitting...";
    btnSubmit.disabled = true;
  }

  const currentUserId = sessionStorage.getItem("userId");

  fetch(`${API_BASE_URL}/api/reports/${currentCEOProjectID}/request-extension`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'ngrok-skip-browser-warning': 'true'
    },
    body: JSON.stringify({
      extensionTargetDate: extensionTargetDate,
      extensionReason: extensionReason,
      userId: currentUserId
    })
  })
    .then(async res => {
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Failed to submit extension request.");
      return data;
    })
    .then(data => {
      if (typeof showToast === 'function') {
        showToast(data.message || "Extension request submitted to CPDO!", "success");
      }
      toggleCeoExtensionForm(false);
      openCEOManageModal(currentCEOProjectID);
    })
    .catch(err => {
      console.error(err);
      if (typeof showToast === 'function') {
        showToast(err.message || "Error submitting extension request.", "error");
      }
    })
    .finally(() => {
      if (btnSubmit) {
        btnSubmit.innerHTML = originalText;
        btnSubmit.disabled = false;
      }
    });
};

window.jumpToCEOMasterlistAndManage = function(reportId) {
  const repairTabBtn = document.querySelector('.nav-menu li[data-target="view-repair"]');
  if (repairTabBtn) {
    repairTabBtn.click();
  } else {
    document.getElementById('view-dashboard').classList.add('hidden');
    document.getElementById('view-repair').classList.remove('hidden');
  }

  setTimeout(() => {
    if (typeof openCEOManageModal === 'function') {
      openCEOManageModal(reportId);
    }
  }, 150);
};

// ==========================================
// 7. FILE UPLOAD & LEAFLET MAP OBSERVERS
// ==========================================
document.addEventListener('DOMContentLoaded', () => {
  // Initial load
  if (document.getElementById('ultimate-ceo-dash-table')) {
    loadCEODashboardData();
  }

  // 1. Drag & Drop File Upload Logic
  const dropzone = document.getElementById('ceo-dropzone-container');
  const fileInput = document.getElementById('ceo-repair-image-upload');
  const defaultState = document.getElementById('ceo-dropzone-default');
  const previewState = document.getElementById('ceo-dropzone-preview');
  const previewImg = document.getElementById('ceo-preview-img');
  const removeBtn = document.getElementById('ceo-btn-remove-image');
  const fileNameDisplay = document.getElementById('ceo-repair-file-name');

  if (dropzone && fileInput) {
    dropzone.addEventListener('click', (e) => {
      if (e.target !== removeBtn) fileInput.click();
    });

    dropzone.addEventListener('dragover', (e) => {
      e.preventDefault();
      dropzone.style.borderColor = '#0d6efd';
      dropzone.style.backgroundColor = '#e0f2fe';
    });

    dropzone.addEventListener('dragleave', (e) => {
      e.preventDefault();
      dropzone.style.borderColor = '#cbd5e1';
      dropzone.style.backgroundColor = '#f8fafc';
    });

    dropzone.addEventListener('drop', (e) => {
      e.preventDefault();
      dropzone.style.borderColor = '#cbd5e1';
      dropzone.style.backgroundColor = '#f8fafc';
      if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
        fileInput.files = e.dataTransfer.files;
        handleFileUpload(e.dataTransfer.files[0]);
      }
    });

    fileInput.addEventListener('change', function() {
      if (this.files && this.files.length > 0) {
        handleFileUpload(this.files[0]);
      }
    });

    function handleFileUpload(file) {
      if (!file.type.startsWith('image/')) {
        if (typeof showToast === 'function') showToast("Please upload a valid image file (JPG, PNG).", "error");
        fileInput.value = '';
        return;
      }
      if (file.size > 5 * 1024 * 1024) {
        if (typeof showToast === 'function') showToast("File is too large! Must be under 5MB.", "error");
        fileInput.value = '';
        return;
      }

      const reader = new FileReader();
      reader.onload = (e) => {
        if (previewImg) previewImg.src = e.target.result;
        if (defaultState) defaultState.style.display = 'none';
        if (previewState) previewState.style.display = 'block';
        if (fileNameDisplay) fileNameDisplay.innerText = file.name;
      };
      reader.readAsDataURL(file);
    }

    if (removeBtn) {
      removeBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        fileInput.value = '';
        if (previewImg) previewImg.src = '';
        if (previewState) previewState.style.display = 'none';
        if (defaultState) defaultState.style.display = 'block';
      });
    }
  }

  // ==========================================
// 🗺️ CEO MANAGE MODAL: LOCATE ON MAP (WITH STREETS & BOUNDARIES)
// ==========================================

// Safe global scoping to prevent duplicate declaration syntax errors
  window.ceoManageMap = window.ceoManageMap || null;
  window.ceoManageMarker = window.ceoManageMarker || null;
  window.ceoManageBoundaryLayer = window.ceoManageBoundaryLayer || null;
  window.ceoManageBounds = window.ceoManageBounds || null;
  window.ceoManageGeoJsonData = window.ceoManageGeoJsonData || null;

// Re-use or pre-fetch official CSJDM boundaries
  function ensureManageMapGeoJsonLoaded() {
    if (typeof sjdmGeoJsonData !== 'undefined' && sjdmGeoJsonData) {
      return Promise.resolve(sjdmGeoJsonData);
    }
    if (window.ceoGeoJsonData) {
      return Promise.resolve(window.ceoGeoJsonData);
    }
    if (window.ceoManageGeoJsonData) {
      return Promise.resolve(window.ceoManageGeoJsonData);
    }
    return fetch('sjdm_barangays.geojson')
      .then(res => res.json())
      .then(data => {
        window.ceoManageGeoJsonData = data;
        return data;
      })
      .catch(err => console.error("Could not load sjdm_barangays.geojson for CEO modal:", err));
  }

// 2. Leaflet Map Locator Event Handler
  const btnLocateMap = document.getElementById('ceo-btn-locate-map');
  if (btnLocateMap) {
    btnLocateMap.addEventListener('click', function(e) {
      e.preventDefault();
      const mapContainer = document.getElementById('ceo-manage-map-container');
      if (!mapContainer) return;

      if (!currentCEOLat || !currentCEOLng || (currentCEOLat === 0 && currentCEOLng === 0)) {
        showToast("No GPS coordinates were provided for this report.", "warning");
        return;
      }

      if (mapContainer.style.display === 'none' || mapContainer.style.display === '') {
        mapContainer.style.display = 'block';

        if (typeof L === 'undefined') {
          console.error("Leaflet library (L) is not loaded.");
          return;
        }

        const redIcon = new L.Icon({
          iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-red.png',
          shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
          iconSize: [25, 41],
          iconAnchor: [12, 41],
          popupAnchor: [1, -34],
          shadowSize: [41, 41]
        });

        // 1. Initialize Map with bounded zoom constraints
        if (!window.ceoManageMap) {
          window.ceoManageMap = L.map('ceo-manage-map', {
            minZoom: 12,             // Stops zooming out past CSJDM scale
            maxZoom: 19,             // Stops over-zooming past satellite resolution
            maxBoundsViscosity: 0.7  // Elastic boundary buffer prevents camera locks
          });

          // 🛰️ GOOGLE HYBRID SATELLITE (Subdivisions, road lines, street names, & landmarks)
          L.tileLayer('https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
            maxZoom: 19,
            subdomains: ['mt0', 'mt1', 'mt2', 'mt3'],
            attribution: '&copy; Google Maps'
          }).addTo(window.ceoManageMap);

          // 2. Render CSJDM Barangay Outlines
          ensureManageMapGeoJsonLoaded().then(geoJson => {
            if (geoJson && window.ceoManageMap) {
              window.ceoManageBoundaryLayer = L.geoJSON(geoJson, {
                style: {
                  color: '#0284c7',       // Engineering Sky-Blue border
                  weight: 2,
                  dashArray: '5, 5',
                  fillColor: '#0369a1',
                  fillOpacity: 0.05,
                  interactive: false
                }
              }).addTo(window.ceoManageMap);

              window.ceoManageBounds = window.ceoManageBoundaryLayer.getBounds();
              window.ceoManageMap.setMaxBounds(window.ceoManageBounds.pad(0.40));
            }
          });

          // 3. Drop & bind Project Pin
          window.ceoManageMarker = L.marker([currentCEOLat, currentCEOLng], { icon: redIcon })
            .addTo(window.ceoManageMap)
            .bindPopup(`<b>Project Location</b><br>Lat: ${currentCEOLat.toFixed(5)}<br>Lng: ${currentCEOLng.toFixed(5)}`);

          window.ceoManageMap.setView([currentCEOLat, currentCEOLng], 17);

        } else {
          // Reuse existing map instance
          window.ceoManageMap.setView([currentCEOLat, currentCEOLng], 17);

          if (window.ceoManageMarker) {
            window.ceoManageMarker.setLatLng([currentCEOLat, currentCEOLng]);
            window.ceoManageMarker.setIcon(redIcon);
            window.ceoManageMarker.setPopupContent(`<b>Project Location</b><br>Lat: ${currentCEOLat.toFixed(5)}<br>Lng: ${currentCEOLng.toFixed(5)}`);
          } else {
            window.ceoManageMarker = L.marker([currentCEOLat, currentCEOLng], { icon: redIcon })
              .addTo(window.ceoManageMap);
          }

          // Failsafe: apply boundaries if GeoJSON finished loading after the first toggle
          if (!window.ceoManageBoundaryLayer) {
            ensureManageMapGeoJsonLoaded().then(geoJson => {
              if (geoJson && window.ceoManageMap) {
                window.ceoManageBoundaryLayer = L.geoJSON(geoJson, {
                  style: {
                    color: '#0284c7',
                    weight: 2,
                    dashArray: '5, 5',
                    fillColor: '#0369a1',
                    fillOpacity: 0.05,
                    interactive: false
                  }
                }).addTo(window.ceoManageMap);

                window.ceoManageBounds = window.ceoManageBoundaryLayer.getBounds();
                window.ceoManageMap.setMaxBounds(window.ceoManageBounds.pad(0.40));
              }
            });
          }
        }

        setTimeout(() => {
          if (window.ceoManageMap) {
            window.ceoManageMap.invalidateSize();
          }
        }, 200);

      } else {
        mapContainer.style.display = 'none';
      }
    });
  }

  // 3. Mark as In Progress Action (With Target Date Logging)
  const btnStartRepair = document.getElementById('ceo-btn-start-repair');
  if (btnStartRepair) {
    btnStartRepair.addEventListener('click', function() {
      if (!currentCEOProjectID || this.disabled || this.style.pointerEvents === 'none') return;

      const targetInput = document.getElementById('ceo-target-date-input');
      const targetCompletionDate = targetInput && targetInput.value ? targetInput.value : null;

      const originalText = this.innerHTML;
      this.innerHTML = `<span class="icon">⏳</span> Updating...`;
      this.disabled = true;
      this.style.pointerEvents = "none";
      this.style.opacity = "0.7";

      const currentUserId = sessionStorage.getItem("userId");

      fetch(`${API_BASE_URL}/api/reports/${currentCEOProjectID}/status`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'ngrok-skip-browser-warning': 'true'
        },
        body: JSON.stringify({
          status: "In Progress",
          targetCompletionDate: targetCompletionDate,
          userId: currentUserId
        })
      })
        .then(res => {
          if (!res.ok) throw new Error("Failed to update status");
          if (typeof showToast === 'function') {
            showToast("Crew Dispatched! Target completion date logged.", "success");
          }

          // Reload modal directly to reflect new in-progress state and countdown
          openCEOManageModal(currentCEOProjectID);

          if (typeof loadCEODashboardData === "function") {
            loadCEODashboardData();
          }
        })
        .catch(err => {
          console.error("Status Update Error:", err);
          if (typeof showToast === 'function') showToast("Failed to update. Check database connection.", "error");

          this.innerHTML = originalText;
          this.disabled = false;
          this.style.pointerEvents = "auto";
          this.style.opacity = "1";
        });
    });
  }

  // 4. Modal Rollback & Dismissal Listeners
  const batchDeferModal = document.getElementById('batch-defer-modal');
  if (batchDeferModal) {
    const cancelBtns = batchDeferModal.querySelectorAll('.close-modal-btn, .btn-secondary, button[type="button"]:not(#btn-confirm-batch-defer)');
    cancelBtns.forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.preventDefault();
        window.closeBatchDeferModal();
      });
    });
  }
});

// ==========================================
// 🏗️ CEO ACTION: UNIFIED DEFER SYSTEM
// ==========================================

// Global variable to track if we are deferring a single project from the Review modal
window.deferringSingleId = null;

// 🔄 HELPER: Safely close defer modal and restore manage modal if needed
window.closeBatchDeferModal = function() {
  const modal = document.getElementById('batch-defer-modal');
  if (modal) modal.classList.add('hidden');

  if (window.deferringSingleId) {
    const manageModal = document.getElementById('manage-modal');
    if (manageModal) manageModal.classList.remove('hidden');
    window.deferringSingleId = null;
  }
};

// 1. OPEN FROM INDIVIDUAL REVIEW MODAL (The Single Button)
window.markAsPendingBudget = function() {
  if (typeof currentCEOProjectID === 'undefined' || !currentCEOProjectID) {
    if (typeof showToast === 'function') showToast("Error: Could not identify the report.", "error");
    return;
  }

  window.deferringSingleId = currentCEOProjectID;
  document.getElementById('manage-modal').classList.add('hidden');

  const titleEl = document.getElementById('defer-modal-title');
  const warningEl = document.getElementById('defer-modal-warning');
  const confirmBtn = document.getElementById('btn-confirm-batch-defer');

  if (titleEl) titleEl.innerText = `Defer Project #PRJ-${String(currentCEOProjectID).padStart(4, '0')}`;
  if (warningEl) warningEl.innerHTML = `You are about to defer this specific repair project to <span class="badge" style="background: #f59e0b; color: white; padding: 2px 6px; border-radius: 4px;">Pending Budget</span>.`;
  if (confirmBtn) confirmBtn.innerText = "Confirm Deferral";

  const reasonField = document.getElementById('batch-defer-reason');
  if (reasonField) reasonField.value = '';
  document.getElementById('batch-defer-modal').classList.remove('hidden');
};

// 2. OPEN FROM BATCH ACTION BAR (The Checkboxes)
window.openBatchDeferModal = function() {
  window.deferringSingleId = null;

  const titleEl = document.getElementById('defer-modal-title');
  const warningEl = document.getElementById('defer-modal-warning');
  const confirmBtn = document.getElementById('btn-confirm-batch-defer');

  if (titleEl) titleEl.innerText = "Batch Defer Projects";
  if (warningEl) warningEl.innerHTML = `This action will instantly sweep up and defer <strong>ALL</strong> selected reports in your queue to <span class="badge" style="background: #f59e0b; color: white; padding: 2px 6px; border-radius: 4px;">Pending Budget</span>.`;
  if (confirmBtn) confirmBtn.innerText = "Confirm Batch Deferral";

  const reasonField = document.getElementById('batch-defer-reason');
  if (reasonField) reasonField.value = '';
  document.getElementById('batch-defer-modal').classList.remove('hidden');
};

// 3. VALIDATE REASON & SHOW CONFIRMATION WARNING
window.submitBatchDefer = function() {
  const reasonInput = document.getElementById('batch-defer-reason');
  const reason = reasonInput ? reasonInput.value.trim() : '';

  if (!reason) {
    if (typeof showToast === 'function') showToast("Please provide a reason for the deferral.", "error");
    if (reasonInput) {
      reasonInput.style.borderColor = "red";
      setTimeout(() => reasonInput.style.borderColor = "#cbd5e1", 2000);
      reasonInput.focus();
    }
    return;
  }

  const confirmText = document.getElementById('confirm-modal-text');
  const confirmBtn = document.getElementById('btn-final-confirm');

  let count = 0;

  if (window.deferringSingleId) {
    count = 1;
    if (confirmText) confirmText.innerHTML = `You are about to defer <strong style="color: #dc3545; font-size: 16px;">#PRJ-${String(window.deferringSingleId).padStart(4, '0')}</strong>. <br>This will immediately notify the CPDO and the Barangay Officials.`;
    if (confirmBtn) confirmBtn.innerText = "Yes, Defer Project";
  } else {
    const checkedBoxes = document.querySelectorAll('.defer-checkbox:checked');
    if (checkedBoxes.length === 0) {
      if (typeof showToast === 'function') showToast("No reports selected.", "error");
      return;
    }
    count = checkedBoxes.length;
    if (confirmText) confirmText.innerHTML = `You are about to defer <strong style="color: #dc3545; font-size: 16px;">${count}</strong> selected reports. <br>This will immediately notify the CPDO and the Barangay Officials.`;
    if (confirmBtn) confirmBtn.innerText = "Yes, Defer Projects";
  }

  document.getElementById('confirm-action-modal').classList.remove('hidden');
};

// 4. EXECUTE THE API CALL
window.executeBatchDeferral = function() {
  const reasonInput = document.getElementById('batch-defer-reason');
  const reason = reasonInput ? reasonInput.value.trim() : '';
  let selectedIds = [];

  if (window.deferringSingleId) {
    selectedIds.push(window.deferringSingleId);
  } else {
    const checkedBoxes = document.querySelectorAll('.defer-checkbox:checked');
    selectedIds = Array.from(checkedBoxes).map(cb => parseInt(cb.value));
  }

  const btn = document.getElementById('btn-final-confirm');
  const originalText = btn ? btn.innerText : 'Yes, Confirm';
  if (btn) {
    btn.innerText = "Processing...";
    btn.disabled = true;
  }

  fetch(`${API_BASE_URL}/api/reports/batch/defer`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      repairRemarks: reason,
      reportIds: selectedIds
    })
  })
    .then(res => res.json())
    .then(data => {
      if (data.error) {
        if (typeof showToast === 'function') showToast(data.error, "error");
      } else {
        if (typeof showToast === 'function') showToast(data.message, "success");

        document.getElementById('confirm-action-modal').classList.add('hidden');
        document.getElementById('batch-defer-modal').classList.add('hidden');

        const actionBar = document.getElementById('batch-action-bar');
        if (actionBar) actionBar.style.display = 'none';

        const selectAllCb = document.getElementById('select-all-checkbox');
        if (selectAllCb) selectAllCb.checked = false;

        window.deferringSingleId = null;

        if (typeof loadCEODashboardData === "function") loadCEODashboardData();
      }
    })
    .catch(err => {
      console.error("Defer Error:", err);
      if (typeof showToast === 'function') showToast("A network error occurred.", "error");
    })
    .finally(() => {
      if (btn) {
        btn.innerText = originalText;
        btn.disabled = false;
      }
    });
};

// ==========================================
// 🏗️ UI LISTENER: TOGGLE ACTION BAR ON CHECK
// ==========================================
window.toggleBatchActionBar = function() {
  const checkedBoxes = document.querySelectorAll('.defer-checkbox:checked');
  const actionBar = document.getElementById('batch-action-bar');
  const countText = document.getElementById('selected-count');

  if (checkedBoxes.length > 0) {
    if (actionBar) actionBar.style.display = 'flex';
    if (countText) countText.innerText = checkedBoxes.length;
  } else {
    if (actionBar) actionBar.style.display = 'none';
  }
};

window.toggleAllCheckboxes = function(masterCheckbox) {
  const checkboxes = document.querySelectorAll('.defer-checkbox');
  checkboxes.forEach(cb => cb.checked = masterCheckbox.checked);
  toggleBatchActionBar();
};

// ==========================================
// CEO: BULLETPROOF SUBMIT REPAIR
// ==========================================
window.submitCEOCompletion = function() {
  if (!currentCEOProjectID) return;

  const btnCompleteRepair = document.getElementById('ceo-btn-complete-repair');
  const imageInput = document.getElementById('ceo-repair-image-upload');
  const remarksInput = document.getElementById('ceo-repair-remarks');
  const dropzoneContainer = document.getElementById('ceo-dropzone-container');

  // Validation: Image is REQUIRED
  if (!imageInput.files || imageInput.files.length === 0) {
    showToast("Please upload a Proof of Repair photo!", "error");
    if (dropzoneContainer) {
      dropzoneContainer.style.borderColor = "red";
      setTimeout(() => dropzoneContainer.style.borderColor = "#cbd5e1", 2000);
    }
    return;
  }

  // UI Loading State
  const originalText = btnCompleteRepair.innerHTML;
  btnCompleteRepair.innerHTML = `<span class="icon">⏳</span> Uploading Proof...`;
  btnCompleteRepair.disabled = true;

  // Build the Form Data
  const formData = new FormData();
  formData.append("proofImage", imageInput.files[0]);
  formData.append("repairRemarks", remarksInput.value || "");

  // Send to Backend
  fetch(`${API_BASE_URL}/api/reports/${currentCEOProjectID}/complete`, {
    method: 'POST',
    body: formData
  })
    .then(res => {
      if (!res.ok) throw new Error("Upload failed");
      return res.json();
    })
    .then(data => {
      showToast("Project marked as Completed!", "success");

      // Close modal and refresh data
      document.getElementById('manage-modal').classList.add('hidden');
      if (typeof loadCEODashboardData === "function") {
        loadCEODashboardData();
      }
    })
    .catch(err => {
      console.error("Completion Error:", err);
      showToast("Failed to complete. Check console.", "error");
    })
    .finally(() => {
      // Restore button state
      btnCompleteRepair.innerHTML = originalText;
      btnCompleteRepair.disabled = false;
    });
};
