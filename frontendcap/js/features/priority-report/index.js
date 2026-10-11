/**
 * Priority report view: open controller, official print/close actions, and
 * the strict priority list generator.
 *
 * Extracted verbatim from app.js. The original code ran inside the main
 * DOMContentLoaded listener, so the slices are wrapped in their own
 * DOMContentLoaded listener to preserve timing. `generatePriorityList` stays
 * module-scoped (no window bridge): the nav dispatcher guard checked
 * window.generatePriorityList and intentionally skipped it.
 */
import { apiFetch } from '../../core/api.js';
import { showToast } from '../../components/toast.js';
import { formatOfficialName } from '../../core/utils.js';

// ==========================================
// 🧠 THE STRICT PRIORITY ALGORITHM & SIGNATORY LOADER
// ==========================================
export async function generatePriorityList() {
  // 🚀 1. SET DATE
  const dateEl = document.getElementById('priority-doc-date');
  if (dateEl) {
    dateEl.textContent = new Date().toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' });
  }

  // 🚀 2. POPULATE CPDO ADMIN NAME (WITH MIDDLE INITIAL)
  const adminFirst = sessionStorage.getItem("firstName") || "";
  const adminMiddle = sessionStorage.getItem("middleName") || "";
  const adminLast = sessionStorage.getItem("lastName") || "";
  const adminFormattedName = formatOfficialName(adminFirst, adminMiddle, adminLast);

  const adminNameEl = document.getElementById('priority-admin-name');
  if (adminNameEl) {
    adminNameEl.textContent = adminFormattedName || "CPDO Administrator";
  }

  // 🚀 3. DYNAMICALLY FETCH CEO (CITY ENGINEER) NAME
  const ceoNameEl = document.getElementById('priority-ceo-name');
  if (ceoNameEl) {
    try {
      const userRes = await apiFetch(`/api/users`);
      if (Array.isArray(userRes)) {
        const engineerUser = userRes.find(u => {
          const r = String(u.role || '').toUpperCase().trim();
          const s = String(u.status || '').toLowerCase().trim();
          return (r === 'ENGINEER' || r.includes('CEO')) && s !== 'deactivated';
        });

        if (engineerUser) {
          const ceoFormatted = formatOfficialName(
            engineerUser.firstName,
            engineerUser.middleName,
            engineerUser.lastName,
            'Engr.'
          );
          ceoNameEl.textContent = ceoFormatted;
        } else {
          ceoNameEl.textContent = "City Engineer / Department Head";
        }
      }
    } catch (err) {
      console.warn("Could not fetch CEO name, using fallback:", err);
      ceoNameEl.textContent = "City Engineer / Department Head";
    }
  }

  // 🚀 4. FETCH & POPULATE REPORTS TABLE
  apiFetch(`/api/reports`)
    .then(reports => {
      // Filter: Validated reports WITH a valid photographic damage image
      const validatedReports = (Array.isArray(reports) ? reports : []).filter(r => {
        const isValidated = String(r.status || '').toLowerCase().trim() === 'validated';
        const hasValidImage = Boolean(
          r.damageImage &&
          typeof r.damageImage === 'string' &&
          r.damageImage.trim() !== '' &&
          r.damageImage.trim().toLowerCase() !== 'no_image.jpg'
        );
        return isValidated && hasValidImage;
      });

      // Calculate Priority Scores
      validatedReports.forEach(report => {
        const severity = String(report.severity || 'Unassessed').toLowerCase();
        const importance = String(report.roadImportance || '').toLowerCase();

        report.tierScore = 0;
        report.tierLabel = 'PENDING AI';
        report.tierColor = '#6c757d';

        if (severity === 'high') {
          report.tierScore = 3;
          report.tierLabel = 'HIGH';
          report.tierColor = '#dc3545';
        } else if (severity === 'medium') {
          if (importance.includes('core')) {
            report.tierScore = 3;
            report.tierLabel = 'HIGH';
            report.tierColor = '#dc3545';
          } else {
            report.tierScore = 2;
            report.tierLabel = 'MEDIUM';
            report.tierColor = '#ff8c00';
          }
        } else if (severity === 'low') {
          if (importance.includes('core')) {
            report.tierScore = 2;
            report.tierLabel = 'MEDIUM';
            report.tierColor = '#ff8c00';
          } else {
            report.tierScore = 1;
            report.tierLabel = 'LOW';
            report.tierColor = '#28a745';
          }
        }

        const dLength = parseFloat(report.damageLength) || 0;
        const dWidth = parseFloat(report.damageWidth) || 0;
        report.areaScore = dLength * dWidth;
      });

      // Sort: Highest Priority Tier first, then Largest Area
      validatedReports.sort((a, b) => {
        if (b.tierScore !== a.tierScore) {
          return b.tierScore - a.tierScore;
        }
        return b.areaScore - a.areaScore;
      });

      // Render to HTML Table
      const tbody = document.getElementById('priority-table-body') || document.querySelector('.document-table tbody');
      if (!tbody) return;

      tbody.innerHTML = '';

      if (validatedReports.length === 0) {
        tbody.innerHTML = `<tr><td colspan="6" style="text-align: center; padding: 20px; color: #64748b;">No validated reports with damage evidence available for dispatch.</td></tr>`;
        return;
      }

      validatedReports.forEach((report, index) => {
        const formatId = `#PRJ-${String(report.id).padStart(4, '0')}`;
        const formatName = report.cityRoadName || 'Unnamed Road';
        const formatBrgy = (report.barangay && report.barangay.barangayName) ? report.barangay.barangayName : 'Unknown';
        const formatDamage = report.damageType || 'Unspecified';
        const dLength = report.damageLength || 0;
        const dWidth = report.damageWidth || 0;

        const tr = document.createElement('tr');
        tr.innerHTML = `
      <td style="text-align: center; padding: 8px 6px; border: 1px solid #334155;"><strong>${index + 1}</strong></td>
      <td style="padding: 8px 6px; border: 1px solid #334155; font-family: monospace; font-weight: 700;">${formatId}</td>
      <td style="padding: 8px 6px; border: 1px solid #334155;"><strong>${formatName}</strong><br><span style="font-size: 11px; color: #64748b;">Brgy. ${formatBrgy}</span></td>
      <td style="padding: 8px 6px; border: 1px solid #334155;">${formatDamage}</td>
      <td style="padding: 8px 6px; border: 1px solid #334155; text-align: center;">${dLength}m × ${dWidth}m</td>
      <td style="text-align: center; padding: 8px 6px; border: 1px solid #334155; font-weight: 800; color: ${report.tierColor};">${report.tierLabel}</td>
    `;
        tbody.appendChild(tr);
      });
    })
    .catch(err => {
      console.error("Error generating priority list:", err);
      if (typeof showToast === 'function') showToast("Error loading priority list.", "error");
    });
}

