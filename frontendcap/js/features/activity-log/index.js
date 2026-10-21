/**
 * System audit & activity log feature.
 *
 * Owns the audit trail state (`rawActivityLogs`, `filteredActivityLogs`), the
 * table renderer, multi-filter logic, the inspector modal, CSV export, and the
 * printable PDF preview. All HTML-callable functions are bridged through
 * `initActivityLog()`.
 */
import { apiFetch } from '../../core/api.js';
import { showToast } from '../../components/toast.js';
import { escapeHtml, formatOfficialName } from '../../core/utils.js';
import { parseAuditTimestamp, formatAuditTimestamp } from '../../core/audit-time.js';
import { formatTargetEntity, getCategoryBadge, getStatusBadge } from '../../domain/activity-log.js';

let rawActivityLogs = [];
let filteredActivityLogs = [];
// The audit endpoint is paged. rawActivityLogs holds the newest loaded window;
// totalActivityLogEntries is the server's count for the whole trail, so the UI and
// the exports can tell the user when they are looking at a partial view.
let totalActivityLogEntries = 0;

function auditWindowIncomplete() {
  return totalActivityLogEntries > rawActivityLogs.length;
}
let initialized = false;

// 1. Fetch & Initialize Activity Logs
export async function loadActivityLogs() {
  const tbody = document.getElementById("activity-log-tbody");
  const countEl = document.getElementById("activity-log-count");

  const searchInput = document.getElementById("activity-search-input");
  if (searchInput) searchInput.value = "";

  const catSelect = document.getElementById("activity-filter-category");
  if (catSelect) catSelect.value = "ALL";

  const roleSelect = document.getElementById("activity-filter-role");
  if (roleSelect) roleSelect.value = "ALL";

  const timeSelect = document.getElementById("activity-filter-time");
  if (timeSelect) timeSelect.value = "ALL";

  if (tbody) {
    tbody.innerHTML = Array.from({ length: 6 }, () => `
      <tr>
        <td colspan="7"><div class="skeleton" style="height: 20px;"></div></td>
      </tr>`).join('');
  }

  try {
    const ACTIVITY_LOG_PAGE_SIZE = 200; // matches the server-side cap
    const res = await apiFetch(`/api/activity-logs?page=0&size=${ACTIVITY_LOG_PAGE_SIZE}`, { cache: 'no-store' });
    if (!res || !Array.isArray(res)) throw new Error("Invalid response format");

    totalActivityLogEntries = (res.pagination && typeof res.pagination.totalElements === 'number')
      ? res.pagination.totalElements
      : res.length;

    // Guarantee newest logs appear at the top
    rawActivityLogs = res.sort((a, b) => (parseInt(b.id) || 0) - (parseInt(a.id) || 0));
    filteredActivityLogs = [...rawActivityLogs];
    renderActivityLogsTable(filteredActivityLogs);
  } catch (err) {
    console.error("Failed to load activity logs:", err);
    if (tbody) {
      tbody.innerHTML = `
        <tr>
          <td colspan="7" style="text-align: center; padding: 30px; color: #ef4444; font-weight: 600;">
            ⚠️ Failed to load activity logs. Please check server connection.
          </td>
        </tr>`;
    }
    if (countEl) countEl.innerText = "Showing 0 log entries";
  }
}

