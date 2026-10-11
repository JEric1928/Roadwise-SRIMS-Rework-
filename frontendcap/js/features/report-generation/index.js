/**
 * Report generation & live preview engine plus the batch archive helpers.
 *
 * Extracted verbatim from app.js. Exposes the same window.* functions the
 * dashboard templates call from inline handlers. The active tracking record
 * helper reads window.currentFilteredTrackedReports, so it stays compatible
 * with the tracking engine that remains in app.js.
 */
import { apiFetch, API_BASE_URL } from '../../core/api.js';
import { showToast } from '../../components/toast.js';
import { formatOfficialName } from '../../core/utils.js';

// ==========================================
// 📊 REPORT GENERATION & LIVE PREVIEW ENGINE
// ==========================================

// Helper: Official Name Formatter (First M.I. Last)

// Helper: Safely get active records, with auto-fetch fallback
async function getActiveTrackingRecords() {
  if (Array.isArray(window.currentFilteredTrackedReports) && window.currentFilteredTrackedReports.length > 0) {
    return window.currentFilteredTrackedReports;
  }
  if (Array.isArray(window.rawTrackedReports) && window.rawTrackedReports.length > 0) {
    return window.rawTrackedReports;
  }

  try {
    const reports = await apiFetch('/api/reports');
    if (Array.isArray(reports)) {
      const tracked = reports.filter(r => {
        const s = String(r.status || '').toLowerCase().trim();
        return s === 'dispatched to ceo' ||
          s === 'in progress' ||
          s === 'completed' ||
          s === 'pending budget' ||
          s === 'closed' ||
          s === 'archived';
      });
      window.rawTrackedReports = tracked;
      window.currentFilteredTrackedReports = tracked;
      return tracked;
    }
  } catch (err) {
    console.error("Failed to auto-fetch tracking records for report:", err);
  }

  return [];
}

