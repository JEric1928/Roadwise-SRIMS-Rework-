/**
 * Barangay dashboard engine: KPI metrics, progress bar, severity chart, report
 * cards, view/edit modals, and the shared report lifecycle timeline renderer.
 *
 * Extracted verbatim from app.js. The DOMContentLoaded boot at the end of this
 * file self-registers the security check, initial load, edit-image preview and
 * the tab observer, matching the original registration.
 *
 * Compatibility bridges installed on window:
 * window.filterBarangayReports, window.toggleViewTimeline, window.toggleEditTimeline,
 * window.openViewModal, window.openEditModal, window.closeBdModals,
 * window.submitEditedReport, window.loadBarangayReports, window.loadReportTimeline.
 *
 * Shared map state: renderViewModalMap reads the leaflet icon through
 * window.redIcon, which app.js publishes once it builds the icon inside the
 * main DOMContentLoaded listener.
 */
import { apiFetch, API_BASE_URL } from '../../core/api.js';
import { escapeHtml, resetModalScroll } from '../../core/utils.js';
import { formatPST } from '../../core/format-pst.js';
import { showToast } from '../../components/toast.js';

// ==========================================
// 🚀 BARANGAY DASHBOARD: REAL DATA ENGINE & RENDERER
// ==========================================
let rawBarangayReports = [];
let severityChartInstance = null;

// 🧠 1. Progress Bar Logic (Cycle-Year Scoped)
function calculateJurisdictionProgress(barangayId, activeReports) {
  const currentCycleYear = new Date().getFullYear();

  // 🚀 THE FIX: Only count roads that have inspections in the CURRENT annual cycle
  const currentCycleReports = activeReports.filter(r => {
    const reportYear = r.inventoryYear ||
      (r.dateSubmitted ? new Date(r.dateSubmitted).getFullYear() :
        (r.date_submitted ? new Date(r.date_submitted).getFullYear() : null));

    return Number(reportYear) === currentCycleYear;
  });

  const inspectedRoadNames = new Set(
    currentCycleReports
      .map(r => r.cityRoadName)
      .filter(name => name && String(name).trim() !== '')
  );
  const inspectedCount = inspectedRoadNames.size;

  if (typeof apiFetch === 'function') {
    apiFetch(`/api/roads`)
      .then(allRoads => {
        if (!Array.isArray(allRoads)) throw new Error("Invalid roads array");
        const barangayRoads = allRoads.filter(road => road.barangay && String(road.barangay.id) === String(barangayId));
        const totalRoads = barangayRoads.length;
        const displayTotal = totalRoads > 0 ? totalRoads : Math.max(inspectedCount, 1);
        updateProgressBarUI(inspectedCount, displayTotal);
      })
      .catch(() => {
        const displayTotal = Math.max(inspectedCount, 1);
        updateProgressBarUI(inspectedCount, displayTotal);
      });
  } else {
    const displayTotal = Math.max(inspectedCount, 1);
    updateProgressBarUI(inspectedCount, displayTotal);
  }
}
function updateProgressBarUI(inspectedCount, displayTotal) {
  let percentage = Math.round((inspectedCount / displayTotal) * 100);
  if (percentage > 100) percentage = 100;

  const progressText = document.getElementById('progress-text');
  const progressPercent = document.getElementById('progress-percentage');
  const barFill = document.getElementById('progress-bar-fill');

  if (progressText && progressPercent && barFill) {
    progressText.innerHTML = `<strong>${inspectedCount}</strong> out of <strong>${displayTotal}</strong> assigned roads inspected.`;
    progressPercent.innerText = `${percentage}%`;
    barFill.style.width = `${percentage}%`;

    if (percentage === 100) {
      barFill.style.background = 'linear-gradient(90deg, #16a34a, #22c55e)';
      progressPercent.style.color = '#16a34a';
    } else {
      barFill.style.background = 'linear-gradient(90deg, #16a34a, #34d399)';
      progressPercent.style.color = '#16a34a';
    }
  }
}

