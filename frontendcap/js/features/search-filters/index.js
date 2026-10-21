/**
 * Dashboard search/filter helpers plus the navigation auto-reset bindings.
 *
 * Exposes the same window.filter* functions that dashboard templates call from
 * inline handlers, handles empty table states with "Clear Filters" actions,
 * and self-registers input listeners and navigation auto-resets.
 */


// ==========================================
// SAVED ADMIN INBOX FILTERS
// ==========================================
// The admin inbox search/status filters persist to localStorage so a refresh or
// accidental navigation does not clear a carefully set filter. Saving happens
// inside filterAdminReports (every filter interaction), restoring on boot.
const ADMIN_FILTER_STORAGE_KEY = 'roadwise.admin-filter-state.v1';

function saveAdminFilterState() {
  if (typeof localStorage === 'undefined') return;
  const searchInput = document.getElementById('adminSearch');
  const statusFilter = document.getElementById('admin-status-filter');
  if (!searchInput || !statusFilter) return;
  try {
    const state = { search: searchInput.value, status: statusFilter.value };
    if (!state.search && (!state.status || String(state.status).toLowerCase() === 'all')) {
      localStorage.removeItem(ADMIN_FILTER_STORAGE_KEY);
    } else {
      localStorage.setItem(ADMIN_FILTER_STORAGE_KEY, JSON.stringify(state));
    }
  } catch (e) { /* best-effort */ }
}

function restoreAdminFilterState() {
  if (typeof localStorage === 'undefined' || typeof document === 'undefined') return false;
  let state;
  try {
    const raw = localStorage.getItem(ADMIN_FILTER_STORAGE_KEY);
    if (!raw) return false;
    state = JSON.parse(raw);
  } catch (e) {
    return false;
  }
  if (!state || typeof state !== 'object') return false;

  const searchInput = document.getElementById('adminSearch');
  const statusFilter = document.getElementById('admin-status-filter');
  let restored = false;

  if (searchInput && typeof state.search === 'string') {
    searchInput.value = state.search;
    restored = true;
  }
  if (statusFilter && typeof state.status === 'string') {
    const matches = [...statusFilter.options].some(o => o.value === state.status);
    if (matches) {
      statusFilter.value = state.status;
      restored = true;
    }
  }
  return restored;
}
window.clearCurrentTableFilter = function(triggerEl) {
  const container = triggerEl ? triggerEl.closest('.main-card, .dashboard-card, .tab-pane, section, main') : document;
  if (!container) return;

  const searchInput = container.querySelector('input[type="search"], input[id*="Search"], input[id*="search"]');
  const filterSelect = container.querySelector('select[id*="filter"], select[id*="Filter"]');

  if (searchInput) searchInput.value = '';
  if (filterSelect) filterSelect.value = filterSelect.options[0]?.value || 'All';

  if (typeof window.filterCEOReports === 'function') window.filterCEOReports();
  if (typeof window.filterAdminReports === 'function') window.filterAdminReports();
  if (typeof window.filterTrackingReports === 'function') window.filterTrackingReports();
  if (typeof window.filterBarangayReports === 'function') window.filterBarangayReports();
};

function ensureEmptyTableState(tbody, isMatchFound, colSpan = 6) {
  if (!tbody) return;

  let noRow = tbody.querySelector('tr.no-search-results');
  if (!isMatchFound) {
    if (!noRow) {
      noRow = document.createElement('tr');
      noRow.className = 'no-search-results';
      noRow.innerHTML = `
        <td colspan="${colSpan}" style="text-align: center; padding: 24px; color: #64748b; font-size: 13.5px; background: #f8fafc;">
          No matching records found.
          <button type="button" class="btn-secondary" style="margin-left: 10px; padding: 4px 10px; font-size: 12px; cursor: pointer;" onclick="clearCurrentTableFilter(this)">
            Clear Filters
          </button>
        </td>
      `;
      tbody.appendChild(noRow);
    } else {
      noRow.style.display = '';
    }
  } else if (noRow) {
    noRow.style.display = 'none';
  }
}