// ==========================================
// 1. OPEN REPORT PREVIEW MODAL (WITH OFFICIAL DUAL SIGNATURE FORMAT)
// ==========================================
window.openTrackingReportPreview = async function() {
  const reports = await getActiveTrackingRecords();
  const modal = document.getElementById('tracking-report-preview-modal');
  const sheet = document.getElementById('tracking-printable-sheet');

  if (!reports || reports.length === 0) {
    if (typeof showToast === 'function') showToast("No tracking records available to generate report.", "warning");
    return;
  }

  if (!modal || !sheet) return;

  // 🚀 1. DYNAMICALLY FETCH REAL CPDO ADMIN & CITY ENGINEER NAMES (WITH M.I.)
  let adminName = 'CPDO Administrator';
  let ceoName = 'City Engineer';

  try {
    // A. Resolve Logged-in Admin from Session/Storage
    const sFirst = sessionStorage.getItem('firstName') || '';
    const sMiddle = sessionStorage.getItem('middleName') || '';
    const sLast = sessionStorage.getItem('lastName') || '';

    if (sFirst || sLast) {
      adminName = formatOfficialName(sFirst, sMiddle, sLast);
    } else {
      const localUser = JSON.parse(localStorage.getItem('user') || localStorage.getItem('currentUser') || '{}');
      if (localUser.firstName || localUser.lastName) {
        adminName = formatOfficialName(localUser.firstName, localUser.middleName, localUser.lastName);
      }
    }

    // B. Fetch Users from Database to resolve Role: "ENGINEER" and fallback Admin
    let users = [];
    if (typeof apiFetch === 'function') {
      users = await apiFetch('/api/users').catch(() => []);
    } else if (typeof API_BASE_URL !== 'undefined') {
      const res = await fetch(`${API_BASE_URL}/api/users`, {
        headers: { 'ngrok-skip-browser-warning': 'true' }
      }).catch(() => null);
      if (res && res.ok) users = await res.json();
    }

    if (Array.isArray(users) && users.length > 0) {
      // Find City Engineer
      const engineerUser = users.find(u => {
        const role = String(u.role || '').toUpperCase().trim();
        const status = String(u.status || '').toLowerCase().trim();
        return (role === 'ENGINEER' || role.includes('CEO')) && status !== 'deactivated';
      });

      if (engineerUser) {
        ceoName = formatOfficialName(
          engineerUser.firstName,
          engineerUser.middleName,
          engineerUser.lastName,
          'Engr.'
        );
      }

      // Fallback for Admin Name if not found in session
      if (!adminName || adminName === 'CPDO Administrator') {
        const adminUser = users.find(u => {
          const role = String(u.role || '').toUpperCase().trim();
          const status = String(u.status || '').toLowerCase().trim();
          return (role === 'ADMIN' || role.includes('CPDO')) && status !== 'deactivated';
        });

        if (adminUser) {
          adminName = formatOfficialName(adminUser.firstName, adminUser.middleName, adminUser.lastName);
        }
      }
    }
  } catch (err) {
    console.warn("Could not fetch signatories from database, using defaults:", err);
  }

  // 2. TIMESTAMPS
  const currentDate = new Date().toLocaleDateString('en-US', {
    timeZone: 'Asia/Manila',
    month: 'long',
    day: 'numeric',
    year: 'numeric'
  });

  const currentTime = new Date().toLocaleTimeString('en-US', {
    timeZone: 'Asia/Manila',
    hour: '2-digit',
    minute: '2-digit'
  });

  // 3. KPI CALCULATIONS
  const inProgressCount = reports.filter(r => String(r.status || '').toLowerCase() === 'in progress').length;
  const completedCount = reports.filter(r => String(r.status || '').toLowerCase() === 'completed').length;
  const pendingBudgetCount = reports.filter(r => String(r.status || '').toLowerCase() === 'pending budget').length;
  const closedCount = reports.filter(r => String(r.status || '').toLowerCase() === 'closed').length;

  // 4. BUILD TABLE ROWS
  let tableRowsHtml = '';
  reports.forEach((r, index) => {
    const formattedId = `#PRJ-${String(r.id).padStart(4, '0')}`;
    const brgy = r.barangay?.barangayName || (typeof r.barangay === 'string' ? r.barangay : 'Pending Assignment');
    const road = r.cityRoadName || 'Unknown Road';
    const currentStatus = String(r.status || 'Dispatched').toUpperCase();
    const remarks = r.repairRemarks || r.adminRemarks || 'No remarks documented.';

    const rawPriority = String(r.priorityLevel || r.priority || r.severity || '').toUpperCase().trim();

    let priorityBadge = '';
    if (rawPriority === 'HIGH') {
      priorityBadge = '<span style="background-color: #ef4444; color: #ffffff !important; padding: 3px 8px; border-radius: 4px; font-size: 11px; font-weight: 700; display: inline-block;">HIGH</span>';
    } else if (rawPriority === 'MEDIUM') {
      priorityBadge = '<span style="background-color: #f59e0b; color: #ffffff !important; padding: 3px 8px; border-radius: 4px; font-size: 11px; font-weight: 700; display: inline-block;">MEDIUM</span>';
    } else if (rawPriority === 'LOW') {
      priorityBadge = '<span style="background-color: #22c55e; color: #ffffff !important; padding: 3px 8px; border-radius: 4px; font-size: 11px; font-weight: 700; display: inline-block;">LOW</span>';
    } else {
      priorityBadge = '<span style="background-color: #dcfce7; color: #15803d !important; border: 1px solid #86efac; padding: 3px 8px; border-radius: 4px; font-size: 11px; font-weight: 700; display: inline-block;">UNASSESSED</span>';
    }

    tableRowsHtml += `
      <tr style="border-bottom: 1px solid #e2e8f0; font-size: 11.5px; background: #ffffff;">
        <td style="padding: 10px 8px; text-align: center; color: #64748b;">${index + 1}</td>
        <td style="padding: 10px 8px; font-weight: 700; font-family: monospace; color: #1e3a8a;">${formattedId}</td>
        <td style="padding: 10px 8px; color: #0f172a; font-weight: 700;">${brgy}</td>
        <td style="padding: 10px 8px; color: #334155;">${road}</td>
        <td style="padding: 10px 8px; text-align: center;">${priorityBadge}</td>
        <td style="padding: 10px 8px; text-align: center; font-weight: 700; color: #0f172a;">${currentStatus}</td>
        <td style="padding: 10px 8px; color: #475569; font-size: 11px;">${remarks}</td>
      </tr>
    `;
  });

  // 5. RENDER PRINTABLE SHEET
  sheet.innerHTML = `
    <!-- Letterhead -->
    <div style="border-bottom: 2px solid #1e3a8a; padding-bottom: 14px; margin-bottom: 18px; display: flex; justify-content: space-between; align-items: flex-end; background: #ffffff;">
      <div>
        <div style="font-size: 10.5px; color: #64748b; text-transform: uppercase; font-weight: 700; letter-spacing: 0.5px;">
          Republic of the Philippines • Province of Bulacan
        </div>
        <div style="font-size: 18px; font-weight: 800; color: #1e3a8a; margin-top: 2px;">
          CITY PLANNING & DEVELOPMENT OFFICE (CPDO)
        </div>
        <div style="font-size: 13px; font-weight: 600; color: #334155;">
          Active Road Damage Repair & Infrastructure Accomplishment Matrix
        </div>
      </div>
      <div style="text-align: right; font-size: 11px; color: #475569; line-height: 1.4;">
        <strong>Generated:</strong> ${currentDate} (${currentTime} PHT)<br>
        <strong>Scope:</strong> ${reports.length} Project Record(s) Listed
      </div>
    </div>

    <!-- KPI Summary Grid -->
    <div style="display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 22px; background: #ffffff;">
      <div style="background: #f8fafc; border: 1px solid #cbd5e1; border-radius: 6px; padding: 10px; text-align: center;">
        <strong style="font-size: 18px; color: #1e3a8a; display: block;">${inProgressCount}</strong>
        <span style="font-size: 10px; color: #64748b; text-transform: uppercase; font-weight: 700;">In Progress</span>
      </div>
      <div style="background: #f8fafc; border: 1px solid #cbd5e1; border-radius: 6px; padding: 10px; text-align: center;">
        <strong style="font-size: 18px; color: #16a34a; display: block;">${completedCount}</strong>
        <span style="font-size: 10px; color: #64748b; text-transform: uppercase; font-weight: 700;">Completed (QA)</span>
      </div>
      <div style="background: #f8fafc; border: 1px solid #cbd5e1; border-radius: 6px; padding: 10px; text-align: center;">
        <strong style="font-size: 18px; color: #d97706; display: block;">${pendingBudgetCount}</strong>
        <span style="font-size: 10px; color: #64748b; text-transform: uppercase; font-weight: 700;">Pending Budget</span>
      </div>
      <div style="background: #f8fafc; border: 1px solid #cbd5e1; border-radius: 6px; padding: 10px; text-align: center;">
        <strong style="font-size: 18px; color: #475569; display: block;">${closedCount}</strong>
        <span style="font-size: 10px; color: #64748b; text-transform: uppercase; font-weight: 700;">Verified Closed</span>
      </div>
    </div>

    <!-- Data Table -->
    <table style="width: 100%; border-collapse: collapse; margin-bottom: 35px; background: #ffffff;">
      <thead>
        <tr style="background: #f1f5f9; border-top: 1px solid #cbd5e1; border-bottom: 2px solid #cbd5e1;">
          <th style="padding: 8px; width: 30px; text-align: center; font-size: 10px; color: #334155; text-transform: uppercase;">#</th>
          <th style="padding: 8px; width: 90px; text-align: left; font-size: 10px; color: #334155; text-transform: uppercase;">Project ID</th>
          <th style="padding: 8px; width: 140px; text-align: left; font-size: 10px; color: #334155; text-transform: uppercase;">Barangay</th>
          <th style="padding: 8px; width: 130px; text-align: left; font-size: 10px; color: #334155; text-transform: uppercase;">City Road Name</th>
          <th style="padding: 8px; width: 100px; text-align: center; font-size: 10px; color: #334155; text-transform: uppercase;">Priority</th>
          <th style="padding: 8px; width: 130px; text-align: center; font-size: 10px; color: #334155; text-transform: uppercase;">Current Status</th>
          <th style="padding: 8px; width: 220px; text-align: left; font-size: 10px; color: #334155; text-transform: uppercase;">Engineering / QA Remarks</th>
        </tr>
      </thead>
      <tbody>
        ${tableRowsHtml}
      </tbody>
    </table>

    <!-- ✍️ DUAL SIGNATURE BLOCK -->
    <div style="display: flex; justify-content: space-between; margin-top: 45px; padding-top: 15px; page-break-inside: avoid; background: #ffffff;">
      <!-- Prepared By (CPDO Admin) -->
      <div style="text-align: center; width: 260px;">
        <p style="margin: 0 0 6px 0; font-size: 11.5px; color: #475569; text-align: left; font-weight: 600;">Prepared & Generated By:</p>
        <div style="border-bottom: 1.5px solid #0f172a; height: 35px; margin-bottom: 6px;"></div>
        <strong style="text-transform: uppercase; font-size: 12.5px; color: #0f172a; display: block; letter-spacing: 0.3px;">${adminName}</strong>
        <span style="font-size: 10.5px; color: #64748b; font-style: italic; display: block;">(Signature over Printed Name)</span>
        <span style="font-size: 11px; color: #334155; font-weight: 600; display: block; margin-top: 3px;">CPDO Lead Administrator</span>
        <span style="font-size: 10px; color: #64748b; display: block;">City Planning & Development Office</span>
      </div>

      <!-- Approved / Noted By (City Engineer) -->
      <div style="text-align: center; width: 260px;">
        <p style="margin: 0 0 6px 0; font-size: 11.5px; color: #475569; text-align: left; font-weight: 600;">Noted & Acknowledged By:</p>
        <div style="border-bottom: 1.5px solid #0f172a; height: 35px; margin-bottom: 6px;"></div>
        <strong style="text-transform: uppercase; font-size: 12.5px; color: #0f172a; display: block; letter-spacing: 0.3px;">${ceoName}</strong>
        <span style="font-size: 10.5px; color: #64748b; font-style: italic; display: block;">(Signature over Printed Name)</span>
        <span style="font-size: 11px; color: #334155; font-weight: 600; display: block; margin-top: 3px;">City Engineer</span>
        <span style="font-size: 10px; color: #64748b; display: block;">City Engineering Office (CEO)</span>
      </div>
    </div>
  `;

  modal.classList.remove('hidden');
  modal.style.display = 'flex';
  const previewBody = document.getElementById('tracking-report-preview-body');
  if (previewBody) previewBody.scrollTop = 0;
};

