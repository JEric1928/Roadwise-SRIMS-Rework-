/**
 * Secure image fetcher (Supabase CDN + legacy local uploads).
 *
 * Owns `loadSecureImage`, which resolves an image name to a displayable src.
 * The window bridge is required because the profile header, sidebar avatar,
 * and tracking modals call `window.loadSecureImage(...)`.
 */
import { API_BASE_URL } from '../../core/api.js';

export function loadSecureImage(imgElementId, imageName) {
  const imgEl = document.getElementById(imgElementId);
  if (!imgEl) return;

  // 🛡️ Catch empty, "no_image.jpg", and string representations of missing values
  if (!imageName ||
    imageName === 'no_image.jpg' ||
    String(imageName).trim().toLowerCase() === 'undefined' ||
    String(imageName).trim().toLowerCase() === 'null') {

    imgEl.src = "https://placehold.co/500x300/png?text=No+Photo+Provided";
    imgEl.style.display = 'block';
    return;
  }

  const imageStr = String(imageName).trim();

  // ☁️ 1. SUPABASE STORAGE (DIRECT LOAD)
  // If it is a full cloud CDN URL, assign directly to let the browser cache and render it instantly
  if (imageStr.startsWith("http://") || imageStr.startsWith("https://")) {
    imgEl.src = imageStr;
    imgEl.style.display = 'block';
    imgEl.onerror = () => {
      imgEl.src = "https://placehold.co/500x300/png?text=Image+Error";
    };
    return;
  }

  // 💾 2. LEGACY LOCAL UPLOADS (BLOB FETCH VIA BACKEND)
  const localUrl = `${API_BASE_URL}/uploads/${imageStr}`;
  fetch(localUrl, { headers: { 'ngrok-skip-browser-warning': 'true' } })
    .then(res => {
      if (!res.ok) throw new Error(`Server returned ${res.status}`);
      return res.blob();
    })
    .then(blob => {
      imgEl.src = URL.createObjectURL(blob);
      imgEl.style.display = 'block';
    })
    .catch(err => {
      console.error("Failed to load legacy image:", err);
      imgEl.src = "https://placehold.co/500x300/png?text=Image+Error";
      imgEl.style.display = 'block';
    });
}

/**
 * Idempotent entry point: installs the HTML-callable window bridge.
 */
export function initSecureImage() {
  window.loadSecureImage = loadSecureImage;
}