// 📋 2. Main Report Loader
export function loadBarangayReports(barangayId) {
  const listContainer = document.getElementById('barangay-report-list');
  if (!listContainer) return;

  listContainer.innerHTML = `
    <div style="text-align: center; padding: 40px; color: #64748b;">
      <div style="font-size: 24px; margin-bottom: 8px;">⏳</div>
      Loading barangay inspection records...
    </div>
  `;

  apiFetch(`/api/reports/barangay/${barangayId}`)
    .then(reports => {
      const allReports = Array.isArray(reports) ? reports : [];

      // =========================================================================
      // 🚀 FILTER OUT ARCHIVED REPORTS (Only shows current annual cycle)
      // =========================================================================
      rawBarangayReports = allReports.filter(r => {
        const s = String(r.status || '').toLowerCase();
        return !s.includes('archiv');
      });

      // Recalculate progress bar using ONLY active cycle reports
      calculateJurisdictionProgress(barangayId, rawBarangayReports);

      // If all reports are archived or none exist, cleanly reset all UI metrics
      if (rawBarangayReports.length === 0) {
        listContainer.innerHTML = `
          <div style="text-align: center; padding: 40px; background: #ffffff; border-radius: 10px; border: 1px solid #e2e8f0; color: #64748b;">
            <div style="font-size: 32px; margin-bottom: 8px;">📋</div>
            <strong>No active road reports for this cycle.</strong>
            <p style="font-size: 13px; margin: 4px 0 0 0;">The annual cycle has been rolled over. Click "New Report" to begin surveys for the new cycle.</p>
          </div>
        `;
        document.getElementById('metric-total').innerText = '0';
        document.getElementById('metric-pending').innerText = '0';
        document.getElementById('metric-validated').innerText = '0';
        document.getElementById('metric-rejected').innerText = '0';
        updateSeverityChart([0, 0, 0]);
        return;
      }

      // Sort Priority: Action Required (Rejected) at top, tie-break with newest date
      rawBarangayReports.sort((a, b) => {
        const getPriority = (status) => {
          const s = String(status || '').toLowerCase();
          if (s.includes('reject')) return 1;
          if (s.includes('pending') || s.includes('resubmit')) return 2;
          if (s.includes('validate') || s.includes('dispatch') || s.includes('progress')) return 3;
          if (s.includes('completed') || s.includes('closed') || s.includes('resolved')) return 4;
          return 5;
        };

        const priorityA = getPriority(a.status);
        const priorityB = getPriority(b.status);

        if (priorityA !== priorityB) return priorityA - priorityB;

        const dateA = new Date(a.date_submitted || a.dateSubmitted || a.createdAt || 0);
        const dateB = new Date(b.date_submitted || b.dateSubmitted || b.createdAt || 0);
        return dateB - dateA;
      });

      // =========================================================================
      // 🚀 KPI METRICS MATH (Includes 'Closed' & 'Resolved' in Validated/Finished)
      // =========================================================================
      let pending = 0, validated = 0, rejected = 0;
      let highSev = 0, medSev = 0, lowSev = 0;

      rawBarangayReports.forEach(r => {
        const s = String(r.status || '').toLowerCase();
        if (s.includes('reject')) {
          rejected++;
        } else if (
          s.includes('validate') ||
          s.includes('dispatch') ||
          s.includes('progress') ||
          s.includes('completed') ||
          s.includes('closed') ||
          s.includes('resolved')
        ) {
          validated++;
        } else {
          pending++;
        }

        const sev = String(r.severity || '').toLowerCase();
        if (sev === 'high') highSev++;
        else if (sev === 'medium') medSev++;
        else if (sev === 'low') lowSev++;
      });

      document.getElementById('metric-total').innerText = rawBarangayReports.length;
      document.getElementById('metric-pending').innerText = pending;
      document.getElementById('metric-validated').innerText = validated;
      document.getElementById('metric-rejected').innerText = rejected;

      updateSeverityChart([highSev, medSev, lowSev]);
      renderFilteredCards(rawBarangayReports);
    })
    .catch(error => {
      console.error("Error loading reports:", error);
      listContainer.innerHTML = `
        <div style="text-align: center; padding: 30px; background: #ffffff; border-radius: 10px; border: 1px solid #fecaca; color: #dc2626;">
          ⚠️ Failed to load reports. Please check your network connection.
        </div>
      `;
    });
}

