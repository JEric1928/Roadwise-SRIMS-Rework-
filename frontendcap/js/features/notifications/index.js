/**
 * Notifications feature.
 *
 * Owns its private state (currentUserId, initialization flag), API reads and
 * mutations, rendering, and event registration for the bell dropdown, unread
 * badge, mark-as-read action, and activity history.
 *
 * Entry points:
 * - initNotifications(): idempotent startup; bridges HTML-callable globals,
 *   binds the bell UI, and performs the initial load.
 * - refreshNotifications(): explicit refresh for external owners (WebSocket
 *   live updates). Do not add a second refresh owner.
 *
 * The window.* assignments below are a temporary compatibility bridge for
 * existing inline HTML handlers (markAllAsRead, viewAllActivity, and the
 * dynamically generated openSingleNotification rows) until those handlers are
 * migrated to registered listeners.
 */
import { apiFetch, API_BASE_URL } from '../../core/api.js';
import { timeAgo } from '../../core/time-ago.js';

// 🚀 DYNAMIC USER ID: Fetch the actual logged-in user's ID from session storage
const currentUserId = sessionStorage.getItem("userId");

let initialized = false;

// 1. HELPER: Fetch Unread Badge Count
function fetchUnreadBadgeCount() {
  if (!currentUserId) return;
  apiFetch(`/api/notifications/me/unread-count`)
    .then(count => {
      const badge = document.getElementById('notification-badge');
      if (badge) {
        if (count > 0) {
          badge.textContent = count > 99 ? '99+' : count;
          badge.style.display = 'flex';
        } else {
          badge.style.display = 'none';
        }
      }
    })
    .catch(err => console.error("Failed to load badge count:", err));
}