// ==========================================
// 2. UNIFIED PRINT TRIGGER (SUPPRESS TITLE HEADER)
// ==========================================
window.executeTrackingPrint = function() {
  const originalTitle = document.title;
  document.title = " ";
  window.print();
  setTimeout(() => {
    document.title = originalTitle;
  }, 1000);
};

// Helper: Close Preview Modal cleanly
window.closeTrackingReportPreview = function() {
  const modal = document.getElementById('tracking-report-preview-modal');
  if (modal) {
    modal.classList.add('hidden');
    modal.style.display = 'none';
  }
};

// ==========================================
// 3. EXPORT CSV ENGINE
// ==========================================
window.exportTrackingMatrixCSV = async function() {
  const reports = await getActiveTrackingRecords();

  if (!reports || reports.length === 0) {
    if (typeof showToast === 'function') showToast("No tracking records available to export.", "warning");
    return;
  }

  const headers = [
    "Project ID",
    "Barangay Jurisdiction",
    "City Road Name",
    "Damage Type",
    "Severity",
    "Road Importance",
    "Current Status",
    "Admin Remarks",
    "CEO Remarks",
    "Date Dispatched/Reported"
  ];

  const csvRows = [];
  csvRows.push(headers.join(","));

  reports.forEach(r => {
    const formattedId = `PRJ-${String(r.id).padStart(4, '0')}`;
    const brgy = (r.barangay?.barangayName || (typeof r.barangay === 'string' ? r.barangay : 'Unassigned')).replace(/"/g, '""');
    const road = (r.cityRoadName || 'Unknown Road').replace(/"/g, '""');
    const damageType = (r.damageType || 'Road Damage').replace(/"/g, '""');
    const severity = (r.severity || 'Low').replace(/"/g, '""');
    const importance = (r.roadImportance || 'Standard').replace(/"/g, '""');
    const status = (r.status || 'Dispatched').replace(/"/g, '""');
    const adminRemarks = (r.adminRemarks || '').replace(/"/g, '""');
    const ceoRemarks = (r.repairRemarks || '').replace(/"/g, '""');
    const date = r.dateReported || r.createdAt || '2026';

    const row = [
      `"${formattedId}"`,
      `"${brgy}"`,
      `"${road}"`,
      `"${damageType}"`,
      `"${severity}"`,
      `"${importance}"`,
      `"${status}"`,
      `"${adminRemarks}"`,
      `"${ceoRemarks}"`,
      `"${date}"`
    ];

    csvRows.push(row.join(","));
  });

  const csvContent = "\uFEFF" + csvRows.join("\n");
  const blob = new Blob([csvContent], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);

  const currentDate = new Date().toISOString().split('T')[0];
  const downloadLink = document.createElement("a");
  downloadLink.setAttribute("href", url);
  downloadLink.setAttribute("download", `RoadWise_Repair_Accomplishment_${currentDate}.csv`);
  document.body.appendChild(downloadLink);
  downloadLink.click();
  document.body.removeChild(downloadLink);

  if (typeof showToast === 'function') showToast("Repair Tracking CSV exported successfully!", "success");
};

// ==========================================
// 4. ATTACH DROPDOWN CLICK LISTENERS
// ==========================================
document.addEventListener('DOMContentLoaded', () => {
  const btnTrackGenerate = document.getElementById('btn-track-generate');
  const trackDropdown = document.getElementById('track-print-dropdown');
  const btnPrintSummary = document.getElementById('btn-print-tracking-summary');
  const btnExportCsv = document.getElementById('btn-export-tracking-csv');

  if (btnTrackGenerate && trackDropdown) {
    btnTrackGenerate.addEventListener('click', (e) => {
      e.stopPropagation();
      trackDropdown.classList.toggle('hidden');
    });

    document.addEventListener('click', (e) => {
      if (!btnTrackGenerate.contains(e.target) && !trackDropdown.contains(e.target)) {
        trackDropdown.classList.add('hidden');
      }
    });
  }

  if (btnPrintSummary) {
    btnPrintSummary.addEventListener('click', () => {
      if (trackDropdown) trackDropdown.classList.add('hidden');
      openTrackingReportPreview();
    });
  }

  if (btnExportCsv) {
    btnExportCsv.addEventListener('click', () => {
      if (trackDropdown) trackDropdown.classList.add('hidden');
      exportTrackingMatrixCSV();
    });
  }
});

// ==========================================
// 🚀 NEW: BATCH ARCHIVE HELPER FUNCTIONS
// ==========================================

// 1. Select / Deselect All logic
window.toggleArchiveSelectAll = function(sourceCheckbox) {
  const checkboxes = document.querySelectorAll('.archive-checkbox');
  checkboxes.forEach(cb => {
    cb.checked = sourceCheckbox.checked;
  });
  updateBatchArchiveUI();
};

// 2. Count checked boxes and show/hide the action bar
window.updateBatchArchiveUI = function() {
  const checkedBoxes = document.querySelectorAll('.archive-checkbox:checked');
  const count = checkedBoxes.length;

  const actionBar = document.getElementById('batch-archive-container');
  const countText = document.getElementById('archive-selected-count');

  if (count > 0) {
    actionBar.style.display = 'flex';
    countText.innerText = count;
  } else {
    actionBar.style.display = 'none';
    const selectAllCb = document.getElementById('archive-select-all');
    if (selectAllCb) selectAllCb.checked = false;
  }
};

// Global variable to temporarily hold the IDs while the modal is open
let pendingArchiveIds = [];

// 3. Triggered when clicking "Archive Selected" in the grey action bar
window.submitBatchArchive = function() {
  const checkedBoxes = document.querySelectorAll('.archive-checkbox:checked');
  if (checkedBoxes.length === 0) return;

  // Gather all the selected IDs
  pendingArchiveIds = Array.from(checkedBoxes).map(cb => parseInt(cb.value));

  // Update the text in the modal to show the exact count
  document.getElementById('batch-archive-confirm-text').innerText =
    `Are you sure you want to securely archive these ${pendingArchiveIds.length} deferred projects?`;

  // Show our beautiful custom modal!
  document.getElementById('batch-archive-confirm-modal').classList.remove('hidden');
};

// 4. Triggered when clicking "Yes, Archive" inside the modal
window.executeBatchArchive = function() {
  // Hide the modal instantly
  document.getElementById('batch-archive-confirm-modal').classList.add('hidden');

  apiFetch('/api/reports/batch/archive', {
    method: 'POST',
    body: JSON.stringify({ reportIds: pendingArchiveIds })
  })
    .then(response => {
      // 🚀 FIX: Pass exactly 2 parameters to match your showToast function
      const successMsg = response.message || `Archived ${pendingArchiveIds.length} projects successfully.`;

      if (window.showToast) {
        window.showToast(successMsg, "success"); // <-- Now correctly formatted!
      } else {
        showToast(successMsg);
      }

      // Hide the action bar and uncheck "Select All"
      document.getElementById('batch-archive-container').style.display = 'none';
      const selectAllCb = document.getElementById('archive-select-all');
      if (selectAllCb) selectAllCb.checked = false;

      // Reload the table instantly and clear the pending array
      loadTrackingData();
      pendingArchiveIds = [];
    })
    .catch(err => {
      console.error("Batch archive error:", err);
      if (window.showToast) {
        window.showToast("Failed to archive projects.", "error"); // <-- Fixed parameter order here too!
      } else {
        showToast("Failed to archive projects.");
      }
      pendingArchiveIds = []; // clear it out on error too
    });
};
// ==========================================
// 🚀 BULLETPROOF MODAL SCROLL RESET HELPER
// ==========================================