// 🎨 3. Render Report Cards Function
function renderFilteredCards(reportsList) {
  const listContainer = document.getElementById('barangay-report-list');
  if (!listContainer) return;

  if (reportsList.length === 0) {
    listContainer.innerHTML = `
      <div style="text-align: center; padding: 35px; background: #ffffff; border-radius: 10px; border: 1px solid #e2e8f0; color: #64748b;">
        No inspection records match your search filter.
      </div>
    `;
    return;
  }

  const defaultBarangayName = sessionStorage.getItem("barangayName") || "Barangay Jurisdiction";

  listContainer.innerHTML = reportsList.map(report => {
    const sLower = String(report.status || '').toLowerCase();
    const prjId = `#PRJ-${String(report.id).padStart(4, '0')}`;
    const roadName = report.cityRoadName || 'Unnamed Road Segment';

    // Clean Barangay Name Resolution
    const brgyName = report.barangay?.barangayName || report.barangay?.name || defaultBarangayName;

    // Date formatting
    const rawDate = report.date_submitted || report.dateSubmitted || report.createdAt;
    const dateStr = rawDate ? new Date(rawDate).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : 'Recently';

    // Damage & Severity Tags
    const damageType = report.damageType || 'General Inspection';
    const severity = report.severity || 'Pending AI';
    let sevBadgeColor = '#64748b';
    if (severity === 'High') sevBadgeColor = '#dc2626';
    else if (severity === 'Medium') sevBadgeColor = '#d97706';
    else if (severity === 'Low') sevBadgeColor = '#16a34a';

    // =========================================================================
// 🚀 ENHANCED STATUS BADGE RESOLVER
// =========================================================================
    let badgeClass = 'bd-badge-pending';
    let badgeLabel = 'Under Review';
    let actionBtnHtml = `<button class="bd-btn-card-action bd-btn-view" onclick="openViewModal(${report.id})">View Details</button>`;

    if (sLower.includes('reject')) {
      badgeClass = 'bd-badge-rejected';
      badgeLabel = 'Action Required';
      actionBtnHtml = `<button class="bd-btn-card-action bd-btn-edit" onclick="openEditModal(${report.id})">✏️ Edit & Resubmit</button>`;
    } else if (sLower.includes('resubmit')) {
      badgeClass = 'bd-badge-resubmitted';
      badgeLabel = 'Resubmitted';
    } else if (sLower.includes('progress')) {
      badgeClass = 'bd-badge-progress';
      badgeLabel = 'In Progress';
    } else if (sLower.includes('completed')) {
      badgeClass = 'bd-badge-completed';
      badgeLabel = 'Completed (Pending QA)';
    } else if (sLower.includes('closed') || sLower.includes('resolved')) {
      badgeClass = 'bd-badge-closed';
      badgeLabel = 'Closed / Resolved';
    } else if (sLower.includes('validate') || sLower.includes('dispatch')) {
      badgeClass = 'bd-badge-validated';
      badgeLabel = 'Validated';
    }

    return `
      <div class="bd-card-item">
        <!-- Thumbnail -->
        <div class="bd-card-image-box">
          <img id="brgy-preview-img-${report.id}"
               src="https://placehold.co/280x210/png?text=Loading+Photo..."
               alt="Road Inspection"
               onclick="openFullscreenImage(this)">
        </div>

        <!-- Details -->
        <div class="bd-card-body">
          <div class="bd-card-header-row">
            <span class="bd-prj-tag">${prjId}</span>
            <span class="bd-card-title">${roadName}</span>
          </div>

          <div class="bd-card-meta">
            <span>📍 <strong>${brgyName}</strong></span>
            <span>📅 ${dateStr}</span>
            <span>⚠️ <strong style="color: ${sevBadgeColor};">${severity}</strong></span>
            <span>🛠️ ${damageType}</span>
          </div>

          ${sLower.includes('reject') && report.adminRemarks ? `
            <div class="bd-card-note-box">
              <strong>💬 CPDO Feedback:</strong> ${escapeHtml(report.adminRemarks)}
            </div>
          ` : `
            <div style="font-size: 12.5px; color: #64748b; line-height: 1.4;">
              ${escapeHtml(report.damageDescription || 'Road assessment submitted and logged into central inventory.')}
            </div>
          `}
        </div>

        <!-- Action / Status Block -->
        <div class="bd-card-actions">
          <div class="bd-badge ${badgeClass}">${badgeLabel}</div>
          ${actionBtnHtml}
        </div>
      </div>
    `;
  }).join('');

  // Securely load damage photos
  reportsList.forEach(report => {
    if (typeof loadSecureImage === 'function') {
      loadSecureImage(`brgy-preview-img-${report.id}`, report.damageImage);
    }
  });
}

// 🔍 4. Search and Status Filter Controller
window.filterBarangayReports = function() {
  const query = (document.getElementById('report-search-bar')?.value || '').toLowerCase().trim();
  const statusFilter = document.getElementById('report-status-filter')?.value || 'All';

  const filtered = rawBarangayReports.filter(r => {
    const prjId = `#prj-${String(r.id).padStart(4, '0')}`.toLowerCase();
    const road = String(r.cityRoadName || '').toLowerCase();
    const damage = String(r.damageType || '').toLowerCase();
    const desc = String(r.damageDescription || '').toLowerCase();
    const s = String(r.status || '').toLowerCase();

    const matchesQuery = !query || road.includes(query) || prjId.includes(query) || damage.includes(query) || desc.includes(query);

    let matchesStatus = true;
    if (statusFilter === 'Rejected') matchesStatus = s.includes('reject');
    else if (statusFilter === 'Pending') matchesStatus = s.includes('pending') || s.includes('resubmit');
    else if (statusFilter === 'Validated') matchesStatus = s.includes('validate') || s.includes('dispatch') || s.includes('progress') || s.includes('completed');

    return matchesQuery && matchesStatus;
  });

  renderFilteredCards(filtered);
};

