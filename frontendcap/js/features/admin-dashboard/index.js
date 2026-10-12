/**
 * CPDO admin dashboard engine: report inbox, review modal, admin review map,
 * action queue, compliance charts, archive/priority navigation, and its own
 * DOMContentLoaded data-engine boot.
 *
 * Extracted verbatim from app.js. The legacy accept/validate/reject
 * addEventListener handlers were lifted out of the main app.js
 * DOMContentLoaded listener into their own DOMContentLoaded listener at the
 * bottom of this file; the module-scope .onclick controllers still fire first
 * and the confirm paths call stopImmediatePropagation, so those listeners stay
 * suppressed exactly as before (show/cancel reject handlers still run their
 * idempotent DOM toggles).
 *
 * Compatibility bridges installed on window:
 * window.reviewReport, window.loadAdminReports, window.loadAdminDashboardData,
 * window.jumpToReportsAndReview, window.jumpToAllReports, window.jumpToArchiveTab,
 * window.toggleAdminReviewTimeline, window.closeReviewModal, window.toggleAdminReviewMap.
 */
import { apiFetch, API_BASE_URL, getAuthHeaders } from '../../core/api.js';
import { resetModalScroll } from '../../core/utils.js';
import { showToast } from '../../components/toast.js';
import { openModal, closeModal } from '../../components/modal.js';

// ==========================================
// ADMIN DASHBOARD: LOAD ALL REPORTS (INBOX)
// ==========================================
function loadAdminReports() {
  // 🛡️ SAFETY CHECK: Only run this if we are actually on the Admin Dashboard!
  if (document.getElementById('ceo-metric-total')) {
    return;
  }

  const reportsTableBody = document.getElementById('admin-inbox-table-body') ||
    document.querySelector('#view-reports .data-table tbody');
  if (!reportsTableBody) return;

  apiFetch(`/api/reports`)
    .then(reports => {
      reportsTableBody.innerHTML = '';

      // ==========================================
      // 🚀 SEPARATION OF CONCERNS: Filter out Tracking & Archive items!
      // ==========================================
      const inboxReports = reports.filter(r => {
        const s = String(r.status || '').toLowerCase();
        return !s.includes('dispatch') &&
          !s.includes('progress') &&
          !s.includes('complet') &&
          !s.includes('clos') &&
          !s.includes('archiv');
      });

      if (inboxReports.length === 0) {
        reportsTableBody.innerHTML = '<tr><td colspan="8" style="text-align: center; padding: 20px;">No new reports in the inbox.</td></tr>';
        return;
      }

      // ==========================================
      // 🚀 SMART PRIORITY SORTING (INDUSTRY STANDARD)
      // ==========================================
      inboxReports.sort((a, b) => {
        const getPriority = (status) => {
          const s = String(status || '').toLowerCase();
          if (s.includes('resubmit')) return 1;
          if (s.includes('pending')) return 2;
          if (s.includes('validate')) return 3;
          return 4;
        };

        const priorityA = getPriority(a.status);
        const priorityB = getPriority(b.status);

        if (priorityA !== priorityB) {
          return priorityA - priorityB;
        }

        const dateA = new Date(a.date_submitted || a.dateSubmitted || 0);
        const dateB = new Date(b.date_submitted || b.dateSubmitted || 0);
        return dateB - dateA;
      });

      // ==========================================
      // BUILD TABLE ROWS
      // ==========================================
      inboxReports.forEach(report => {
        const formattedId = `#RPT-${String(report.id || 0).padStart(4, '0')}`;
        const roadId = report.cityRoadId || 'N/A';
        const roadName = report.cityRoadName || 'Unknown Road';
        const dateSubmitted = report.date_submitted || report.dateSubmitted || 'N/A';

        const barangayDisplay = (report.barangay && report.barangay.barangayName)
          ? report.barangay.barangayName
          : 'Unknown Barangay';

        // 🚀 DYNAMIC SEVERITY BADGES (STANDARDIZED UNASSESSED & SEVERITY TIERS)
        const rawSeverity = String(report.severity || '').toLowerCase().trim();
        let severityBadgeHtml = '';

        if (rawSeverity === 'high') {
          severityBadgeHtml = `<span style="background-color: #dc2626; color: #ffffff; border: 1px solid #dc2626; padding: 3px 9px; border-radius: 5px; font-size: 11px; font-weight: 800; display: inline-block; letter-spacing: 0.3px; white-space: nowrap;">HIGH</span>`;
        } else if (rawSeverity === 'medium') {
          severityBadgeHtml = `<span style="background-color: #ffc107; color: #000000; border: 1px solid #eab308; padding: 3px 9px; border-radius: 5px; font-size: 11px; font-weight: 800; display: inline-block; letter-spacing: 0.3px; white-space: nowrap;">MEDIUM</span>`;
        } else if (rawSeverity === 'low') {
          severityBadgeHtml = `<span style="background-color: #16a34a; color: #ffffff; border: 1px solid #16a34a; padding: 3px 9px; border-radius: 5px; font-size: 11px; font-weight: 800; display: inline-block; letter-spacing: 0.3px; white-space: nowrap;">LOW</span>`;
        } else {
          severityBadgeHtml = `<span style="background-color: #ecfdf5; color: #047857; border: 1px solid #34d399; padding: 3px 9px; border-radius: 5px; font-size: 11px; font-weight: 800; display: inline-block; letter-spacing: 0.3px; white-space: nowrap;">UNASSESSED</span>`;
        }

        const status = report.status || 'Pending';
        const sLower = status.toLowerCase();

        // 🚀 DYNAMIC BADGES BASED ON AUDIT TRAIL LOGIC
        let statusHtml = '';
        let buttonHtml = `<button class="btn-small validate-btn" onclick="reviewReport(${report.id})">Review</button>`;

        if (sLower.includes('resubmit')) {
          statusHtml = `<span class="status-badge" style="background-color: #fff3cd; color: #856404; border: 1px solid #ffeeba;">⚠️ Resubmitted</span>`;
        } else if (sLower.includes('pending')) {
          statusHtml = `<span class="status-badge pending">Pending Validation</span>`;
        } else if (sLower.includes('reject')) {
          statusHtml = `<span class="status-badge" style="background-color: #f8d7da; color: #721c24;">❌ Rejected</span>`;
          buttonHtml = `<button class="btn-small validate-btn" disabled style="background-color: #ccc; cursor: not-allowed;">Archived</button>`;
        } else {
          statusHtml = `<span class="status-badge validated">${status}</span>`;
          buttonHtml = `<button class="btn-small validate-btn" disabled style="background-color: #ccc; cursor: not-allowed;">Done</button>`;
        }

        const row = document.createElement('tr');

        if (sLower.includes('reject') || sLower.includes('validate')) {
          row.style.opacity = '0.5';
          row.style.backgroundColor = '#f8f9fa';
        }

        row.innerHTML = `
          <td>${formattedId}</td>
          <td>${barangayDisplay}</td>
          <td><b>${roadId}</b></td>
          <td>${roadName}</td>
          <td style="text-align: center; white-space: nowrap;">${severityBadgeHtml}</td>
          <td>${dateSubmitted}</td>
          <td>${statusHtml}</td>
          <td>${buttonHtml}</td>
        `;
        reportsTableBody.appendChild(row);
      });

      const tableContainer = document.querySelector('.table-container') || document.querySelector('.table-responsive');
      if (tableContainer) tableContainer.scrollTop = 0;

      if (typeof window.filterAdminReports === 'function') {
        window.filterAdminReports();
      }

    })
    .catch(error => {
      console.error("Error loading admin reports:", error);
      if (reportsTableBody) {
        reportsTableBody.innerHTML = '<tr><td colspan="8" style="text-align: center; color: red; padding: 20px;">Error loading reports from database.</td></tr>';
      }
    });
}

