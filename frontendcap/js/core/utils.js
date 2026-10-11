/**
 * Shared Core Utility Functions
 */

export function escapeHtml(str) {
  if (str === null || str === undefined) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

export function formatOfficialName(firstName, middleName, lastName, prefix = '') {
  const first = (firstName || '').trim();
  const last = (lastName || '').trim();

  let mi = '';
  if (middleName && typeof middleName === 'string') {
    const cleaned = middleName.trim().replace(/\./g, '');
    if (cleaned.length > 0 && cleaned.toLowerCase() !== 'null' && cleaned.toLowerCase() !== 'undefined') {
      mi = `${cleaned.charAt(0).toUpperCase()}.`;
    }
  }

  const fullName = [first, mi, last].filter(Boolean).join(' ');
  return prefix ? `${prefix.trim()} ${fullName}` : (fullName || 'Official');
}

export function resetModalScroll(modalElementOrId) {
  const modal = typeof modalElementOrId === 'string'
    ? document.getElementById(modalElementOrId)
    : modalElementOrId;

  if (!modal) return;

  const performReset = () => {
    modal.scrollTop = 0;
    if (typeof modal.scrollTo === 'function') {
      modal.scrollTo({ top: 0, left: 0, behavior: 'instant' });
    }

    const scrollableTargets = modal.querySelectorAll(
      '.modal-body, .bd-modal-body, .modal-content, .bd-modal-box, .report-timeline, [id$="-timeline-container"]'
    );

    scrollableTargets.forEach(el => {
      el.scrollTop = 0;
      if (typeof el.scrollTo === 'function') {
        el.scrollTo({ top: 0, left: 0, behavior: 'instant' });
      }
    });
  };

  performReset();
  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(performReset);
  }
  setTimeout(performReset, 50);
  setTimeout(performReset, 200);
}

if (typeof window !== 'undefined') {
  window.escapeHtml = escapeHtml;
  window.formatOfficialName = formatOfficialName;
  window.resetModalScroll = resetModalScroll;
}