// 📊 5. Chart.js Doughnut Initializer
function updateSeverityChart(dataArray) {
  const canvasId = 'severityChart';
  const ctx = document.getElementById(canvasId);
  if (!ctx) return;

  if (severityChartInstance) {
    severityChartInstance.destroy();
  }

  let existingChart = Chart.getChart(canvasId);
  if (existingChart) existingChart.destroy();

  const totalSeverity = dataArray.reduce((a, b) => a + b, 0);
  const isEmpty = totalSeverity === 0;

  severityChartInstance = new Chart(ctx, {
    type: 'doughnut',
    data: {
      labels: isEmpty ? ['Pending AI Assessment'] : ['High', 'Medium', 'Low'],
      datasets: [{
        data: isEmpty ? [1] : dataArray,
        backgroundColor: isEmpty ? ['#e2e8f0'] : ['#dc2626', '#f59e0b', '#16a34a'],
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
            font: { size: 11, weight: '600' },
            padding: 10
          }
        },
        tooltip: { enabled: !isEmpty }
      },
      cutout: '72%'
    }
  });
}

// 6. View & Edit Modals Controller
function closeBdModals() {
  const viewModal = document.getElementById('bd-view-modal');
  const editModal = document.getElementById('bd-edit-modal');
  if (viewModal !== null) viewModal.classList.remove('active');
  if (editModal !== null) editModal.classList.remove('active');
}

// ==========================================
// 🗺️ VIEW MODAL LEAFLET MAP INSTANCE
// ==========================================
let viewModalMap = null;
let viewModalMarker = null;
let viewModalBoundaryLayer = null;

function renderViewModalMap(lat, lng, targetBarangay) {
  const mapDiv = document.getElementById('view-modal-map');
  const noMapMsg = document.getElementById('view-modal-no-map');

  if (!lat || !lng || isNaN(lat) || isNaN(lng)) {
    if (mapDiv) mapDiv.classList.add('hidden');
    if (noMapMsg) noMapMsg.classList.remove('hidden');
    return;
  }

  if (mapDiv) mapDiv.classList.remove('hidden');
  if (noMapMsg) noMapMsg.classList.add('hidden');

  // 1. Initialize map instance once
  if (!viewModalMap) {
    viewModalMap = L.map('view-modal-map', {
      zoomControl: true,
      attributionControl: false
    });

    // Google Hybrid satellite tiles
    L.tileLayer('https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
      maxZoom: 20,
      subdomains: ['mt0', 'mt1', 'mt2', 'mt3']
    }).addTo(viewModalMap);
  }

  // 2. Remove previous boundary layer if exists
  if (viewModalBoundaryLayer) {
    viewModalMap.removeLayer(viewModalBoundaryLayer);
    viewModalBoundaryLayer = null;
  }

  // 3. Render red dashed boundary line of the assigned barangay
  if (typeof sjdmGeoJsonData !== 'undefined' && sjdmGeoJsonData && targetBarangay) {
    function normalizeBrgy(str) {
      return (str || "")
        .toLowerCase()
        .replace(/[\u2013\u2014\u2212-]/g, "-")
        .replace(/^sto\.\s*|^santo\s*/, "santo ")
        .replace(/^sta\.\s*|^santa\s*/, "santa ")
        .replace(/\s+/g, " ")
        .trim();
    }

    const cleanTarget = normalizeBrgy(targetBarangay);
    const feature = sjdmGeoJsonData.features.find(f => {
      const geoName = normalizeBrgy(f.properties.name || f.properties.adm4_name || "");
      if (geoName === cleanTarget) return true;
      if (cleanTarget === "sapang palay proper" && geoName === "sapang palay") return true;
      if (cleanTarget === "sapang palay" && geoName === "sapang palay proper") return true;
      return false;
    });

    if (feature) {
      viewModalBoundaryLayer = L.geoJSON(feature, {
        style: {
          color: '#ef4444',
          weight: 2.5,
          dashArray: '5, 5',
          fillOpacity: 0
        },
        interactive: false
      }).addTo(viewModalMap);
    }
  }

  // 4. Center map and position marker on incident coordinates
  viewModalMap.setView([lat, lng], 17);

  if (viewModalMarker) {
    viewModalMap.removeLayer(viewModalMarker);
  }

  const iconToUse = (typeof window.redIcon !== 'undefined') ? window.redIcon : new L.Icon.Default();
  viewModalMarker = L.marker([lat, lng], { icon: iconToUse }).addTo(viewModalMap);

  // Recalculate dimensions after modal becomes visible
  setTimeout(() => {
    if (viewModalMap) viewModalMap.invalidateSize();
  }, 250);
}