// ==========================================
// 🔍 CEO REPORTS: SEARCH & PRIORITY FILTER
// ==========================================
window.filterCEOReports = function() {
  const searchInput = document.getElementById('ceoSearch');
  const priorityFilter = document.getElementById('ceo-priority-filter');
  const tbody = document.getElementById('deploy-master-table');

  if (!searchInput || !priorityFilter || !tbody) return;

  const tableRows = tbody.querySelectorAll('tr:not(.no-search-results)');
  const searchTerm = searchInput.value.toLowerCase().trim();
  const filterValue = priorityFilter.value.toLowerCase();

  let visibleCount = 0;

  tableRows.forEach(row => {
    if (row.cells.length < 2) return;

    const rowText = row.textContent.toLowerCase();
    const matchesSearch = !searchTerm || rowText.includes(searchTerm);

    let matchesPriority = false;
    if (filterValue === 'all') {
      matchesPriority = true;
    } else if (rowText.includes(filterValue)) {
      matchesPriority = true;
    }

    if (matchesSearch && matchesPriority) {
      row.style.display = '';
      visibleCount++;
    } else {
      row.style.display = 'none';
    }
  });

  ensureEmptyTableState(tbody, visibleCount > 0, 7);
};

// ==========================================
// 🔍 ADMIN REPORTS: SEARCH & DROPDOWN FILTER
// ==========================================
window.filterAdminReports = function() {
  saveAdminFilterState();
  const searchInput = document.getElementById('adminSearch');
  const statusFilter = document.getElementById('admin-status-filter');
  const tbody = document.querySelector('#view-reports table tbody');

  if (!searchInput || !statusFilter || !tbody) return;

  const tableRows = tbody.querySelectorAll('tr:not(.no-search-results)');
  const searchTerm = searchInput.value.toLowerCase().trim();
  const filterValue = statusFilter.value.toLowerCase();

  let visibleCount = 0;

  tableRows.forEach(row => {
    if (row.cells.length < 2) return;

    const rowText = row.textContent.toLowerCase();
    const matchesSearch = !searchTerm || rowText.includes(searchTerm);

    let matchesStatus = false;
    if (filterValue === 'all') {
      matchesStatus = true;
    } else if (rowText.includes(filterValue)) {
      matchesStatus = true;
    }

    if (matchesSearch && matchesStatus) {
      row.style.display = '';
      visibleCount++;
    } else {
      row.style.display = 'none';
    }
  });

  ensureEmptyTableState(tbody, visibleCount > 0, 7);
};

// ==========================================
// 🔍 REPAIR TRACKING: SEARCH & DROPDOWN FILTER
// ==========================================
window.filterTrackingReports = function() {
  const searchInput = document.getElementById('trackSearch');
  const statusFilter = document.getElementById('track-status-filter');
  const tbody = document.querySelector('#view-tracking table tbody');

  if (!searchInput || !statusFilter || !tbody) return;

  const tableRows = tbody.querySelectorAll('tr:not(.no-search-results)');
  const searchTerm = searchInput.value.toLowerCase().trim();
  const filterValue = statusFilter.value.toLowerCase();

  let visibleCount = 0;

  tableRows.forEach(row => {
    if (row.cells.length < 2) return;

    const rowText = row.textContent.toLowerCase();
    const matchesSearch = !searchTerm || rowText.includes(searchTerm);

    let matchesStatus = false;
    if (filterValue === 'all') {
      matchesStatus = true;
    } else if (rowText.includes(filterValue)) {
      matchesStatus = true;
    }

    if (matchesSearch && matchesStatus) {
      row.style.display = '';
      visibleCount++;
    } else {
      row.style.display = 'none';
    }
  });

  ensureEmptyTableState(tbody, visibleCount > 0, 8);
};

// ==========================================
// 🔍 REUSABLE GLOBAL TABLE SEARCH ENGINE
// ==========================================
window.executeGlobalSearch = function(inputId, tbodyId) {
  const inputEl = document.getElementById(inputId);
  const tbody = document.getElementById(tbodyId);
  if (!inputEl || !tbody) return;

  const searchTerm = inputEl.value.toLowerCase().trim();
  const tableRows = tbody.querySelectorAll('tr:not(.no-search-results)');

  let visibleCount = 0;

  tableRows.forEach(row => {
    if (row.cells.length < 2) return;

    const rowText = row.textContent.toLowerCase();
    const roadsData = (row.getAttribute('data-roads') || '').toLowerCase();
    const isMatch = !searchTerm || rowText.includes(searchTerm) || roadsData.includes(searchTerm);

    if (isMatch) {
      row.style.display = '';
      visibleCount++;
    } else {
      row.style.display = 'none';
    }
  });

  ensureEmptyTableState(tbody, visibleCount > 0, 6);
};