// 2. CORE FUNCTION: Load Compact Dropdown Notifications
function loadNotifications() {
  if (!currentUserId) return;
  fetchUnreadBadgeCount();

  apiFetch(`/api/notifications/me`)
    .then(notifications => {
      const listContainer = document.getElementById('notification-list');
      if (!listContainer) return;

      if (!notifications || notifications.length === 0) {
        listContainer.innerHTML = '<div style="padding: 30px 20px; text-align: center; color: #94a3b8; font-size: 13px;">You have no notifications.</div>';
        return;
      }

      let html = '';
      const topNotifications = notifications.slice(0, 10);

      topNotifications.forEach(notif => {
        const bgClass = notif.read ? '#ffffff' : '#eff6ff';
        const weightClass = notif.read ? '600' : '700';
        const dotHtml = notif.read ? '' : '<span class="notif-dot" style="height: 8px; width: 8px; background: #3b82f6; border-radius: 50%; display: inline-block; margin-top: 4px; box-shadow: 0 0 5px rgba(59,130,246,0.5);"></span>';

        // Escaping text so quotes don't break the HTML attributes
        const safeTitle = notif.title ? notif.title.replace(/"/g, '&quot;') : 'Notification';
        const safeMessage = notif.message ? notif.message.replace(/"/g, '&quot;') : '';
        const timeStr = timeAgo(notif.createdAt);

        html += `
          <div data-read="${notif.read}"
               data-title="${safeTitle}"
               data-message="${safeMessage}"
               data-time="${timeStr}"
               onclick="openSingleNotification(${notif.id}, this)"
               style="padding: 14px 18px; border-bottom: 1px solid #f1f5f9; cursor: pointer; background: ${bgClass}; transition: background 0.2s;">

              <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 4px;">
                  <div class="notif-title" style="font-size: 13.5px; color: #1e293b; font-weight: ${weightClass};">${notif.title}</div>
                  ${dotHtml}
              </div>
              <div class="notif-message" style="font-size: 12.5px; color: #475569; line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden; text-overflow: ellipsis;">${notif.message}</div>
              <div style="font-size: 11px; color: #94a3b8; margin-top: 6px; font-weight: 500;">${timeStr}</div>
          </div>
        `;
      });
      listContainer.innerHTML = html;
    })
    .catch(err => console.error("Failed to load notifications:", err));
}

// 3. 🚀 THE NEW LOGIC: OPEN POPUP MODAL & MARK READ
function openSingleNotification(notifId, element) {
  // A. Get the data stored safely inside the clicked element
  const title = element.getAttribute('data-title');
  const message = element.getAttribute('data-message');
  const time = element.getAttribute('data-time');

  // B. Populate the modal with the specific notification data
  document.getElementById('single-notif-title').innerText = title;
  document.getElementById('single-notif-message').innerText = message;
  document.getElementById('single-notif-time').innerText = time;

  // C. Open the detail modal and hide the dropdown bell
  document.getElementById('single-notif-modal').classList.remove('hidden');
  const dropdown = document.getElementById('notification-dropdown');
  if (dropdown) dropdown.classList.add('hidden');

  // D. If it's already read, we are done!
  if (element.getAttribute('data-read') === 'true') return;

  // E. Instantly update UI locally to "Read" state
  element.setAttribute('data-read', 'true');
  element.style.backgroundColor = '#ffffff';

  const dot = element.querySelector('.notif-dot');
  if (dot) dot.style.display = 'none';

  const titleEl = element.querySelector('.notif-title');
  if (titleEl) titleEl.style.fontWeight = '600';

  // F. Send the read request to the backend silently
  apiFetch(`/api/notifications/${notifId}/read`, { method: 'PUT' })
    .then(() => fetchUnreadBadgeCount())
    .catch(err => {
      console.error("Error marking as read:", err);
      // Rollback optimistic UI update on failure
      element.setAttribute('data-read', 'false');
      element.style.backgroundColor = '#eff6ff';
      if (dot) dot.style.display = 'inline-block';
      if (titleEl) titleEl.style.fontWeight = '700';
    });
}

// 4. ACTION: Mark ALL notifications as read
function markAllAsRead() {
  if (!currentUserId) return;

  const markAllBtn = document.getElementById('btn-mark-all-read');
  if (markAllBtn) markAllBtn.innerText = "Marking...";

  apiFetch(`/api/notifications/me/read-all`, { method: 'PUT' })
    .then(() => loadNotifications())
    .catch(err => console.error("Error marking all as read:", err))
    .finally(() => {
      if (markAllBtn) markAllBtn.innerText = "Mark all as read";
    });
}

// 5. ACTION: View All Activity Modal
function viewAllActivity() {
  const dropdown = document.getElementById('notification-dropdown');
  if (dropdown) dropdown.classList.add('hidden');

  const allModal = document.getElementById('all-notifications-modal');
  if (allModal) {
    allModal.classList.remove('hidden');

    const modalList = document.getElementById('all-notifications-list');
    modalList.innerHTML = '<div style="padding: 30px; text-align: center; color: #64748b;">Loading history...</div>';

    apiFetch(`/api/notifications/me`)
      .then(notifications => {
        if (!notifications || notifications.length === 0) {
          modalList.innerHTML = '<div style="padding: 40px 20px; text-align: center; color: #94a3b8;">No notification history found.</div>';
          return;
        }

        let html = '';
        notifications.forEach(notif => {
          const bgClass = notif.read ? '#ffffff' : '#eff6ff';
          const weightClass = notif.read ? '600' : '700';
          const dotHtml = notif.read ? '' : '<span class="notif-dot" style="height: 8px; width: 8px; background: #3b82f6; border-radius: 50%; display: inline-block; margin-top: 4px;"></span>';

          const safeTitle = notif.title ? notif.title.replace(/"/g, '&quot;') : 'Notification';
          const safeMessage = notif.message ? notif.message.replace(/"/g, '&quot;') : '';
          const timeStr = timeAgo(notif.createdAt);

          html += `
            <div data-read="${notif.read}"
                 data-title="${safeTitle}"
                 data-message="${safeMessage}"
                 data-time="${timeStr}"
                 onclick="openSingleNotification(${notif.id}, this)"
                 style="padding: 16px 24px; border-bottom: 1px solid #f1f5f9; cursor: pointer; background: ${bgClass}; transition: 0.2s;">

                <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 6px;">
                    <div class="notif-title" style="font-size: 14px; color: #1e293b; font-weight: ${weightClass};">${notif.title}</div>
                    ${dotHtml}
                </div>
                <div class="notif-message" style="font-size: 13px; color: #475569; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden; text-overflow: ellipsis;">${notif.message}</div>
                <div style="font-size: 12px; color: #94a3b8; margin-top: 8px; font-weight: 500;">${timeStr}</div>
            </div>
          `;
        });
        modalList.innerHTML = html;
      })
      .catch(err => {
        modalList.innerHTML = '<div style="padding: 20px; text-align: center; color: red;">Failed to load history.</div>';
      });
  }
}

// Bell dropdown open/close behavior. Registered once by initNotifications().
function bindBellUI() {
  const bellBtn = document.getElementById('btn-notification');
  const dropdown = document.getElementById('notification-dropdown');

  if (bellBtn && dropdown) {
    // 1. Toggle dropdown when clicking the bell
    bellBtn.addEventListener('click', (e) => {
      e.stopPropagation(); // Prevents the document click listener below from instantly closing it
      dropdown.classList.toggle('hidden');

      // 🚀 THE SCROLL BUG FIX: Force scroll to top when opened
      if (!dropdown.classList.contains('hidden')) {
        const listContainer = document.getElementById('notification-list');
        if (listContainer) {
          listContainer.scrollTop = 0;
        }
      }
    });

    // 2. Close dropdown if the user clicks anywhere else on the screen
    document.addEventListener('click', (e) => {
      if (!dropdown.contains(e.target) && !bellBtn.contains(e.target)) {
        dropdown.classList.add('hidden');
      }
    });
  }
}

/**
 * Explicit refresh operation for external refresh owners (WebSocket live
 * updates). Startup and successful mutations are the other documented
 * triggers; do not add more owners.
 */
export function refreshNotifications() {
  loadNotifications();
}

/**
 * Idempotent feature entry point. Called once on DOMContentLoaded by app.js.
 */
export function initNotifications() {
  if (initialized) return;
  initialized = true;

  // Temporary HTML/global compatibility bridge for inline handlers.
  window.loadNotifications = loadNotifications;
  window.fetchUnreadBadgeCount = fetchUnreadBadgeCount;
  window.openSingleNotification = openSingleNotification;
  window.markAllAsRead = markAllAsRead;
  window.viewAllActivity = viewAllActivity;

  bindBellUI();
  loadNotifications();
}