if (typeof window !== 'undefined') {
  window.generatePriorityList = generatePriorityList;
}

document.addEventListener('DOMContentLoaded', () => {
// 4. Option 2: Open Priority Repair List View
  window.openPriorityReportList = function(event) {
    if (event) event.stopPropagation();
    const dropdown = document.getElementById("print-dropdown");
    if (dropdown) {
      dropdown.classList.add("hidden");
      dropdown.style.display = "none";
    }

    // Hide all sections
    document.querySelectorAll(".content-section").forEach(sec => {
      sec.classList.add("hidden");
      sec.style.display = "none";
    });
    document.querySelectorAll(".nav-menu li").forEach(l => l.classList.remove("active"));

    // Show Priority View
    const prioritySection = document.getElementById("view-report-priority");
    if (prioritySection) {
      prioritySection.classList.remove("hidden");
      prioritySection.style.display = "block";
    }

    // Generate Priority List
    if (typeof generatePriorityList === "function") generatePriorityList();
  };

// ==========================================
// 10. OFFICIAL REPORT LOGIC (CEO PRIORITY LIST)
// ==========================================
  const btnPrintDocument = document.getElementById('btn-print-document');
  const btnCloseReport = document.getElementById('btn-close-report');
  const viewReportPriority = document.getElementById('view-report-priority');

// Helper: Official Name Formatter with Middle Initial

// 1. Print Official Document (Suppresses browser header & footer stamps)
  if (btnPrintDocument) {
    btnPrintDocument.addEventListener('click', () => {
      const originalTitle = document.title;
      document.title = " ";
      window.print();
      setTimeout(() => {
        document.title = originalTitle;
      }, 1000);
    });
  }

// 2. Back to Reports / Dashboard
  if (btnCloseReport) {
    btnCloseReport.addEventListener('click', () => {
      if (viewReportPriority) {
        viewReportPriority.classList.add('hidden');
        viewReportPriority.style.display = 'none';
      }

      const viewReports = document.getElementById('view-reports');
      if (viewReports) {
        viewReports.classList.remove('hidden');
        viewReports.style.display = 'block';
        return;
      }

      const mainDashboardView =
        document.getElementById('view-dashboard') ||
        document.getElementById('view-overview') ||
        document.querySelector('.content-section:not(#view-report-priority):not(#view-settings)');

      if (mainDashboardView) {
        mainDashboardView.classList.remove('hidden');
        mainDashboardView.style.display = '';
      }
    });
  }
});