// ==========================================
// 🚀 BULLETPROOF MODAL SCROLL RESET HELPER
// ==========================================

// ==========================================
// ADMIN MODAL MAP VARIABLES
// ==========================================
let currentReviewLat = null;
let currentReviewLng = null;
let adminReviewMap = null;
let adminReviewMarker = null;
let currentReviewReportId = null;

// Ensure it runs when the script loads
if (typeof loadAdminReports === 'function') {
  loadAdminReports();
}

// 📜 ADMIN REVIEW TIMELINE ACCORDION TOGGLE
window.toggleAdminReviewTimeline = function() {
  const container = document.getElementById('admin-review-timeline-container');
  const arrow = document.getElementById('admin-timeline-arrow');
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
// ADMIN DASHBOARD: OPEN REVIEW MODAL
// ==========================================
function reviewReport(reportId) {
  currentReviewReportId = reportId;
  isReviewSubmitting = false;

  // 1. Grab and open modal
  openModal('review-modal');

  // 3. Force map container closed on open
  const mapContainer = document.getElementById('admin-review-map-container');
  if (mapContainer) mapContainer.style.display = 'none';

  // 4. Reset Rejection Form & Actions
  const primaryActions = document.getElementById('primary-actions');
  const rejectFeedbackForm = document.getElementById('reject-feedback-form');
  const adminRemarksInput = document.getElementById('admin-remarks-input');

  if (primaryActions) primaryActions.classList.remove('hidden');
  if (rejectFeedbackForm) rejectFeedbackForm.classList.add('hidden');
  if (adminRemarksInput) adminRemarksInput.value = '';

  // 5. ⏱️ Reset accordion to collapsed state (at top)
  const timelineContainer = document.getElementById('admin-review-timeline-container');
  if (timelineContainer) {
    timelineContainer.classList.add('hidden');
    timelineContainer.scrollTop = 0;
  }
  const arrow = document.getElementById('admin-timeline-arrow');
  if (arrow) arrow.style.transform = 'rotate(0deg)';

  // Temporary loading text
  document.getElementById('modal-header-id').textContent = `#RPT-${String(reportId).padStart(4, '0')} (Loading...)`;

  // 6. Fetch report data
  apiFetch(`/api/reports/${reportId}`)
    .then(report => {
      // 🛡️ RACE CONDITION GUARD: Ignore superseded response if user opened another report
      if (currentReviewReportId !== reportId) return;

      const formattedId = `#RPT-${String(report.id).padStart(4, '0')}`;

      currentReviewLat = parseFloat(report.latitude) || 0;
      currentReviewLng = parseFloat(report.longitude) || 0;

      document.getElementById('modal-header-id').textContent = formattedId;
      document.getElementById('modal-report-id').textContent = formattedId;

      // Severity Badge with Confidence
      const rawSev = String(report.severity || '').trim().toLowerCase();
      const confText = report.cvConfidenceScore ? ` (${report.cvConfidenceScore}%)` : '';
      const severityBadge = document.getElementById('modal-severity');

      if (severityBadge) {
        if (rawSev === 'high') {
          severityBadge.textContent = `HIGH${confText}`;
          severityBadge.style.cssText = 'background-color: #dc2626; color: #ffffff; border: 1px solid #b91c1c; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11.5px; display: inline-block; letter-spacing: 0.4px;';
        } else if (rawSev === 'medium') {
          severityBadge.textContent = `MEDIUM${confText}`;
          severityBadge.style.cssText = 'background-color: #f59e0b; color: #ffffff; border: 1px solid #d97706; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11.5px; display: inline-block; letter-spacing: 0.4px;';
        } else if (rawSev === 'low') {
          severityBadge.textContent = `LOW${confText}`;
          severityBadge.style.cssText = 'background-color: #16a34a; color: #ffffff; border: 1px solid #15803d; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11.5px; display: inline-block; letter-spacing: 0.4px;';
        } else {
          severityBadge.textContent = 'UNASSESSED';
          severityBadge.style.cssText = 'background-color: #ecfdf5; color: #047857; border: 1.5px solid #34d399; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11.5px; display: inline-block; letter-spacing: 0.4px;';
        }
      }

      document.getElementById('modal-date').textContent = report.dateSubmitted || 'N/A';
      document.getElementById('modal-gps').textContent = (currentReviewLat !== 0 && currentReviewLng !== 0)
        ? `${currentReviewLat.toFixed(5)}° N, ${currentReviewLng.toFixed(5)}° E`
        : '0° N, 0° E';
      document.getElementById('modal-barangay').textContent = (report.barangay && report.barangay.barangayName) ? report.barangay.barangayName : 'Unknown';

      // Submitter Info
      let submitterText = `Barangay Official (${report.barangay?.barangayName || 'Unknown'})`;
      if (report.user && report.user.firstName && report.user.lastName) {
        submitterText = `${report.user.firstName} ${report.user.lastName} (${report.barangay?.barangayName || 'Unknown'})`;
      } else if (report.reportedBy) {
        submitterText = report.reportedBy;
      }
      const reportByEl = document.getElementById('modal-report-by');
      if (reportByEl) reportByEl.textContent = submitterText;

      // Road Details
      document.getElementById('modal-road-name').textContent = report.cityRoadName || 'N/A';
      document.getElementById('modal-road-id').textContent = report.cityRoadId || 'N/A';
      document.getElementById('modal-importance').textContent = report.roadImportance || 'N/A';
      document.getElementById('modal-terrain').textContent = report.terrainType || 'N/A';
      document.getElementById('modal-road-type').textContent = report.roadType || 'N/A';

      document.getElementById('modal-length').textContent = parseFloat(report.length) || 0;
      document.getElementById('modal-width').textContent = parseFloat(report.width) || 0;
      document.getElementById('modal-culverts').textContent = parseFloat(report.lengthOfCulverts) || 0;
      document.getElementById('modal-bridges').textContent = report.numberOfBridges || 0;

      document.getElementById('modal-damage-type').textContent = report.damageType || 'None';
      document.getElementById('modal-damage-length').textContent = parseFloat(report.damageLength) || 0;
      document.getElementById('modal-damage-width').textContent = parseFloat(report.damageWidth) || 0;

      document.getElementById('modal-description').textContent = report.damageDescription || 'No description provided.';

      // Image Preview
      const placeholderEl = document.getElementById('modal-damage-image');
      if (placeholderEl) placeholderEl.style.display = 'none';
      if (typeof loadSecureImage === 'function') {
        loadSecureImage('modal-damage-image', report.damageImage);
      }

      // 🕒 Load Timeline into the Accordion
      if (typeof loadReportTimeline === 'function') {
        loadReportTimeline(report.id, 'admin-review-timeline');
      }

      // 🚀 Final Scroll Reset after data & timeline injection completes
      resetModalScroll(modal);
    })
    .catch(error => {
      console.error("Error:", error);
      document.getElementById('modal-header-id').textContent = "Database Error!";
    });
}

// ==========================================
// ADMIN DASHBOARD: CLOSE REVIEW MODAL
// ==========================================
window.closeReviewModal = function() {
  closeModal('review-modal');

  const mapContainer = document.getElementById('admin-review-map-container');
  if (mapContainer) mapContainer.style.display = 'none';
};

// ==========================================
// ADMIN DASHBOARD: LOCATE ON MAP BUTTON (SAFE SCOPE)
// ==========================================

// Attach helpers to window to avoid duplicate 'let' declaration errors
window.adminSjdmGeoJsonData = window.adminSjdmGeoJsonData || null;
window.adminBoundaryLayer = window.adminBoundaryLayer || null;
window.adminCsjdmBounds = window.adminCsjdmBounds || null;

// Pre-fetch official CSJDM boundaries once
if (!window.adminSjdmGeoJsonData) {
  fetch('sjdm_barangays.geojson')
    .then(res => res.json())
    .then(data => {
      window.adminSjdmGeoJsonData = data;
    })
    .catch(err => console.error("Could not load sjdm_barangays.geojson for Admin:", err));
}

function toggleAdminReviewMap() {
  const mapContainer = document.getElementById('admin-review-map-container');
  if (!mapContainer) return;

  if (!currentReviewLat || !currentReviewLng || (currentReviewLat === 0 && currentReviewLng === 0)) {
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

    // 1. Initialize Map with strict zoom limits and Google Hybrid layer
    if (!adminReviewMap) {
      adminReviewMap = L.map('admin-review-map', {
        minZoom: 12,             // Stops zooming out past CSJDM scale
        maxZoom: 19,             // Stops over-zooming past satellite resolution
        maxBoundsViscosity: 0.7  // Elastic boundary lock
      });

      // 🛰️ GOOGLE HYBRID SATELLITE (Detailed street names, road lines & landmarks)
      L.tileLayer('https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
        maxZoom: 19,
        subdomains: ['mt0', 'mt1', 'mt2', 'mt3'],
        attribution: '&copy; Google Maps'
      }).addTo(adminReviewMap);

      // 2. Render CSJDM Barangay Boundaries
      if (window.adminSjdmGeoJsonData) {
        window.adminBoundaryLayer = L.geoJSON(window.adminSjdmGeoJsonData, {
          style: {
            color: '#3b82f6',
            weight: 2,
            dashArray: '5, 5',
            fillColor: '#2563eb',
            fillOpacity: 0.05,
            interactive: false
          }
        }).addTo(adminReviewMap);

        window.adminCsjdmBounds = window.adminBoundaryLayer.getBounds();
        adminReviewMap.setMaxBounds(window.adminCsjdmBounds.pad(0.4));
      }

      // 3. Place Report Marker
      adminReviewMarker = L.marker([currentReviewLat, currentReviewLng], { icon: redIcon })
        .addTo(adminReviewMap)
        .bindPopup(`<b>Reported Damage Location</b><br>Lat: ${currentReviewLat.toFixed(5)}<br>Lng: ${currentReviewLng.toFixed(5)}`);

      adminReviewMap.setView([currentReviewLat, currentReviewLng], 17);

    } else {
      // Reuse existing map instance
      adminReviewMap.setView([currentReviewLat, currentReviewLng], 17);

      if (adminReviewMarker) {
        adminReviewMarker.setLatLng([currentReviewLat, currentReviewLng]);
        adminReviewMarker.setIcon(redIcon);
        adminReviewMarker.setPopupContent(`<b>Reported Damage Location</b><br>Lat: ${currentReviewLat.toFixed(5)}<br>Lng: ${currentReviewLng.toFixed(5)}`);
      } else {
        adminReviewMarker = L.marker([currentReviewLat, currentReviewLng], { icon: redIcon }).addTo(adminReviewMap);
      }

      // Apply boundary if GeoJSON finished loading after first toggle
      if (!window.adminBoundaryLayer && window.adminSjdmGeoJsonData) {
        window.adminBoundaryLayer = L.geoJSON(window.adminSjdmGeoJsonData, {
          style: {
            color: '#3b82f6',
            weight: 2,
            dashArray: '5, 5',
            fillColor: '#2563eb',
            fillOpacity: 0.05,
            interactive: false
          }
        }).addTo(adminReviewMap);

        window.adminCsjdmBounds = window.adminBoundaryLayer.getBounds();
        adminReviewMap.setMaxBounds(window.adminCsjdmBounds.pad(0.4));
      }
    }

    setTimeout(() => {
      adminReviewMap.invalidateSize();
    }, 150);

  } else {
    mapContainer.style.display = 'none';
  }
}