// 2. Render Table Rows
function renderActivityLogsTable(logs) {
  const tbody = document.getElementById("activity-log-tbody");
  const countEl = document.getElementById("activity-log-count");
  if (!tbody) return;

  if (countEl) {
    const windowNote = auditWindowIncomplete()
      ? ` (newest ${rawActivityLogs.length} of ${totalActivityLogEntries} on server)`
      : '';
    countEl.innerText = `Showing ${logs.length} of ${rawActivityLogs.length} log entries${windowNote}`;
  }

  if (logs.length === 0) {
    tbody.innerHTML = `
      <tr>
        <td colspan="7" style="text-align: center; padding: 35px; color: #64748b; font-style: italic;">
          No audit logs match the selected filter criteria.
        </td>
      </tr>`;
    return;
  }

  tbody.innerHTML = logs.map(log => {
    const dateFormatted = formatAuditTimestamp(log.timestamp);
    const categoryBadge = getCategoryBadge(log.category);
    const statusBadge = getStatusBadge(log.status);
    const targetEntityInfo = formatTargetEntity(log.targetEntity);

    return `
      <tr style="border-bottom: 1px solid #e2e8f0; transition: background 0.15s;" onmouseover="this.style.background='#f8fafc'" onmouseout="this.style.background='white'">
        <td style="padding: 10px 12px; font-family: monospace; font-size: 11px; text-align: center; color: #475569; white-space: nowrap;">
          ${dateFormatted}
        </td>
        <td style="padding: 10px 12px;">
          <strong style="color: #0f172a; display: block; font-size: 12px;">${escapeHtml(log.actorName || 'System')}</strong>
          <span style="font-size: 10.5px; color: #64748b;">${escapeHtml(log.actorRole || 'SYSTEM')} • ${escapeHtml(log.actorOffice || 'Central')}</span>
        </td>
        <td style="padding: 10px 12px; text-align: center;">
          ${categoryBadge}
        </td>
        <td style="padding: 10px 12px; text-align: center;">
          ${targetEntityInfo.html}
        </td>
        <td style="padding: 10px 12px; color: #334155; line-height: 1.4;">
          <strong style="font-size: 11px; color: #0f172a; display: block;">${escapeHtml(log.action || 'ACTION')}</strong>
          <span style="font-size: 11.5px; color: #475569;">${escapeHtml(log.description || '-')}</span>
        </td>
        <td style="padding: 10px 12px; text-align: center;">
          ${statusBadge}
        </td>
        <td style="padding: 10px 12px; text-align: center;">
          <button type="button" onclick="inspectActivityLog(${log.id})" style="padding: 4px 8px; background: #f1f5f9; color: #2563eb; border: 1px solid #cbd5e1; border-radius: 4px; font-size: 11px; font-weight: 700; cursor: pointer;" title="Inspect Details">
            View
          </button>
        </td>
      </tr>`;
  }).join('');
}

// 3. Multi-Filter Logic
export function filterActivityLogsTable() {
  const query = (document.getElementById("activity-search-input")?.value || "").toLowerCase().trim();
  const cat = document.getElementById("activity-filter-category")?.value || "ALL";
  const role = document.getElementById("activity-filter-role")?.value || "ALL";
  const timeframe = document.getElementById("activity-filter-time")?.value || "ALL";

  const now = new Date();

  filteredActivityLogs = rawActivityLogs.filter(log => {
    const formattedTarget = formatTargetEntity(log.targetEntity).text.toLowerCase();

    const matchQuery = !query ||
      (log.actorName && log.actorName.toLowerCase().includes(query)) ||
      (log.targetEntity && log.targetEntity.toLowerCase().includes(query)) ||
      formattedTarget.includes(query) ||
      (log.action && log.action.toLowerCase().includes(query)) ||
      (log.description && log.description.toLowerCase().includes(query)) ||
      (log.actorRole && log.actorRole.toLowerCase().includes(query));

    const matchCat = (cat === "ALL") || (log.category && log.category.toUpperCase() === cat);

    const matchRole = (role === "ALL") ||
      (role === "ADMIN" && String(log.actorRole).toUpperCase().includes("ADMIN")) ||
      (role === "CEO" && (String(log.actorRole).toUpperCase().includes("ENGINEER") || String(log.actorRole).toUpperCase().includes("CEO"))) ||
      (role === "BARANGAY" && String(log.actorRole).toUpperCase().includes("BARANGAY")) ||
      (role === "SYSTEM" && String(log.actorRole).toUpperCase().includes("SYSTEM"));

    let matchTime = true;
    if (timeframe !== "ALL") {
      if (!log.timestamp) {
        matchTime = false;
      } else {
        const logDate = parseAuditTimestamp(log.timestamp);
        if (!logDate || isNaN(logDate.getTime())) {
          matchTime = false;
        } else {
          const diffHours = (now - logDate) / (1000 * 60 * 60);
          if (timeframe === "TODAY") matchTime = diffHours >= -0.05 && diffHours <= 24;
          else if (timeframe === "7DAYS") matchTime = diffHours >= -0.05 && diffHours <= (24 * 7);
          else if (timeframe === "30DAYS") matchTime = diffHours >= -0.05 && diffHours <= (24 * 30);
        }
      }
    }

    return matchQuery && matchCat && matchRole && matchTime;
  });

  renderActivityLogsTable(filteredActivityLogs);
}