// ==========================================
// 🕒 TIMELINE AUDIT TRAIL RENDERER
// ==========================================
function loadReportTimeline(reportId, targetContainerId) {
  const container = document.getElementById(targetContainerId);
  if (!container) return;

  // 1. Identify the outer scroll box (the one with max-height & overflow-y: auto)
  const scrollBox = container.closest('[id$="-timeline-container"]') || container.parentElement || container;

  // Reset scroll on start
  container.scrollTop = 0;
  if (scrollBox) scrollBox.scrollTop = 0;

  container.innerHTML = `
    <div style="text-align: center; padding: 20px; color: #94a3b8; font-size: 13px;">
      <span>⏳ Loading project history...</span>
    </div>
  `;

  apiFetch(`/api/reports/${reportId}/timeline`)
    .then(timeline => {
      if (!timeline || timeline.length === 0) {
        container.innerHTML = `
          <div style="text-align: center; padding: 15px; color: #94a3b8; font-size: 12.5px; background: #f8fafc; border-radius: 8px; border: 1px dashed #e2e8f0;">
            No lifecycle events recorded for this report.
          </div>
        `;
        if (scrollBox) scrollBox.scrollTop = 0;
        return;
      }

      container.innerHTML = '';

      timeline.forEach(event => {
        const item = document.createElement('div');
        const actionLower = (event.action || '').toLowerCase();
        item.className = `timeline-item ${actionLower}`;

        // Select badge style
        let badgeClass = 'badge-green';
        if (['rejected', 'repair_rework_requested'].includes(actionLower)) {
          badgeClass = 'badge-red';
        } else if (['in_progress', 'dispatched', 'resubmitted'].includes(actionLower)) {
          badgeClass = 'badge-blue';
        } else if (actionLower === 'pending_budget') {
          badgeClass = 'badge-yellow';
        }

        // Format label
        const displayAction = (event.action || '').replace(/_/g, ' ');

        // 🇵🇭 EXACT LINE CHANGED: Guaranteed Philippine Standard Time (Asia/Manila)
        const dateStr = event.createdAt ? formatPST(event.createdAt, true) : 'Recent';

        // Build remarks block
        let remarksHtml = '';
        if (event.remarks && event.remarks.trim()) {
          const isRedBox = ['rejected', 'repair_rework_requested'].includes(actionLower);
          remarksHtml = `
            <div class="timeline-remarks-box ${isRedBox ? 'remarks-red' : 'remarks-default'}">
              <strong>${isRedBox ? '⚠️ Feedback / Reason:' : '📝 Remarks:'}</strong> ${escapeHtml(event.remarks)}
            </div>
          `;
        }

        // Build attachment thumbnail link
        let attachmentHtml = '';
        if (event.attachmentUrl && event.attachmentUrl !== 'no_image.jpg') {
          attachmentHtml = `
            <a class="timeline-attachment-link" onclick="openFullscreenImage({ src: '${event.attachmentUrl}' })">
              <span>📸</span> View Logged Attachment
            </a>
          `;
        }

        item.innerHTML = `
          <div class="timeline-card">
            <div class="timeline-header">
              <span class="timeline-badge ${badgeClass}">${displayAction}</span>
              <span class="timeline-date">${dateStr}</span>
            </div>
            <div class="timeline-actor">
              By: <strong>${escapeHtml(event.actorName || 'System')}</strong>
              <span style="color: #64748b; font-size: 11px;">(${escapeHtml(event.actorRole || 'Official')})</span>
            </div>
            ${remarksHtml}
            ${attachmentHtml}
          </div>
        `;

        container.appendChild(item);
      });

      // 🚀 RESET TIMELINE SCROLL POSITION TO TOP ONCE CONTENT IS INJECTED
      container.scrollTop = 0;
      if (scrollBox) {
        scrollBox.scrollTop = 0;
      }
      requestAnimationFrame(() => {
        container.scrollTop = 0;
        if (scrollBox) scrollBox.scrollTop = 0;
      });
    })
    .catch(err => {
      console.error("Failed to load timeline:", err);
      container.innerHTML = `
        <div style="color: #ef4444; font-size: 12px; padding: 10px; background: #fef2f2; border-radius: 6px;">
          Failed to load lifecycle audit trail.
        </div>
      `;
      if (scrollBox) scrollBox.scrollTop = 0;
    });
}



// ==========================================
// 🚀 BULLETPROOF MODAL SCROLL RESET HELPER
// ==========================================

