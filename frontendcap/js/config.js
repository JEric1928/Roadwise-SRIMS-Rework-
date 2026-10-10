// js/config.js
let API_BASE_URL = "http://localhost:8080";

if (window.location.hostname !== "localhost" && window.location.hostname !== "127.0.0.1") {
  API_BASE_URL = "https://backend-hmxf.onrender.com";
}

function getAuthHeaders() {
  const token = localStorage.getItem('accessToken');
  const headers = {
    'Content-Type': 'application/json'
  };
  if (token) {
    headers['Authorization'] = 'Bearer ' + token;
  }
  return headers;
}

const API_HEADERS = new Proxy({}, {
  get: function(target, prop) {
    const headers = getAuthHeaders();
    return headers[prop];
  }
});

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