// 4. Audit Event Inspector Modal
export function inspectActivityLog(logId) {
  const log = rawActivityLogs.find(l => l.id === logId);
  if (!log) return;

  const modal = document.getElementById("activity-detail-modal");
  if (!modal) return;

  document.getElementById("audit-modal-log-id").innerText = `#LOG-${String(log.id).padStart(4, '0')}`;
  document.getElementById("audit-modal-timestamp").innerText = formatAuditTimestamp(log.timestamp);

  const statusBadge = document.getElementById("audit-modal-status-badge");
  if (statusBadge) {
    const s = (log.status || "SUCCESS").toUpperCase();
    statusBadge.innerText = s;
    if (s === "FAILED") {
      statusBadge.style.cssText = "padding: 3px 10px; border-radius: 20px; font-size: 11px; font-weight: 800; background: #fef2f2; color: #dc2626; border: 1px solid #fecaca; display: inline-block;";
    } else if (s === "WARNING") {
      statusBadge.style.cssText = "padding: 3px 10px; border-radius: 20px; font-size: 11px; font-weight: 800; background: #fffbeb; color: #d97706; border: 1px solid #fde68a; display: inline-block;";
    } else {
      statusBadge.style.cssText = "padding: 3px 10px; border-radius: 20px; font-size: 11px; font-weight: 800; background: #f0fdf4; color: #16a34a; border: 1px solid #bbf7d0; display: inline-block;";
    }
  }

  document.getElementById("audit-modal-actor-name").innerText = log.actorName || "System Automation";
  document.getElementById("audit-modal-actor-role").innerText = log.actorRole || "SYSTEM";
  document.getElementById("audit-modal-actor-office").innerText = log.actorOffice || "Central";
  document.getElementById("audit-modal-actor-id").innerText = log.actorId ? `#USR-${String(log.actorId).padStart(4, '0')}` : "SYSTEM";

  document.getElementById("audit-modal-category").innerText = log.category || "SYSTEM";
  document.getElementById("audit-modal-target").innerText = formatTargetEntity(log.targetEntity).text;
  document.getElementById("audit-modal-action").innerText = log.action || "GENERAL_ACTION";
  document.getElementById("audit-modal-description").innerText = log.description || "No description logged.";

  document.getElementById("audit-modal-ip").innerText = log.ipAddress || "127.0.0.1";
  document.getElementById("audit-modal-method").innerText = log.httpMethod || "GET";
  document.getElementById("audit-modal-useragent").innerText = log.userAgent || "Unknown Client / Direct API";

  modal.classList.remove("hidden");
  modal.style.display = "flex";

  requestAnimationFrame(() => {
    const modalBody = document.getElementById("audit-modal-body");
    if (modalBody) {
      modalBody.scrollTop = 0;
      modalBody.scrollTo({ top: 0, behavior: 'instant' });
    }
    modal.scrollTop = 0;
  });
}

// 5. Dropdown Menu Toggle
export function toggleActivityExportDropdown(event) {
  if (event) event.stopPropagation();
  const dropdown = document.getElementById("activity-export-dropdown");
  if (!dropdown) return;

  const isHidden = dropdown.style.display === "none" || dropdown.classList.contains("hidden");
  dropdown.style.display = isHidden ? "block" : "none";
  dropdown.classList.toggle("hidden", !isHidden);
}

function bindOutsideClick() {
  document.addEventListener("click", (e) => {
    const dropdown = document.getElementById("activity-export-dropdown");
    const btn = document.getElementById("btn-activity-export-menu");
    if (dropdown && !dropdown.contains(e.target) && (!btn || !btn.contains(e.target))) {
      dropdown.style.display = "none";
      dropdown.classList.add("hidden");
    }
  });
}

