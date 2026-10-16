/**
 * Reports, inventory, settings, rollover, maintenance, and archive feature.
 *
 * Extracted verbatim from app.js. This module self-registers its
 * DOMContentLoaded bindings and click handlers (preserving the original
 * registration behavior) and exposes the same window.* functions the
 * templates and other app.js code already call.
 */
import { apiFetch, API_BASE_URL, getAuthHeaders } from '../../core/api.js';
import { showToast } from '../../components/toast.js';
import { formatOfficialName, resetModalScroll } from '../../core/utils.js';
import { parseToKilometers, formatTerrainType, getReportYear, formatInventoryDate, getReportYearSettings } from '../../domain/inventory.js';

// ==========================================
// 📊 ANNUAL REPORT & PREVIEW SYSTEM (CHRONOLOGICAL & DETAILED)
// ==========================================

let currentPreviewReports = [];

// ==========================================
// 🖋️ 0. OFFICIAL NAME FORMATTER (FIRST M.I. LAST)
// ==========================================

// ==========================================
// 🕒 1. ROBUST DATE FORMATTER HELPER
// ==========================================
function formatReportDate(r) {
  if (!r) return "N/A";

  const rawDate = r.dateSubmitted || r.date_submitted || r.createdAt || r.created_at || r.dateReported || r.date;
  if (!rawDate) return "N/A";

  if (Array.isArray(rawDate) && rawDate.length >= 3) {
    const year = rawDate[0];
    const month = String(rawDate[1]).padStart(2, '0');
    const day = String(rawDate[2]).padStart(2, '0');
    return new Date(`${year}-${month}-${day}`).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
  }

  const parsed = new Date(rawDate);
  if (!isNaN(parsed.getTime())) {
    return parsed.toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
  }

  return String(rawDate);
}

// ==========================================
// 🖨️ 4. CLEAN PRINT TRIGGER (SUPPRESS TITLE HEADER)
// ==========================================
window.printReportDocument = function() {
  const originalTitle = document.title;
  document.title = " ";
  window.print();
  setTimeout(() => {
    document.title = originalTitle;
  }, 1000);
};

// ==========================================
// 📥 5. CSV DOWNLOAD (WITH FULL ROAD SPECS & CLEAN DATES)
// ==========================================
window.downloadPreviewedCSV = function() {
  if (!currentPreviewReports || currentPreviewReports.length === 0) return;

  const selectedYear = document.getElementById("export-inventory-year")?.value || "ALL";
  const headers = [
    "Project ID", "Inventory Year", "Barangay", "City Road Name",
    "Road Type", "Terrain Type", "Length (m)", "Width (m)",
    "Length of Culverts (m)", "Number of Bridges", "Damage Type",
    "Damage Length (m)", "Damage Width (m)", "Severity",
    "Status", "Reported By", "Date Reported"
  ];

  const rows = currentPreviewReports.map(r => {
    const brgyName = r.barangay ? (r.barangay.name || r.barangay.barangayName) : (r.barangayName || "N/A");
    const dateStr = formatReportDate(r);

    return [
      `"PRJ-${r.id}"`,
      `"${r.inventoryYear || 'N/A'}"`,
      `"${brgyName}"`,
      `"${(r.cityRoadName || 'N/A').replace(/"/g, '""')}"`,
      `"${r.roadType || r.roadImportance || 'N/A'}"`,
      `"${r.terrainType || 'N/A'}"`,
      `"${r.length != null ? r.length : ''}"`,
      `"${r.width != null ? r.width : ''}"`,
      `"${r.lengthOfCulverts != null ? r.lengthOfCulverts : '0'}"`,
      `"${r.numberOfBridges != null ? r.numberOfBridges : '0'}"`,
      `"${(r.damageType || 'N/A').replace(/"/g, '""')}"`,
      `"${r.damageLength != null ? r.damageLength : ''}"`,
      `"${r.damageWidth != null ? r.damageWidth : ''}"`,
      `"${r.severity || 'N/A'}"`,
      `"${r.status || 'N/A'}"`,
      `"${(r.reportedBy || 'Official').replace(/"/g, '""')}"`,
      `"${dateStr}"`
    ].join(",");
  });

  const csvString = "\uFEFF" + [headers.join(","), ...rows].join("\r\n");
  const blob = new Blob([csvString], { type: "text/csv;charset=utf-8;" });
  const downloadUrl = URL.createObjectURL(blob);

  const link = document.createElement("a");
  link.href = downloadUrl;
  link.download = `RoadWise_Audit_Report_${selectedYear}_${Date.now()}.csv`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(downloadUrl);
};

// ==========================================
// ❌ 6. CLOSE MODAL
// ==========================================
window.closeReportPreviewModal = function() {
  const modal = document.getElementById("annual-report-preview-modal");
  if (modal) {
    modal.classList.add("hidden");
    modal.style.display = "none";
  }
};

// ==========================================
// 🚀 7. INITIALIZE
// ==========================================
document.addEventListener("DOMContentLoaded", () => {
  if (typeof window.loadDynamicInventoryYears === "function") {
    window.loadDynamicInventoryYears();
  }
});

// =======================================================
// 📑 ADMIN CITY ROAD INVENTORY CONTROLLER (ACTIVE VIEW)
// =======================================================

let adminCachedInventory = [];
window.activeInventoryYear = new Date().getFullYear(); // Fallback to current calendar year

// Helper: Official Name Formatter with Middle Initial

// =======================================================
// 📊 LOAD, AUTO-FILTER TO ACTIVE YEAR, & RENDER
// =======================================================
window.loadAdminRoadInventory = function() {
  const tbody = document.getElementById("admin-inventory-table-body");
  const tfoot = document.getElementById("admin-inventory-table-foot");

  // Populate Document Meta Labels
  const dateLabel = document.getElementById("admin-inventory-date-label");
  if (dateLabel) dateLabel.textContent = new Date().toLocaleDateString("en-US", { year: "numeric", month: "long", day: "numeric" });

  // 🚀 Format Admin Name with Middle Initial (e.g., Juan D. Dela Cruz)
  const adminFirst = sessionStorage.getItem("firstName") || "";
  const adminMiddle = sessionStorage.getItem("middleName") || "";
  const adminLast = sessionStorage.getItem("lastName") || "";
  const adminFullName = formatOfficialName(adminFirst, adminMiddle, adminLast);

  const prepByEl = document.getElementById("admin-inventory-prepared-by");
  if (prepByEl) prepByEl.textContent = adminFullName;

  apiFetch("/api/reports")
    .then(reports => {
      if (!Array.isArray(reports) || reports.length === 0) {
        tbody.innerHTML = `<tr><td colspan="15" style="text-align: center; padding: 25px; color: #64748b;">No road records found in database.</td></tr>`;
        if (tfoot) tfoot.innerHTML = "";
        return;
      }

      // 🚀 AUTO-DETECT ACTIVE YEAR (The most recent year in the database)
      const uniqueYears = [...new Set(reports.map(r => getReportYear(r)).filter(y => y !== ""))].sort((a, b) => Number(b) - Number(a));
      window.activeInventoryYear = uniqueYears.length > 0 ? uniqueYears[0] : new Date().getFullYear();

      // Update UI to reflect the locked Active Year
      const yearLabel = document.getElementById("admin-inventory-year-label");
      if (yearLabel) yearLabel.textContent = `${window.activeInventoryYear} CYCLE`;

      const badgeLabel = document.getElementById("active-inventory-badge");
      if (badgeLabel) badgeLabel.textContent = `ACTIVE CYCLE: ${window.activeInventoryYear}`;

      // 1. Strictly filter by the Active Year
      let list = reports.filter(r => getReportYear(r) === String(window.activeInventoryYear));

      if (list.length === 0) {
        tbody.innerHTML = `<tr><td colspan="15" style="text-align: center; padding: 25px; color: #64748b;">No active records found for the ${window.activeInventoryYear} cycle.</td></tr>`;
        if (tfoot) tfoot.innerHTML = "";
        return;
      }

      // 2. 🚫 DEDUPLICATION: Keep unique roads (retaining newest inspection)
      const uniqueRoadsMap = new Map();
      list.forEach(r => {
        const roadKey = String(r.cityRoadId || r.cityRoadName || r.id).trim().toLowerCase();
        if (!uniqueRoadsMap.has(roadKey)) {
          uniqueRoadsMap.set(roadKey, r);
        } else {
          const existing = uniqueRoadsMap.get(roadKey);
          const currentDate = new Date(r.dateSubmitted || r.createdAt || 0);
          const existingDate = new Date(existing.dateSubmitted || existing.createdAt || 0);
          if (currentDate > existingDate) uniqueRoadsMap.set(roadKey, r);
        }
      });
      let deduplicatedList = Array.from(uniqueRoadsMap.values());

      // 3. 🔢 SORT ASCENDING BY ROAD ID
      deduplicatedList.sort((a, b) => {
        const idA = String(a.cityRoadId || a.id || '').replace(/\D/g, '');
        const idB = String(b.cityRoadId || b.id || '').replace(/\D/g, '');
        if (idA && idB && !isNaN(Number(idA)) && !isNaN(Number(idB))) return Number(idA) - Number(idB);
        return String(a.cityRoadId || a.id).localeCompare(String(b.cityRoadId || b.id), undefined, { numeric: true, sensitivity: 'base' });
      });

      adminCachedInventory = deduplicatedList;

      // Surface & Dimension Totals
      let sumLength = 0, sumAsphalt = 0, sumGravel = 0, sumEarth = 0;
      let sumConcrete = 0, sumMixed = 0, sumCulverts = 0, sumBridges = 0;

      // Render Table Rows
      tbody.innerHTML = deduplicatedList.map((r, index) => {
        const roadId = r.cityRoadId || `3142000000${String(r.id).padStart(2, '0')}`;
        const roadName = r.cityRoadName || "Unnamed Road";
        const dateInspected = formatInventoryDate(r);
        const totalKm = parseToKilometers(r.length);
        const roadWidth = r.width != null && !isNaN(r.width) && String(r.width).trim() !== "" ? Number(r.width).toFixed(2) : "N/A";
        const roadType = (r.roadType || "").toLowerCase();

        const asphaltVal = roadType.includes("asphalt") ? totalKm : 0;
        const gravelVal = roadType.includes("gravel") ? totalKm : 0;
        const earthVal = roadType.includes("earth") ? totalKm : 0;
        const concreteVal = (roadType.includes("concrete") || roadType.includes("paved") || roadType === "") ? totalKm : 0;
        const mixedVal = roadType.includes("mixed") ? totalKm : 0;
        const culvertVal = r.lengthOfCulverts != null ? (parseFloat(r.lengthOfCulverts) || 0) : 0;
        const bridgesVal = r.numberOfBridges != null ? (parseInt(r.numberOfBridges, 10) || 0) : 0;

        sumLength += totalKm; sumAsphalt += asphaltVal; sumGravel += gravelVal; sumEarth += earthVal;
        sumConcrete += concreteVal; sumMixed += mixedVal; sumCulverts += culvertVal; sumBridges += bridgesVal;

        const importance = r.roadImportance ? (r.roadImportance.toLowerCase().includes("non") ? "Non-Core" : "Core") : "Core";
        const terrain = formatTerrainType(r.terrainType);
        const rowBg = index % 2 === 0 ? "#ffffff" : "#f8fafc";

        return `
          <tr style="background: ${rowBg}; border-bottom: 1px solid #cbd5e1; color: #0f172a;">
            <td style="padding: 7px 6px; border: 1px solid #cbd5e1; font-family: monospace; font-weight: 700; text-align: center;">${roadId}</td>
            <td style="padding: 7px 6px; border: 1px solid #cbd5e1; font-weight: 600;">${roadName}</td>
            <td style="padding: 7px 4px; border: 1px solid #cbd5e1; text-align: center;">City</td>
            <td style="padding: 7px 5px; border: 1px solid #cbd5e1; text-align: right; font-weight: 700;">${totalKm.toFixed(3)}</td>
            <td style="padding: 7px 5px; border: 1px solid #cbd5e1; text-align: right; font-weight: 600;">${roadWidth}</td>
            <td style="padding: 7px 5px; border: 1px solid #cbd5e1; text-align: center;">${asphaltVal > 0 ? asphaltVal.toFixed(3) : '0'}</td>
            <td style="padding: 7px 5px; border: 1px solid #cbd5e1; text-align: center;">${gravelVal > 0 ? gravelVal.toFixed(3) : '0'}</td>
            <td style="padding: 7px 5px; border: 1px solid #cbd5e1; text-align: center;">${earthVal > 0 ? earthVal.toFixed(3) : '0'}</td>
            <td style="padding: 7px 5px; border: 1px solid #cbd5e1; text-align: center;">${concreteVal > 0 ? concreteVal.toFixed(3) : '0'}</td>
            <td style="padding: 7px 5px; border: 1px solid #cbd5e1; text-align: center;">${mixedVal > 0 ? mixedVal.toFixed(3) : '0'}</td>
            <td style="padding: 7px 6px; border: 1px solid #cbd5e1; text-align: center;">${importance}</td>
            <td style="padding: 7px 6px; border: 1px solid #cbd5e1; text-align: center;">${terrain}</td>
            <td style="padding: 7px 6px; border: 1px solid #cbd5e1; text-align: right;">${culvertVal > 0 ? culvertVal.toFixed(2) : '0'}</td>
            <td style="padding: 7px 6px; border: 1px solid #cbd5e1; text-align: center;">${bridgesVal}</td>
            <td style="padding: 7px 6px; border: 1px solid #cbd5e1; text-align: center; font-weight: 600;">${dateInspected}</td>
          </tr>
        `;
      }).join("");

      // Summary totals footer row
      if (tfoot) {
        tfoot.innerHTML = `
          <tr style="background: #e2e8f0; color: #0f172a; font-size: 11px;">
            <td colspan="3" style="padding: 8px 6px; border: 1px solid #94a3b8; text-align: right; font-weight: 800;">TOTALS:</td>
            <td style="padding: 8px 5px; border: 1px solid #94a3b8; text-align: right; font-weight: 800;">${sumLength.toFixed(3)}</td>
            <td style="padding: 8px 5px; border: 1px solid #94a3b8; text-align: center; color: #64748b; font-size: 10px;">-</td>
            <td style="padding: 8px 5px; border: 1px solid #94a3b8; text-align: center; font-weight: 800;">${sumAsphalt.toFixed(3)}</td>
            <td style="padding: 8px 5px; border: 1px solid #94a3b8; text-align: center; font-weight: 800;">${sumGravel.toFixed(3)}</td>
            <td style="padding: 8px 5px; border: 1px solid #94a3b8; text-align: center; font-weight: 800;">${sumEarth.toFixed(3)}</td>
            <td style="padding: 8px 5px; border: 1px solid #94a3b8; text-align: center; font-weight: 800;">${sumConcrete.toFixed(3)}</td>
            <td style="padding: 8px 5px; border: 1px solid #94a3b8; text-align: center; font-weight: 800;">${sumMixed.toFixed(3)}</td>
            <td colspan="2" style="padding: 8px 6px; border: 1px solid #94a3b8; text-align: center; font-size: 10px; color: #475569;">${deduplicatedList.length} Unique Roads</td>
            <td style="padding: 8px 6px; border: 1px solid #94a3b8; text-align: right; font-weight: 800;">${sumCulverts.toFixed(2)}</td>
            <td style="padding: 8px 6px; border: 1px solid #94a3b8; text-align: center; font-weight: 800;">${sumBridges}</td>
            <td style="padding: 8px 6px; border: 1px solid #94a3b8; text-align: center; color: #64748b; font-size: 10px;">-</td>
          </tr>
        `;
      }
    })
    .catch(err => {
      console.error("Error loading active inventory:", err);
      tbody.innerHTML = `<tr><td colspan="15" style="text-align: center; padding: 25px; color: #ef4444;">Failed to fetch active inventory data.</td></tr>`;
    });
};

