/**
 * Centralized API service wrapper
 */

import { isStepUpChallenge, requestStepUp } from './step-up.js';
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
  // stepUpRetried is internal bookkeeping: it marks the single replay allowed
  // after a successful step-up prompt, so a repeated 403 can never loop.
  const { stepUpRetried = false, ...fetchOptions } = options;
  const url = endpoint.startsWith && endpoint.startsWith('http') ? endpoint : `${API_BASE_URL}${endpoint.startsWith('/') ? '' : '/'}${endpoint}`;
  fetchOptions.cache = fetchOptions.cache || 'no-store';

  const customHeaders = (typeof window !== 'undefined' && window.API_HEADERS) ? window.API_HEADERS : {};
  const headers = { ...getAuthHeaders(), ...customHeaders, ...(fetchOptions.headers || {}) };
  const response = await fetch(url, { ...fetchOptions, headers });

  if (!response.ok) {
    let message = getErrorMessage(response.status, `API request failed with status: ${response.status}`);
    let body = null;
    try {
      const contentType = response.headers.get('content-type') || '';
      if (contentType.includes('application/json')) {
        body = await response.clone().json();
        if (body && (body.error || body.message)) {
          message = body.error || body.message;
        }
      }
    } catch (e) {
      // Non-JSON error body; keep the status-based message.
    }

    const error = new Error(message);
    error.status = response.status;
    if (body && body.code) {
      // Structured codes (STEP_UP_REQUIRED, MALFORMED_CREDENTIAL, ...) stay on
      // the error so callers can branch on them without re-reading the body.
      error.code = body.code;
    }

    if (response.status === 401) {
      // Expired/revoked session: clear local credentials and let the shell
      // redirect to login. Distinct from 403 (authenticated but not allowed).
      error.code = 'SESSION_EXPIRED';
      clearAuthSession();
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('roadwise:session-expired'));
      }
      throw error;
    }

    // Changing sign-in methods needs a recent password entry. The server says
    // so with 403 + STEP_UP_REQUIRED: prompt for the password, then replay the
    // same request once. The replay re-reads the token from storage, so it
    // carries the freshly elevated one.
    if (!stepUpRetried && isStepUpChallenge(response.status, body)) {
      try {
        await requestStepUp(API_BASE_URL);
      } catch (stepUpCancelled) {
        error.message = 'Please re-enter your password to change your sign-in methods.';
        throw error;
      }
      return apiFetch(endpoint, { ...options, stepUpRetried: true });
    }

    throw error;
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

  // Cross-tab logout: when another tab clears the token, mirror it here.
  window.addEventListener('storage', (event) => {
    if (event.key === 'accessToken' && !event.newValue && !localStorage.getItem('accessToken')) {
      window.dispatchEvent(new CustomEvent('roadwise:session-expired'));
    }
  });
}