window.toggleAdminReviewMap = toggleAdminReviewMap;

const btnLocateMap = document.getElementById('btn-admin-locate-map');
if (btnLocateMap) {
  btnLocateMap.onclick = toggleAdminReviewMap;
}

// ==========================================
// 🎯 REVIEW MODAL ACTION CONTROLLERS (ACCEPT / REJECT)
// ==========================================

// 🛡️ Global mutex lock to prevent concurrent submissions
let isReviewSubmitting = false;

// 1. Show Rejection Form
const btnShowReject = document.getElementById('btn-show-reject');
if (btnShowReject) {
  btnShowReject.onclick = function(e) {
    if (e) e.preventDefault();
    const primaryActions = document.getElementById('primary-actions');
    const rejectForm = document.getElementById('reject-feedback-form');
    const remarksInput = document.getElementById('admin-remarks-input');
    if (primaryActions) primaryActions.classList.add('hidden');
    if (rejectForm) rejectForm.classList.remove('hidden');
    if (remarksInput) remarksInput.focus();
  };
}

// 2. Cancel Rejection
const btnCancelReject = document.getElementById('btn-cancel-reject');
if (btnCancelReject) {
  btnCancelReject.onclick = function(e) {
    if (e) e.preventDefault();
    const primaryActions = document.getElementById('primary-actions');
    const rejectForm = document.getElementById('reject-feedback-form');
    const remarksInput = document.getElementById('admin-remarks-input');
    if (rejectForm) rejectForm.classList.add('hidden');
    if (primaryActions) primaryActions.classList.remove('hidden');
    if (remarksInput) remarksInput.value = '';
  };
}

