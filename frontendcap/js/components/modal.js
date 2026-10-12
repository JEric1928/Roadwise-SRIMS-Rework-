/**
 * Shared Modal Controller Component
 * Handles modal display, scroll reset, ARIA roles, focus trap, and Escape key dismissal.
 */

import { resetModalScroll } from '../core/utils.js';

const activeModalStack = [];
let isEscapeListenerBound = false;

function getFocusableElements(container) {
  if (!container) return [];
  const selector = 'a[href], area[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), iframe, object, embed, [tabindex]:not([tabindex="-1"]), [contenteditable]';
  return Array.from(container.querySelectorAll(selector)).filter(el => {
    return el.offsetWidth > 0 || el.offsetHeight > 0 || el === document.activeElement;
  });
}

function handleGlobalEscape(e) {
  if (e.key === 'Escape' && activeModalStack.length > 0) {
    e.preventDefault();
    const topmostModal = activeModalStack[activeModalStack.length - 1];
    closeModal(topmostModal);
  }
}

function ensureEscapeListener() {
  if (!isEscapeListenerBound && typeof document !== 'undefined') {
    document.addEventListener('keydown', handleGlobalEscape);
    isEscapeListenerBound = true;
  }
}

export function openModal(modalId) {
  const modal = typeof modalId === 'string' ? document.getElementById(modalId) : modalId;
  if (!modal) return;

  ensureEscapeListener();

  const prevActiveElement = document.activeElement;

  modal.classList.remove('hidden');
  modal.style.display = 'flex';
  modal.setAttribute('role', 'dialog');
  modal.setAttribute('aria-modal', 'true');

  const heading = modal.querySelector('h1, h2, h3, h4, h5, h6');
  if (heading) {
    if (!heading.id) {
      heading.id = `modal-heading-${Math.random().toString(36).substr(2, 9)}`;
    }
    modal.setAttribute('aria-labelledby', heading.id);
  } else if (!modal.hasAttribute('aria-label')) {
    modal.setAttribute('aria-label', 'Dialog Window');
  }

  resetModalScroll(modal);

  const focusables = getFocusableElements(modal);
  if (focusables.length > 0) {
    focusables[0].focus();
  } else {
    modal.setAttribute('tabindex', '-1');
    modal.focus();
  }

  // Focus containment (Focus Trap)
  const handleKeydown = function(e) {
    if (e.key !== 'Tab') return;
    const currentFocusables = getFocusableElements(modal);
    if (currentFocusables.length === 0) {
      e.preventDefault();
      return;
    }
    const first = currentFocusables[0];
    const last = currentFocusables[currentFocusables.length - 1];

    if (e.shiftKey) {
      if (document.activeElement === first || document.activeElement === modal) {
        e.preventDefault();
        last.focus();
      }
    } else {
      if (document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }
  };

  modal._modalKeydownHandler = handleKeydown;
  modal.addEventListener('keydown', handleKeydown);

  // Stack management
  const stackIndex = activeModalStack.indexOf(modal);
  if (stackIndex === -1) {
    activeModalStack.push(modal);
  }
  modal._previousTriggerElement = prevActiveElement;
}

export function closeModal(modalId) {
  const modal = typeof modalId === 'string' ? document.getElementById(modalId) : modalId;
  if (!modal) return;

  modal.classList.add('hidden');
  modal.style.display = 'none';

  if (modal._modalKeydownHandler) {
    modal.removeEventListener('keydown', modal._modalKeydownHandler);
    delete modal._modalKeydownHandler;
  }

  const index = activeModalStack.indexOf(modal);
  if (index !== -1) {
    activeModalStack.splice(index, 1);
  }

  if (modal._previousTriggerElement && typeof modal._previousTriggerElement.focus === 'function') {
    modal._previousTriggerElement.focus();
    delete modal._previousTriggerElement;
  }
}

if (typeof window !== 'undefined') {
  window.openModal = openModal;
  window.closeModal = closeModal;
}
