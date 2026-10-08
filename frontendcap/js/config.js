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