// 📜 VIEW MODAL ACCORDION TOGGLE
window.toggleViewTimeline = function() {
  const container = document.getElementById('view-modal-timeline-container');
  const arrow = document.getElementById('view-timeline-arrow');
  if (!container) return;

  const isHidden = container.classList.toggle('hidden');
  if (arrow) arrow.style.transform = isHidden ? 'rotate(0deg)' : 'rotate(180deg)';

  // 🚀 RESET INNER TIMELINE SCROLL UPON OPENING
  if (!isHidden) {
    container.scrollTop = 0;
    requestAnimationFrame(() => { container.scrollTop = 0; });
  }
};

// ==========================================
// 🔍 OPEN VIEW MODAL CONTROLLER
// ==========================================
function openViewModal(reportId) {
  const viewModal = document.getElementById('bd-view-modal');

  // 1. ⏱️ Reset accordion state to collapsed
  const timelineContainer = document.getElementById('view-modal-timeline-container');
  if (timelineContainer) timelineContainer.classList.add('hidden');
  const arrow = document.getElementById('view-timeline-arrow');
  if (arrow) arrow.style.transform = 'rotate(0deg)';

  // 2. Reveal modal & run initial scroll reset
  if (viewModal) {
    viewModal.classList.add('active');
    resetModalScroll(viewModal);
  }

  // 3. Fetch and populate details
  apiFetch(`/api/reports/${reportId}`)
    .then(report => {
      document.getElementById('view-modal-id-header').innerText = `#RPT-${report.id.toString().padStart(4, '0')}`;
      const statusBadge = document.getElementById('view-modal-status');
      statusBadge.innerText = report.status;
      statusBadge.className = "bd-status-badge " +
        (report.status === 'Validated' ? 'bd-badge-validated' :
          (report.status === 'Rejected' ? 'bd-badge-rejected' : 'bd-badge-pending'));

      // AI Severity Display
      const severityEl = document.getElementById('view-modal-severity');
      if (report.severity) {
        const confText = report.cvConfidenceScore ? ` (${report.cvConfidenceScore}%)` : '';
        severityEl.innerText = `${report.severity}${confText}`;

        if (report.severity === 'High') {
          severityEl.style.color = '#dc2626';
        } else if (report.severity === 'Medium') {
          severityEl.style.color = '#d97706';
        } else {
          severityEl.style.color = '#16a34a';
        }
      } else {
        severityEl.innerText = "🤖 Pending AI Assessment";
        severityEl.style.color = '#64748b';
      }

      document.getElementById('view-modal-date').innerText = new Date(report.dateSubmitted).toLocaleDateString();
      document.getElementById('view-modal-gps').innerText =
        (report.latitude && report.longitude) ? `${report.latitude.toFixed(5)}, ${report.longitude.toFixed(5)}` : "Not provided";

      document.getElementById('view-modal-road-name').innerText = report.cityRoadName || "N/A";
      document.getElementById('view-modal-road-id').innerText = report.cityRoadId || "N/A";
      document.getElementById('view-modal-importance').innerText = report.roadImportance || "N/A";
      document.getElementById('view-modal-terrain').innerText = report.terrainType || "N/A";
      document.getElementById('view-modal-road-type').innerText = report.roadType || "N/A";
      document.getElementById('view-modal-length').innerText = report.length || "0";
      document.getElementById('view-modal-width').innerText = report.width || "0";
      document.getElementById('view-modal-culverts').innerText = report.lengthOfCulverts || "0";
      document.getElementById('view-modal-bridges').innerText = report.numberOfBridges || "0";

      document.getElementById('view-modal-damage-type').innerText = report.damageType || "None";
      document.getElementById('view-modal-damage-length').innerText = report.damageLength || "0";
      document.getElementById('view-modal-damage-width').innerText = report.damageWidth || "0";
      document.getElementById('view-modal-desc').innerText = report.damageDescription || "No description provided.";

      loadSecureImage('view-modal-img', report.damageImage);

      // Extract assigned barangay name
      const reportBarangayName = (report.barangay && report.barangay.name)
        ? report.barangay.name
        : (sessionStorage.getItem("barangayName") || "Kaypian");

      // Render satellite map pin with the barangay boundary overlay
      renderViewModalMap(report.latitude, report.longitude, reportBarangayName);

      // 🕒 Load audit trail timeline into the accordion
      if (typeof loadReportTimeline === 'function') {
        loadReportTimeline(report.id, 'view-modal-timeline');
      }

      // 🚀 Bulletproof Scroll Reset after all DOM insertions complete
      resetModalScroll(viewModal);
    })
    .catch(err => {
      console.error(err);
      if (typeof showToast === 'function') showToast("Error loading details.", "error");
    });
}

