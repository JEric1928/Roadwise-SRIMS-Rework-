/**
 * Centralized API service wrapper
 */

import { isStepUpChallenge, requestStepUp } from './step-up.js';

import {
  clearAuthSession,
  readAuthToken,
  writeAuthTokens
} from './auth-storage.js';

// Re-exported because app.js and the window bridge import it from here; storage
// ownership itself lives in auth-storage.js.
export { clearAuthSession, readAuthToken };

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
  const token = readAuthToken('accessToken');
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
  const token = readAuthToken('accessToken');
  return token ? { Authorization: 'Bearer ' + token } : {};
}

/**
 * Endpoints that must never trigger an automatic refresh: they either establish
 * the session, are the refresh itself, or are the revocation call. Attempting a
 * refresh around them would recurse or resurrect a session that is being ended.
 *
 * /api/auth/mfa/** is deliberately absent. Those are the MFA *management* screens
 * (overview, factors, recovery codes), which need a live session and must rotate an
 * expired access token like any other authenticated call. Matching the `mfa/` prefix
 * here made a 401 on those screens drop the user to login instead.
 */
const NO_REFRESH_ENDPOINTS = /^\/api\/auth\/(login|refresh|logout|verify-mfa|forgot-password|reset-password|step-up)/;

export function isRefreshableEndpoint(endpoint) {
  if (typeof endpoint !== 'string') return false;
  const path = endpoint.startsWith('http') ? endpoint.replace(/^https?:\/\/[^/]+/, '') : endpoint;
  return !NO_REFRESH_ENDPOINTS.test(path.startsWith('/') ? path : `/${path}`);
}

/**
 * Single-flight refresh. Concurrent 401s share one rotation instead of racing:
 * the backend treats a replayed (already rotated) refresh token as a compromise
 * signal and revokes every session for the user, so duplicate refreshes would log
 * the user out of everything.
 *
 * Single-flight alone is per-tab: `refreshInFlight` cannot see a second tab, and two
 * tabs redeeming the same token is exactly the replay the server revokes for. So the
 * rotation is serialised across tabs with the Web Locks API where it is available, and
 * a rotation this tab did not win is adopted from storage instead of retried. Adoption
 * is what covers the cases the lock cannot: browsers without it, and a winner running
 * in another process.
 *
 * Resolves with the new access token, or null when the session cannot be
 * recovered. Never throws — callers decide what a null means.
 */
let refreshInFlight = null;

const REFRESH_LOCK_NAME = 'roadwise.refresh-token';

function withRefreshLock(task) {
  const locks = typeof navigator !== 'undefined' ? navigator.locks : null;
  if (!locks || typeof locks.request !== 'function') {
    // No Web Locks (older browser, or a non-secure context): keep the per-tab
    // promise and let adoption recover from a cross-tab race.
    return Promise.resolve().then(task);
  }
  return locks.request(REFRESH_LOCK_NAME, task);
}

/**
 * Returns the access token another tab stored for a rotation this tab did not win, or
 * null while the stored refresh token is still the one about to be presented.
 */
function adoptTokensRotatedElsewhere(presentedToken) {
  const currentRefreshToken = readAuthToken('refreshToken');
  if (currentRefreshToken && currentRefreshToken !== presentedToken) {
    return readAuthToken('accessToken');
  }
  return null;
}

async function rotateRefreshToken() {
  // Captured before queueing on the lock: this is the token the tab intends to spend,
  // and comparing it afterwards is how a cross-tab rotation is detected.
  const presentedToken = readAuthToken('refreshToken');
  if (!presentedToken) return null;

  return withRefreshLock(async () => {
    const adopted = adoptTokensRotatedElsewhere(presentedToken);
    if (adopted) return adopted;

    const response = await fetch(`${API_BASE_URL}/api/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: presentedToken }),
      cache: 'no-store'
    });

    if (!response.ok) {
      // A rejection is also what a concurrent rotation looks like from here, so check
      // storage before treating the session as over.
      return adoptTokensRotatedElsewhere(presentedToken);
    }

    let data = null;
    try {
      data = await response.json();
    } catch (e) {
      return null;
    }
    if (!data || !data.accessToken) return null;

    writeAuthTokens(data);
    return data.accessToken;
  });
}

export function refreshAccessToken() {
  if (refreshInFlight) return refreshInFlight;

  refreshInFlight = rotateRefreshToken()
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

/**
 * Unwraps Spring's PageResponseDto envelope into its `content` array.
 *
 * GET /api/reports is the only paged endpoint and answers
 * `{content:[…], page, size, totalElements, totalPages, last}`. Every report
 * screen treats that response as a bare array: the ones without a guard threw a
 * TypeError, and the ones guarding with Array.isArray rendered "no records"
 * instead — the admin inbox, CEO dashboard, global maps, priority list and
 * repair tracking all silently showed nothing. Unwrapping once here fixes all
 * of them, including whichever screen is written next.
 *
 * The paging metadata is not discarded: it rides on the returned array as a
 * non-enumerable `pagination` property, so a caller that wants to page can read
 * `result.pagination.totalPages` while spreading, JSON serialization and
 * deepStrictEqual stay blind to it.
 */
export function unwrapPagedResponse(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return body;

  const isPagedEnvelope = Array.isArray(body.content)
    && typeof body.page === 'number'
    && typeof body.totalPages === 'number';
  if (!isPagedEnvelope) return body;

  const items = body.content;
  Object.defineProperty(items, 'pagination', {
    value: {
      page: body.page,
      size: body.size,
      totalElements: body.totalElements,
      totalPages: body.totalPages,
      last: body.last
    },
    enumerable: false,
    writable: false,
    configurable: true
  });
  return items;
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
    return unwrapPagedResponse(await response.json());
  }
  return response;
}

if (typeof window !== 'undefined') {
  window.apiFetch = apiFetch;
  window.API_BASE_URL = API_BASE_URL;
  window.getAuthHeaders = getAuthHeaders;
  window.getAuthOnlyHeaders = getAuthOnlyHeaders;
  window.clearAuthSession = clearAuthSession;
  // js/config.js is copied to dist as a classic script and cannot import, so it calls
  // this bridge for the token instead of reading storage itself.
  window.readAuthToken = readAuthToken;

  // Cross-tab logout: when another tab clears the token, mirror it here.
  window.addEventListener('storage', (event) => {
    if (event.key === 'accessToken' && !event.newValue && !readAuthToken('accessToken')) {
      window.dispatchEvent(new CustomEvent('roadwise:session-expired'));
    }
  });
}