// 3. Confirm Rejection (Attaches userId for audit trail)
const btnConfirmReject = document.getElementById('btn-confirm-reject');
if (btnConfirmReject) {
  btnConfirmReject.onclick = function(e) {
    if (e) {
      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();
    }

    // 🛡️ BLOCK DUPLICATE INVOCATION
    if (!currentReviewReportId || isReviewSubmitting) return;

    const remarksInput = document.getElementById('admin-remarks-input');
    const remarks = remarksInput ? remarksInput.value.trim() : '';

    if (!remarks) {
      showToast("Please provide a reason for rejecting this report.", "error");
      return;
    }

    isReviewSubmitting = true;
    const targetReportId = currentReviewReportId;
    const originalText = btnConfirmReject.innerHTML;

    btnConfirmReject.innerHTML = "⏳ Submitting...";
    btnConfirmReject.disabled = true;
    btnConfirmReject.style.pointerEvents = "none";

    const currentUserId = sessionStorage.getItem("userId");

    fetch(`${API_BASE_URL}/api/reports/${targetReportId}/status`, {
      method: 'PUT',
      headers: {
        ...getAuthHeaders(),
        'ngrok-skip-browser-warning': 'true'
      },
      body: JSON.stringify({
        status: "Rejected",
        adminRemarks: remarks,
        userId: currentUserId
      })
    })
      .then(res => {
        if (!res.ok) throw new Error("Failed to reject report");
        return res.text();
      })
      .then(() => {
        if (typeof showToast === 'function') {
          showToast("Report rejected and returned to Barangay official.", "success");
        }
        currentReviewReportId = null;
        closeReviewModal();
        if (typeof loadAdminReports === 'function') loadAdminReports();
        if (typeof loadAdminDashboardData === 'function') loadAdminDashboardData();
      })
      .catch(err => {
        console.error(err);
        if (typeof showToast === 'function') showToast("Error rejecting report.", "error");
      })
      .finally(() => {
        isReviewSubmitting = false;
        btnConfirmReject.innerHTML = originalText;
        btnConfirmReject.disabled = false;
        btnConfirmReject.style.pointerEvents = "auto";
      });
  };
}