// 📜 EDIT MODAL ACCORDION TOGGLE
window.toggleEditTimeline = function() {
  const container = document.getElementById('edit-modal-timeline-container');
  const arrow = document.getElementById('edit-timeline-arrow');
  if (!container) return;

  const isHidden = container.classList.toggle('hidden');
  if (arrow) arrow.style.transform = isHidden ? 'rotate(0deg)' : 'rotate(180deg)';

  // 🚀 RESET INNER TIMELINE SCROLL UPON OPENING
  if (!isHidden) {
    container.scrollTop = 0;
    requestAnimationFrame(() => { container.scrollTop = 0; });
  }
};

// ==========================================
// ✏️ OPEN EDIT MODAL CONTROLLER
// ==========================================
function openEditModal(reportId) {
  const editModal = document.getElementById('bd-edit-modal');

  // 1. ⏱️ Reset accordion state to collapsed
  const timelineContainer = document.getElementById('edit-modal-timeline-container');
  if (timelineContainer) timelineContainer.classList.add('hidden');
  const arrow = document.getElementById('edit-timeline-arrow');
  if (arrow) arrow.style.transform = 'rotate(0deg)';

  // 2. Reveal modal & run initial scroll reset
  if (editModal) {
    editModal.classList.add('active');
    resetModalScroll(editModal);
  }

  // 3. Fetch report details
  apiFetch(`/api/reports/${reportId}`)
    .then(report => {
      document.getElementById('edit-modal-id-header').innerText = `#RPT-${report.id.toString().padStart(4, '0')}`;
      document.getElementById('edit-report-id').value = report.id;
      document.getElementById('edit-modal-remarks').innerText = report.adminRemarks || "Please review and fix the details below.";

      document.getElementById('edit-modal-road-name').value = report.cityRoadName || "N/A";
      document.getElementById('edit-modal-road-id').value = report.cityRoadId || "N/A";
      document.getElementById('edit-modal-importance').value = report.roadImportance || "N/A";
      document.getElementById('edit-modal-road-type').value = report.roadType || "N/A";
      document.getElementById('edit-modal-terrain').value = report.terrainType || "N/A";

      // 🧠 AI Severity & Confidence Display in Edit Field
      const editSeverityInput = document.getElementById('edit-modal-severity');
      if (report.severity) {
        const confText = report.cvConfidenceScore ? ` (${report.cvConfidenceScore}% Confidence)` : '';
        editSeverityInput.value = `${report.severity}${confText}`;
      } else {
        editSeverityInput.value = "🤖 Pending AI Assessment";
      }

      document.getElementById('edit-modal-gps').innerText = (report.latitude && report.longitude)
        ? `${report.latitude.toFixed(5)}, ${report.longitude.toFixed(5)}`
        : "Not Selected";
      document.getElementById('edit-latitude').value = report.latitude || "";
      document.getElementById('edit-longitude').value = report.longitude || "";

      document.getElementById('edit-modal-length').value = report.length || "";
      document.getElementById('edit-modal-width').value = report.width || "";
      document.getElementById('edit-modal-culverts').value = report.lengthOfCulverts || "";
      document.getElementById('edit-modal-bridges').value = report.numberOfBridges || "";

      document.getElementById('edit-modal-damage-length').value = report.damageLength || "";
      document.getElementById('edit-modal-damage-width').value = report.damageWidth || "";

      const standardTypes = ["Pothole", "Surface Cracking", "Edge Deformation", "Washout/Sinkhole", "None"];
      const savedType = report.damageType || "None";

      if (standardTypes.includes(savedType)) {
        document.getElementById('edit-modal-damage-type').value = savedType;
        document.getElementById('edit-modal-damage-other').classList.add('hidden');
      } else {
        document.getElementById('edit-modal-damage-type').value = "Other";
        const otherInput = document.getElementById('edit-modal-damage-other');
        otherInput.value = savedType;
        otherInput.classList.remove('hidden');
      }
      document.getElementById('edit-modal-desc').value = report.damageDescription || "";
      loadSecureImage('edit-modal-current-img', report.damageImage);

      document.getElementById('edit-modal-img').value = "";
      document.getElementById('edit-modal-filename').innerText = "";

      // 🕒 Load timeline audit trail into the Accordion
      if (typeof loadReportTimeline === 'function') {
        loadReportTimeline(report.id, 'edit-modal-timeline');
      }

      // 🚀 Bulletproof Scroll Reset after all DOM insertions complete
      resetModalScroll(editModal);
    })
    .catch(err => {
      console.error(err);
      if (typeof showToast === 'function') showToast("Error loading report.", "error");
    });
}

