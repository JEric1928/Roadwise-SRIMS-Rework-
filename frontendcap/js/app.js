// Must stay first: restores a remembered session into sessionStorage before the
// feature modules below read identity at module scope. See the module's header.
import './core/session-bootstrap.js';
import { API_BASE_URL, clearAuthSession, readAuthToken, refreshAccessToken } from './core/api.js';
import { initNotifications } from './features/notifications/index.js';
import { initImageLightbox } from './features/image-lightbox/index.js';
import { initLiveClock } from './features/live-clock/index.js';
import { initAuth } from './features/auth/index.js';
import { initSecureImage } from './features/secure-image/index.js';
import { initActivityLog } from './features/activity-log/index.js';
import { initProfileAccount } from './features/profile-account/index.js';
import { initPasskeySettings } from './features/passkeys/index.js';

import './features/administration/index.js'; // self-registers admin bindings + window bridges
import './features/reports-archive/index.js'; // self-registers reports/archive bindings + window bridges
import './features/live-updates/index.js'; // self-registers WebSocket live refresh
import './features/search-filters/index.js'; // window.filter* bridges + listener attach
import './features/navigation/index.js'; // switchView + SPA history (own DOMContentLoaded)
import './features/profile-header/index.js'; // populateProfileData + auto-load (own DOMContentLoaded)
import './features/priority-report/index.js'; // priority view + list generation (own DOMContentLoaded)
import './features/report-generation/index.js'; // report preview/print/CSV + batch archive
import './features/ui-shell/index.js'; // sidebar, keyboard, accordion, unified toast
import './features/report-wizard/index.js'; // report wizard steps + roads dropdown
import './features/barangay-dashboard/index.js'; // barangay engine + window bridges
import './features/admin-dashboard/index.js'; // admin engine + window bridges
import './features/ceo-dashboard/index.js'; // CEO engine + window bridges
import './features/report-submission/index.js'; // report submission, modal confirm & form reset
import './features/priority-dispatch/index.js'; // masterlist priority dispatch to CEO
import './features/field-report/index.js'; // 3-field barangay upload, client-side compression + GPS
import './features/cpdo-survey/index.js'; // CPDO technical survey capture + window bridges
import './features/priority-masterlist/index.js'; // ranked masterlist rendering (tier badges)
import './features/repair-tracking/index.js'; // repair tracking, modals, QA & extension review
import './features/map-engine/index.js'; // Leaflet map instances, spotlight isolation & global maps


// ==========================================
// 🚀 GLOBAL SECURITY BOUNCER (HYBRID CLIENT + SERVER)
// ==========================================
(function enforceSecurity() {
  const currentPath = window.location.pathname.toLowerCase();

  const storedRole = sessionStorage.getItem("userRole");
  const storedUserId = sessionStorage.getItem("userId");
  const isLoggedIn = storedRole && storedUserId;

  // 1. FAST CLIENT PASS: Immediate redirect if logged out
  if (!isLoggedIn && currentPath.includes("dashboard")) {
    window.location.replace("login.html");
    return;
  }

  // 2. FAST CLIENT PASS: Forward away from login if already logged in
  if (isLoggedIn && currentPath.includes("login.html")) {
    redirectByRole(storedRole);
    return;
  }

  // 3. SERVER-SIDE AUDIT: If on a dashboard, verify identity with Spring Boot
  if (isLoggedIn && currentPath.includes("dashboard")) {
    const verifySession = (token) => fetch(`${API_BASE_URL}/api/auth/verify-session`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: 'Bearer ' + token } : {})
      },
      body: JSON.stringify({})
    });

    verifySession(readAuthToken('accessToken'))
      .then(async (res) => {
        // A 401 here is usually just the 15-minute access token expiring while
        // the tab sat idle. Redeem the refresh token once and re-verify before
        // treating the session as over — otherwise every page load after the
        // access-token lifetime kicks the user back to login.
        if (res.status === 401) {
          const renewed = await refreshAccessToken();
          if (renewed) return verifySession(renewed);
        }
        if (!res.ok) throw new Error("Session invalid on server");
        return res.json();
      })
      .then(data => {
        if (!data.valid) {
          throw new Error("Invalid session state");
        }
      })
      .catch(() => {
        // 🛑 Server rejected or account suspended: wipe storage and kick to login
        clearAuthSession();
        window.location.replace("login.html");
      });
  }

  function redirectByRole(role) {
    const r = String(role || '').toLowerCase();
    if (r.includes("admin") || r.includes("cpdo")) {
      window.location.replace("admin_dashboard.html");
    } else if (r.includes("ceo") || r.includes("engineer")) {
      window.location.replace("ceo_dashboard.html");
    } else {
      window.location.replace("barangay_dashboard.html");
    }
  }
})();

// ==========================================
// CENTRALIZED SESSION-EXPIRY HANDLING
// ==========================================
window.addEventListener('roadwise:session-expired', () => {
  clearAuthSession();
  if (!window.location.pathname.toLowerCase().includes('login')) {
    window.location.replace('login.html');
  }
});

// ==========================================
// FEATURE STARTUP (composition)
// ==========================================
document.addEventListener('DOMContentLoaded', () => {
  initNotifications();
  initImageLightbox();
  initLiveClock();
  initAuth();
  initSecureImage();
  initActivityLog();
  initProfileAccount();
  initPasskeySettings();
});