// =======================================================
// 📥 3. EXPORT CSV (ACTIVE VIEW)
// =======================================================
window.downloadAdminInventoryCSV = function() {
  if (!adminCachedInventory || adminCachedInventory.length === 0) {
    if (typeof showToast === "function") showToast("No inventory records to export.", "info");
    return;
  }

  const headers = ["Road ID", "Road Name", "Class", "Length (km)", "Width (m)", "Asphalt (km)", "Gravel (km)", "Earth (km)", "Concrete (km)", "Mixed (km)", "Road Importance", "Terrain Type", "Length of Culverts (m)", "Number of Bridges", "Date Inspected"];

  const rows = adminCachedInventory.map(r => {
    const roadId = r.cityRoadId || `3142000000${String(r.id).padStart(2, '0')}`;
    const totalKm = parseToKilometers(r.length);
    const roadType = (r.roadType || "").toLowerCase();

    return [
      `"${roadId}"`, `"${(r.cityRoadName || "Unnamed").replace(/"/g, '""')}"`, `"City"`, `"${totalKm.toFixed(3)}"`,
      `"${r.width != null ? Number(r.width).toFixed(2) : ""}"`,
      `"${roadType.includes("asphalt") ? totalKm.toFixed(3) : "0"}"`,
      `"${roadType.includes("gravel") ? totalKm.toFixed(3) : "0"}"`,
      `"${roadType.includes("earth") ? totalKm.toFixed(3) : "0"}"`,
      `"${(roadType.includes("concrete") || roadType.includes("paved") || roadType === "") ? totalKm.toFixed(3) : "0"}"`,
      `"${roadType.includes("mixed") ? totalKm.toFixed(3) : "0"}"`,
      `"${r.roadImportance?.includes("non") ? "Non-Core" : "Core"}"`,
      `"${formatTerrainType(r.terrainType)}"`,
      `"${r.lengthOfCulverts != null ? Number(r.lengthOfCulverts).toFixed(2) : "0"}"`,
      `"${r.numberOfBridges != null ? r.numberOfBridges : "0"}"`,
      `"${formatInventoryDate(r)}"`
    ].join(",");
  });

  const blob = new Blob(["\uFEFF" + [headers.join(","), ...rows].join("\r\n")], { type: "text/csv;charset=utf-8;" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = `CSJDM_Active_Road_Inventory_${window.activeInventoryYear}_${Date.now()}.csv`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
};

// =======================================================
// 🖨️ 4. PRINT REPORTS TAB INVENTORY (ACTIVE VIEW)
// =======================================================
window.printAdminRoadInventory = function() {
  const originalTitle = document.title;
  document.title = `CSJDM_Active_Road_Inventory_${window.activeInventoryYear}`;

  const printStyle = document.createElement('style');
  printStyle.id = "temp-active-inventory-print-style";
  printStyle.innerHTML = `
    @media print {
      .sidebar, .top-header, .no-print, .bd-controls { display: none !important; }
      .main-content, .content-area { margin: 0 !important; padding: 0 !important; width: 100% !important; }
      #admin-inventory-sheet { box-shadow: none !important; border: none !important; padding: 0 !important; margin: 0 !important; width: 100% !important; }
    }
  `;
  document.head.appendChild(printStyle);
  window.print();

  setTimeout(() => {
    const styleToRemove = document.getElementById('temp-active-inventory-print-style');
    if (styleToRemove) document.head.removeChild(styleToRemove);
    document.title = originalTitle;
  }, 1000);
};

// ==========================================
// ⬅️ BACK TO REPORTS BUTTON CONTROLLER
// ==========================================
document.addEventListener("DOMContentLoaded", () => {
  const btnCloseInventory = document.getElementById("btn-close-inventory");
  const viewRoadInventory = document.getElementById("view-road-inventory");
  const viewReports = document.getElementById("view-reports");

  if (btnCloseInventory) {
    btnCloseInventory.addEventListener("click", () => {
      if (viewRoadInventory) { viewRoadInventory.classList.add("hidden"); viewRoadInventory.style.display = "none"; }
      if (viewReports) { viewReports.classList.remove("hidden"); viewReports.style.display = "block"; }
    });
  }
});

// =======================================================
// ⚙️ ADMIN SETTINGS & CYCLE TRACKER LOGIC
// =======================================================

// Main Function: Load Dynamic Counts from Database
window.loadActiveCycleOverview = function() {
  const currentYear = String(new Date().getFullYear());
  const yearEl = document.getElementById("settings-active-year");
  if (yearEl) yearEl.textContent = `${currentYear} Cycle`;

  // Check Maintenance Status on Load
  if (typeof window.loadMaintenanceStatus === "function") {
    window.loadMaintenanceStatus();
  }

  // 1. Concurrently fetch real city roads and reports (no-store prevents stale cached counts)
  Promise.all([
    apiFetch("/api/roads", { cache: "no-store" })
      .catch(() => apiFetch("/api/city-roads", { cache: "no-store" }))
      .catch(() => []),
    apiFetch("/api/reports", { cache: "no-store" }).catch(() => [])
  ])
    .then(([cityRoads, reports]) => {
      // A. Get Actual Total Road Count from DB (Fallback to 378 if API fails)
      const totalRoads = (Array.isArray(cityRoads) && cityRoads.length > 0) ? cityRoads.length : 0;

      // =========================================================================
      // 🚀 B. FILTER FOR ACTIVE CYCLE ONLY (EXCLUDE ARCHIVED & PAST YEARS)
      // =========================================================================
      const currentYearReports = (Array.isArray(reports) ? reports : []).filter(r => {
        const status = String(r.status || '').trim().toLowerCase();

        // 🚫 Exclude any archived records
        if (status.includes('archiv')) return false;

        const reportYear = getReportYearSettings(r);
        return !reportYear || reportYear === currentYear;
      });

      // =========================================================================
      // C. DEDUPLICATE: COUNT UNIQUE ACTIVE ROADS INSPECTED THIS CYCLE
      // =========================================================================
      const uniqueRoadsInspected = new Set();
      currentYearReports.forEach(r => {
        const roadKey = String(r.cityRoadName || r.cityRoadId || '').trim().toLowerCase();
        if (roadKey) uniqueRoadsInspected.add(roadKey);
      });

      const inspectedCount = uniqueRoadsInspected.size;

      // D. Calculate Percentage safely
      let percentage = 0;
      if (totalRoads > 0) {
        percentage = Math.round((inspectedCount / totalRoads) * 100);
      }
      if (percentage > 100) percentage = 100;

      // E. Update UI DOM Elements
      const countEl = document.getElementById("settings-inspected-count");
      const totalEl = document.getElementById("settings-total-roads");
      const barEl = document.getElementById("settings-progress-bar");
      const percentEl = document.getElementById("settings-progress-percent");
      const badgeEl = document.getElementById("cycle-status-badge");

      if (countEl) countEl.textContent = inspectedCount;
      if (totalEl) totalEl.textContent = totalRoads;
      if (percentEl) percentEl.textContent = `${percentage}%`;

      if (barEl) {
        barEl.style.width = `${percentage}%`;

        // Visual feedback based on completion
        if (percentage >= 100) {
          barEl.style.background = "#16a34a"; // Green
          if (percentEl) percentEl.style.color = "#16a34a";
          if (badgeEl) {
            badgeEl.textContent = "COMPLETED (READY TO ARCHIVE)";
            badgeEl.style.background = "#fee2e2";
            badgeEl.style.color = "#dc2626";
            badgeEl.style.borderColor = "#fecaca";
          }
        } else {
          barEl.style.background = "#2563eb"; // Blue
          if (percentEl) percentEl.style.color = "#2563eb";
          if (badgeEl) {
            badgeEl.textContent = "ACTIVE (OPEN FOR INSPECTION)";
            badgeEl.style.background = "#dcfce7";
            badgeEl.style.color = "#16a34a";
            badgeEl.style.borderColor = "#bbf7d0";
          }
        }
      }
    })
    .catch(err => console.error("Failed to load cycle overview data:", err));
};

// =======================================================
// 🚨 DANGER ZONE: ANNUAL CYCLE ROLLOVER LOGIC (WITH PRE-FLIGHT)
// =======================================================

// 1. Open the Verification Modal & Run Pre-Flight Check
window.openRolloverModal = function() {
  const modal = document.getElementById("modal-rollover-confirm");
  const input = document.getElementById("input-rollover-confirm");
  const btn = document.getElementById("btn-execute-rollover");
  const preflightBox = document.getElementById("rollover-preflight-box");

  if (!modal) {
    console.error("❌ Modal element #modal-rollover-confirm not found in DOM!");
    return;
  }

  // Display the modal
  modal.style.display = "flex";
  modal.classList.remove("hidden");

  // Reset input and lock execute button
  if (input && btn) {
    input.value = "";
    btn.style.opacity = "0.5";
    btn.style.pointerEvents = "none";
    btn.style.cursor = "not-allowed";

    input.oninput = function() {
      if (this.value.trim().toUpperCase() === "ARCHIVE CYCLE") {
        btn.style.opacity = "1";
        btn.style.pointerEvents = "auto";
        btn.style.cursor = "pointer";
      } else {
        btn.style.opacity = "0.5";
        btn.style.pointerEvents = "none";
        btn.style.cursor = "not-allowed";
      }
    };
  }

  if (!preflightBox) {
    console.error("❌ #rollover-preflight-box not found in DOM! Check for duplicate modal IDs.");
    return;
  }

  // Initial loader state
  preflightBox.style.display = "block";
  preflightBox.style.padding = "10px 12px";
  preflightBox.style.background = "#f8fafc";
  preflightBox.style.border = "1px solid #e2e8f0";
  preflightBox.style.color = "#64748b";
  preflightBox.innerHTML = `🔍 Running pre-flight system check...`;

  // Pre-flight check via /api/reports
  apiFetch("/api/reports")
    .then(reports => {
      console.log("Pre-flight data:", reports);

      if (!Array.isArray(reports)) {
        throw new Error("Invalid response format (expected an array of reports)");
      }

      // Detect unreviewed QA completions
      const pendingQa = reports.filter(r => {
        const s = String(r.status || '').trim().toLowerCase();
        return s === 'completed';
      });

      // Detect active construction/maintenance
      const inProgress = reports.filter(r => {
        const s = String(r.status || '').trim().toLowerCase();
        return s.includes('progress') || s.includes('dispatch');
      });

      if (pendingQa.length > 0) {
        preflightBox.style.background = "#fffbeb";
        preflightBox.style.border = "1px solid #fef3c7";
        preflightBox.style.color = "#92400e";
        preflightBox.innerHTML = `
          <div style="font-weight: 700; margin-bottom: 4px; display: flex; align-items: center; gap: 5px;">
            <span>⚠️</span> Pre-Flight Reminder (${pendingQa.length} Pending QA)
          </div>
          <div>
            You have <strong>${pendingQa.length}</strong> repair(s) marked as <em>Completed</em> by the CEO awaiting your QA sign-off in <strong>Repair Tracking</strong>.
          </div>
          <div style="margin-top: 6px; font-size: 11.5px; color: #b45309;">
            You can still proceed, but unverified repairs will carry over into the new cycle.
          </div>
        `;
      } else if (inProgress.length > 0) {
        preflightBox.style.background = "#eff6ff";
        preflightBox.style.border = "1px solid #dbeafe";
        preflightBox.style.color = "#1e40af";
        preflightBox.innerHTML = `
          <div style="font-weight: 700; margin-bottom: 3px;">ℹ️ Active Engineering Notice</div>
          <div>There are <strong>${inProgress.length}</strong> active repairs currently In Progress. These will safely carry over to the new year.</div>
        `;
      } else {
        preflightBox.style.background = "#f0fdf4";
        preflightBox.style.border = "1px solid #dcfce7";
        preflightBox.style.color = "#166534";
        preflightBox.innerHTML = `
          <div style="font-weight: 700;">✅ Pre-Flight Clear</div>
          <div>All completed repairs have been officially verified and closed.</div>
        `;
      }
    })
    .catch(err => {
      console.warn("Pre-flight check error:", err);
      // Fallback display instead of turning display to 'none'
      preflightBox.style.display = "block";
      preflightBox.style.background = "#f1f5f9";
      preflightBox.style.border = "1px solid #cbd5e1";
      preflightBox.style.color = "#475569";
      preflightBox.innerHTML = `
        <div style="font-weight: 600;">⚠️ Notice</div>
        <div>Proceeding with standard cycle rollover. Verified and closed reports will be archived.</div>
      `;
    });
};

// 2. Close the Modal
window.closeRolloverModal = function() {
  const modal = document.getElementById("modal-rollover-confirm");
  if (modal) {
    modal.style.display = "none";
    modal.classList.add("hidden");
  }
};

// 3. Fire the Rollover Request
window.executeAnnualRollover = function() {
  const btn = document.getElementById("btn-execute-rollover");
  if (btn) {
    btn.innerHTML = "Processing... ⏳";
    btn.style.pointerEvents = "none";
    btn.style.opacity = "0.7";
  }

  apiFetch("/api/reports/rollover-annual-cycle", {
    method: "POST"
  })
    .then(res => {
      showToast(`Rollover Complete: Archived ${res.archivedCount || 0} reports. Notifications sent.`, "success");

      window.closeRolloverModal();
      if (typeof window.loadActiveCycleOverview === "function") {
        window.loadActiveCycleOverview();
      }
    })
    .catch(err => {
      console.error("Rollover failed:", err);
      showToast("Error processing annual rollover.", "error");
    })
    .finally(() => {
      if (btn) {
        btn.innerHTML = "Execute Rollover";
      }
    });
};
// =======================================================
// 🔒 SYSTEM MAINTENANCE LOGIC
// =======================================================

// 5. Load Maintenance Status on Dashboard Load
window.loadMaintenanceStatus = function() {
  apiFetch("/api/settings")
    .then(settings => {
      const toggle = document.getElementById("toggle-maintenance");
      const slider = document.getElementById("maintenance-slider");
      const knob = document.getElementById("maintenance-knob");

      if (toggle && settings) {
        toggle.checked = settings.maintenanceMode;
        if (settings.maintenanceMode) {
          slider.style.backgroundColor = "#dc2626"; // Red when locked
          knob.style.transform = "translateX(24px)";
        } else {
          slider.style.backgroundColor = "#cbd5e1"; // Gray when unlocked
          knob.style.transform = "translateX(0)";
        }
      }
    }).catch(err => console.error("Failed to load settings", err));
};

// 6. Toggle Maintenance Mode
window.toggleMaintenanceMode = function(isLocked) {
  apiFetch(`/api/settings/toggle-maintenance?status=${isLocked}`, { method: "POST" })
    .then(settings => {
      const slider = document.getElementById("maintenance-slider");
      const knob = document.getElementById("maintenance-knob");

      if (settings.maintenanceMode) {
        slider.style.backgroundColor = "#dc2626";
        knob.style.transform = "translateX(24px)";
        if (typeof showToast === "function") showToast("System Locked. Non-admins cannot log in.", "warning");
      } else {
        slider.style.backgroundColor = "#cbd5e1";
        knob.style.transform = "translateX(0)";
        if (typeof showToast === "function") showToast("System Unlocked. Normal logins restored.", "success");
      }
    })
    .catch(err => {
      console.error(err);
      showToast("Failed to toggle maintenance mode.", "error");
      // Revert the toggle visually if it failed
      document.getElementById("toggle-maintenance").checked = !isLocked;
    });
};

// ==========================================
// 📅 1. DYNAMIC INVENTORY YEARS LOADER (SMART FILTERED)
// ==========================================
window.loadDynamicInventoryYears = function() {
  const yearSelect = document.getElementById("export-inventory-year");
  if (!yearSelect) return;

  const barangayId = sessionStorage.getItem("barangayId");
  const userRole = (sessionStorage.getItem("userRole") || "").toUpperCase();

  let endpoint = "/api/reports";
  if (userRole.includes("BARANGAY") && barangayId && barangayId !== "null") {
    endpoint = `/api/reports/barangay/${barangayId}`;
  }

  // apiFetch unwraps Spring's paged envelope and handles token refresh. The raw fetch
  // this replaced received {content:[…]}, which the Array.isArray guard below dropped.
  apiFetch(endpoint, { headers: { "ngrok-skip-browser-warning": "true" } })
    .then(reports => {
      if (!Array.isArray(reports)) return;

      // 🚀 CEO SMART FILTER: Only check years where the CEO actually has projects
      let validReports = reports;
      if (userRole.includes("CEO") || userRole.includes("ENGINEER")) {
        validReports = reports.filter(r => {
          const stat = (r.status || "").toLowerCase();
          return stat.includes("dispatched") || stat.includes("in progress") || stat.includes("completed") || stat.includes("pending budget") || stat.includes("defer");
        });
      }

      const uniqueYears = [...new Set(
        validReports
          .map(r => r.inventoryYear)
          .filter(y => y && String(y).trim() !== "" && String(y).toLowerCase() !== "null" && String(y).toLowerCase() !== "undefined")
      )].sort((a, b) => Number(a) - Number(b));

      yearSelect.innerHTML = `<option value="ALL">All Recorded Years</option>`;

      uniqueYears.forEach(year => {
        const opt = document.createElement("option");
        opt.value = year;
        opt.textContent = `${year} Inventory Cycle`;
        yearSelect.appendChild(opt);
      });
    })
    .catch(err => console.error("Failed to load inventory years:", err));
};

// ==========================================
// 👁️ 2. OPEN & POPULATE PREVIEW (WITH MIDDLE INITIAL + CEO SUPPORT)
// ==========================================
window.openAnnualReportPreview = async function() {
  const yearSelect = document.getElementById("export-inventory-year");
  const selectedYear = yearSelect ? yearSelect.value : "ALL";

  const categorySelect = document.getElementById("export-report-category");
  const selectedCategory = categorySelect ? categorySelect.value : "ALL";

  const barangayId = sessionStorage.getItem("barangayId");
  const userRole = (sessionStorage.getItem("userRole") || "").toUpperCase();

  // 🚀 1. BUILD FORMATTED NAME WITH MIDDLE INITIAL (e.g., JM L. POGIII)
  const firstName = (sessionStorage.getItem("firstName") || "").trim();
  const rawMiddle = (sessionStorage.getItem("middleName") || "").trim();
  const lastName = (sessionStorage.getItem("lastName") || "").trim();

  let mi = "";
  if (rawMiddle && rawMiddle.toLowerCase() !== "null" && rawMiddle.toLowerCase() !== "undefined") {
    mi = rawMiddle.charAt(0).toUpperCase() + ". ";
  }
  const formattedUserName = `${firstName} ${mi}${lastName}`.trim() || "Designated Official";

  // 🚀 2. DYNAMIC LOOKUP FOR CITY ENGINEER (CEO) & CPDO ADMIN
  let ceoName = "City Engineer";
  let cpdoAdminName = "CPDO Lead Administrator";

  try {
    const userRes = await fetch(`${API_BASE_URL}/api/users`, {
      headers: { ...getAuthHeaders(), "ngrok-skip-browser-warning": "true" }
    });
    if (userRes.ok) {
      const allUsers = await userRes.json();
      if (Array.isArray(allUsers)) {
        // Find City Engineer
        const engineerUser = allUsers.find(u => {
          const r = String(u.role || '').toUpperCase().trim();
          const s = String(u.status || '').toLowerCase().trim();
          return r === 'ENGINEER' && s !== 'deactivated';
        });
        if (engineerUser) {
          const eMid = (engineerUser.middleName && engineerUser.middleName.toLowerCase() !== 'null')
            ? `${engineerUser.middleName.charAt(0).toUpperCase()}. `
            : "";
          const eFull = `${engineerUser.firstName || ''} ${eMid}${engineerUser.lastName || ''}`.trim();
          ceoName = eFull ? `Engr. ${eFull}` : ceoName;
        }

        // Find CPDO Admin
        const adminUser = allUsers.find(u => {
          const r = String(u.role || '').toUpperCase().trim();
          const s = String(u.status || '').toLowerCase().trim();
          return (r === 'ADMIN' || r.includes('CPDO')) && s !== 'deactivated';
        });
        if (adminUser) {
          const aMid = (adminUser.middleName && adminUser.middleName.toLowerCase() !== 'null')
            ? `${adminUser.middleName.charAt(0).toUpperCase()}. `
            : "";
          cpdoAdminName = `${adminUser.firstName || ''} ${aMid}${adminUser.lastName || ''}`.trim() || cpdoAdminName;
        }
      }
    }
  } catch (e) {
    console.warn("Signatory fetch warning:", e);
  }

  // 🚀 3. FETCH REPORT DATA
  let endpoint = "/api/reports";
  if (userRole.includes("BARANGAY") && barangayId && barangayId !== "null") {
    endpoint = `/api/reports/barangay/${barangayId}`;
  }

  // Routed through apiFetch for the same reason as loadDynamicInventoryYears: it unwraps
  // the paged envelope, so `reports` is the array this chain was already expecting.
  apiFetch(endpoint, { headers: { "ngrok-skip-browser-warning": "true" } })
    .then(reports => {
      if (!Array.isArray(reports) || reports.length === 0) {
        if (typeof showToast === 'function') showToast("No reports found to generate preview.", "info");
        return;
      }

      // Filter by Year
      let filtered = selectedYear === "ALL"
        ? reports
        : reports.filter(r => String(r.inventoryYear) === String(selectedYear));

      // Category Split for CEO / Engineer
      if (userRole.includes("CEO") || userRole.includes("ENGINEER")) {
        filtered = filtered.filter(r => {
          const stat = (r.status || "").toLowerCase();
          if (selectedCategory === "ACTIVE") {
            return stat.includes("dispatched") || stat.includes("in progress") || stat.includes("pending budget") || stat.includes("defer");
          } else if (selectedCategory === "ACCOMPLISHMENT") {
            return stat.includes("completed") || stat.includes("archived");
          } else {
            return stat.includes("dispatched") || stat.includes("in progress") || stat.includes("completed") || stat.includes("pending budget") || stat.includes("defer") || stat.includes("archived");
          }
        });
      }

      if (filtered.length === 0) {
        if (typeof showToast === 'function') showToast(`No records found for ${selectedYear} in this category.`, "warning");
        return;
      }

      // Status Hierarchy Sorting
      const statusWeight = {
        "in progress": 1,
        "dispatched": 2,
        "pending budget": 3,
        "defer": 3,
        "completed": 4,
        "archived": 5
      };

      filtered.sort((a, b) => {
        const statA = (a.status || "").toLowerCase();
        const statB = (b.status || "").toLowerCase();
        const weightA = statusWeight[statA] || 99;
        const weightB = statusWeight[statB] || 99;
        if (weightA !== weightB) return weightA - weightB;
        return Number(a.id) - Number(b.id);
      });

      currentPreviewReports = filtered;

      // 🚀 4. DOM METADATA INJECTION
      const brgyName = sessionStorage.getItem("barangayName") || "City-Wide Scope";

      const elTitle = document.querySelector("#printable-report-area h2");
      if (elTitle) {
        if (selectedCategory === "ACTIVE") {
          elTitle.textContent = "ACTIVE ENGINEERING PROJECTS & BACKLOG";
        } else if (selectedCategory === "ACCOMPLISHMENT") {
          elTitle.textContent = "ANNUAL ENGINEERING ACCOMPLISHMENT REPORT";
        } else {
          elTitle.textContent = "ENGINEERING PROJECT & REPAIR INVENTORY REPORT";
        }
      }

      const elSubtitle = document.getElementById("preview-report-subtitle");
      if (elSubtitle) elSubtitle.textContent = `Inventory Cycle: ${selectedYear === "ALL" ? "All Recorded Years" : selectedYear}`;

      const elGenerated = document.getElementById("preview-generated-by");
      if (elGenerated) elGenerated.textContent = formattedUserName;

      let scopeText = "All Barangays (City-Wide)";
      if (userRole.includes("BARANGAY")) scopeText = brgyName;
      if (userRole.includes("CEO") || userRole.includes("ENGINEER")) scopeText = "City Engineering Office (CEO)";

      const elScope = document.getElementById("preview-scope");
      if (elScope) elScope.textContent = scopeText;

      const elDate = document.getElementById("preview-date");
      if (elDate) elDate.textContent = new Date().toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' });

      const elCount = document.getElementById("preview-total-count");
      if (elCount) elCount.textContent = currentPreviewReports.length;

      // ✍️ 5. POPULATE SIGNATURE BLOCKS
      const signNameElem = document.getElementById("preview-sign-name");
      const signRoleElem = document.getElementById("preview-sign-role");
      if (signNameElem) signNameElem.textContent = formattedUserName;
      if (signRoleElem) {
        if (userRole.includes("BARANGAY")) signRoleElem.textContent = "Barangay Official";
        else if (userRole.includes("CEO") || userRole.includes("ENGINEER")) signRoleElem.textContent = "City Engineer";
        else signRoleElem.textContent = "CPDO Lead Administrator";
      }

      // Dual signature targets (if present in modal)
      const cpdoSignElem = document.getElementById("preview-cpdo-sign-name");
      if (cpdoSignElem) cpdoSignElem.textContent = cpdoAdminName;

      const ceoSignElem = document.getElementById("preview-ceo-sign-name");
      if (ceoSignElem) ceoSignElem.textContent = ceoName;

      // 🚀 6. POPULATE TABLE ROWS
      const tbody = document.getElementById("preview-report-table-body");
      if (tbody) {
        tbody.innerHTML = currentPreviewReports.map((r, index) => {
          const bName = r.barangay ? (r.barangay.name || r.barangay.barangayName) : (r.barangayName || "N/A");

          let dateStr = new Date(r.createdAt || new Date()).toLocaleDateString();
          if (typeof formatReportDate === 'function') {
            try { dateStr = formatReportDate(r); } catch(e) {}
          }

          const rowBg = index % 2 === 0 ? "#ffffff" : "#f8fafc";
          const roadType = r.roadType || r.roadImportance || "Standard";
          const terrain = r.terrainType ? ` • ${r.terrainType}` : "";
          const dimensions = `L: ${r.length != null ? r.length + 'm' : 'N/A'} | W: ${r.width != null ? r.width + 'm' : 'N/A'}`;
          const culvertBridge = `Culv: ${r.lengthOfCulverts != null ? r.lengthOfCulverts + 'm' : '0m'}<br>Bridges: ${r.numberOfBridges != null ? r.numberOfBridges : '0'}`;
          const damageDetails = `<strong>${r.damageType || 'General'}</strong>${r.damageLength ? ` (${r.damageLength}m × ${r.damageWidth || 0}m)` : ''}`;

          return `
            <tr style="background: ${rowBg}; border-bottom: 1px solid #e2e8f0; vertical-align: top;">
              <td style="padding: 8px 6px; border: 1px solid #cbd5e1; font-weight: 700;">PRJ-${r.id}</td>
              <td style="padding: 8px 6px; border: 1px solid #cbd5e1;">${bName}</td>
              <td style="padding: 8px 6px; border: 1px solid #cbd5e1;">
                <strong>${r.cityRoadName || 'N/A'}</strong><br>
                <span style="font-size: 10px; color: #64748b;">${roadType}${terrain}</span>
              </td>
              <td style="padding: 8px 6px; border: 1px solid #cbd5e1; font-size: 10px;">${dimensions}</td>
              <td style="padding: 8px 6px; border: 1px solid #cbd5e1; font-size: 10px;">${culvertBridge}</td>
              <td style="padding: 8px 6px; border: 1px solid #cbd5e1;">${damageDetails}</td>
              <td style="padding: 8px 6px; border: 1px solid #cbd5e1; text-align: center; font-weight: 700; color: ${r.severity === 'High' ? '#dc2626' : (r.severity === 'Medium' ? '#d97706' : '#16a34a')};">${r.severity || 'N/A'}</td>
              <td style="padding: 8px 6px; border: 1px solid #cbd5e1; text-align: center; font-size: 10px; font-weight: bold;">${r.status || 'Pending'}</td>
              <td style="padding: 8px 6px; border: 1px solid #cbd5e1; text-align: right; font-size: 10px; font-weight: 600;">${dateStr}</td>
            </tr>
          `;
        }).join("");
      }

      const modal = document.getElementById("annual-report-preview-modal");
      if (modal) {
        modal.classList.remove("hidden");
        modal.style.display = "flex";
      } else {
        console.error("Modal ID 'annual-report-preview-modal' not found in HTML!");
      }
    })
    .catch(err => {
      console.error(err);
      if (typeof showToast === 'function') showToast("Failed to load report preview.", "error");
    });
};

// Ensure it loads dynamically when the page opens
document.addEventListener("DOMContentLoaded", () => {
  if (typeof window.loadDynamicInventoryYears === "function") {
    window.loadDynamicInventoryYears();
  }
});

// =======================================================
// 🗄️ ADMIN ARCHIVE & REPORTS CONTROLLER (100% COMPLETE)
// =======================================================

let cacheArchiveInventory = [];
let cacheArchivePriority = [];
let allArchiveDatabaseReports = [];
let archiveDetailMap = null;
let archiveDetailMarker = null;
let currentArchiveLat = 0;
let currentArchiveLng = 0;

// =======================================================
// 📅 1. INITIALIZE DROPDOWNS & TABLE ON TAB LOAD
// =======================================================
window.initArchiveTab = function() {
  apiFetch("/api/reports")
    .then(reports => {
      if (!Array.isArray(reports)) return;

      const uniqueYears = [...new Set(
        reports.map(r => getReportYear(r)).filter(y => y !== "")
      )].sort((a, b) => Number(b) - Number(a));

      const optionsHTML = `<option value="ALL">All Recorded Years</option>` +
        uniqueYears.map(year => `<option value="${year}">${year} Cycle</option>`).join("");

      // Top Cards Dropdowns
      const invSelect = document.getElementById("archive-inventory-year");
      const prioSelect = document.getElementById("archive-priority-year");
      if (invSelect) invSelect.innerHTML = optionsHTML;
      if (prioSelect) prioSelect.innerHTML = optionsHTML;

      // Bottom Historical Database Table & Filter Dropdown
      const tableYearSelect = document.getElementById("archive-filter-year");
      if (tableYearSelect) tableYearSelect.innerHTML = optionsHTML;

      loadArchiveDatabaseTable(reports);
    })
    .catch(err => console.error("Failed to load archive dropdown years:", err));
};

// =======================================================
// 🗃️ 2. LOAD CONCLUDED RECORDS INTO DATABASE TABLE
// =======================================================
window.loadArchiveDatabaseTable = function(reports) {
  // Isolate concluded/finalized records only
  allArchiveDatabaseReports = (Array.isArray(reports) ? reports : []).filter(r => {
    const stat = String(r.status || '').toLowerCase();
    return stat === 'completed' || stat === 'closed' || stat === 'resolved' || stat === 'archived';
  });

  renderArchiveTableRows(allArchiveDatabaseReports);
};

// =======================================================
// 🔍 3. SEARCH & FILTER ARCHIVE DATABASE TABLE
// =======================================================
window.filterArchiveDatabaseTable = function() {
  const searchTerm = (document.getElementById("archive-search-input")?.value || "").toLowerCase().trim();
  const selectedYear = document.getElementById("archive-filter-year")?.value || "ALL";
  const selectedStatus = (document.getElementById("archive-filter-status")?.value || "ALL").toUpperCase();

  const filtered = allArchiveDatabaseReports.filter(r => {
    const year = getReportYear(r);
    const rawStat = String(r.status || "").toUpperCase();
    const id = `PRJ-${String(r.id || "")}`.toLowerCase();
    const roadName = String(r.cityRoadName || "").toLowerCase();
    const brgyName = String(r.barangay?.barangayName || r.barangayName || "").toLowerCase();

    // 1. Year Match
    const matchesYear = (selectedYear === "ALL") || (year === selectedYear);

    // 2. Status Match
    let matchesStatus = true;
    if (selectedStatus === "COMPLETED") {
      matchesStatus = (rawStat === "COMPLETED" || rawStat === "CLOSED" || rawStat === "RESOLVED");
    } else if (selectedStatus === "ARCHIVED") {
      matchesStatus = (rawStat === "ARCHIVED");
    }

    // 3. Search Term Match
    const matchesSearch = !searchTerm || id.includes(searchTerm) || roadName.includes(searchTerm) || brgyName.includes(searchTerm);

    return matchesYear && matchesStatus && matchesSearch;
  });

  renderArchiveTableRows(filtered);
};

// =======================================================
// 🕒 DATE FORMATTER HELPER (DATE ARCHIVED TARGET)
// =======================================================
function formatArchiveDate(report) {
  // 🎯 1. Prioritize dateArchived from backend; fall back only if null
  const rawDate = report.dateArchived || report.archivedAt || report.completedAt || report.dateSubmitted;
  if (!rawDate) return 'N/A';

  const dateObj = new Date(rawDate);
  if (isNaN(dateObj.getTime())) return 'N/A';

  // 🇵🇭 Enforce Philippine Standard Time (PST / UTC+8)
  return dateObj.toLocaleDateString('en-US', {
    timeZone: 'Asia/Manila',
    month: 'short',
    day: 'numeric',
    year: 'numeric'
  });
}

// =======================================================
// 🎨 4. RENDER ARCHIVE TABLE ROWS
// =======================================================
function renderArchiveTableRows(records) {
  const tbody = document.getElementById("archive-database-tbody");
  const countEl = document.getElementById("archive-database-count");
  if (!tbody) return;

  if (countEl) {
    countEl.textContent = `Showing ${records.length} archived ${records.length === 1 ? 'record' : 'records'}`;
  }

  if (records.length === 0) {
    tbody.innerHTML = `<tr><td colspan="9" style="text-align: center; padding: 30px; color: #64748b; font-style: italic;">No archived records found matching your filters.</td></tr>`;
    return;
  }

  tbody.innerHTML = records.map((r, index) => {
    const formatId = `#PRJ-${String(r.id).padStart(4, '0')}`;
    const formatName = r.cityRoadName || 'Unnamed Road';
    const formatBrgy = (r.barangay && r.barangay.barangayName) ? r.barangay.barangayName : (r.barangayName || 'Unknown');
    const formatDamage = r.damageType || 'General Repair';
    const year = getReportYear(r) || 'N/A';
    const rawStatus = (r.status || 'Archived').toUpperCase();

    // 🎯 Resolves the actual archived/closed date from backend
    const dateArchived = formatArchiveDate(r);

    let badgeBg = '#f8fafc';
    let badgeColor = '#475569';
    let badgeBorder = '#cbd5e1';
    let displayLabel = 'ARCHIVED';

    if (rawStatus === 'COMPLETED' || rawStatus === 'CLOSED' || rawStatus === 'RESOLVED') {
      badgeBg = '#f0fdf4';
      badgeColor = '#16a34a';
      badgeBorder = '#bbf7d0';
      displayLabel = 'COMPLETED';
    }

    const severityColor = r.severity === 'High' ? '#dc2626' : (r.severity === 'Medium' ? '#d97706' : '#16a34a');

    return `
      <tr style="border-bottom: 1px solid #e2e8f0; background: ${index % 2 === 0 ? '#ffffff' : '#f8fafc'};">
        <td style="padding: 10px 12px; border: 1px solid #cbd5e1; text-align: center; font-family: monospace; font-weight: 700;">${formatId}</td>
        <td style="padding: 10px 12px; border: 1px solid #cbd5e1; font-weight: 600; color: #0f172a;">${formatName}</td>
        <td style="padding: 10px 12px; border: 1px solid #cbd5e1; color: #475569;">${formatBrgy}</td>
        <td style="padding: 10px 12px; border: 1px solid #cbd5e1;">${formatDamage}</td>
        <td style="padding: 10px 12px; border: 1px solid #cbd5e1; text-align: center; font-weight: 700; color: ${severityColor};">${r.severity || 'N/A'}</td>
        <td style="padding: 10px 12px; border: 1px solid #cbd5e1; text-align: center; font-weight: 600;">${year}</td>
        <td style="padding: 10px 12px; border: 1px solid #cbd5e1; text-align: center;">
          <span style="padding: 4px 10px; border-radius: 20px; font-size: 11px; font-weight: 700; background: ${badgeBg}; color: ${badgeColor}; border: 1px solid ${badgeBorder}; display: inline-block;">
            ${displayLabel}
          </span>
        </td>
        <td style="padding: 10px 12px; border: 1px solid #cbd5e1; text-align: center; font-size: 11px; color: #0f172a; font-weight: 600;">${dateArchived}</td>
        <td style="padding: 8px 10px; border: 1px solid #cbd5e1; text-align: center;">
          <button onclick="event.stopPropagation(); openArchiveDetailModal(${r.id})" style="padding: 6px 12px; background: #0f172a; color: #ffffff; border: none; border-radius: 4px; font-size: 11.5px; font-weight: 600; cursor: pointer; transition: background 0.2s; display: inline-flex; align-items: center; gap: 4px;">
            View
          </button>
        </td>
      </tr>
    `;
  }).join('');
}

// =======================================================
// 📜 ARCHIVE TIMELINE ACCORDION TOGGLE
// =======================================================
window.toggleArchiveTimeline = function() {
  const container = document.getElementById('archive-modal-timeline-container');
  const arrow = document.getElementById('archive-timeline-arrow');

  if (!container) return;
  const isHidden = container.classList.toggle('hidden');
  if (arrow) {
    arrow.style.transform = isHidden ? 'rotate(0deg)' : 'rotate(180deg)';
  }

  // 🚀 Force timeline internal scroll to top when opened
  if (!isHidden) {
    container.scrollTop = 0;
    requestAnimationFrame(() => { container.scrollTop = 0; });
  }
};

// =======================================================
// 📋 OPEN INDIVIDUAL PROJECT ARCHIVE DETAIL MODAL
// =======================================================
window.openArchiveDetailModal = function(reportId) {
  if (!reportId) return;

  const NO_IMAGE_PLACEHOLDER = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='120' height='120' viewBox='0 0 24 24' fill='none' stroke='%2394a3b8' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'%3E%3Crect width='18' height='18' x='3' y='3' rx='2' ry='2'/%3E%3Ccircle cx='9' cy='9' r='2'/%3E%3Cpath d='m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21'/%3E%3C/svg%3E";

  // Close lingering image modals
  document.querySelectorAll('.fullscreen-modal, #image-modal, #fullscreen-image-modal, #image-viewer-modal').forEach(m => {
    m.classList.add('hidden');
    m.style.display = 'none';
  });

  const modal = document.getElementById('archive-detail-modal');
  if (!modal) return;

  // Reset map container
  const mapContainer = document.getElementById('archive-detail-map-container');
  if (mapContainer) mapContainer.style.display = 'none';
  currentArchiveLat = 0;
  currentArchiveLng = 0;

  // Reset image previews
  const dmgImg = document.getElementById('archive-modal-damage-image');
  if (dmgImg) dmgImg.src = NO_IMAGE_PLACEHOLDER;
  const proofImg = document.getElementById('archive-modal-proof-image');
  if (proofImg) proofImg.src = '';
  const proofContainer = document.getElementById('archive-modal-proof-container');
  if (proofContainer) proofContainer.style.display = 'none';

  // ⏱️ Reset accordion to collapsed state & reset timeline scroll
  const timelineContainer = document.getElementById('archive-modal-timeline-container');
  if (timelineContainer) {
    timelineContainer.classList.add('hidden');
    timelineContainer.scrollTop = 0;
  }
  const arrow = document.getElementById('archive-timeline-arrow');
  if (arrow) arrow.style.transform = 'rotate(0deg)';

  // Dynamic Admin Signer (Middle Initial Only)
  const adminFirst = (sessionStorage.getItem("firstName") || "").trim();
  const rawMiddle = (sessionStorage.getItem("middleInitial") || sessionStorage.getItem("middleName") || "").trim();
  const adminLast = (sessionStorage.getItem("lastName") || "").trim();
  const middleInitial = rawMiddle ? `${rawMiddle.charAt(0).toUpperCase()}.` : "";
  const adminFullName = [adminFirst, middleInitial, adminLast].filter(Boolean).join(" ");

  const signerEl = document.getElementById("archive-modal-signer-name");
  if (signerEl) {
    signerEl.innerText = adminFullName || "CPDO ADMINISTRATOR";
  }

  modal.classList.remove('hidden');
  modal.style.display = 'flex';

  // 🚀 Initial Scroll Reset to Top
  const scrollableBody = document.getElementById('archive-printable-audit-sheet');
  if (scrollableBody) scrollableBody.scrollTop = 0;
  if (typeof resetModalScroll === 'function') resetModalScroll(modal);

  document.getElementById('archive-modal-prj-id').innerText = `#PRJ-${String(reportId).padStart(4, '0')} (Loading...)`;
  document.getElementById('archive-modal-date').innerText = 'Loading...';

  apiFetch(`/api/reports/${reportId}`, { cache: 'no-store' })
    .then(report => {
      if (!report) throw new Error('Report data is empty');

      currentArchiveLat = parseFloat(report.latitude) || 0;
      currentArchiveLng = parseFloat(report.longitude) || 0;

      // Header Banner
      document.getElementById('archive-modal-prj-id').innerText = `#PRJ-${String(report.id).padStart(4, '0')}`;

      const year = typeof getReportYear === 'function' ? getReportYear(report) : (report.inventoryYear || 'N/A');
      document.getElementById('archive-modal-year').innerText = year || 'N/A';

      // 🎯 1. PULL ACTUAL "CLOSED" / "ARCHIVED" DATE DIRECTLY FROM TIMELINE
      apiFetch(`/api/reports/${report.id}/timeline`)
        .then(timeline => {
          if (Array.isArray(timeline) && timeline.length > 0) {
            // Find the concluding action (CLOSED, ARCHIVED, or COMPLETED)
            const closedEvent = [...timeline].reverse().find(ev => {
              const act = String(ev.action || '').toUpperCase();
              return act === 'CLOSED' || act === 'ARCHIVED' || act === 'COMPLETED' || act === 'RESOLVED';
            }) || timeline[timeline.length - 1];

            if (closedEvent && closedEvent.createdAt) {
              const d = new Date(closedEvent.createdAt);
              if (!isNaN(d.getTime())) {
                document.getElementById('archive-modal-date').innerText = d.toLocaleDateString('en-US', {
                  year: 'numeric', month: 'short', day: 'numeric'
                });
                return;
              }
            }
          }
          // Fallback if no timeline event exists
          const fallbackDate = report.archivedAt || report.completedAt || report.dateSubmitted || report.createdAt;
          document.getElementById('archive-modal-date').innerText = fallbackDate
            ? new Date(fallbackDate).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' })
            : 'N/A';
        })
        .catch(() => {
          document.getElementById('archive-modal-date').innerText = report.dateSubmitted || 'N/A';
        });

      // Status Badge
      const rawStat = String(report.status || 'Archived').toUpperCase();
      const statusBadge = document.getElementById('archive-modal-status-badge');
      if (statusBadge) {
        if (rawStat.includes('COMPLET') || rawStat.includes('CLOSE') || rawStat.includes('RESOLV')) {
          statusBadge.innerText = 'COMPLETED';
          statusBadge.style.cssText = 'padding: 2px 8px; border-radius: 20px; font-size: 10px; font-weight: 800; background: #f0fdf4; color: #16a34a; border: 1px solid #bbf7d0; display: inline-block;';
        } else {
          statusBadge.innerText = 'ARCHIVED';
          statusBadge.style.cssText = 'padding: 2px 8px; border-radius: 20px; font-size: 10px; font-weight: 800; background: #f8fafc; color: #475569; border: 1px solid #cbd5e1; display: inline-block;';
        }
      }

      // 1. Road Specifications (Separated Fields)
      document.getElementById('archive-modal-road-name').innerText = report.cityRoadName || 'Unnamed Road';
      document.getElementById('archive-modal-brgy').innerText = (report.barangay && report.barangay.barangayName) ? report.barangay.barangayName : (report.barangayName || 'Unknown');
      document.getElementById('archive-modal-road-id').innerText = report.cityRoadId || `31420000${String(report.id).padStart(2, '0')}`;
      document.getElementById('archive-modal-importance').innerText = String(report.roadImportance || '').toLowerCase().includes('non') ? 'Non-Core' : 'Core';

      const terrain = typeof formatTerrainType === 'function' ? formatTerrainType(report.terrainType) : (report.terrainType || 'FLAT');
      document.getElementById('archive-modal-terrain').innerText = terrain;
      document.getElementById('archive-modal-road-type').innerText = report.roadType || 'Concrete';

      const rLen = parseFloat(report.length) || 0;
      const rWid = parseFloat(report.width) || 0;
      document.getElementById('archive-modal-road-length').innerText = rLen > 0 ? `${rLen} km` : '0 km';
      document.getElementById('archive-modal-road-width').innerText = rWid > 0 ? `${rWid} m` : '0 m';

      const culverts = report.lengthOfCulverts != null && !isNaN(report.lengthOfCulverts) ? Number(report.lengthOfCulverts).toFixed(2) : '0.00';
      document.getElementById('archive-modal-culverts').innerText = `${culverts} m`;
      document.getElementById('archive-modal-bridges').innerText = report.numberOfBridges != null ? report.numberOfBridges : 0;

      // 2. Damage Assessment & Scope
      document.getElementById('archive-modal-damage-type').innerText = report.damageType || 'General Wear';

      const dLen = parseFloat(report.damageLength) || 0;
      const dWid = parseFloat(report.damageWidth) || 0;
      const dArea = dLen * dWid;
      document.getElementById('archive-modal-damage-dims').innerText = (dLen > 0 || dWid > 0) ? `${dLen}m (L) × ${dWid}m (W)` : 'Not specified';
      document.getElementById('archive-modal-damage-area').innerText = `${dArea > 0 ? dArea.toFixed(1) : '0.0'} sq.m`;

      // 🧠 3. AI Severity Badge
      const rawSev = String(report.severity || '').trim().toLowerCase();
      const confScore = report.cvConfidenceScore || report.cvconfidenceScore;
      const confText = confScore ? ` (${confScore}%)` : '';
      const priorityBadge = document.getElementById('archive-modal-priority-badge');

      if (priorityBadge) {
        if (rawSev === 'high') {
          priorityBadge.innerText = `HIGH${confText}`;
          priorityBadge.style.cssText = 'background-color: #dc2626; color: #ffffff; border: 1px solid #b91c1c; font-weight: 800; font-size: 11px; padding: 3px 8px; border-radius: 5px; display: inline-block; letter-spacing: 0.3px;';
        } else if (rawSev === 'medium') {
          priorityBadge.innerText = `MEDIUM${confText}`;
          priorityBadge.style.cssText = 'background-color: #f59e0b; color: #ffffff; border: 1px solid #d97706; font-weight: 800; font-size: 11px; padding: 3px 8px; border-radius: 5px; display: inline-block; letter-spacing: 0.3px;';
        } else if (rawSev === 'low') {
          priorityBadge.innerText = `LOW${confText}`;
          priorityBadge.style.cssText = 'background-color: #16a34a; color: #ffffff; border: 1px solid #15803d; font-weight: 800; font-size: 11px; padding: 3px 8px; border-radius: 5px; display: inline-block; letter-spacing: 0.3px;';
        } else {
          priorityBadge.innerText = 'UNASSESSED';
          priorityBadge.style.cssText = 'background-color: #ecfdf5; color: #047857; border: 1.5px solid #34d399; font-weight: 800; font-size: 11px; padding: 3px 8px; border-radius: 5px; display: inline-block; letter-spacing: 0.3px;';
        }
      }

      document.getElementById('archive-modal-gps').innerText = (currentArchiveLat !== 0 && currentArchiveLng !== 0)
        ? `${currentArchiveLat.toFixed(6)}°, ${currentArchiveLng.toFixed(6)}°`
        : 'No GPS Data';

      let submitter = 'Barangay Official';
      if (report.user && report.user.firstName && report.user.lastName) {
        submitter = `${report.user.firstName} ${report.user.lastName}`;
      } else if (report.reportedBy) {
        submitter = report.reportedBy;
      }
      document.getElementById('archive-modal-submitter').innerText = submitter;
      document.getElementById('archive-modal-description').innerText = report.damageDescription || 'No description provided.';

      // 4. Photographic Evidence
      if (report.damageImage && report.damageImage !== 'no_image.jpg' && report.damageImage.trim() !== '') {
        if (typeof loadSecureImage === 'function') {
          loadSecureImage('archive-modal-damage-image', report.damageImage);
        } else {
          const dmgEl = document.getElementById('archive-modal-damage-image');
          if (dmgEl) dmgEl.src = `/api/reports/image/${encodeURIComponent(report.damageImage)}`;
        }
      } else {
        const dmgEl = document.getElementById('archive-modal-damage-image');
        if (dmgEl) dmgEl.src = NO_IMAGE_PLACEHOLDER;
      }

      if (report.proofOfRepairImage && report.proofOfRepairImage !== 'no_image.jpg' && report.proofOfRepairImage.trim() !== '') {
        if (proofContainer) proofContainer.style.display = 'block';
        if (typeof loadSecureImage === 'function') {
          loadSecureImage('archive-modal-proof-image', report.proofOfRepairImage);
        } else {
          const proofEl = document.getElementById('archive-modal-proof-image');
          if (proofEl) proofEl.src = `/api/reports/image/${encodeURIComponent(report.proofOfRepairImage)}`;
        }
      } else {
        if (proofContainer) proofContainer.style.display = 'none';
      }

      // 5. Remarks
      document.getElementById('archive-modal-admin-remarks').innerText = report.adminRemarks || 'None logged';
      document.getElementById('archive-modal-repair-remarks').innerText = report.repairRemarks || 'None logged';

      // 🕒 Load Timeline into the Archive Accordion
      if (typeof loadReportTimeline === 'function') {
        loadReportTimeline(report.id, 'archive-modal-timeline');
      }

      // 🚀 Final Scroll Reset after rendering all details
      if (scrollableBody) scrollableBody.scrollTop = 0;
      if (typeof resetModalScroll === 'function') resetModalScroll(modal);
    })
    .catch(err => {
      console.error('Error fetching archive report detail:', err);
      if (typeof showToast === 'function') showToast('Failed to load project audit details.', 'error');
    });
};

// =======================================================
// 🗺️ DIRECT GLOBAL MAP TOGGLE
// =======================================================
window.toggleArchiveMap = function() {
  const container = document.getElementById('archive-detail-map-container');
  if (!container) return;

  if (!currentArchiveLat || !currentArchiveLng || (currentArchiveLat === 0 && currentArchiveLng === 0)) {
    showToast("No GPS coordinates recorded for this project.", "warning");
    return;
  }

  if (container.style.display === 'none' || container.style.display === '') {
    container.style.display = 'block';

    if (typeof L === 'undefined') {
      console.error("Leaflet is not loaded.");
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

    if (!archiveDetailMap) {
      archiveDetailMap = L.map('archive-detail-map').setView([currentArchiveLat, currentArchiveLng], 17);
      L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
        attribution: 'Tiles &copy; Esri &mdash; Source: Esri'
      }).addTo(archiveDetailMap);

      archiveDetailMarker = L.marker([currentArchiveLat, currentArchiveLng], { icon: redIcon }).addTo(archiveDetailMap);
    } else {
      archiveDetailMap.setView([currentArchiveLat, currentArchiveLng], 17);
      archiveDetailMarker.setLatLng([currentArchiveLat, currentArchiveLng]);
    }

    setTimeout(() => {
      archiveDetailMap.invalidateSize();
    }, 200);
  } else {
    container.style.display = 'none';
  }
};

// Close modal handler
window.closeArchiveDetailModal = function() {
  const modal = document.getElementById('archive-detail-modal');
  if (modal) {
    modal.classList.add('hidden');
    modal.style.display = 'none';
  }
  const mapContainer = document.getElementById('archive-detail-map-container');
  if (mapContainer) mapContainer.style.display = 'none';
};

// Print handler
window.printSingleArchiveIncident = function() {
  const prjId = document.getElementById('archive-modal-prj-id')?.innerText.replace(/\D/g, '') || '0000';
  const originalTitle = document.title;
  document.title = `Project_Audit_Sheet_PRJ-${prjId}`;

  window.print();

  setTimeout(() => {
    document.title = originalTitle;
  }, 1000);
};

// =======================================================
// 📂 8. EXPORT DROPDOWN MENU CONTROLLER
// =======================================================
window.toggleExportMenu = function(menuId) {
  const menu = document.getElementById(menuId);
  if (!menu) return;

  if (menu.classList.contains('hidden')) {
    document.querySelectorAll('.export-menu').forEach(m => m.classList.add('hidden'));
    menu.classList.remove('hidden');
  } else {
    menu.classList.add('hidden');
  }
};

// Helper: Official Name Formatter (First M.I. Last)

// =======================================================
// 👁️ 9. OPEN MASTERLIST PREVIEW MODALS (WITH MIDDLE INITIAL)
// =======================================================
window.generateAdminReport = function(type) {
  // 🚀 1. Build Admin Name with Middle Initial (e.g., JM L. POGIII)
  const adminFirst = sessionStorage.getItem("firstName") || "";
  const adminMiddle = sessionStorage.getItem("middleName") || "";
  const adminLast = sessionStorage.getItem("lastName") || "";
  const adminFormattedName = formatOfficialName(adminFirst, adminMiddle, adminLast);

  const today = new Date().toLocaleDateString("en-US", { year: "numeric", month: "long", day: "numeric" });

  if (type === 'INVENTORY') {
    const year = document.getElementById('archive-inventory-year')?.value || "ALL";
    const modal = document.getElementById('archive-inventory-preview-modal');
    if (modal) {
      modal.classList.remove('hidden');
      modal.style.display = 'flex';
    }

    const yearEl = document.getElementById('archive-preview-inventory-year');
    const dateEl = document.getElementById('archive-preview-inventory-date');
    const adminEl = document.getElementById('archive-preview-inventory-admin');

    if (yearEl) yearEl.innerText = year === "ALL" ? "ALL RECORDED YEARS" : `${year} CYCLE`;
    if (dateEl) dateEl.innerText = today;
    if (adminEl) adminEl.innerText = adminFormattedName;

    renderArchivePreviewInventory(year);
  }
  else if (type === 'PRIORITY') {
    const year = document.getElementById('archive-priority-year')?.value || "ALL";
    const modal = document.getElementById('archive-priority-preview-modal');
    if (modal) {
      modal.classList.remove('hidden');
      modal.style.display = 'flex';
    }

    const yearEl = document.getElementById('archive-preview-priority-year');
    const dateEl = document.getElementById('archive-preview-priority-date');
    const adminEl = document.getElementById('archive-preview-priority-admin');

    if (yearEl) yearEl.innerText = year === "ALL" ? "ALL RECORDED YEARS" : `${year} CYCLE`;
    if (dateEl) dateEl.innerText = today;
    if (adminEl) adminEl.innerText = adminFormattedName;

    renderArchivePreviewPriority(year);
  }
};

// =======================================================
// 📊 10. RENDER ANNUAL ROAD INVENTORY MODAL TABLE
// =======================================================
window.renderArchivePreviewInventory = function(selectedYear) {
  const tbody = document.getElementById('archive-preview-inventory-tbody');
  const tfoot = document.getElementById('archive-preview-inventory-tfoot');
  if (!tbody) return;

  tbody.innerHTML = `<tr><td colspan="15" style="text-align: center; padding: 25px; color: #64748b;">Loading City Road Inventory records...</td></tr>`;

  apiFetch("/api/reports").then(reports => {
    let list = selectedYear === "ALL" ? reports : reports.filter(r => getReportYear(r) === String(selectedYear));

    const uniqueMap = new Map();
    list.forEach(r => {
      const key = String(r.cityRoadId || r.cityRoadName || r.id).trim().toLowerCase();
      if (!uniqueMap.has(key)) uniqueMap.set(key, r);
      else if (new Date(r.createdAt || 0) > new Date(uniqueMap.get(key).createdAt || 0)) uniqueMap.set(key, r);
    });
    let deduped = Array.from(uniqueMap.values());
    deduped.sort((a, b) => Number(String(a.cityRoadId || a.id).replace(/\D/g, '')) - Number(String(b.cityRoadId || b.id).replace(/\D/g, '')));

    cacheArchiveInventory = deduped;

    if (deduped.length === 0) {
      tbody.innerHTML = `<tr><td colspan="15" style="text-align: center; padding: 25px;">No records found for ${selectedYear}.</td></tr>`;
      if (tfoot) tfoot.innerHTML = "";
      return;
    }

    let sums = { len: 0, asp: 0, grv: 0, ert: 0, con: 0, mix: 0, cul: 0, brg: 0 };

    tbody.innerHTML = deduped.map((r, i) => {
      const id = r.cityRoadId || `31420000${String(r.id).padStart(2, '0')}`;
      const name = r.cityRoadName || "Unnamed";
      const km = parseToKilometers(r.length);
      const typ = (r.roadType || "").toLowerCase();
      const w = r.width != null && !isNaN(r.width) && String(r.width).trim() !== "" ? Number(r.width).toFixed(2) : "N/A";

      const asp = typ.includes("asphalt") ? km : 0;
      const grv = typ.includes("gravel") ? km : 0;
      const ert = typ.includes("earth") ? km : 0;
      const con = (typ.includes("concrete") || typ.includes("paved") || typ === "") ? km : 0;
      const mix = typ.includes("mixed") ? km : 0;
      const cul = parseFloat(r.lengthOfCulverts) || 0;
      const brg = parseInt(r.numberOfBridges) || 0;

      sums.len += km; sums.asp += asp; sums.grv += grv; sums.ert += ert; sums.con += con; sums.mix += mix; sums.cul += cul; sums.brg += brg;

      return `
        <tr style="background: ${i % 2 === 0 ? '#fff' : '#f8fafc'}; border-bottom: 1px solid #cbd5e1;">
          <td style="padding: 7px 6px; border: 1px solid #cbd5e1; text-align: center; font-family: monospace; font-weight: 700;">${id}</td>
          <td style="padding: 7px 6px; border: 1px solid #cbd5e1; font-weight: bold;">${name}</td>
          <td style="padding: 7px 4px; border: 1px solid #cbd5e1; text-align: center;">City</td>
          <td style="padding: 7px 5px; border: 1px solid #cbd5e1; text-align: right; font-weight: 700;">${km.toFixed(3)}</td>
          <td style="padding: 7px 5px; border: 1px solid #cbd5e1; text-align: right;">${w}</td>
          <td style="padding: 7px 5px; border: 1px solid #cbd5e1; text-align: center;">${asp > 0 ? asp.toFixed(3) : '0'}</td>
          <td style="padding: 7px 5px; border: 1px solid #cbd5e1; text-align: center;">${grv > 0 ? grv.toFixed(3) : '0'}</td>
          <td style="padding: 7px 5px; border: 1px solid #cbd5e1; text-align: center;">${ert > 0 ? ert.toFixed(3) : '0'}</td>
          <td style="padding: 7px 5px; border: 1px solid #cbd5e1; text-align: center;">${con > 0 ? con.toFixed(3) : '0'}</td>
          <td style="padding: 7px 5px; border: 1px solid #cbd5e1; text-align: center;">${mix > 0 ? mix.toFixed(3) : '0'}</td>
          <td style="padding: 7px 6px; border: 1px solid #cbd5e1; text-align: center;">${r.roadImportance?.includes("non") ? "Non-Core" : "Core"}</td>
          <td style="padding: 7px 6px; border: 1px solid #cbd5e1; text-align: center;">${typeof formatTerrainType === 'function' ? formatTerrainType(r.terrainType) : (r.terrainType || 'FLAT')}</td>
          <td style="padding: 7px 6px; border: 1px solid #cbd5e1; text-align: right;">${cul.toFixed(2)}</td>
          <td style="padding: 7px 6px; border: 1px solid #cbd5e1; text-align: center;">${brg}</td>
          <td style="padding: 7px 6px; border: 1px solid #cbd5e1; text-align: center; font-weight: 600;">${typeof formatInventoryDate === 'function' ? formatInventoryDate(r) : 'N/A'}</td>
        </tr>`;
    }).join("");

    if (tfoot) {
      tfoot.innerHTML = `
        <tr style="background: #e2e8f0; color: #0f172a; font-size: 11px;">
          <td colspan="3" style="padding: 8px 6px; border: 1px solid #94a3b8; text-align: right; font-weight: 800;">TOTALS:</td>
          <td style="padding: 8px 5px; border: 1px solid #94a3b8; text-align: right; font-weight: 800;">${sums.len.toFixed(3)}</td>
          <td style="padding: 8px 5px; border: 1px solid #94a3b8; text-align: center; color: #64748b;">-</td>
          <td style="padding: 8px 5px; border: 1px solid #94a3b8; text-align: center; font-weight: 800;">${sums.asp.toFixed(3)}</td>
          <td style="padding: 8px 5px; border: 1px solid #94a3b8; text-align: center; font-weight: 800;">${sums.grv.toFixed(3)}</td>
          <td style="padding: 8px 5px; border: 1px solid #94a3b8; text-align: center; font-weight: 800;">${sums.ert.toFixed(3)}</td>
          <td style="padding: 8px 5px; border: 1px solid #94a3b8; text-align: center; font-weight: 800;">${sums.con.toFixed(3)}</td>
          <td style="padding: 8px 5px; border: 1px solid #94a3b8; text-align: center; font-weight: 800;">${sums.mix.toFixed(3)}</td>
          <td colspan="2" style="padding: 8px 6px; border: 1px solid #94a3b8; text-align: center; font-size: 10px; color: #475569;">${deduped.length} Unique Roads</td>
          <td style="padding: 8px 6px; border: 1px solid #94a3b8; text-align: right; font-weight: 800;">${sums.cul.toFixed(2)}</td>
          <td style="padding: 8px 6px; border: 1px solid #94a3b8; text-align: center; font-weight: 800;">${sums.brg}</td>
          <td style="padding: 8px 6px; border: 1px solid #94a3b8; text-align: center; color: #64748b;">-</td>
        </tr>`;
    }
  });
};

// =======================================================
// 🚨 11. CARD 2: RENDER PRIORITY LIST MODAL TABLE
// =======================================================
window.renderArchivePreviewPriority = function(selectedYear) {
  const tbody = document.getElementById('archive-preview-priority-tbody');
  if (!tbody) return;

  tbody.innerHTML = `<tr><td colspan="6" style="text-align: center; padding: 25px; color: #64748b;">Loading Priority List records...</td></tr>`;

  apiFetch('/api/reports')
    .then(reports => {
      let backlog = (Array.isArray(reports) ? reports : []).filter(r => {
        const stat = String(r.status || '').toLowerCase();
        return stat === 'validated' || stat === 'pending budget' || stat === 'dispatched' || stat === 'defer';
      });

      if (selectedYear !== 'ALL') {
        backlog = backlog.filter(r => getReportYear(r) === String(selectedYear));
      }

      if (backlog.length === 0) {
        tbody.innerHTML = `<tr><td colspan="6" style="text-align: center; padding: 25px; color: #64748b;">No backlog records found for ${selectedYear === 'ALL' ? 'all recorded years' : selectedYear + ' cycle'}.</td></tr>`;
        cacheArchivePriority = [];
        return;
      }

      backlog.forEach(report => {
        const severity = String(report.severity || 'Unassessed').toLowerCase();
        const importance = String(report.roadImportance || '').toLowerCase();

        report.tierScore = 0;
        report.tierLabel = 'PENDING AI';
        report.tierColor = '#6c757d';

        if (severity === 'high') {
          report.tierScore = 3; report.tierLabel = 'HIGH'; report.tierColor = '#dc3545';
        } else if (severity === 'medium') {
          if (importance.includes('core')) {
            report.tierScore = 3; report.tierLabel = 'HIGH'; report.tierColor = '#dc3545';
          } else {
            report.tierScore = 2; report.tierLabel = 'MEDIUM'; report.tierColor = '#ff8c00';
          }
        } else if (severity === 'low') {
          if (importance.includes('core')) {
            report.tierScore = 2; report.tierLabel = 'MEDIUM'; report.tierColor = '#ff8c00';
          } else {
            report.tierScore = 1; report.tierLabel = 'LOW'; report.tierColor = '#28a745';
          }
        }

        const dLength = parseFloat(report.damageLength) || 0;
        const dWidth = parseFloat(report.damageWidth) || 0;
        report.areaScore = dLength * dWidth;
      });

      backlog.sort((a, b) => {
        if (b.tierScore !== a.tierScore) return b.tierScore - a.tierScore;
        if (b.areaScore !== a.areaScore) return b.areaScore - a.areaScore;
        return Number(a.id) - Number(b.id);
      });

      cacheArchivePriority = backlog;

      tbody.innerHTML = backlog.map((report, index) => {
        const formatId = `#PRJ-${String(report.id).padStart(4, '0')}`;
        const formatName = report.cityRoadName || 'Unnamed Road';
        const formatBrgy = (report.barangay && report.barangay.barangayName) ? report.barangay.barangayName : (report.barangayName || 'Unknown');
        const formatDamage = report.damageType || 'Unspecified';
        const dLength = report.damageLength || 0;
        const dWidth = report.damageWidth || 0;

        return `
          <tr style="border-bottom: 1px solid #e2e8f0; background: ${index % 2 === 0 ? '#ffffff' : '#f8fafc'};">
            <td style="padding: 10px 8px; border: 1px solid #cbd5e1; text-align: center;"><strong>${index + 1}</strong></td>
            <td style="padding: 10px 8px; border: 1px solid #cbd5e1; font-weight: 700;">${formatId}</td>
            <td style="padding: 10px 8px; border: 1px solid #cbd5e1;">
              <strong>${formatName}</strong><br>
              <span style="font-size: 11px; color: #64748b;">Brgy. ${formatBrgy}</span>
            </td>
            <td style="padding: 10px 8px; border: 1px solid #cbd5e1;">${formatDamage}</td>
            <td style="padding: 10px 8px; border: 1px solid #cbd5e1; font-size: 11px;">${dLength}m × ${dWidth}m</td>
            <td style="padding: 10px 8px; border: 1px solid #cbd5e1; text-align: center; font-weight: 800; color: ${report.tierColor};">${report.tierLabel}</td>
          </tr>
        `;
      }).join('');
    })
    .catch(err => {
      console.error('Error rendering archive priority list:', err);
      if (typeof showToast === 'function') showToast('Error loading priority list records.', 'error');
    });
};

// =======================================================
// 📥 12. CSV TRIGGER & DOWNLOAD CONTROLLER (CARD 1 & CARD 2)
// =======================================================
window.triggerArchiveCSV = function(type) {
  if (typeof showToast === "function") showToast("Preparing your spreadsheet...", "success");

  if (type === 'INVENTORY') {
    const year = document.getElementById('archive-inventory-year')?.value || "ALL";
    renderArchivePreviewInventory(year);
    setTimeout(() => { downloadArchiveCSV('INVENTORY', year); }, 600);
  } else if (type === 'PRIORITY') {
    const year = document.getElementById('archive-priority-year')?.value || "ALL";
    renderArchivePreviewPriority(year);
    setTimeout(() => { downloadArchiveCSV('PRIORITY', year); }, 600);
  }
};

window.downloadArchiveCSV = function(type, year) {
  let headers, rows, filename;

  if (type === 'INVENTORY') {
    if (!cacheArchiveInventory || cacheArchiveInventory.length === 0) {
      if (typeof showToast === "function") showToast("No inventory records to export.", "error");
      return;
    }
    headers = [
      "Road ID", "Road Name", "Class", "Length (km)", "Width (m)",
      "Asphalt (km)", "Gravel (km)", "Earth (km)", "Concrete (km)", "Mixed (km)",
      "Road Importance", "Terrain Type", "Length of Culverts (m)", "Number of Bridges", "Date Inspected"
    ];
    rows = cacheArchiveInventory.map(r => {
      const roadId = r.cityRoadId || `31420000${String(r.id).padStart(2, '0')}`;
      const totalKm = parseToKilometers(r.length);
      const roadType = (r.roadType || "").toLowerCase();
      const roadWidth = r.width != null && !isNaN(r.width) && String(r.width).trim() !== "" ? Number(r.width).toFixed(2) : "";

      return [
        `"${roadId}"`, `"${(r.cityRoadName || "Unnamed").replace(/"/g, '""')}"`, `"City"`, `"${totalKm.toFixed(3)}"`,
        `"${roadWidth}"`,
        `"${roadType.includes("asphalt") ? totalKm.toFixed(3) : "0"}"`,
        `"${roadType.includes("gravel") ? totalKm.toFixed(3) : "0"}"`,
        `"${roadType.includes("earth") ? totalKm.toFixed(3) : "0"}"`,
        `"${(roadType.includes("concrete") || roadType.includes("paved") || roadType === "") ? totalKm.toFixed(3) : "0"}"`,
        `"${roadType.includes("mixed") ? totalKm.toFixed(3) : "0"}"`,
        `"${r.roadImportance?.includes("non") ? "Non-Core" : "Core"}"`,
        `"${typeof formatTerrainType === 'function' ? formatTerrainType(r.terrainType) : (r.terrainType || 'FLAT')}"`,
        `"${r.lengthOfCulverts != null ? Number(r.lengthOfCulverts).toFixed(2) : "0"}"`,
        `"${r.numberOfBridges != null ? r.numberOfBridges : "0"}"`,
        `"${typeof formatInventoryDate === 'function' ? formatInventoryDate(r) : 'N/A'}"`
      ].join(",");
    });
    filename = `CSJDM_Archive_Inventory_${year}_${Date.now()}.csv`;
  }
  else if (type === 'PRIORITY') {
    if (!cacheArchivePriority || cacheArchivePriority.length === 0) {
      if (typeof showToast === "function") showToast("No priority records to export.", "error");
      return;
    }
    headers = ["Rank", "Project ID", "Road Name", "Barangay", "Damage Type", "Est. Area (sq.m)", "Priority Tier"];
    rows = cacheArchivePriority.map((r, i) => [
      `"${i + 1}"`,
      `"PRJ-${String(r.id).padStart(4, '0')}"`,
      `"${(r.cityRoadName || "Unnamed Road").replace(/"/g, '""')}"`,
      `"${(r.barangay?.barangayName || r.barangayName || "Unknown").replace(/"/g, '""')}"`,
      `"${r.damageType || "Unspecified"}"`,
      `"${(parseFloat(r.damageLength) || 0) * (parseFloat(r.damageWidth) || 0)}"`,
      `"${r.tierLabel || "LOW"}"`
    ].join(","));
    filename = `CSJDM_Archive_Priority_Backlog_${year}_${Date.now()}.csv`;
  }

  const csvString = "\uFEFF" + [headers.join(","), ...rows].join("\r\n");
  const blob = new Blob([csvString], { type: "text/csv;charset=utf-8;" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(link.href);
};

// =======================================================
// 🖨️ 13. PDF PRINT MASTERLISTS CONTROLLER
// =======================================================
window.printArchiveDocument = function(modalId) {
  const modal = document.getElementById(modalId);
  if (!modal) return;

  const originalTitle = document.title;
  const isInventory = modalId.includes('inventory');
  const yearEl = document.getElementById(isInventory ? 'archive-inventory-year' : 'archive-priority-year');
  const year = yearEl?.value || "ALL";

  document.title = isInventory
    ? `CSJDM_Archive_Road_Inventory_${year}`
    : `CSJDM_Archive_Priority_Masterlist_${year}`;

  window.print();

  setTimeout(() => {
    document.title = originalTitle;
  }, 1000);
};

// =======================================================
// 🚀 14. AUTO-INITIALIZE ON TAB LOAD OR CLICK
// =======================================================
document.addEventListener("DOMContentLoaded", () => {
  setTimeout(() => {
    if (typeof window.initArchiveTab === "function") window.initArchiveTab();
  }, 500);
});

document.addEventListener("click", (e) => {
  if (e.target.closest("li[data-target='view-archive']")) {
    if (typeof window.initArchiveTab === "function") window.initArchiveTab();
  }
});


