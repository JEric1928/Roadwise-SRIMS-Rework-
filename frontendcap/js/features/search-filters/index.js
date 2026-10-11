/**
 * Dashboard search/filter helpers plus the navigation auto-reset bindings.
 *
 * Extracted verbatim from app.js. Exposes the same window.filter* functions
 * that the dashboard templates call from inline handlers, and self-registers
 * the input listeners and the navigation auto-reset listener.
 */

// ==========================================
// 🔍 CEO REPORTS: SEARCH & PRIORITY FILTER
// ==========================================
window.filterCEOReports = function() {
  const searchInput = document.getElementById('ceoSearch');
  const priorityFilter = document.getElementById('ceo-priority-filter');

  // Target the specific CEO Deploy Masterlist table rows
  const tableRows = document.querySelectorAll('#deploy-master-table tr');

  if (!searchInput || !priorityFilter) return;

  const searchTerm = searchInput.value.toLowerCase().trim();
  const filterValue = priorityFilter.value.toLowerCase();

  tableRows.forEach(row => {
    // Skip empty state rows
    if (row.cells.length < 2) return;

    const rowText = row.textContent.toLowerCase();

    // Check Search Bar
    const matchesSearch = rowText.includes(searchTerm);

    // Check Priority Dropdown
    let matchesPriority = false;
    if (filterValue === 'all') {
      matchesPriority = true;
    } else if (rowText.includes(filterValue)) { // Matches "High", "Medium", or "Low"
      matchesPriority = true;
    }

    // Show row only if BOTH match
    if (matchesSearch && matchesPriority) {
      row.style.display = '';
    } else {
      row.style.display = 'none';
    }
  });
};

// ==========================================
// 🔍 ADMIN REPORTS: SEARCH & DROPDOWN FILTER
// ==========================================
window.filterAdminReports = function() {
  const searchInput = document.getElementById('adminSearch');
  const statusFilter = document.getElementById('admin-status-filter');

  // 🚀 THE FIX: This magically finds ANY table rows inside the View Reports tab!
  const tableRows = document.querySelectorAll('#view-reports table tbody tr');

  if (!searchInput || !statusFilter) return;

  const searchTerm = searchInput.value.toLowerCase().trim();
  const filterValue = statusFilter.value.toLowerCase();

  tableRows.forEach(row => {
    // Skip empty state rows (like "Loading..." or "No data")
    if (row.cells.length < 2) return;

    // Grab all text in the row for the search text
    const rowText = row.textContent.toLowerCase();

    // Condition A: Does the text match the search bar?
    const matchesSearch = rowText.includes(searchTerm);

    // Condition B: Does the status match the dropdown?
    let matchesStatus = false;
    if (filterValue === 'all') {
      matchesStatus = true;
    } else if (rowText.includes(filterValue)) {
      matchesStatus = true;
    }

    // 🚀 Show the row ONLY if both conditions are met!
    if (matchesSearch && matchesStatus) {
      row.style.display = '';
    } else {
      row.style.display = 'none';
    }
  });
};

// ==========================================
// 🔍 REPAIR TRACKING: SEARCH & DROPDOWN FILTER
// ==========================================
window.filterTrackingReports = function() {
  const searchInput = document.getElementById('trackSearch');
  const statusFilter = document.getElementById('track-status-filter');

  // Magically finds ANY table rows inside the Tracking tab
  const tableRows = document.querySelectorAll('#view-tracking table tbody tr');

  if (!searchInput || !statusFilter) return;

  const searchTerm = searchInput.value.toLowerCase().trim();
  const filterValue = statusFilter.value.toLowerCase();

  tableRows.forEach(row => {
    // Skip empty state rows
    if (row.cells.length < 2) return;

    const rowText = row.textContent.toLowerCase();

    // Check Search Bar
    const matchesSearch = rowText.includes(searchTerm);

    // Check Dropdown Filter
    let matchesStatus = false;
    if (filterValue === 'all') {
      matchesStatus = true;
    } else if (rowText.includes(filterValue)) {
      matchesStatus = true;
    }

    // Show row only if BOTH match
    if (matchesSearch && matchesStatus) {
      row.style.display = '';
    } else {
      row.style.display = 'none';
    }
  });
};

// ==========================================
// 🔍 REUSABLE GLOBAL TABLE SEARCH ENGINE
// ==========================================
/**
 * Searches any table by matching input value against table row text AND optional data attributes (like data-roads).
 * @param {string} inputId - ID of the input field
 * @param {string} tbodyId - ID of the table body (tbody)
 */