// 6. Export to CSV
export async function exportActivityLogCSV() {
  const dropdown = document.getElementById("activity-export-dropdown");
  if (dropdown) { dropdown.style.display = "none"; dropdown.classList.add("hidden"); }

  if (!filteredActivityLogs || filteredActivityLogs.length === 0) {
    if (typeof showToast === "function") showToast("No activity log records to export.", "warning");
    return;
  }

  if (auditWindowIncomplete() && typeof showToast === "function") {
    showToast(`Export covers the newest ${rawActivityLogs.length} of ${totalActivityLogEntries} log entries.`, "warning");
  }

  const currentUserId = sessionStorage.getItem("userId");
  try {
    await apiFetch('/api/activity-logs/log-action', {
      method: 'POST',
      body: JSON.stringify({
        userId: currentUserId,
        category: 'EXPORT',
        action: 'EXPORT_AUDIT_LOG_CSV',
        targetEntity: 'ACTIVITY_LOGS',
        description: `Exported ${filteredActivityLogs.length} audit trail records to CSV.`
      })
    });
  } catch (e) {
    console.warn("Could not log export action:", e);
  }

  const headers = ["Log ID", "Timestamp (PHT)", "Actor Name", "Actor Role", "Actor Office", "Category", "Action", "Target Entity", "Description", "Status", "IP Address"];
  const rows = filteredActivityLogs.map(l => [
    `#LOG-${String(l.id).padStart(4, '0')}`,
    `"${formatAuditTimestamp(l.timestamp)}"`,
    `"${(l.actorName || '').replace(/"/g, '""')}"`,
    `"${(l.actorRole || '').replace(/"/g, '""')}"`,
    `"${(l.actorOffice || '').replace(/"/g, '""')}"`,
    `"${l.category || ''}"`,
    `"${l.action || ''}"`,
    `"${formatTargetEntity(l.targetEntity).text}"`,
    `"${(l.description || '').replace(/"/g, '""')}"`,
    `"${l.status || 'SUCCESS'}"`,
    `"${l.ipAddress || ''}"`
  ]);

  const csvContent = "data:text/csv;charset=utf-8,\uFEFF" + [headers.join(","), ...rows.map(r => r.join(","))].join("\n");
  const encodedUri = encodeURI(csvContent);
  const link = document.createElement("a");
  link.setAttribute("href", encodedUri);
  link.setAttribute("download", `RoadWise_System_Audit_Log_${new Date().toISOString().slice(0, 10)}.csv`);
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);

  if (typeof showToast === "function") showToast(`Exported ${filteredActivityLogs.length} log records to CSV!`, "success");
}