// 4. Accept & Validate (Attaches userId for audit trail)
const btnAcceptValidate = document.getElementById('btn-accept-validate');
if (btnAcceptValidate) {
  btnAcceptValidate.onclick = function(e) {
    if (e) {
      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();
    }

    // 🛡️ BLOCK DUPLICATE INVOCATION
    if (!currentReviewReportId || isReviewSubmitting) return;

    isReviewSubmitting = true;
    const targetReportId = currentReviewReportId;
    const originalText = btnAcceptValidate.innerHTML;

    btnAcceptValidate.innerHTML = "⏳ Validating...";
    btnAcceptValidate.disabled = true;
    btnAcceptValidate.style.pointerEvents = "none";

    const currentUserId = sessionStorage.getItem("userId");

    fetch(`${API_BASE_URL}/api/reports/${targetReportId}/status`, {
      method: 'PUT',
      headers: {
        ...getAuthHeaders(),
        'ngrok-skip-browser-warning': 'true'
      },
      body: JSON.stringify({
        status: "Validated",
        userId: currentUserId
      })
    })
      .then(res => {
        if (!res.ok) throw new Error("Failed to validate report");
        return res.text();
      })
      .then(() => {
        if (typeof showToast === 'function') {
          showToast("Report validated!", "success");
        }
        currentReviewReportId = null;
        closeReviewModal();
        if (typeof loadAdminReports === 'function') loadAdminReports();
        if (typeof loadAdminDashboardData === 'function') loadAdminDashboardData();
      })
      .catch(err => {
        console.error(err);
        if (typeof showToast === 'function') showToast("Error validating report.", "error");
      })
      .finally(() => {
        isReviewSubmitting = false;
        btnAcceptValidate.innerHTML = originalText;
        btnAcceptValidate.disabled = false;
        btnAcceptValidate.style.pointerEvents = "auto";
      });
  };
}


// ==========================================
// 🚀 CPDO ADMIN DASHBOARD: DATA ENGINE & ANALYTICS
// ==========================================

document.addEventListener("DOMContentLoaded", () => {
  if (document.getElementById('fresh-admin-queue-body') || document.getElementById('adminComplianceChart')) {
    loadAdminDashboardData();
  }
});