window.executeGlobalSearch = function(inputId, tbodyId) {
  const inputEl = document.getElementById(inputId);
  if (!inputEl) return;

  const searchTerm = inputEl.value.toLowerCase().trim();
  const tableRows = document.querySelectorAll(`#${tbodyId} tr`);

  tableRows.forEach(row => {
    // Skip empty state, loading state, or dynamic no-results rows
    if (row.cells.length < 2 || row.classList.contains('no-search-results')) return;

    // 1. Visible text in table cells
    const rowText = row.textContent.toLowerCase();

    // 2. Hidden road list attribute (populated on Barangay rows)
    const roadsData = (row.getAttribute('data-roads') || '').toLowerCase();

    // 3. Match against either visible text OR registered road names
    const isMatch = !searchTerm || rowText.includes(searchTerm) || roadsData.includes(searchTerm);

    row.style.display = isMatch ? '' : 'none';
  });
};

// ==========================================
// 🚀 ATTACH SEARCH LISTENERS
// ==========================================
document.addEventListener('DOMContentLoaded', () => {

  // 1. 🚀 BARANGAY DASHBOARD: Search Bar & Dropdown Listeners
  const brgySearchInput = document.getElementById('report-search-bar');
  const brgyStatusFilter = document.getElementById('report-status-filter');

  if (brgySearchInput) {
    brgySearchInput.addEventListener('input', window.filterBarangayReports);
  }
  if (brgyStatusFilter) {
    brgyStatusFilter.addEventListener('change', window.filterBarangayReports);
  }

  // 2. Global Table Search: Barangay Management (Admin)
  const adminBrgyInput = document.getElementById('search-barangay-input');
  if (adminBrgyInput) {
    adminBrgyInput.addEventListener('input', () => {
      executeGlobalSearch('search-barangay-input', 'barangay-table-body');
    });
  }

  // 3. Global Table Search: User Management (Admin)
  const userInput = document.getElementById('search-user-input');
  if (userInput) {
    userInput.addEventListener('input', () => {
      executeGlobalSearch('search-user-input', 'user-management-tbody');
    });
  }

  // 4. 🚀 NEW: Admin Reports Table Search & Filter Listeners
  const adminSearchInput = document.getElementById('adminSearch');
  const adminStatusFilter = document.getElementById('admin-status-filter');

  if (adminSearchInput) {
    adminSearchInput.addEventListener('input', window.filterAdminReports);
  }
  if (adminStatusFilter) {
    adminStatusFilter.addEventListener('change', window.filterAdminReports);
  }

  // 5. 🚀 NEW: Repair Tracking Table Search & Filter Listeners
  const trackSearchInput = document.getElementById('trackSearch');
  const trackStatusFilter = document.getElementById('track-status-filter');

  if (trackSearchInput) {
    trackSearchInput.addEventListener('input', window.filterTrackingReports);
  }
  if (trackStatusFilter) {
    trackStatusFilter.addEventListener('change', window.filterTrackingReports);
  }

  // 6. 🚀 NEW: CEO Repair Queue Search & Filter Listeners
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

  // Grab every button in your sidebar navigation menu
  const navButtons = document.querySelectorAll('.nav-menu li');

  navButtons.forEach(btn => {
    btn.addEventListener('click', () => {

      // 1. Reset Admin Reports Search & Filter
      const adminSearch = document.getElementById('adminSearch');
      const adminFilter = document.getElementById('admin-status-filter');
      if (adminSearch) adminSearch.value = '';
      if (adminFilter) adminFilter.value = 'All';
      if (typeof window.filterAdminReports === 'function') window.filterAdminReports();

      // 2. Reset Barangay Dashboard Search & Filter
      const brgySearch = document.getElementById('report-search-bar');
      const brgyFilter = document.getElementById('report-status-filter');
      if (brgySearch) brgySearch.value = '';
      if (brgyFilter) brgyFilter.value = 'All';
      if (typeof window.filterBarangayReports === 'function') window.filterBarangayReports();

      // 3. Reset Global Admin Settings Tables (User & Barangay Management)
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

      // 4. Reset Repair Tracking Search & Filter
      const trackSearch = document.getElementById('trackSearch');
      const trackFilter = document.getElementById('track-status-filter');
      if (trackSearch) trackSearch.value = '';
      if (trackFilter) trackFilter.value = 'All';
      if (typeof window.filterTrackingReports === 'function') window.filterTrackingReports();


      // 5. Reset CEO Repair Queue Search & Filter
      const ceoSearch = document.getElementById('ceoSearch');
      const ceoFilter = document.getElementById('ceo-priority-filter');
      if (ceoSearch) ceoSearch.value = '';
      if (ceoFilter) ceoFilter.value = 'All';
      if (typeof window.filterCEOReports === 'function') window.filterCEOReports();


    });
  });
});


