/**
 * Centralized API service wrapper
 */

// Deployment override: hosts may set window.__ROADWISE_API_BASE__ before the
// bundle loads (e.g. an inline script next to js/config.js). Falls back to the
// historical defaults when unset.
const DEPLOYED_API_BASE = (typeof window !== 'undefined' && window.__ROADWISE_API_BASE__) || null;

export let API_BASE_URL = DEPLOYED_API_BASE || "http://localhost:8080";

if (!DEPLOYED_API_BASE && typeof window !== 'undefined' && window.location.hostname !== "localhost" && window.location.hostname !== "127.0.0.1") {
  API_BASE_URL = "https://backend-hmxf.onrender.com";
}

export function getAuthHeaders() {
  const token = typeof localStorage !== 'undefined' ? localStorage.getItem('accessToken') : null;
  const headers = {
    'Content-Type': 'application/json'
  };
  if (token) {
    headers['Authorization'] = 'Bearer ' + token;
  }
  return headers;
}

/**
 * Authorization-only headers for requests whose body is FormData. The browser
 * must set the multipart Content-Type (including the boundary) itself, so this
 * deliberately omits Content-Type.
 */
export function getAuthOnlyHeaders() {
  const token = typeof localStorage !== 'undefined' ? localStorage.getItem('accessToken') : null;
  return token ? { Authorization: 'Bearer ' + token } : {};
}

/**
 * Clears every place a session token or identity can be persisted. Logout must
 * not leave access/refresh tokens behind in localStorage.
 */
export function clearAuthSession() {
  try {
    if (typeof sessionStorage !== 'undefined') sessionStorage.clear();
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem('user');
      localStorage.removeItem('currentUser');
      localStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
    }
  } catch (e) {
    // Storage may be unavailable (private mode); nothing else to do.
  }
}

export function getErrorMessage(status, fallbackText = "Request failed") {
  switch (status) {
    case 401: return "Authentication required. Please log in again.";
    case 403: return "Access denied. You do not have permission for this action.";
    case 404: return "Requested resource or endpoint was not found.";
    case 409: return "A conflict occurred with the existing data.";
    case 500: return "Server error. Please try again or contact system support.";
    default: return fallbackText;
  }
}

export async function apiFetch(endpoint, options = {}) {
  const url = endpoint.startsWith && endpoint.startsWith('http') ? endpoint : `${API_BASE_URL}${endpoint.startsWith('/') ? '' : '/'}${endpoint}`;
  options.cache = options.cache || 'no-store';

  const customHeaders = (typeof window !== 'undefined' && window.API_HEADERS) ? window.API_HEADERS : {};
  const headers = { ...getAuthHeaders(), ...customHeaders, ...(options.headers || {}) };
  const response = await fetch(url, { ...options, headers });

  if (!response.ok) {
    throw new Error(`API request failed with status: ${response.status}`);
  }

  const contentType = response.headers.get('content-type') || '';
  if (contentType.includes('application/json')) {
    return response.json();
  }
  return response;
}

if (typeof window !== 'undefined') {
  window.apiFetch = apiFetch;
  window.API_BASE_URL = API_BASE_URL;
  window.getAuthHeaders = getAuthHeaders;
  window.getAuthOnlyHeaders = getAuthOnlyHeaders;
  window.clearAuthSession = clearAuthSession;
}