export function loadAdminDashboardData() {
  // 1. Concurrently fetch all reports and the live database road registry
  Promise.all([
    apiFetch(`/api/reports`, { cache: 'no-store' }).catch(err => {
      console.error("🚨 [Reports API Error]:", err);
      return [];
    }),
    // Query roads endpoint with automatic fallback to guarantee real DB count
    apiFetch(`/api/roads`, { cache: 'no-store' })
      .catch(() => apiFetch(`/api/city-roads`, { cache: 'no-store' }))
      .catch(err => {
        console.error("🚨 [Roads API Error]:", err);
        return [];
      })
  ])
    .then(([reports, roads]) => {
      const allReports = Array.isArray(reports) ? reports : [];
      const allRoads = Array.isArray(roads) ? roads : [];

      // =========================================================================
      // 🚀 2. CYCLE & ARCHIVE FILTER: ACTIVE CYCLE ONLY
      // =========================================================================
      const currentCycleYear = String(new Date().getFullYear());

      const activeCycleReports = allReports.filter(r => {
        const s = String(r.status || '').trim().toLowerCase();

        // 🚫 Exclude any archived records
        if (s.includes('archiv')) return false;

        // Extract report year from field or timestamp
        const yearVal = r.inventory_year || r.inventoryYear;
        const rawDate = r.dateSubmitted || r.date_submitted || r.createdAt || r.created_at;
        let reportYear = "";

        if (yearVal && String(yearVal).trim() !== "" && String(yearVal).toLowerCase() !== "null") {
          reportYear = String(yearVal).trim();
        } else if (rawDate) {
          const parsed = new Date(rawDate);
          if (!isNaN(parsed.getTime())) reportYear = String(parsed.getFullYear());
        }

        // If from a past year and already terminal/validated, exclude from current active cycle
        if (reportYear && reportYear !== currentCycleYear) {
          if (s === 'validated' || s === 'closed' || s === 'resolved' || s.includes('reject')) {
            return false;
          }
        }

        return true;
      });

      // =========================================================================
      // 3. CATEGORICAL METRICS (STRICTLY ACTIVE REPORTS)
      // =========================================================================
      const pendingReports = activeCycleReports.filter(r => {
        const s = String(r.status || '').trim().toLowerCase();
        return s === 'pending validation' || s === 'resubmit' || s === 'resubmitted';
      });

      const validatedReports = activeCycleReports.filter(r =>
        String(r.status || '').trim().toLowerCase() === 'validated'
      );

      const criticalReports = activeCycleReports.filter(r => {
        const s = String(r.status || '').trim().toLowerCase();
        return String(r.severity || '').trim().toLowerCase() === 'high' && s !== 'closed' && s !== 'resolved';
      });

      const dispatchedReports = activeCycleReports.filter(r => {
        const s = String(r.status || '').trim().toLowerCase();
        return s === 'dispatched to ceo' || s === 'in progress';
      });

      const completedQAReports = activeCycleReports.filter(r =>
        String(r.status || '').trim().toLowerCase() === 'completed'
      );

      const deferredReports = activeCycleReports.filter(r =>
        String(r.status || '').trim().toLowerCase() === 'pending budget'
      );

      // =========================================================================
      // 4. REAL DATABASE ROAD COUNT & QUOTA COVERAGE
      // =========================================================================
      // Deduplicate unique active roads inspected this cycle
      const uniqueInspectedRoads = new Set(
        activeCycleReports
          .map(r => String(r.cityRoadName || '').trim().toLowerCase())
          .filter(name => name !== '')
      ).size;

      // Exact total road count directly from the database query
      const totalCityRoads = allRoads.length > 0 ? allRoads.length : 0;

      let quotaPercentage = totalCityRoads > 0 ? Math.round((uniqueInspectedRoads / totalCityRoads) * 100) : 0;
      if (quotaPercentage > 100) quotaPercentage = 100;

      // =========================================================================
      // 5. INJECT PROGRESS BANNER DATA
      // =========================================================================
      const progressText = document.getElementById('admin-quota-text');
      const progressPercent = document.getElementById('admin-metric-quota');
      const barFill = document.getElementById('admin-progress-bar-fill');

      if (progressText && progressPercent && barFill) {
        progressText.innerHTML = `<strong>${uniqueInspectedRoads}</strong> of <strong>${totalCityRoads}</strong> total city roads inspected across all barangays.`;
        progressPercent.innerText = `${quotaPercentage}%`;
        barFill.style.width = `${quotaPercentage}%`;

        if (quotaPercentage === 100) {
          barFill.style.background = 'linear-gradient(90deg, #16a34a, #22c55e)';
          progressPercent.style.color = '#16a34a';
        } else {
          barFill.style.background = 'linear-gradient(90deg, #0b2545, #1e40af)';
          progressPercent.style.color = '#0b2545';
        }
      }

      // =========================================================================
      // 6. UPDATE DYNAMIC CEO BUDGET DEFERRAL ALERT BANNER
      // =========================================================================
      const deferralBanner = document.getElementById('admin-deferral-banner');
      const deferralBannerText = document.getElementById('admin-deferral-banner-text');
      if (deferralBanner) {
        if (deferredReports.length > 0) {
          deferralBanner.style.display = 'flex';
          if (deferralBannerText) {
            deferralBannerText.innerHTML = `The City Engineering Office has deferred <strong>${deferredReports.length} road repair projects</strong> due to budget constraints. Review remarks and batch-archive to the fiscal backlog.`;
          }
        } else {
          deferralBanner.style.display = 'none';
        }
      }

      // =========================================================================
      // 7. INJECT 6-CARD KPI VALUES
      // =========================================================================
      if (document.getElementById('admin-metric-pending')) document.getElementById('admin-metric-pending').innerText = pendingReports.length;
      if (document.getElementById('admin-metric-critical')) document.getElementById('admin-metric-critical').innerText = criticalReports.length;
      if (document.getElementById('admin-metric-validated')) document.getElementById('admin-metric-validated').innerText = validatedReports.length;
      if (document.getElementById('admin-metric-dispatched')) document.getElementById('admin-metric-dispatched').innerText = dispatchedReports.length;
      if (document.getElementById('admin-metric-qa')) document.getElementById('admin-metric-qa').innerText = completedQAReports.length;
      if (document.getElementById('admin-metric-deferred')) document.getElementById('admin-metric-deferred').innerText = deferredReports.length;

      // =========================================================================
      // 8. ACTION QUEUE & CHARTS (SCOPED TO ACTIVE CYCLE)
      // =========================================================================
      if (typeof renderAdminActionQueue === 'function') {
        renderAdminActionQueue(pendingReports);
      }

      if (typeof renderRealAdminCharts === 'function') {
        renderRealAdminCharts(activeCycleReports);
      }
    })
    .catch(err => {
      console.error("🚨 Error loading Admin Dashboard data:", err);
    });
}