// ==========================================
// 🚀 SUBMIT EDITED / RESUBMITTED REPORT
// ==========================================
function submitEditedReport() {
  const reportId = document.getElementById('edit-report-id').value;
  const fileInput = document.getElementById('edit-modal-img');

  if (fileInput.files.length > 0 && fileInput.files[0].size > 5 * 1024 * 1024) {
    if (typeof showToast === 'function') showToast("File is too large! Must be under 5MB.", "error");
    return;
  }

  const formData = new FormData();

  // 👤 Pass userId for audit trail logging
  const currentUserId = sessionStorage.getItem("userId");
  if (currentUserId) {
    formData.append("userId", currentUserId);
  }

  formData.append("damageDescription", document.getElementById('edit-modal-desc').value);
  formData.append("length", document.getElementById('edit-modal-length').value);
  formData.append("width", document.getElementById('edit-modal-width').value);
  formData.append("lengthOfCulverts", document.getElementById('edit-modal-culverts').value);
  formData.append("numberOfBridges", document.getElementById('edit-modal-bridges').value);
  formData.append("latitude", document.getElementById('edit-latitude').value);
  formData.append("longitude", document.getElementById('edit-longitude').value);

  if (fileInput.files.length > 0) {
    formData.append("imageFile", fileInput.files[0]);
  }

  let editedDamageType = document.getElementById('edit-modal-damage-type').value;
  if (editedDamageType === "Other") {
    editedDamageType = document.getElementById('edit-modal-damage-other').value || "Other";
  }
  formData.append("damageType", editedDamageType);
  formData.append("damageLength", document.getElementById('edit-modal-damage-length').value || 0);
  formData.append("damageWidth", document.getElementById('edit-modal-damage-width').value || 0);

  fetch(`${API_BASE_URL}/api/reports/update/${reportId}`, {
    method: 'PUT',
    headers: { 'ngrok-skip-browser-warning': 'true' },
    body: formData
  })
    .then(response => {
      if (!response.ok) throw new Error("Update failed");
      return response.json();
    })
    .then(data => {
      const aiSeverity = data.severity || "Unassessed";
      const aiConfidence = data.cvConfidenceScore ? data.cvConfidenceScore : 0;
      const hasNewImage = fileInput.files.length > 0;

      if (typeof showToast === 'function') {
        if (hasNewImage && data.severity) {
          const icon = aiSeverity === 'High' ? '🚨' : (aiSeverity === 'Medium' ? '⚠️' : '✅');
          showToast(`${icon} Resubmitted! AI re-evaluated as ${aiSeverity.toUpperCase()} Severity (${aiConfidence}% confidence).`, "success");
        } else {
          showToast("Report successfully updated and resubmitted!", "success");
        }
      }

      closeBdModals();

      const storedBarangayId = sessionStorage.getItem("barangayId");
      if (storedBarangayId) loadBarangayReports(storedBarangayId);
    })
    .catch(error => {
      console.error(error);
      if (typeof showToast === 'function') showToast("Error updating report.", "error");
    });
}

// ==========================================
// 7. UTILITIES & OBSERVERS
// ==========================================


document.addEventListener("DOMContentLoaded", () => {
  const storedBarangayId = sessionStorage.getItem("barangayId");
  if (!storedBarangayId && document.getElementById('barangay-report-list')) {
    if (typeof showToast === "function") showToast("Security Check: You must log in first!", "error");
    window.location.href = "login.html";
    return;
  }

  if (storedBarangayId && document.getElementById('barangay-report-list')) {
    loadBarangayReports(storedBarangayId);
  }

  const editImageInput = document.getElementById('edit-modal-img');
  const editImagePreview = document.getElementById('edit-modal-current-img');
  const editFileNameDisplay = document.getElementById('edit-modal-filename');

  if (editImageInput) {
    editImageInput.addEventListener('change', function() {
      const file = this.files[0];
      if (file) {
        if (file.size > 5 * 1024 * 1024) {
          if (typeof showToast === 'function') showToast("File is too large! Max 5MB.", "error");
          this.value = "";
          if (editFileNameDisplay) editFileNameDisplay.textContent = "";
          return;
        }
        if (editFileNameDisplay) editFileNameDisplay.textContent = "Selected: " + file.name;
        const reader = new FileReader();
        reader.onload = function(e) {
          if (editImagePreview) editImagePreview.src = e.target.result;
        };
        reader.readAsDataURL(file);
      }
    });
  }

});

// Compatibility bridges
window.openViewModal = openViewModal;
window.openEditModal = openEditModal;
window.closeBdModals = closeBdModals;
window.submitEditedReport = submitEditedReport;
window.loadBarangayReports = loadBarangayReports;
window.loadReportTimeline = loadReportTimeline;
