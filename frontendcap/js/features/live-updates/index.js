/**
 * WebSocket live-refresh feature.
 *
 * Extracted verbatim from app.js. Connects to the backend STOMP endpoint and
 * refreshes the notification bell and the role dashboards when the server
 * broadcasts REFRESH_DASHBOARDS. Self-registers on DOMContentLoaded.
 */
import { API_BASE_URL, getAuthOnlyHeaders } from '../../core/api.js';
import { refreshNotifications } from '../notifications/index.js';
import { liveUpdateChannel } from './channel.js';

// ==========================================
// 🚀 ENTERPRISE WEBSOCKETS (LIVE DASHBOARD)
// ==========================================
let stompClient = null;
let refreshDebounceTimer = null;
let reconnectTimer = null;
let reconnectDelayMs = 0;

/** Backoff ceiling. A server that is down should not be probed every few seconds forever. */
const MAX_RECONNECT_DELAY_MS = 30000;

const INITIAL_RECONNECT_DELAY_MS = 5000;

function scheduleReconnect() {
  if (reconnectTimer) return;
  reconnectDelayMs = reconnectDelayMs === 0
    ? INITIAL_RECONNECT_DELAY_MS
    : Math.min(reconnectDelayMs * 2, MAX_RECONNECT_DELAY_MS);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connectLiveDashboards();
  }, reconnectDelayMs);
}

function connectLiveDashboards() {
  // The server authenticates the STOMP CONNECT frame, not the handshake: a browser cannot
  // set an Authorization header on a WebSocket upgrade, so the token travels as a native
  // frame header instead. Without it the server refuses the connection outright.
  const authHeaders = getAuthOnlyHeaders();

  // No token means there is no session yet - the login page loads this module too. Asking
  // to connect would only earn a refusal, and retrying would earn one every few seconds.
  if (!authHeaders.Authorization) return;

  // Connect to the Java backend endpoint using your global API_BASE_URL (Great for Ngrok!)
  const socket = new SockJS(`${API_BASE_URL}/ws-live`);
  stompClient = Stomp.over(socket);

  // Disable excessive debug logs in the console to keep it clean
  stompClient.debug = null;
  stompClient.connect(authHeaders, function (frame) {
    // A working connection resets the backoff, so a blip does not leave the next retry slow.
    reconnectDelayMs = 0;

    const channel = liveUpdateChannel(
      sessionStorage.getItem("userRole") || sessionStorage.getItem("role"),
      sessionStorage.getItem("barangayId")
    );
    if (!channel) {
      // Nothing to listen to without a usable identity. The server would refuse the
      // subscription anyway; not asking keeps the console clean and the intent obvious.
      return;
    }

    stompClient.subscribe(channel, function (message) {

      // When we hear the pulse from the Java backend:
      if (message.body === "REFRESH_DASHBOARDS") {
        if (refreshDebounceTimer) clearTimeout(refreshDebounceTimer);

        // Debounce updates and scope refreshes to the current active role
        refreshDebounceTimer = setTimeout(() => {
          // 🔔 1. REFRESH THE NOTIFICATIONS
          refreshNotifications();

          const role = (sessionStorage.getItem("userRole") || sessionStorage.getItem("role") || "").toUpperCase();

          // 📊 2. SCOPED DASHBOARD REFRESH BY USER ROLE
          if (role.includes("BARANGAY")) {
            const brgyId = sessionStorage.getItem("barangayId");
            if (brgyId && typeof window.loadBarangayReports === "function") {
              window.loadBarangayReports(brgyId);
            }
          } else if (role.includes("CEO") || role.includes("ENGINEER")) {
            if (typeof window.loadCEODashboardData === "function") window.loadCEODashboardData();
          } else if (role.includes("ADMIN") || role.includes("CPDO")) {
            if (typeof window.loadAdminDashboardData === "function") window.loadAdminDashboardData();
            if (typeof window.loadAdminReports === "function") window.loadAdminReports();
            if (typeof window.loadTrackingData === "function") window.loadTrackingData();
            if (typeof window.loadUserManagementTable === "function") window.loadUserManagementTable();
            if (typeof window.loadBarangayManagement === "function") window.loadBarangayManagement();
          }
        }, 300);
      }
    });

  }, function (error) {
    // The server refuses the CONNECT frame when the stored session is no longer usable
    // (expired, revoked, suspended). Retrying with the same token cannot succeed, so the
    // retry is backed off and, once the session is gone, the expiry handler below has
    // already cleared storage and redirected by the time the next attempt is due.
    stompClient = null;
    scheduleReconnect();
  });
}

// Start the WebSocket connection the second the page loads
document.addEventListener("DOMContentLoaded", () => {
  connectLiveDashboards();
});

// A revoked session must not leave a live socket behind. The expiry handler clears storage
// and usually navigates away, which drops the connection along with the page; closing it here
// also covers a tab that stays put, and stops the retry loop from running on a dead session.
window.addEventListener('roadwise:session-expired', () => {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  stopLiveDashboards();
});

function stopLiveDashboards() {
  if (!stompClient) return;
  try {
    stompClient.disconnect();
  } catch (e) {
    // The socket is already gone; nothing to close.
  }
  stompClient = null;
}
