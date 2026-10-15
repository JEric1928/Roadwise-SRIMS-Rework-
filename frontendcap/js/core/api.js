/**
 * Centralized API service wrapper
 */

import { isStepUpChallenge, requestStepUp } from './step-up.js';

/**
 * Backend base URL. js/api-base.js is the single source of truth — it is loaded
 * as a classic script before this bundle and honours, in order, a runtime
 * window.__ROADWISE_API_BASE__ override, the build-time ROADWISE_API_BASE_URL
 * value, and a loopback default.
 *
 * The local fallback below is deliberately loopback-only: if the helper script
 * is ever missing from a page, production fails loudly against localhost
 * instead of silently reaching for a stale hardcoded host. That silent reach is
 * exactly how forecasting ended up calling a retired deployment.
 */
function resolveApiBaseUrl() {
  if (typeof window !== 'undefined' && window.RoadWiseApiBase
      && typeof window.RoadWiseApiBase.resolve === 'function') {
    return window.RoadWiseApiBase.resolve();
  }
  const host = (typeof window !== 'undefined' && window.location && window.location.hostname) || '';
  const loopback = host === '' || host === 'localhost' || host === '127.0.0.1' || host === '::1';
  if (loopback) return 'http://localhost:8080';

  if (typeof console !== 'undefined' && console.error) {
    console.error(
      '[RoadWise] js/api-base.js did not load, so the backend base URL is unknown. ' +
      'Falling back to http://localhost:8080; all API calls will fail until the ' +
      'script is restored to this page.'
    );
  }
  return 'http://localhost:8080';
}

export let API_BASE_URL = resolveApiBaseUrl();

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
/* ------------------------------------------------------------------ *
 * Session tokens
 * ------------------------------------------------------------------ */

function readStoredToken(key) {
  try {
    return typeof localStorage !== 'undefined' ? localStorage.getItem(key) : null;
  } catch (e) {
    return null;
  }
}

function writeStoredTokens(tokens) {
  try {
    if (tokens.accessToken) localStorage.setItem('accessToken', tokens.accessToken);
    if (tokens.refreshToken) localStorage.setItem('refreshToken', tokens.refreshToken);
  } catch (e) {
    // Storage may be unavailable (private mode). The request still succeeds;
    // the next call will simply re-use whatever token is still readable.
  }
}

/**
 * Endpoints that must never trigger an automatic refresh: they either establish
 * the session, are the refresh itself, or are the revocation call. Attempting a
 * refresh around them would recurse or resurrect a session that is being ended.
 */
const NO_REFRESH_ENDPOINTS = /^\/api\/auth\/(login|refresh|logout|verify-mfa|mfa\/|forgot-password|reset-password|step-up)/;

export function isRefreshableEndpoint(endpoint) {
  if (typeof endpoint !== 'string') return false;
  const path = endpoint.startsWith('http') ? endpoint.replace(/^https?:\/\/[^/]+/, '') : endpoint;
  return !NO_REFRESH_ENDPOINTS.test(path.startsWith('/') ? path : `/${path}`);
}

/**
 * Single-flight refresh. Concurrent 401s share one rotation instead of racing:
 * the backend treats a replayed (already rotated) refresh token as a
 * compromise signal and revokes every session for the user, so duplicate
 * refreshes would log the user out of everything.
 *
 * Resolves with the new access token, or null when the session cannot be
 * recovered. Never throws — callers decide what a null means.
 */
let refreshInFlight = null;

export function refreshAccessToken() {
  if (refreshInFlight) return refreshInFlight;

  const refreshToken = readStoredToken('refreshToken');
  if (!refreshToken) return Promise.resolve(null);

  refreshInFlight = fetch(`${API_BASE_URL}/api/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken }),
    cache: 'no-store'
  })
    .then(async (response) => {
      if (!response.ok) return null;
      let data = null;
      try {
        data = await response.json();
      } catch (e) {
        return null;
      }
      if (!data || !data.accessToken) return null;
      writeStoredTokens(data);
      return data.accessToken;
    })
    .catch(() => null)
    .then((token) => {
      refreshInFlight = null;
      return token;
    });

  return refreshInFlight;
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
  // stepUpRetried and refreshRetried are internal bookkeeping: each marks the
  // single replay allowed after a step-up prompt or a token refresh, so a
  // server that keeps answering 401/403 can never loop the caller.
  const { stepUpRetried = false, refreshRetried = false, ...fetchOptions } = options;
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
      // A 15-minute access token expiring is the normal case, not a logout.
      // Redeem the refresh token once and replay the request; only when that
      // fails is the session actually over. Auth endpoints are excluded so a
      // rejected login or a logout cannot trigger a rotation.
      if (!refreshRetried && isRefreshableEndpoint(endpoint)) {
        const renewed = await refreshAccessToken();
        if (renewed) {
          return apiFetch(endpoint, { ...options, refreshRetried: true });
        }
      }

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
