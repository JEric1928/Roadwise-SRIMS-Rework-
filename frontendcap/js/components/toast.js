/**
 * Toast Notification Component
 * Consolidates screen-reader announcements via role="status" and aria-live="polite".
 */

let toastTimeout = null;

export function showToast(message, type = 'success', duration = 4000) {
  if (typeof document === 'undefined') return;

  let toast = document.getElementById('toast-notification');
  let toastMsg = document.getElementById('toast-message');
  let toastIcon = document.getElementById('toast-icon');

  if (!toast) {
    const toastHtml = `
      <div id="toast-notification" role="status" aria-live="polite" aria-atomic="true" style="position: fixed; top: 20px; right: 20px; padding: 14px 20px; border-radius: 8px; color: white; transition: all 0.3s ease; z-index: 999999; opacity: 0; transform: translateY(-20px); pointer-events: none; box-shadow: 0 4px 12px rgba(0,0,0,0.15); display: flex; align-items: center; gap: 10px; max-width: 380px;">
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
  if (t === 'success' || t === 'true') {
    bg = '#16a34a';
    icon = '✅';
  } else if (t === 'error' || t === 'danger') {
    bg = '#dc2626';
    icon = '❌';
  } else if (t === 'warning') {
    bg = '#d97706';
    icon = '⚠️';
  } else if (t === 'info') {
    bg = '#2563eb';
    icon = 'ℹ️';
  }

  if (toast) {
    toast.style.backgroundColor = bg;
    if (toastIcon) toastIcon.textContent = icon;

    toast.setAttribute('role', 'status');
    toast.setAttribute('aria-live', 'polite');

    toast.style.opacity = '1';
    toast.style.transform = 'translateY(0)';
    toast.style.pointerEvents = 'auto';

    toastTimeout = setTimeout(() => {
      toast.style.opacity = '0';
      toast.style.transform = 'translateY(-20px)';
      toast.style.pointerEvents = 'none';
    }, duration);
  }
}

if (typeof window !== 'undefined') {
  window.showToast = showToast;
}
