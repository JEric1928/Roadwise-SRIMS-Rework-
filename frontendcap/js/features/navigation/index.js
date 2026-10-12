/**
 * Sidebar navigation and SPA history (switchView).
 *
 * Updates aria-current="page" on active navigation items, manages mobile drawer
 * aria-expanded state and focus management upon view switching.
 */
document.addEventListener('DOMContentLoaded', () => {
// ==========================================
// 1. SIDEBAR NAVIGATION & SPA HISTORY LOGIC
// ==========================================
  const navLinks = document.querySelectorAll('.nav-menu li[data-target]');
  const contentSections = document.querySelectorAll('.content-section');
  const menuToggleBtn = document.querySelector('.menu-toggle, #menu-toggle, .hamburger-btn');
  const sidebarNav = document.querySelector('.sidebar, .nav-sidebar');

// 🗺️ VIEW DISPATCH REGISTRY: Maps views directly to their loaders
  const viewDispatchers = {
    'view-dashboard': () => {
      const role = (sessionStorage.getItem('userRole') || sessionStorage.getItem('role') || '').toUpperCase();
      if (role.includes('CEO') || role.includes('ENGINEER')) {
        if (typeof window.loadCEODashboardData === 'function') window.loadCEODashboardData();
      } else if (role.includes('BARANGAY')) {
        const brgyId = sessionStorage.getItem("barangayId");
        if (brgyId && typeof window.loadBarangayReports === 'function') window.loadBarangayReports(brgyId);
      } else {
        if (typeof window.loadAdminDashboardData === 'function') window.loadAdminDashboardData();
      }
    },
    'view-admin-dashboard': () => {
      if (typeof window.loadAdminDashboardData === 'function') window.loadAdminDashboardData();
    },
    'view-repair': () => {
      if (typeof window.loadCEODashboardData === 'function') window.loadCEODashboardData();
    },
    'view-barangay': () => {
      if (typeof window.loadBarangayManagement === 'function') window.loadBarangayManagement();
    },
    'view-barangay-management': () => {
      if (typeof window.loadBarangayManagement === 'function') window.loadBarangayManagement();
    },
    'view-archive': () => {
      if (typeof window.initializeArchiveModule === 'function') {
        window.initializeArchiveModule();
      } else if (typeof window.loadArchiveDatabaseData === 'function') {
        window.loadArchiveDatabaseData();
      }
    },
    'view-user-management': () => {
      if (typeof window.loadBarangayDropdownForAdmin === 'function') window.loadBarangayDropdownForAdmin();
      if (typeof window.loadUserManagementTable === 'function') window.loadUserManagementTable();
    },
    'view-reports': () => {
      if (typeof window.loadAdminReports === 'function') window.loadAdminReports();
    },
    'view-tracking': () => {
      if (typeof window.loadTrackingData === 'function') window.loadTrackingData();
    },
    'view-profile': () => {
      if (typeof window.populateProfileData === 'function') window.populateProfileData();
      document.querySelectorAll('#profile-nav-menu li:not(.logout-btn)').forEach(l => l.classList.remove('active'));
      document.querySelectorAll('.profile-tab').forEach(t => t.classList.add('hidden'));

      const defaultLink = document.querySelector('#profile-nav-menu li[data-target="tab-identity"]');
      const defaultTab = document.getElementById('tab-identity');
      if (defaultLink) defaultLink.classList.add('active');
      if (defaultTab) defaultTab.classList.remove('hidden');
    },
    'view-report-priority': () => {
      if (typeof window.generatePriorityList === 'function') window.generatePriorityList();
    },
    'view-settings': () => {
      if (typeof window.loadActiveCycleOverview === 'function') window.loadActiveCycleOverview();
    },
    'view-activity-log': () => {
      if (typeof window.loadActivityLogs === 'function') window.loadActivityLogs();
    },
    // Map loaders with automatic Leaflet canvas invalidation
    'view-map': () => {
      if (typeof window.loadAdminGlobalMap === 'function') window.loadAdminGlobalMap();
      setTimeout(() => window.adminGlobalMap?.invalidateSize?.(), 100);
    },
    'view-ceo-map': () => {
      if (typeof window.loadCEOGlobalMap === 'function') window.loadCEOGlobalMap();
      setTimeout(() => window.ceoGlobalMap?.invalidateSize?.(), 100);
    },
    'view-barangay-map': () => {
      if (typeof window.loadBarangayLocalMap === 'function') window.loadBarangayLocalMap();
      setTimeout(() => window.barangayLocalMap?.invalidateSize?.(), 100);
    }
  };

// 🛠 Safe View Switching Engine with Unified History Tracking & Accessibility
  window.switchView = function(targetId, updateHistory = true) {
    if (!targetId) return;

    const targetSection = document.getElementById(targetId);
    if (!targetSection) {
      console.warn(`[switchView] Target section #${targetId} does not exist in DOM.`);
      return;
    }

    // 1. Push to browser history whenever switched by user actions
    if (updateHistory) {
      if (window.location.hash !== "#" + targetId) {
        history.pushState({ target: targetId }, "", "#" + targetId);
      }
    }

    // 2. Persist active tab across refreshes
    sessionStorage.setItem('roadwise_active_tab', targetId);

    // 3. Dismiss active modal backdrops
    document.querySelectorAll('.modal-overlay').forEach(modal => {
      modal.classList.add('hidden');
    });

    // 4. Toggle active tab indicator, aria-current & section visibility
    navLinks.forEach(nav => {
      nav.classList.remove('active');
      nav.removeAttribute('aria-current');
    });

    contentSections.forEach(section => {
      section.classList.add('hidden');
      section.style.display = '';
    });

    const activeLink = document.querySelector(`.nav-menu li[data-target="${targetId}"]`);
    if (activeLink) {
      activeLink.classList.add('active');
      activeLink.setAttribute('aria-current', 'page');
    }

    targetSection.classList.remove('hidden');
    targetSection.style.display = '';

    // Close mobile drawer if active and update aria-expanded
    if (sidebarNav && sidebarNav.classList.contains('mobile-open')) {
      sidebarNav.classList.remove('mobile-open');
      if (menuToggleBtn) menuToggleBtn.setAttribute('aria-expanded', 'false');
    }

    // Move focus to target section heading for keyboard navigation accessibility
    const heading = targetSection.querySelector('h1, h2, h3');
    if (heading) {
      heading.setAttribute('tabindex', '-1');
      heading.focus({ preventScroll: true });
    } else {
      targetSection.setAttribute('tabindex', '-1');
      targetSection.focus({ preventScroll: true });
    }

    // 5. Targeted View Scroll Reset
    const contentArea = document.querySelector('.content-area');
    if (contentArea) {
      contentArea.scrollTo({ top: 0, behavior: 'instant' });
    }
    targetSection.scrollTop = 0;
    window.scrollTo(0, 0);

    // 6. Dispatch targeted view loader directly
    if (typeof viewDispatchers[targetId] === 'function') {
      viewDispatchers[targetId]();
    }
  };

// 👆 Handle Sidebar Click Delegation
  navLinks.forEach(link => {
    link.addEventListener('click', function(event) {
      event.preventDefault();
      const targetId = this.getAttribute('data-target');
      if (targetId) {
        switchView(targetId, true);
      }
    });
  });

// ⏪ Browser Back & Forward Navigation Watcher
  window.addEventListener('popstate', function(event) {
    let target = event.state && event.state.target;

    if (!target) {
      target = window.location.hash.replace('#', '').trim();
    }

    if (!target || !document.getElementById(target)) {
      target = sessionStorage.getItem('roadwise_active_tab');
    }

    if (target && document.getElementById(target)) {
      switchView(target, false);
    }
  });

// 🟢 Initial Page Boot Handler
  (function initializeView() {
    const hash = window.location.hash.replace('#', '').trim();
    const savedTab = sessionStorage.getItem('roadwise_active_tab');

    const firstSection = document.querySelector('.content-section');
    const fallbackDefault = firstSection ? firstSection.id : 'view-dashboard';

    let finalTarget = hash || savedTab || fallbackDefault;

    if (!document.getElementById(finalTarget)) {
      finalTarget = fallbackDefault;
    }

    if (finalTarget && document.getElementById(finalTarget)) {
      history.replaceState({ target: finalTarget }, "", "#" + finalTarget);
      switchView(finalTarget, false);
    }
  })();
});
