/**
 * Fullscreen image lightbox feature.
 *
 * Owns the lazily created `#fullscreen-image-modal`, its overlay click
 * listener, the Escape-key close listener, and the open/close operations.
 *
 * Entry points:
 * - initImageLightbox(): idempotent startup; installs the temporary window
 *   compatibility bridge and binds the Escape listener once.
 * - destroyImageLightbox(): unbinds the Escape listener (no route teardown
 *   calls it yet; it exists so the listener has an explicit cleanup path).
 *
 * The window bridge is required because dashboard templates and generated
 * table rows call `openFullscreenImage(...)` and `closeFullscreenImage()`
 * from inline onclick attributes. app.js itself imports
 * `closeFullscreenImage` for its global Escape handler.
 */
let initialized = false;
let escapeHandler = null;

// Helper to ensure the modal element exists in the DOM
function ensureFullscreenModalExists() {
  let modal = document.getElementById("fullscreen-image-modal");
  if (!modal) {
    const modalHtml = `
      <div id="fullscreen-image-modal" style="display: none; position: fixed; inset: 0; z-index: 999999; background: rgba(15, 23, 42, 0.9); backdrop-filter: blur(6px); justify-content: center; align-items: center; cursor: zoom-out;">
        <span class="close-fullscreen-btn" onclick="closeFullscreenImage()" style="position: absolute; top: 20px; right: 30px; font-size: 36px; color: #ffffff; cursor: pointer; line-height: 1; z-index: 1000000; font-weight: bold;">&times;</span>
        <img id="fullscreen-modal-img" src="" alt="Full Size" style="max-width: 90vw; max-height: 85vh; object-fit: contain; border-radius: 8px; box-shadow: 0 25px 50px rgba(0,0,0,0.5); cursor: default;" onclick="event.stopPropagation();">
      </div>`;
    document.body.insertAdjacentHTML('beforeend', modalHtml);
    modal = document.getElementById("fullscreen-image-modal");

    // Close when clicking the dark background overlay
    modal.addEventListener('click', () => closeFullscreenImage());
  }
  return modal;
}

// Global Open Function
export function openFullscreenImage(imgElement) {
  if (!imgElement) return;

  const imgSrc = typeof imgElement === 'string' ? imgElement : imgElement.src;

  // Ignore clicks on empty src, error placeholders, or default svg/png placeholders
  if (!imgSrc ||
    imgSrc === '' ||
    imgSrc.includes('placehold.co') ||
    imgSrc.includes('data:image/svg+xml') ||
    imgSrc.endsWith('undefined') ||
    imgSrc.endsWith('null')) {
    console.warn("Fullscreen viewer skipped: Invalid or placeholder image source.", imgSrc);
    return;
  }

  // Guarantee modal exists
  const modal = ensureFullscreenModalExists();
  const modalImg = document.getElementById("fullscreen-modal-img");

  if (modal && modalImg) {
    modalImg.src = imgSrc;
    modal.classList.remove('hidden');
    modal.style.setProperty('display', 'flex', 'important');
    modal.style.setProperty('z-index', '999999', 'important');
  }
}

// Global Close Function
export function closeFullscreenImage() {
  const modal = document.getElementById("fullscreen-image-modal");
  if (modal) {
    modal.classList.add('hidden');
    modal.style.setProperty('display', 'none', 'important');
    const modalImg = document.getElementById("fullscreen-modal-img");
    if (modalImg) modalImg.src = "";
  }
}

function bindEscapeKey() {
  escapeHandler = (e) => {
    if (e.key === 'Escape') {
      closeFullscreenImage();
    }
  };
  document.addEventListener('keydown', escapeHandler);
}

/**
 * Idempotent feature entry point. Called once on DOMContentLoaded by app.js.
 */
export function initImageLightbox() {
  if (initialized) return;
  initialized = true;

  // Temporary HTML/global compatibility bridge for inline onclick handlers.
  window.openFullscreenImage = openFullscreenImage;
  window.closeFullscreenImage = closeFullscreenImage;

  bindEscapeKey();
}

/**
 * Unbinds the Escape listener. The window bridge is intentionally left in
 * place so late inline handlers can call `closeFullscreenImage` safely.
 */
export function destroyImageLightbox() {
  if (!initialized) return;
  initialized = false;

  if (escapeHandler) {
    document.removeEventListener('keydown', escapeHandler);
    escapeHandler = null;
  }
}