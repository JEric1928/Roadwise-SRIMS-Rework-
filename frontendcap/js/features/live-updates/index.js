/**
 * WebSocket live-refresh feature.
 *
 * Extracted verbatim from app.js. Connects to the backend STOMP endpoint and
 * refreshes the notification bell and the role dashboards when the server
 * broadcasts REFRESH_DASHBOARDS. Self-registers on DOMContentLoaded.
 */
import { API_BASE_URL } from '../../core/api.js';
import { refreshNotifications } from '../notifications/index.js';

// ==========================================
// 🚀 ENTERPRISE WEBSOCKETS (LIVE DASHBOARD)
// ==========================================
let stompClient = null;

function connectLiveDashboards() {
  // Connect to the Java backend endpoint using your global API_BASE_URL (Great for Ngrok!)
  const socket = new SockJS(`${API_BASE_URL}/ws-live`);
  stompClient = Stomp.over(socket);

  // Disable excessive debug logs in the console to keep it clean
  stompClient.debug = null;

  stompClient.connect({}, function (frame) {
    // Tune in to the /topic/updates radio frequency
    stompClient.subscribe('/topic/updates', function (message) {

      // When we hear the pulse from the Java backend:
      if (message.body === "REFRESH_DASHBOARDS") {
        // 🚀 THE FIX: Give the database 0.3 seconds to officially commit the save!
        setTimeout(() => {
          // 🔔 1. REFRESH THE NOTIFICATIONS
          refreshNotifications();

          // 📊 2. REFRESH THE BARANGAY DASHBOARD
          const brgyId = sessionStorage.getItem("barangayId");
          if (brgyId && typeof window.loadBarangayReports === "function") {
            window.loadBarangayReports(brgyId);
          }

          // 📊 3. REFRESH THE ADMIN DASHBOARD
          if (typeof window.loadAdminDashboardData === "function") window.loadAdminDashboardData();
          if (typeof window.loadAdminReports === "function") window.loadAdminReports();
          if (typeof window.loadTrackingData === "function") window.loadTrackingData();
          if (typeof window.loadUserManagementTable === "function") window.loadUserManagementTable();
          if (typeof window.loadBarangayManagement === "function") window.loadBarangayManagement();

          // 📊 4. REFRESH THE CEO DASHBOARD
          if (typeof window.loadCEODashboardData === "function") window.loadCEODashboardData();
        }, 300); // <-- 300 milliseconds delay
      }
    });

  }, function (error) {
    // If the server turns off, try to reconnect every 5 seconds silently
    setTimeout(connectLiveDashboards, 5000);
  });
}

// Start the WebSocket connection the second the page loads
document.addEventListener("DOMContentLoaded", () => {
  connectLiveDashboards();
});
