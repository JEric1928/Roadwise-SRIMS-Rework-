/**
 * Shared Modal Controller Component
 */

import { resetModalScroll } from '../core/utils.js';

export function openModal(modalId) {
  const modal = typeof modalId === 'string' ? document.getElementById(modalId) : modalId;
  if (!modal) return;

  modal.classList.remove('hidden');
  modal.style.display = 'flex';
  resetModalScroll(modal);
}

export function closeModal(modalId) {
  const modal = typeof modalId === 'string' ? document.getElementById(modalId) : modalId;
  if (!modal) return;

  modal.classList.add('hidden');
  modal.style.display = 'none';
}

if (typeof window !== 'undefined') {
  window.openModal = openModal;
  window.closeModal = closeModal;
}