// ==========================================
// 📋 ACTION QUEUE TABLE GENERATOR
// ==========================================
function renderAdminActionQueue(pendingReports) {
  const queueBody = document.getElementById('fresh-admin-queue-body');
  if (!queueBody) return;

  queueBody.innerHTML = '';

  // Sort: Priority to oldest submissions so they don't get delayed
  pendingReports.sort((a, b) => {
    const dateA = new Date(a.date_submitted || a.dateSubmitted || a.createdAt || 0);
    const dateB = new Date(b.date_submitted || b.dateSubmitted || b.createdAt || 0);
    return dateA - dateB;
  });

  const topPending = pendingReports.slice(0, 6);

  if (topPending.length === 0) {
    queueBody.innerHTML = `
      <tr>
        <td colspan="6" style="text-align: center; color: #64748b; padding: 35px; font-style: italic;">
          ✅ All caught up! No pending inspections requiring validation audit.
        </td>
      </tr>`;
    return;
  }

  topPending.forEach(report => {
    const formatId = `#PRJ-${String(report.id).padStart(4, '0')}`;
    const formatBrgy = (report.barangay && report.barangay.barangayName) ? report.barangay.barangayName : 'Unknown';
    const roadName = report.cityRoadName || 'Unnamed Road Segment';
    const damageType = report.damageType || 'Road Assessment';

    const rawDate = report.date_submitted || report.dateSubmitted || report.createdAt;
    const dateObj = rawDate ? new Date(rawDate) : new Date();
    const dateStr = dateObj.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });

    // Relative Aging calculation
    const diffTime = Math.abs(new Date() - dateObj);
    const diffDays = Math.floor(diffTime / (1000 * 60 * 60 * 24));
    let ageTag = `<span style="font-size: 11px; color: #64748b;">${diffDays === 0 ? 'Today' : `${diffDays}d ago`}</span>`;
    if (diffDays >= 14) {
      ageTag = `<span style="font-size: 11px; color: #dc2626; font-weight: 700;">⚠️ ${diffDays}d ago</span>`;
    }

    // Unified AI Severity Badge Styles (Matching CEO & Barangay)
    const sev = String(report.severity || 'Unassessed').trim();

// Default: Mint green UNASSESSED badge
    let badgeBg = '#ecfdf5';
    let badgeColor = '#047857';
    let badgeBorder = '1px solid #34d399';
    let badgeLabel = 'UNASSESSED';

    if (sev.toLowerCase() === 'high') {
      badgeBg = '#dc2626';
      badgeColor = '#ffffff';
      badgeBorder = '1px solid #dc2626';
      badgeLabel = 'HIGH';
    } else if (sev.toLowerCase() === 'medium') {
      badgeBg = '#ffc107';
      badgeColor = '#000000';
      badgeBorder = '1px solid #eab308';
      badgeLabel = 'MEDIUM';
    } else if (sev.toLowerCase() === 'low') {
      badgeBg = '#16a34a';
      badgeColor = '#ffffff';
      badgeBorder = '1px solid #16a34a';
      badgeLabel = 'LOW';
    }

    const tr = document.createElement('tr');
    tr.style.borderBottom = "1px solid #f1f5f9";
    tr.style.transition = "background-color 0.15s";
    tr.onmouseover = () => tr.style.backgroundColor = "#f8fafc";
    tr.onmouseout = () => tr.style.backgroundColor = "transparent";

    tr.innerHTML = `
      <td style="padding: 12px 15px; font-family: monospace; font-weight: 800; color: #0b2545;">${formatId}</td>
      <td style="padding: 12px 15px;">
        <div style="font-weight: 700; color: #0f172a;">${roadName}</div>
        <div style="font-size: 11.5px; color: #64748b;">🛠️ ${damageType}</div>
      </td>
      <td style="padding: 12px 15px; color: #475569;">${formatBrgy}</td>
      <td style="padding: 12px 15px; text-align: center;">
  <span style="background-color: ${badgeBg}; color: ${badgeColor}; border: ${badgeBorder}; padding: 3px 9px; border-radius: 4px; font-size: 10.5px; font-weight: 800; display: inline-block; letter-spacing: 0.3px; white-space: nowrap;">
    ${badgeLabel}
  </span>
</td>
      <td style="padding: 12px 15px; white-space: nowrap;">
        <div>${dateStr}</div>
        <div>${ageTag}</div>
      </td>
      <td style="padding: 12px 15px; text-align: center;">
        <button onclick="jumpToReportsAndReview(${report.id})" class="ad-btn-review-action">Review</button>
      </td>
    `;
    queueBody.appendChild(tr);
  });
}

