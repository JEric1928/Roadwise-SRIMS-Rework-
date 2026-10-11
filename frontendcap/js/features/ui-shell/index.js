/**
 * Global UI shell: mobile sidebar, keyboard shortcuts, analytics accordion,
 * and the unified window.showToast used by inline template handlers.
 *
 * Extracted verbatim from app.js. All statements were top-level; they now run
 * at import evaluation, before the main app.js body (no handler timing change
 * for user interaction).
 */

// ==========================================
// 📱 MOBILE SIDEBAR & RESPONSIVE DRAWER CONTROLLER
// ==========================================
window.toggleMobileSidebar = function(forceState) {
  const sidebar = document.getElementById('app-sidebar') || document.querySelector('.sidebar');
  const backdrop = document.getElementById('sidebar-backdrop');
  if (!sidebar) return;

  const isOpen = sidebar.classList.contains('open');
  const newState = forceState !== undefined ? forceState : !isOpen;

  if (newState) {
    sidebar.classList.add('open');
    if (backdrop) backdrop.classList.add('active');
  } else {
    sidebar.classList.remove('open');
    if (backdrop) backdrop.classList.remove('active');
  }
};

// ==========================================
// 🎹 GLOBAL KEYBOARD INTERACTION & FOCUS MANAGEMENT
// ==========================================
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    closeFullscreenImage();
    toggleMobileSidebar(false);
    const dropdown = document.getElementById('notification-dropdown');
    if (dropdown) dropdown.classList.add('hidden');
    document.querySelectorAll('.modal-overlay, .bd-modal-overlay').forEach(modal => {
      modal.classList.add('hidden');
      modal.style.display = 'none';
    });
  }
});

// ==========================================
// 🔮 ADVANCED ANALYTICS EXPANDABLE ACCORDION
// ==========================================
window.toggleAdvancedAnalyticsAccordion = function() {
  const content = document.getElementById('advanced-analytics-content');
  const arrow = document.getElementById('analytics-accordion-arrow');
  if (!content) return;

  const isHidden = content.classList.contains('hidden');
  if (isHidden) {
    content.classList.remove('hidden');
    if (arrow) arrow.textContent = '▲ Collapse';
    if (typeof triggerForecastingUpdate === 'function') {
      triggerForecastingUpdate();
    }
  } else {
    content.classList.add('hidden');
    if (arrow) arrow.textContent = '▼ Expand';
  }
};

// ==========================================
// UNIFIED TOAST NOTIFICATION SYSTEM
// ==========================================
let toastTimeout = null;

window.showToast = function(message, type = 'success', duration = 4000) {
  let toast = document.getElementById('toast-notification');
  let toastMsg = document.getElementById('toast-message');
  let toastIcon = document.getElementById('toast-icon');

  if (!toast) {
    const toastHtml = `
      <div id="toast-notification" style="position: fixed; top: 20px; right: 20px; padding: 14px 20px; border-radius: 8px; color: white; transition: all 0.3s ease; z-index: 999999; opacity: 0; transform: translateY(-20px); pointer-events: none; box-shadow: 0 4px 12px rgba(0,0,0,0.15); display: flex; align-items: center; gap: 10px; max-width: 380px;">
        <span id="toast-icon" style="font-size: 18px;">ℹ️</span>
        <span id="toast-message" style="font-weight: 600; font-size: 13px;"></span>
      </div>`;
    document.body.insertAdjacentHTML('beforeend', toastHtml);
    toast = document.getElementById('toast-notification');
    toastMsg = document.getElementById('toast-message');
    toastIcon = document.getElementById('toast-icon');
  }

  if (toastTimeout) {
    clearTimeout(toastTimeout);
  }

  if (toastMsg) toastMsg.textContent = message;

  let bg = '#16a34a';
  let icon = '✅';

  const t = String(type).toLowerCase();
  if (t === 'success' || t === 'true' || type === false) {
    bg = '#16a34a';
    icon = '✅';
  } else if (t === 'warning') {
    bg = '#d97706';
    icon = '⚠️';
  } else if (t === 'error' || t === 'danger' || type === true) {
    bg = '#dc2626';
    icon = '🚨';
  } else if (t === 'loading' || t === 'info') {
    bg = '#0f172a';
    icon = '⏳';
  }

  if (toastIcon) toastIcon.textContent = icon;
  if (toast) toast.style.backgroundColor = bg;

  if (toast) {
    toast.style.display = 'flex';
    setTimeout(() => {
      toast.style.opacity = '1';
      toast.style.transform = 'translateY(0)';
      toast.style.pointerEvents = 'auto';
    }, 10);
  }

  if (t !== 'loading') {
    toastTimeout = setTimeout(() => {
      if (toast) {
        toast.style.opacity = '0';
        toast.style.transform = 'translateY(-20px)';
        toast.style.pointerEvents = 'none';
      }
    }, duration);
  }
};