// ==========================================
// 🚀 ATTACH SEARCH LISTENERS
// ==========================================
document.addEventListener('DOMContentLoaded', () => {

  const brgySearchInput = document.getElementById('report-search-bar');
  const brgyStatusFilter = document.getElementById('report-status-filter');

  if (brgySearchInput) {
    brgySearchInput.addEventListener('input', window.filterBarangayReports);
  }
  if (brgyStatusFilter) {
    brgyStatusFilter.addEventListener('change', window.filterBarangayReports);
  }

  const adminBrgyInput = document.getElementById('search-barangay-input');
  if (adminBrgyInput) {
    adminBrgyInput.addEventListener('input', () => {
      executeGlobalSearch('search-barangay-input', 'barangay-table-body');
    });
  }

  const userInput = document.getElementById('search-user-input');
  if (userInput) {
    userInput.addEventListener('input', () => {
      executeGlobalSearch('search-user-input', 'user-management-tbody');
    });
  }

  // Restore saved admin filter state (if any) before binding live listeners, so
  // a refresh keeps the queue exactly as the user left it. The filter is applied
  // immediately; it early-returns on pages without the admin inbox.
  if (restoreAdminFilterState() && typeof window.filterAdminReports === 'function') {
    window.filterAdminReports();
  }

  const adminSearchInput = document.getElementById('adminSearch');
  const adminStatusFilter = document.getElementById('admin-status-filter');

  if (adminSearchInput) {
    adminSearchInput.addEventListener('input', window.filterAdminReports);
  }
  if (adminStatusFilter) {
    adminStatusFilter.addEventListener('change', window.filterAdminReports);
  }

  const trackSearchInput = document.getElementById('trackSearch');
  const trackStatusFilter = document.getElementById('track-status-filter');

  if (trackSearchInput) {
    trackSearchInput.addEventListener('input', window.filterTrackingReports);
  }
  if (trackStatusFilter) {
    trackStatusFilter.addEventListener('change', window.filterTrackingReports);
  }

  const ceoSearchInput = document.getElementById('ceoSearch');
  const ceoPriorityFilter = document.getElementById('ceo-priority-filter');

  if (ceoSearchInput) {
    ceoSearchInput.addEventListener('input', window.filterCEOReports);
  }
  if (ceoPriorityFilter) {
    ceoPriorityFilter.addEventListener('change', window.filterCEOReports);
  }

});

// ==========================================
// 🧹 AUTO-RESET SEARCH BARS ON NAVIGATION
// ==========================================
document.addEventListener("DOMContentLoaded", () => {
  const navButtons = document.querySelectorAll('.nav-menu li');

  navButtons.forEach(btn => {
    btn.addEventListener('click', () => {

      const adminSearch = document.getElementById('adminSearch');
      const adminFilter = document.getElementById('admin-status-filter');
      if (adminSearch) adminSearch.value = '';
      if (adminFilter) adminFilter.value = 'All';
      if (typeof window.filterAdminReports === 'function') window.filterAdminReports();

      const brgySearch = document.getElementById('report-search-bar');
      const brgyFilter = document.getElementById('report-status-filter');
      if (brgySearch) brgySearch.value = '';
      if (brgyFilter) brgyFilter.value = 'All';
      if (typeof window.filterBarangayReports === 'function') window.filterBarangayReports();

      const adminBrgySearch = document.getElementById('search-barangay-input');
      const adminUserSearch = document.getElementById('search-user-input');

      if (adminBrgySearch) {
        adminBrgySearch.value = '';
        if (typeof window.executeGlobalSearch === 'function') {
          window.executeGlobalSearch('search-barangay-input', 'barangay-table-body');
        }
      }

      if (adminUserSearch) {
        adminUserSearch.value = '';
        if (typeof window.executeGlobalSearch === 'function') {
          window.executeGlobalSearch('search-user-input', 'user-management-tbody');
        }
      }

      const trackSearch = document.getElementById('trackSearch');
      const trackFilter = document.getElementById('track-status-filter');
      if (trackSearch) trackSearch.value = '';
      if (trackFilter) trackFilter.value = 'All';
      if (typeof window.filterTrackingReports === 'function') window.filterTrackingReports();

      const ceoSearch = document.getElementById('ceoSearch');
      const ceoFilter = document.getElementById('ceo-priority-filter');
      if (ceoSearch) ceoSearch.value = '';
      if (ceoFilter) ceoFilter.value = 'All';
      if (typeof window.filterCEOReports === 'function') window.filterCEOReports();

    });
  });
});