// ==========================================
// 📊 CHARTS ENGINE (FAIL-SAFE)
// ==========================================
function renderRealAdminCharts(reports) {
  if (typeof Chart === 'undefined') {
    console.warn("Notice: Chart.js not loaded. Skipping chart generation.");
    return;
  }

  // --- Chart 1: Severity Breakdown ---
  let high = 0, med = 0, low = 0, unassessed = 0;
  reports.forEach(r => {
    const sev = (r.severity || '').toLowerCase();
    if (sev === 'high') high++;
    else if (sev === 'medium') med++;
    else if (sev === 'low') low++;
    else unassessed++;
  });

  const ctxDoughnut = document.getElementById('adminSeverityChart');
  if (ctxDoughnut) {
    let existingDoughnut = Chart.getChart(ctxDoughnut);
    if (existingDoughnut) existingDoughnut.destroy();

    new Chart(ctxDoughnut.getContext('2d'), {
      type: 'doughnut',
      data: {
        labels: ['High', 'Medium', 'Low', 'Pending AI'],
        datasets: [{
          data: [high, med, low, unassessed],
          backgroundColor: ['#dc2626', '#f59e0b', '#16a34a', '#64748b'],
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
            labels: { boxWidth: 10, font: { size: 10.5, weight: '600' }, padding: 10 }
          }
        },
        cutout: '70%'
      }
    });
  }

  // --- Chart 2: Barangay Compliance (Preserves "Pending Assignment" intact) ---
  const brgyData = {};
  reports.forEach(r => {
    const brgyName = (r.barangay && r.barangay.barangayName) ? r.barangay.barangayName : 'Unknown';
    if (!brgyData[brgyName]) brgyData[brgyName] = new Set();
    if (r.cityRoadName) brgyData[brgyName].add(r.cityRoadName);
  });

  const brgyLabels = [];
  const brgyCounts = [];
  Object.entries(brgyData)
    .sort((a, b) => b[1].size - a[1].size)
    .slice(0, 6)
    .forEach(([name, roadSet]) => {
      brgyLabels.push(name);
      brgyCounts.push(roadSet.size);
    });

  const ctxBar = document.getElementById('adminComplianceChart');
  if (ctxBar && brgyLabels.length > 0) {
    let existingBar = Chart.getChart(ctxBar);
    if (existingBar) existingBar.destroy();

    new Chart(ctxBar.getContext('2d'), {
      type: 'bar',
      data: {
        labels: brgyLabels,
        datasets: [{
          label: 'Unique Roads Inspected',
          data: brgyCounts,
          backgroundColor: '#0b2545',
          borderRadius: 5
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false }
        },
        scales: {
          y: {
            beginAtZero: true,
            ticks: { stepSize: 1 }
          }
        }
      }
    });
  }
}

// ==========================================
// 🚀 TAB NAVIGATION HELPERS
// ==========================================
function jumpToReportsAndReview(reportId) {
  const reportsTabBtn = document.querySelector('.nav-menu li[data-target="view-reports"]') ||
    document.querySelector('li[data-target="view-reports"]');

  if (reportsTabBtn) {
    reportsTabBtn.click();
  } else if (typeof switchView === 'function') {
    switchView('view-reports');
  }

  setTimeout(() => {
    if (typeof reviewReport === 'function') {
      reviewReport(reportId);
    } else {
      console.warn("reviewReport function not found.");
    }
  }, 150);
}

function jumpToAllReports() {
  const reportsTabBtn = document.querySelector('.nav-menu li[data-target="view-reports"]') ||
    document.querySelector('li[data-target="view-reports"]');
  if (reportsTabBtn) {
    reportsTabBtn.click();
  } else if (typeof switchView === 'function') {
    switchView(' ');
  }
}

function jumpToArchiveTab() {
  const trackingTabBtn = document.querySelector('.nav-menu li[data-target="view-tracking"]') ||
    document.querySelector('li[data-target="view-tracking"]');
  if (trackingTabBtn) {
    trackingTabBtn.click();
  } else if (typeof switchView === 'function') {
    switchView('view-tracking');
  }
}

// Window Compatibility Bridges
window.reviewReport = reviewReport;
window.loadAdminReports = loadAdminReports;
window.loadAdminDashboardData = loadAdminDashboardData;
window.jumpToReportsAndReview = jumpToReportsAndReview;
window.jumpToAllReports = jumpToAllReports;
window.jumpToArchiveTab = jumpToArchiveTab;