// 7. Printable PDF Report Preview & Action
export function openActivityLogPrintModal() {
  const dropdown = document.getElementById("activity-export-dropdown");
  if (dropdown) { dropdown.style.display = "none"; dropdown.classList.add("hidden"); }

  const modal = document.getElementById("activity-log-print-modal");
  const tbody = document.getElementById("activity-print-tbody");
  if (!modal || !tbody) return;

  // Metadata injection using toLocaleString for reliable date + time display
  const dateEl = document.getElementById("activity-print-generated-date");
  if (dateEl) {
    dateEl.innerText = new Date().toLocaleString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      timeZone: 'Asia/Manila'
    });
  }

  const catFilter = document.getElementById("activity-filter-category")?.value || "ALL";
  const catEl = document.getElementById("activity-print-category");
  if (catEl) catEl.innerText = catFilter === "ALL" ? "ALL CATEGORIES" : catFilter;

  const timeFilter = document.getElementById("activity-filter-time")?.value || "ALL";
  const timeEl = document.getElementById("activity-print-timeframe");
  if (timeEl) timeEl.innerText = timeFilter === "ALL" ? "All Records" : (timeFilter === "TODAY" ? "Today" : `Last ${timeFilter.replace('DAYS', ' Days')}`);

  // Dynamic Administrator Signer (First M.I. Last)
  const adminFirst = sessionStorage.getItem("firstName") || "";
  const adminMiddle = sessionStorage.getItem("middleName") || "";
  const adminLast = sessionStorage.getItem("lastName") || "";
  const signerEl = document.getElementById("activity-print-signer-name");

  if (signerEl) {
    const formattedSigner = formatOfficialName(adminFirst, adminMiddle, adminLast).toUpperCase();
    signerEl.innerText = formattedSigner || "CPDO ADMINISTRATOR";
  }

  if (auditWindowIncomplete() && typeof showToast === "function") {
    showToast(`Report covers the newest ${rawActivityLogs.length} of ${totalActivityLogEntries} log entries.`, "warning");
  }

  // Populate Printable Rows
  if (filteredActivityLogs.length === 0) {
    tbody.innerHTML = `<tr><td colspan="6" style="text-align: center; padding: 25px; color: #64748b;">No log records matching filter selection.</td></tr>`;
  } else {
    tbody.innerHTML = filteredActivityLogs.map(l => `
      <tr style="border-bottom: 1px solid #cbd5e1;">
        <td style="padding: 6px 8px; text-align: center; font-family: monospace; font-size: 9.5px; border: 1px solid #cbd5e1; white-space: nowrap;">${formatAuditTimestamp(l.timestamp)}</td>
        <td style="padding: 6px 8px; border: 1px solid #cbd5e1;">
          <strong>${escapeHtml(l.actorName || 'System')}</strong><br>
          <span style="font-size: 9px; color: #64748b;">${escapeHtml(l.actorRole || 'SYSTEM')}</span>
        </td>
        <td style="padding: 6px 8px; text-align: center; font-weight: 700; border: 1px solid #cbd5e1;">${escapeHtml(l.category || '-')}</td>
        <td style="padding: 6px 8px; text-align: center; font-family: monospace; border: 1px solid #cbd5e1;">${formatTargetEntity(l.targetEntity).text}</td>
        <td style="padding: 6px 8px; border: 1px solid #cbd5e1;">
          <strong>${escapeHtml(l.action || '-')}</strong>: ${escapeHtml(l.description || '-')}
        </td>
        <td style="padding: 6px 8px; text-align: center; font-weight: 700; border: 1px solid #cbd5e1;">${escapeHtml(l.status || 'SUCCESS')}</td>
      </tr>
    `).join('');
  }

  modal.classList.remove("hidden");
  modal.style.display = "flex";
}

// 8. Clean Print Trigger
export async function printActivityLogSheet() {
  const currentUserId = sessionStorage.getItem("userId");
  try {
    await apiFetch('/api/activity-logs/log-action', {
      method: 'POST',
      body: JSON.stringify({
        userId: currentUserId,
        category: 'EXPORT',
        action: 'PRINT_AUDIT_LOG_PDF',
        targetEntity: 'ACTIVITY_LOGS',
        description: `Generated printable PDF audit report (${filteredActivityLogs.length} records).`
      })
    });
  } catch (e) {
    console.warn("Could not log print action:", e);
  }

  const originalTitle = document.title;
  document.title = " ";
  window.print();

  setTimeout(() => {
    document.title = originalTitle;
  }, 1000);
}

// Helper: Close Activity Print Modal
export function closeActivityLogPrintModal() {
  const modal = document.getElementById("activity-log-print-modal");
  if (modal) {
    modal.classList.add("hidden");
    modal.style.display = "none";
  }
}

/**
 * Idempotent entry point: installs the HTML-callable window bridge and binds
 * the export dropdown outside-click listener once.
 */
export function initActivityLog() {
  if (initialized) return;
  initialized = true;

  window.loadActivityLogs = loadActivityLogs;
  window.filterActivityLogsTable = filterActivityLogsTable;
  window.inspectActivityLog = inspectActivityLog;
  window.toggleActivityExportDropdown = toggleActivityExportDropdown;
  window.exportActivityLogCSV = exportActivityLogCSV;
  window.openActivityLogPrintModal = openActivityLogPrintModal;
  window.printActivityLogSheet = printActivityLogSheet;
  window.closeActivityLogPrintModal = closeActivityLogPrintModal;

  bindOutsideClick();
}