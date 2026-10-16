// js/config.js
// The backend base URL now comes from js/api-base.js, which every page loads
// before this script. That module is the single source of truth: the URL used
// to be hardcoded here *and* in js/core/api.js with two different hosts, which
// is what sent forecasting traffic to a retired deployment.
const API_BASE_URL = (typeof window !== 'undefined' && window.RoadWiseApiBase)
  ? window.RoadWiseApiBase.resolve()
  : 'http://localhost:8080';

function getAuthHeaders() {
  // Delegates to the bundle's reader (js/core/auth-storage.js) so a session stored in
  // sessionStorage is found too. This file ships as a classic script and cannot import
  // it; the header is only built when a request is made, long after the bundle ran.
  const token = (typeof window !== 'undefined' && typeof window.readAuthToken === 'function')
    ? window.readAuthToken('accessToken')
    : null;
  const headers = {
    'Content-Type': 'application/json'
  };
  if (token) {
    headers['Authorization'] = 'Bearer ' + token;
  }
  return headers;
}

// Live auth headers. The Proxy computes the current token on each access, and
// also exposes enumerable own keys so that `{ ...API_HEADERS }` (and fetch's
// HeadersInit iteration) actually forwards the Authorization header.
const API_HEADERS = new Proxy({}, {
  get: function(target, prop) {
    const headers = getAuthHeaders();
    return headers[prop];
  },
  ownKeys: function() {
    return Object.keys(getAuthHeaders());
  },
  getOwnPropertyDescriptor: function(target, prop) {
    const headers = getAuthHeaders();
    if (Object.prototype.hasOwnProperty.call(headers, prop)) {
      return { enumerable: true, configurable: true, value: headers[prop] };
    }
    return undefined;
  }
});

if (typeof window !== 'undefined') {
  window.API_HEADERS = API_HEADERS;
}

// Utility: Debounce user input events
function debounce(func, delay = 300) {
  let timeoutId;
  return function(...args) {
    clearTimeout(timeoutId);
    timeoutId = setTimeout(() => func.apply(this, args), delay);
  };
}

// Utility: Cancellable fetch with AbortController
function cancellableFetch(url, options = {}) {
  const controller = new AbortController();
  const promise = fetch(url, { ...options, signal: controller.signal });
  return { promise, cancel: () => controller.abort() };
}

// Utility: Fetch with timeout
function fetchWithTimeout(url, options = {}, timeoutMs = 15000) {
  const controller = new AbortController();
  const id = setTimeout(() => controller.abort(), timeoutMs);
  return fetch(url, { ...options, signal: controller.signal })
    .finally(() => clearTimeout(id));
}

// Utility: User-friendly API error formatter
function getErrorMessage(status, fallbackText = "Request failed") {
  switch (status) {
    case 401: return "Authentication required. Please log in again.";
    case 403: return "Access denied. You do not have permission for this action.";
    case 404: return "Requested resource or endpoint was not found.";
    case 409: return "A conflict occurred with the existing data.";
    case 500: return "Server error. Please try again or contact system support.";
    default: return fallbackText;
  }
}
