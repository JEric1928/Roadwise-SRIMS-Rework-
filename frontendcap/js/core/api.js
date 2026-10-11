/**
 * Centralized API service wrapper
 */

export let API_BASE_URL = "http://localhost:8080";

if (typeof window !== 'undefined' && window.location.hostname !== "localhost" && window.location.hostname !== "127.0.0.1") {
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
}
