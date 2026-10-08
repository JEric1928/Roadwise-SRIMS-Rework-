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
