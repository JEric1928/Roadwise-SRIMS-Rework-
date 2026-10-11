import { escapeHtml, formatOfficialName, resetModalScroll } from './core/utils.js';
import { REPORT_STATUS, normalizeStatus, getStatusBadgeClass } from './core/status.js';
import { apiFetch, getAuthHeaders, getErrorMessage, API_BASE_URL } from './core/api.js';
import { formatPST } from './core/format-pst.js';
import { parseAuditTimestamp, formatAuditTimestamp } from './core/audit-time.js';
import { showToast } from './components/toast.js';
import { openModal, closeModal } from './components/modal.js';
import { calculateEstimatedDays } from './domain/repair-estimates.js';
import { parseToKilometers, formatTerrainType, getReportYear, formatInventoryDate, getReportYearSettings } from './domain/inventory.js';
import { formatTargetEntity, getCategoryBadge, getStatusBadge } from './domain/activity-log.js';
import { initNotifications, refreshNotifications } from './features/notifications/index.js';
import { initImageLightbox, closeFullscreenImage } from './features/image-lightbox/index.js';
import { initLiveClock } from './features/live-clock/index.js';
import { initAuth } from './features/auth/index.js';
import { initSecureImage } from './features/secure-image/index.js';
import { initActivityLog } from './features/activity-log/index.js';
import { initProfileAccount } from './features/profile-account/index.js';
import './features/administration/index.js'; // self-registers admin bindings + window bridges
import './features/reports-archive/index.js'; // self-registers reports/archive bindings + window bridges
import './features/live-updates/index.js'; // self-registers WebSocket live refresh
import './features/search-filters/index.js'; // window.filter* bridges + listener attach
import './features/navigation/index.js'; // switchView + SPA history (own DOMContentLoaded)
import './features/profile-header/index.js'; // populateProfileData + auto-load (own DOMContentLoaded)
import './features/priority-report/index.js'; // priority view + list generation (own DOMContentLoaded)
import './features/report-generation/index.js'; // report preview/print/CSV + batch archive
import './features/ui-shell/index.js'; // sidebar, keyboard, accordion, unified toast
import './features/report-wizard/index.js'; // report wizard steps + roads dropdown
import { loadBarangayReports } from './features/barangay-dashboard/index.js'; // barangay engine + window bridges
import { loadAdminDashboardData } from './features/admin-dashboard/index.js'; // admin engine + window bridges
import './features/ceo-dashboard/index.js'; // CEO engine + window bridges (self-registers watchdogs)


// ==========================================
// 🚀 GLOBAL SECURITY BOUNCER (HYBRID CLIENT + SERVER)
// ==========================================
(function enforceSecurity() {
  const currentPath = window.location.pathname.toLowerCase();

  const storedRole = sessionStorage.getItem("userRole");
  const storedUserId = sessionStorage.getItem("userId");
  const isLoggedIn = storedRole && storedUserId;

  // 1. FAST CLIENT PASS: Immediate redirect if logged out
  if (!isLoggedIn && currentPath.includes("dashboard")) {
    window.location.replace("login.html");
    return;
  }

  // 2. FAST CLIENT PASS: Forward away from login if already logged in
  if (isLoggedIn && currentPath.includes("login.html")) {
    redirectByRole(storedRole);
    return;
  }

  // 3. SERVER-SIDE AUDIT: If on a dashboard, verify identity with Spring Boot
  if (isLoggedIn && currentPath.includes("dashboard")) {
    fetch(`${API_BASE_URL}/api/auth/verify-session`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: storedUserId, role: storedRole })
    })
      .then(res => {
        if (!res.ok) throw new Error("Session invalid on server");
        return res.json();
      })
      .then(data => {
        if (!data.valid) {
          throw new Error("Invalid session state");
        }
      })
      .catch(() => {
        // 🛑 Server rejected or account suspended: wipe storage and kick to login
        sessionStorage.clear();
        localStorage.removeItem("user");
        window.location.replace("login.html");
      });
  }

  function redirectByRole(role) {
    const r = String(role || '').toLowerCase();
    if (r.includes("admin") || r.includes("cpdo")) {
      window.location.replace("admin_dashboard.html");
    } else if (r.includes("ceo") || r.includes("engineer")) {
      window.location.replace("ceo_dashboard.html");
    } else {
      window.location.replace("barangay_dashboard.html");
    }
  }
})();

// ==========================================
// GLOBAL MAP VARIABLES (Must remain empty at first!)
// ==========================================
let map;
let mapMarker;
let selectedLat = 14.8139; // Default center of San Jose del Monte
let selectedLng = 121.0453; // Default center of San Jose del Monte
let redIcon; // Just declare it, don't build it yet!

document.addEventListener('DOMContentLoaded', () => {

  // ==========================================
  // 🚀 ADMIN DASHBOARD: REPAIR TRACKING WATCHDOG
  // ==========================================
  const trackingSection = document.getElementById('view-tracking');
  if (trackingSection) {
    const trackingObserver = new MutationObserver((mutations) => {
      mutations.forEach((mutation) => {
        if (mutation.attributeName === 'class' && !trackingSection.classList.contains('hidden')) {
          if (typeof loadTrackingData === 'function') loadTrackingData();
        }
      });
    });
    trackingObserver.observe(trackingSection, { attributes: true });
  }

// ==========================================
// 🛡️ THE LEAFLET SAFETY CHECK 🛡️
// ==========================================
  if (typeof L !== 'undefined') {

    // ⬇️ 1. SAFE TO DEFINE THE RED ICON HERE ⬇️
    redIcon = new L.Icon({
      iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-red.png',
      shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
      iconSize: [25, 41],
      iconAnchor: [12, 41],
      popupAnchor: [1, -34],
      shadowSize: [41, 41]
    });
    window.redIcon = redIcon; // shared leaflet icon for the barangay dashboard module

    const mapModal = document.getElementById('map-modal');
    const btnCloseMap = document.getElementById('close-map-btn');

    // State trackers for boundary isolation
    let sjdmGeoJsonData = null;
    let currentBoundaryLayer = null;
    let currentMaskLayer = null;
    let activePolygonCoords = null;

    // Pre-fetch the GeoJSON boundary file
    fetch('sjdm_barangays.geojson')
      .then(res => res.json())
      .then(data => { sjdmGeoJsonData = data; })
      .catch(err => console.error("Could not load sjdm_barangays.geojson:", err));

    // Ray-casting algorithm: validates if [lat, lng] is strictly inside the polygon
    function isPointInsidePolygon(lat, lng, polyCoords) {
      let inside = false;
      for (let i = 0, j = polyCoords.length - 1; i < polyCoords.length; j = i++) {
        const xi = polyCoords[i][0], yi = polyCoords[i][1];
        const xj = polyCoords[j][0], yj = polyCoords[j][1];
        const intersect = ((yi > lat) !== (yj > lat)) &&
          (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi);
        if (intersect) inside = !inside;
      }
      return inside;
    }

    // Applies inverted dark spotlight mask and red dashed border
    function applySpotlightAndLock(targetBarangay) {
      if (currentBoundaryLayer) map.removeLayer(currentBoundaryLayer);
      if (currentMaskLayer) map.removeLayer(currentMaskLayer);

      if (!sjdmGeoJsonData) return null;

      function normalizeBrgyName(str) {
        return (str || "")
          .toLowerCase()
          .replace(/[\u2013\u2014\u2212-]/g, "-")        // En-dash (–) to hyphen (-)
          .replace(/^sto\.\s*|^santo\s*/, "santo ")      // Sto. to Santo
          .replace(/^sta\.\s*|^santa\s*/, "santa ")      // Sta. to Santa
          .replace(/\s+/g, " ")                          // Normalize spaces
          .trim();
      }

      const targetClean = normalizeBrgyName(targetBarangay || "Kaypian");

      const feature = sjdmGeoJsonData.features.find(f => {
        const geoNameClean = normalizeBrgyName(f.properties.name || f.properties.adm4_name || "");

        if (geoNameClean === targetClean) return true;
        if (targetClean === "sapang palay proper" && geoNameClean === "sapang palay") return true;
        if (targetClean === "sapang palay" && geoNameClean === "sapang palay proper") return true;

        return false;
      }) || sjdmGeoJsonData.features.find(f => normalizeBrgyName(f.properties.name) === "kaypian") || sjdmGeoJsonData.features[0];

      if (!feature) return null;

      const coords = feature.geometry.type === 'MultiPolygon'
        ? feature.geometry.coordinates[0][0]
        : feature.geometry.coordinates[0];

      // 1. Inverted dark mask covering outside area
      const worldOuter = [
        [90, -180],
        [90, 180],
        [-90, 180],
        [-90, -180]
      ];
      const innerRing = coords.map(c => [c[1], c[0]]);
      activePolygonCoords = coords; // [lng, lat]

      currentMaskLayer = L.polygon([worldOuter, innerRing], {
        color: 'transparent',
        fillColor: '#000000',
        fillOpacity: 0.65,
        interactive: false
      }).addTo(map);

      // 2. Red dashed border (Google Maps style)
      currentBoundaryLayer = L.geoJSON(feature, {
        style: {
          color: '#ef4444',
          weight: 3,
          dashArray: '6, 6',
          fillOpacity: 0
        },
        interactive: false
      }).addTo(map);

      // 3. Smooth Camera Fit with Generous Padding to Prevent Border Clipping[cite: 4]
      const bounds = currentBoundaryLayer.getBounds();

      // Allows zooming out to level 12 so large barangays fit entirely in the modal viewport[cite: 4]
      map.setMinZoom(12);

      // 80% boundary buffer keeps Leaflet from clipping viewport edges during zoom-out
      map.setMaxBounds(bounds.pad(0.8));

      // 40px margin ensures boundary lines never press against the frame[cite: 4]
      map.fitBounds(bounds, {
        padding: [40, 40],
        maxZoom: 16
      });

      return bounds;
    }

    // ==========================================
    // 🔍 FLOATING GOOGLE-STYLE SEARCH ENGINE
    // ==========================================
    const searchInput = document.getElementById('custom-map-search');
    const clearSearchBtn = document.getElementById('clear-search-btn');
    const suggestionsList = document.getElementById('search-suggestions');
    let searchDebounceTimer = null;

    function debounce(callback, delay = 350) {
      return function(...args) {
        clearTimeout(searchDebounceTimer);
        searchDebounceTimer = setTimeout(() => callback.apply(this, args), delay);
      };
    }

    function resetSearchUI() {
      if (searchInput) searchInput.value = '';
      if (clearSearchBtn) clearSearchBtn.classList.add('hidden');
      if (suggestionsList) {
        suggestionsList.innerHTML = '';
        suggestionsList.classList.add('hidden');
      }
    }

    if (searchInput && suggestionsList) {
      // 1. Live debounced search input
      searchInput.addEventListener('input', debounce(function() {
        const query = this.value.trim();

        if (query.length < 2) {
          suggestionsList.classList.add('hidden');
          suggestionsList.innerHTML = '';
          if (clearSearchBtn) clearSearchBtn.classList.add('hidden');
          return;
        }

        if (clearSearchBtn) clearSearchBtn.classList.remove('hidden');

        // Derive boundary coordinates for the active barangay
        if (!currentBoundaryLayer) return;
        const bounds = currentBoundaryLayer.getBounds();
        const sw = bounds.getSouthWest();
        const ne = bounds.getNorthEast();

        // Query Nominatim restricted to the active territory bounding box
        const url = `https://nominatim.openstreetmap.org/search?format=json&q=${encodeURIComponent(query)}&countrycodes=ph&viewbox=${sw.lng},${ne.lat},${ne.lng},${sw.lat}&bounded=1&limit=6&addressdetails=1`;

        fetch(url)
          .then(res => res.json())
          .then(results => {
            suggestionsList.innerHTML = '';

            // Filter out any results falling outside the official polygon perimeter
            const validResults = results.filter(item => {
              const lat = parseFloat(item.lat);
              const lon = parseFloat(item.lon);
              return activePolygonCoords && isPointInsidePolygon(lat, lon, activePolygonCoords);
            });

            if (validResults.length === 0) {
              suggestionsList.innerHTML = `
              <li style="cursor: default; color: #94a3b8; padding: 10px 14px; font-size: 12.5px;">
                No matching places found within this barangay.
              </li>`;
              suggestionsList.classList.remove('hidden');
              return;
            }

            validResults.forEach(item => {
              const li = document.createElement('li');
              const parts = item.display_name.split(',');
              const mainTitle = parts[0].trim();
              const subtitle = parts.slice(1, 4).join(',').trim();

              li.innerHTML = `
              <svg width="18" height="18" viewBox="0 0 24 24" fill="#ef4444" style="flex-shrink: 0;">
                <path d="M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z"/>
              </svg>
              <div style="overflow: hidden; text-align: left;">
                <span class="place-title">${mainTitle}</span>
                <span class="place-subtitle">${subtitle}</span>
              </div>
            `;

              li.addEventListener('click', () => {
                const lat = parseFloat(item.lat);
                const lon = parseFloat(item.lon);

                // Smoothly pan and zoom to street level
                map.flyTo([lat, lon], 18, { animate: true, duration: 1.2 });
                searchInput.value = mainTitle;
                suggestionsList.classList.add('hidden');

                showToast("Camera moved! Click on the road to place the pin.", "success");
              });

              suggestionsList.appendChild(li);
            });

            suggestionsList.classList.remove('hidden');
          })
          .catch(err => console.error("Geocoding fetch error:", err));
      }));

      // 2. Clear input button
      if (clearSearchBtn) {
        clearSearchBtn.addEventListener('click', () => {
          resetSearchUI();
          searchInput.focus();
        });
      }

      // 3. Close suggestion dropdown when clicking outside
      document.addEventListener('click', (e) => {
        if (!searchInput.contains(e.target) && !suggestionsList.contains(e.target)) {
          suggestionsList.classList.add('hidden');
        }
      });
    }

    // ------------------------------------------
    // A. "DEFINE ON MAP" FOR ADD REPORT FORM
    // ------------------------------------------
    const btnDefineMap = document.getElementById('btn-define-map');

    if (btnDefineMap && mapModal) {
      btnDefineMap.addEventListener('click', () => {

        const loggedInBarangay = sessionStorage.getItem("barangayName") || "Kaypian";

        mapModal.classList.remove('hidden');
        resetSearchUI();

        // 1. Initialize Leaflet if not already initialized
        if (!map) {
          map = L.map('roadwiseMap', {
            minZoom: 12,             // 🌐 Allows fitting large boundaries comfortably[cite: 4]
            maxZoom: 19,             // 🛑 Stops over-zooming in past satellite resolution
            maxBoundsViscosity: 0.5  // 🌊 Soft elastic edges prevent viewport lockups
          });

          // 🛰️ GOOGLE HYBRID SATELLITE TILES
          L.tileLayer('https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
            maxZoom: 19,
            subdomains: ['mt0', 'mt1', 'mt2', 'mt3'],
            attribution: '&copy; Google Maps'
          }).addTo(map);

          // Strict boundary check: pin is strictly forbidden outside barangay polygon
          map.on('click', function(e) {
            if (activePolygonCoords && !isPointInsidePolygon(e.latlng.lat, e.latlng.lng, activePolygonCoords)) {
              showToast(`You cannot drop a pin outside Barangay ${loggedInBarangay}!`, "error");
              return;
            }
            selectedLat = e.latlng.lat;
            selectedLng = e.latlng.lng;
            if (mapMarker) map.removeLayer(mapMarker);
            mapMarker = L.marker([selectedLat, selectedLng], { icon: redIcon }).addTo(map);
          });
        }

        // 2. Apply spotlight and lock camera
        applySpotlightAndLock(loggedInBarangay);

        // Reset pin on fresh open
        if (mapMarker) {
          map.removeLayer(mapMarker);
          mapMarker = null;
        }

        // 3. Save handler for ADD form
        const liveSaveBtn = document.getElementById('btn-save-coords');
        const newSaveBtn = liveSaveBtn.cloneNode(true);
        liveSaveBtn.parentNode.replaceChild(newSaveBtn, liveSaveBtn);

        newSaveBtn.addEventListener('click', () => {
          if (!mapMarker) {
            showToast(" Please click on the map to drop a pin first!", "warning");
            return;
          }
          document.getElementById('latitude').value = selectedLat;
          document.getElementById('longitude').value = selectedLng;
          document.getElementById('coords-display').textContent = `Locked: ${selectedLat.toFixed(5)}, ${selectedLng.toFixed(5)}`;

          mapModal.classList.add('hidden');
          showToast("Location locked successfully!", "success");
        });

        // Recalculates canvas layout and fits the full territory into view[cite: 4]
        setTimeout(() => {
          map.invalidateSize();
          if (currentBoundaryLayer) {
            map.fitBounds(currentBoundaryLayer.getBounds(), { padding: [40, 40] });
          }
        }, 200);
      });

      if (btnCloseMap) {
        btnCloseMap.addEventListener('click', () => mapModal.classList.add('hidden'));
      }
    }

    // ------------------------------------------
    // B. "UPDATE LOCATION" FOR EDIT MODAL
    // ------------------------------------------
    const btnEditDefineMap = document.getElementById('btn-edit-define-map');

    if (btnEditDefineMap && mapModal) {
      btnEditDefineMap.addEventListener('click', () => {

        const loggedInBarangay = sessionStorage.getItem("barangayName") || "Kaypian";
        const currentLat = parseFloat(document.getElementById('edit-latitude').value);
        const currentLng = parseFloat(document.getElementById('edit-longitude').value);

        mapModal.classList.remove('hidden');
        resetSearchUI();

        // 1. Initialize Leaflet if not already initialized
        if (!map) {
          map = L.map('roadwiseMap', {
            minZoom: 12,             // 🌐 Allows fitting large boundaries comfortably[cite: 4]
            maxZoom: 19,             // 🛑 Stops over-zooming in past satellite resolution
            maxBoundsViscosity: 0.5  // 🌊 Soft elastic edges prevent viewport lockups
          });

          // 🛰️ GOOGLE HYBRID SATELLITE TILES
          L.tileLayer('https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
            maxZoom: 19,
            subdomains: ['mt0', 'mt1', 'mt2', 'mt3'],
            attribution: '&copy; Google Maps'
          }).addTo(map);

          // Strict boundary check: pin is strictly forbidden outside barangay polygon
          map.on('click', function(e) {
            if (activePolygonCoords && !isPointInsidePolygon(e.latlng.lat, e.latlng.lng, activePolygonCoords)) {
              showToast(`You cannot drop a pin outside Barangay ${loggedInBarangay}!`, "error");
              return;
            }
            selectedLat = e.latlng.lat;
            selectedLng = e.latlng.lng;
            if (mapMarker) map.removeLayer(mapMarker);
            mapMarker = L.marker([selectedLat, selectedLng], { icon: redIcon }).addTo(map);
          });
        }

        // 2. Apply spotlight and lock camera
        applySpotlightAndLock(loggedInBarangay);

        // 3. Set existing pin if valid, otherwise clear
        if (!isNaN(currentLat) && !isNaN(currentLng) && activePolygonCoords && isPointInsidePolygon(currentLat, currentLng, activePolygonCoords)) {
          selectedLat = currentLat;
          selectedLng = currentLng;
          map.setView([selectedLat, selectedLng], 18);

          if (mapMarker) map.removeLayer(mapMarker);
          mapMarker = L.marker([selectedLat, selectedLng], { icon: redIcon }).addTo(map);
        } else {
          if (mapMarker) {
            map.removeLayer(mapMarker);
            mapMarker = null;
          }
        }

        const liveEditSaveBtn = document.getElementById('btn-save-coords');
        const newEditSaveBtn = liveEditSaveBtn.cloneNode(true);
        liveEditSaveBtn.parentNode.replaceChild(newEditSaveBtn, liveEditSaveBtn);

        newEditSaveBtn.addEventListener('click', () => {
          if (!mapMarker) {
            showToast(" Please click on the map to drop a pin first!", "warning");
            return;
          }
          document.getElementById('edit-latitude').value = selectedLat;
          document.getElementById('edit-longitude').value = selectedLng;
          document.getElementById('edit-modal-gps').textContent = `${selectedLat.toFixed(5)}, ${selectedLng.toFixed(5)}`;

          mapModal.classList.add('hidden');
          showToast("Location updated successfully!", "success");
        });

        // Recalculates canvas layout and fits the full territory into view[cite: 4]
        setTimeout(() => {
          map.invalidateSize();
          if (currentBoundaryLayer) {
            map.fitBounds(currentBoundaryLayer.getBounds(), { padding: [40, 40] });
          }
        }, 200);
      });
    }


  } // End of if (typeof L !== 'undefined')


  // ==========================================
  // IMAGE UPLOAD & PREVIEW LOGIC
  // ==========================================
  const imageInput = document.getElementById('damageImageFile');
  const imagePreview = document.getElementById('imagePreview');
  const fileNameDisplay = document.getElementById('fileNameDisplay');

  if (imageInput) {
    imageInput.addEventListener('change', function() {
      const file = this.files[0];

      if (file) {
        // --- NEW: FILE SIZE SECURITY CHECK ---
        const maxSizeInMB = 5;
        const maxSizeInBytes = maxSizeInMB * 1024 * 1024;

        if (file.size > maxSizeInBytes) {
          // Reject the file and warn the user
          showToast(`File is too large! Please choose an image smaller than ${maxSizeInMB}MB.`, "error");

          // Reset the hidden input and preview
          this.value = "";
          imagePreview.style.display = 'none';
          imagePreview.src = "";
          fileNameDisplay.textContent = "";
          return; // Stop running the rest of the code
        }
        // -------------------------------------

        fileNameDisplay.textContent = file.name;

        const reader = new FileReader();
        reader.onload = function(e) {
          imagePreview.src = e.target.result;
          imagePreview.style.display = 'block';
        }
        reader.readAsDataURL(file);
      } else {
        imagePreview.style.display = 'none';
        imagePreview.src = "";
        fileNameDisplay.textContent = "";
      }
    });
  }


  // =======================================================
// 🖨️ GLOBAL GENERATE REPORT CONTROLLER (FAIL-PROOF)
// =======================================================

// 1. Toggle Dropdown Menu Open/Close
  window.toggleGenerateReportDropdown = function(event) {
    if (event) event.stopPropagation();
    const dropdown = document.getElementById("print-dropdown");
    if (!dropdown) return;

    if (dropdown.classList.contains("hidden")) {
      dropdown.classList.remove("hidden");
      dropdown.style.display = "block";
    } else {
      dropdown.classList.add("hidden");
      dropdown.style.display = "none";
    }
  };

// 2. Global Outside-Click Listener to Close Dropdown
  document.addEventListener("click", function(e) {
    const dropdown = document.getElementById("print-dropdown");
    const btn = document.getElementById("btn-generate-menu");
    if (dropdown && !dropdown.classList.contains("hidden")) {
      if (btn && !btn.contains(e.target) && !dropdown.contains(e.target)) {
        dropdown.classList.add("hidden");
        dropdown.style.display = "none";
      }
    }
  });

// 3. Option 1: Open Annual City Road Inventory View
  window.openAdminRoadInventory = function(event) {
    if (event) event.stopPropagation();
    const dropdown = document.getElementById("print-dropdown");
    if (dropdown) {
      dropdown.classList.add("hidden");
      dropdown.style.display = "none";
    }

    // Hide all sections
    document.querySelectorAll(".content-section").forEach(sec => {
      sec.classList.add("hidden");
      sec.style.display = "none";
    });
    document.querySelectorAll(".nav-menu li").forEach(l => l.classList.remove("active"));

    // Show Inventory View
    const invSection = document.getElementById("view-road-inventory");
    if (invSection) {
      invSection.classList.remove("hidden");
      invSection.style.display = "block";
    }

    // Load Inventory Data
    if (typeof window.loadAdminInventoryYears === "function") window.loadAdminInventoryYears();
    if (typeof window.loadAdminRoadInventory === "function") window.loadAdminRoadInventory();
  };


  // ==========================================
// 8. BARANGAY MANAGEMENT: LOAD MAIN TABLE & MODALS
// ==========================================
  function loadBarangayManagement() {
    const tableBody = document.getElementById('barangay-table-body');
    if (!tableBody) return;

    tableBody.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 15px;">Loading Barangays... <span class="icon">⏳</span></td></tr>';

    // 🚀 Phase 2 Backend Endpoint (We will build this in Java next)
    apiFetch('/api/barangays/dashboard-summary')
      .then(data => {
        tableBody.innerHTML = '';

        if (!data || data.length === 0) {
          tableBody.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 15px;">No barangays found in the system.</td></tr>';
          return;
        }

        data.forEach(brgy => {
          // 🚀 Smart Badge Logic
          let badgeHtml = `<span class="badge" style="background-color: #e9ecef; color: #6c757d;">0 Active</span>`;
          if (brgy.activeReportCount >= 5) {
            badgeHtml = `<span class="badge high">${brgy.activeReportCount} Active</span>`; // Red
          } else if (brgy.activeReportCount > 0) {
            badgeHtml = `<span class="badge medium">${brgy.activeReportCount} Active</span>`; // Orange
          }

          const row = document.createElement('tr');
          row.innerHTML = `
          <td><strong>${brgy.name}</strong></td>
          <td>${brgy.contactName || 'Unassigned'}</td>
          <td>${brgy.roadCount || 0} Roads</td>
          <td>${badgeHtml}</td>
          <td>
            <button class="btn-small manage-brgy-btn"
              onclick="openManageBarangayModal(${brgy.id}, '${brgy.name}', '${brgy.contactName || 'Unassigned'}', '${brgy.contactNumber || ''}', '${brgy.email || ''}', '${brgy.district || ''}')">
              Manage Barangay
            </button>
          </td>
        `;
          tableBody.appendChild(row);
        });
      })
      .catch(err => {
        console.error('Error loading barangays:', err);
        tableBody.innerHTML = '<tr><td colspan="5" style="text-align: center; color: red; padding: 15px;">Failed to load database.</td></tr>';
      });
  }



}); // <--- THIS CLOSES THE MAIN DOMContentLoaded EVENT LISTENER ONCE AND FOR ALL!

// ==========================================
// BACKEND API CONNECTION & FORM LOGIC (RoadWise)
// ==========================================

// 🛡️ Global Submission Guard (Prevents duplicate requests / rapid clicks)
let isSubmittingReport = false;

// 🚀 Toggle for "Other" damage type selection
window.toggleOtherDamageType = function() {
  const select = document.getElementById('damageType');
  const otherGroup = document.getElementById('otherDamageTypeGroup');
  const otherInput = document.getElementById('otherDamageType');

  if (!select || !otherGroup) return;

  if (select.value === 'Other') {
    otherGroup.classList.remove('hidden');
    otherGroup.style.display = 'block';
    if (otherInput) otherInput.focus();
  } else {
    otherGroup.classList.add('hidden');
    otherGroup.style.display = 'none';
    if (otherInput) otherInput.value = '';
  }
};

// STEP 1: Validate and open the confirmation modal
function submitRoadReport() {
  if (isSubmittingReport) return;

  const roadName = document.getElementById("cityRoadName")?.value;
  const widthVal = document.getElementById("width")?.value;
  const lengthVal = document.getElementById("length")?.value;

  if (!roadName || !widthVal || !lengthVal) {
    showToast("Please fill in all required fields (Road Name, Width, and Length).", "error");
    return;
  }

  const damageType = document.getElementById("damageType")?.value;
  if (damageType === "Other" && !document.getElementById("otherDamageType")?.value.trim()) {
    showToast("Please specify the 'Other' damage type.", "error");
    return;
  }

  const width = parseFloat(widthVal);
  const length = parseFloat(lengthVal);
  const bridges = parseInt(document.getElementById("numberOfBridges")?.value) || 0;
  const culverts = parseFloat(document.getElementById("lengthOfCulverts")?.value) || 0;

  if (width < 0 || length < 0 || bridges < 0 || culverts < 0) {
    showToast("Measurements cannot be negative numbers! Please correct them.", "error");
    return;
  }

  const modal = document.getElementById('confirm-modal');
  if (modal) {
    modal.classList.remove('hidden');
    modal.style.display = 'flex';
  }
}

// STEP 2: Close confirmation modal
function closeConfirmModal() {
  const modal = document.getElementById('confirm-modal');
  if (modal) {
    modal.classList.add('hidden');
    modal.style.display = 'none';
  }
}

// STEP 3: Single-flight server submission
function executeFinalSubmission() {
  // 🛡️ Block duplicate execution if already in flight
  if (isSubmittingReport) return;
  isSubmittingReport = true;

  closeConfirmModal();

  // Disable modal confirm button
  const modalConfirmBtn = document.querySelector("#confirm-modal .btn-submit, #confirm-modal [onclick*='executeFinalSubmission']");
  if (modalConfirmBtn) {
    modalConfirmBtn.disabled = true;
    modalConfirmBtn.innerText = "Submitting... ⏳";
  }

  // Disable form submit button
  const submitBtn = document.getElementById("submit-report-btn");
  if (submitBtn) {
    submitBtn.innerHTML = "⏳ Submitting...";
    submitBtn.disabled = true;
    submitBtn.style.opacity = "0.7";
  }

  const formData = new FormData();

  const loggedInBarangayId = sessionStorage.getItem("barangayId");
  if (loggedInBarangayId) {
    formData.append("barangayId", loggedInBarangayId);
  }

  const loggedInUserId = sessionStorage.getItem("userId");
  if (loggedInUserId) {
    formData.append("userId", loggedInUserId);
  }

  function getVal(id) {
    const el = document.getElementById(id);
    if (!el) return "";

    if (el.tagName === "SELECT") {
      if (el.selectedIndex === -1) return "";
      const opt = el.options[el.selectedIndex];
      if (opt.disabled) return "";
      return (opt.value && opt.value.trim() !== "") ? opt.value : opt.text;
    }
    return el.value || "";
  }

  // 1. Road Details
  formData.append("cityRoadName", getVal("cityRoadName"));
  formData.append("cityRoadId", getVal("cityRoadId"));
  formData.append("roadImportance", getVal("roadImportance"));
  formData.append("roadType", getVal("roadType"));
  formData.append("terrainType", getVal("terrainType"));

  // 2. Measurements
  formData.append("width", parseFloat(getVal("width")) || 0.0);
  formData.append("length", parseFloat(getVal("length")) || 0.0);
  formData.append("numberOfBridges", parseInt(getVal("numberOfBridges")) || 0);
  formData.append("lengthOfCulverts", parseFloat(getVal("lengthOfCulverts")) || 0.0);
  formData.append("damageDescription", getVal("damageDescription"));

  // 3. Damage Information
  let finalDamageType = getVal("damageType");
  if (!finalDamageType || finalDamageType.includes("Select Damage")) {
    finalDamageType = "None";
  } else if (finalDamageType === "Other") {
    finalDamageType = getVal("otherDamageType") || "Other";
  }

  formData.append("damageType", finalDamageType);
  formData.append("damageLength", parseFloat(getVal("damageLength")) || 0.0);
  formData.append("damageWidth", parseFloat(getVal("damageWidth")) || 0.0);

  // 4. GPS & Analytics
  formData.append("latitude", parseFloat(getVal("latitude")) || 0.0);
  formData.append("longitude", parseFloat(getVal("longitude")) || 0.0);
  formData.append("inventoryYear", new Date().getFullYear());

  // 5. Image Processing
  const imageInput = document.getElementById("damageImageFile");
  const hasImage = imageInput && imageInput.files && imageInput.files.length > 0;
  if (hasImage) {
    formData.append("imageFile", imageInput.files[0]);
  }

  // 6. Send to Spring Boot API
  fetch(`${API_BASE_URL}/api/reports`, {
    method: "POST",
    body: formData
  })
    .then(async response => {
      if (response.ok) return response.json();
      const errData = await response.json().catch(() => ({}));
      throw new Error(errData.error || errData.message || 'Network response was not ok.');
    })
    .then(data => {
      // 🚀 NORMALIZE AI SEVERITY & CONFIDENCE SCORE
      const rawSev = String(data.severity || '').trim().toLowerCase();
      const aiConfidence = data.cvConfidenceScore ? data.cvConfidenceScore : 0;

      // 🚀 SHOW ACCURATE DYNAMIC TOAST
      if (!hasImage) {
        // Case 1: Inspector deliberately submitted without an image
        showToast("📋 Report saved successfully! Severity is UNASSESSED (No damage photo attached).", "success");
      } else if (rawSev === "unassessed" || rawSev === "") {
        // Case 2: Image was uploaded, but failed road surface checks or fell below confidence floor
        showToast(" Report saved! AI could not detect valid pavement distress (Marked as UNASSESSED).", "warning");
      } else if (rawSev === "high") {
        showToast(`🚨 Report saved! AI graded this as HIGH Severity (${aiConfidence}% confidence).`, "success");
      } else if (rawSev === "medium") {
        showToast(` Report saved! AI graded this as MEDIUM Severity (${aiConfidence}% confidence).`, "success");
      } else if (rawSev === "low") {
        showToast(` Report saved! AI graded this as LOW Severity (${aiConfidence}% confidence).`, "success");
      } else {
        showToast("📋 Report saved successfully! Severity is UNASSESSED.", "success");
      }

      // Complete reset of form fields, images, coordinates, and "Define on Map" button state
      resetAddReportForm();

      if (typeof loadBarangayReports === 'function') {
        const brgyId = sessionStorage.getItem("barangayId");
        const canvasId = 'severityChart';
        if (typeof Chart !== 'undefined') {
          let existingChart = Chart.getChart(canvasId);
          if (existingChart) existingChart.destroy();
        }
        if (brgyId) loadBarangayReports(brgyId);
      }
    })
    .catch(error => {
      console.error("Error submitting report:", error);
      showToast(error.message || "Failed to upload report. Check your internet connection.", "error");
    })
    .finally(() => {
      // Release submission lock
      isSubmittingReport = false;

      if (modalConfirmBtn) {
        modalConfirmBtn.disabled = false;
        modalConfirmBtn.innerText = "Yes, Submit";
      }

      if (submitBtn) {
        submitBtn.innerHTML = "Submit Report";
        submitBtn.disabled = false;
        submitBtn.style.opacity = "1";
      }
    });
}

// ==========================================
// 🧹 COMPREHENSIVE FORM & MAP RESET FUNCTION
// ==========================================
function resetAddReportForm() {
  // 1. Native form reset (resets standard inputs and controls)
  const form = document.getElementById("addReportForm");
  if (form) {
    form.reset();
  }

  // 2. Explicitly reset all dropdowns to their placeholder options
  const selectIds = ["cityRoadName", "roadImportance", "roadType", "terrainType", "damageType"];
  selectIds.forEach(id => {
    const el = document.getElementById(id);
    if (el) el.selectedIndex = 0;
  });

  // 3. Clear manual & read-only input values
  const inputIds = [
    "cityRoadId", "width", "length", "numberOfBridges",
    "lengthOfCulverts", "damageLength", "damageWidth",
    "damageDescription", "otherDamageType"
  ];
  inputIds.forEach(id => {
    const el = document.getElementById(id);
    if (el) el.value = "";
  });

  // 4. Hide and wipe "Other" damage type field
  const otherGroup = document.getElementById("otherDamageTypeGroup");
  const otherInput = document.getElementById("otherDamageType");
  if (otherGroup) {
    otherGroup.classList.add("hidden");
    otherGroup.style.display = "none";
  }
  if (otherInput) {
    otherInput.value = "";
  }

  // 5. Complete reset of "Define on Map" button and GPS badges
  const latEl = document.getElementById("latitude");
  const lngEl = document.getElementById("longitude");
  if (latEl) latEl.value = "";
  if (lngEl) lngEl.value = "";

  const coordsDisplay = document.getElementById("coords-display");
  if (coordsDisplay) {
    coordsDisplay.textContent = "Not Selected";
    coordsDisplay.style.background = "#ecfdf5";
    coordsDisplay.style.color = "#047857";
    coordsDisplay.style.borderColor = "#34d399";
  }

  const btnDefineMap = document.getElementById("btn-define-map");
  if (btnDefineMap) {
    btnDefineMap.innerHTML = '<span class="icon">📍</span> Define on Map';
    btnDefineMap.style.backgroundColor = '#16a34a';
  }

  // Clear map markers if present in window/global scope
  if (typeof reportMarker !== "undefined" && reportMarker && typeof map !== "undefined" && map) {
    try { map.removeLayer(reportMarker); } catch (e) {}
    reportMarker = null;
  }
  if (window.reportMarker && window.map) {
    try { window.map.removeLayer(window.reportMarker); } catch (e) {}
    window.reportMarker = null;
  }

  // 6. Wipe image upload, file label, and image preview
  const fileInput = document.getElementById("damageImageFile");
  if (fileInput) fileInput.value = "";

  const preview = document.getElementById("imagePreview");
  if (preview) {
    preview.style.display = 'none';
    preview.src = "";
  }

  const fileNameDisplay = document.getElementById("fileNameDisplay");
  if (fileNameDisplay) fileNameDisplay.textContent = "";
}

// ==========================================
// 🚀 FINAL APPROVE & DISPATCH TO CEO (UPGRADED)
// ==========================================
document.addEventListener("DOMContentLoaded", () => {
  const btnApproveDispatch = document.getElementById('btn-approve-dispatch');

  // 1. Just open the custom modal when they click the button
  if (btnApproveDispatch) {
    btnApproveDispatch.addEventListener('click', () => {
      const dispatchModal = document.getElementById('dispatch-confirm-modal');
      if (dispatchModal) {
        dispatchModal.classList.remove('hidden');
        dispatchModal.style.display = 'flex';
      }
    });
  }
});

// 2. The actual execution function attached to the Modal's "Yes" button
window.executePriorityDispatch = function(event) {
  if (event) event.preventDefault();

  const dispatchModal = document.getElementById('dispatch-confirm-modal');
  const btnApproveDispatch = document.getElementById('btn-approve-dispatch');

  // Hide the modal
  if (dispatchModal) {
    dispatchModal.classList.add('hidden');
    dispatchModal.style.display = 'none';
  }

  if (btnApproveDispatch) {
    btnApproveDispatch.innerText = "⏳ Dispatching...";
    btnApproveDispatch.disabled = true;
  }

  // Helper: Safely restores the CPDO Admin Dashboard without blank screens
  function returnToDashboardView() {
    const priorityView = document.getElementById('view-report-priority');
    if (priorityView) {
      priorityView.classList.add('hidden');
      priorityView.style.display = 'none';
    }

    const adminDashboard = document.getElementById('view-admin-dashboard');
    if (adminDashboard) {
      adminDashboard.classList.remove('hidden');
      adminDashboard.style.display = 'block'; // 🛑 KEY: Clears inline display:none
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }

    if (typeof loadAdminDashboardData === 'function') {
      loadAdminDashboardData();
    }
  }

  const baseUrl = typeof API_BASE_URL !== 'undefined' ? API_BASE_URL : '';
  const currentUserId = sessionStorage.getItem("userId") || '';

  // 🚀 Call the Java Endpoint with userId for strict audit logging
  fetch(`${baseUrl}/api/reports/dispatch-masterlist?userId=${currentUserId}`, {
    method: 'PUT',
    headers: {
      'ngrok-skip-browser-warning': 'true'
    }
  })
    .then(async response => {
      const text = await response.text();

      // If backend returns 400 (Queue is empty), catch it as a specific notice
      if (!response.ok) {
        if (response.status === 400 || text.toLowerCase().includes("no 'validated' reports")) {
          throw new Error("EMPTY_QUEUE");
        }
        throw new Error(text || "Failed to dispatch");
      }

      return text;
    })
    .then(message => {
      // 🚀 THE BEAUTIFUL TOAST SUCCESS MESSAGE
      showToast(message || "Masterlist successfully dispatched to CEO!", "success");
      returnToDashboardView();
    })
    .catch(err => {
      console.warn("Dispatch result:", err.message);

      // Revised friendly message when there are no valid reports
      if (err.message === "EMPTY_QUEUE") {
        showToast("No validated reports with photo evidence available to dispatch.", "warning");
      } else {
        showToast("Error dispatching Masterlist. Is the server running?", "error");
      }

      // Return to dashboard so you never get stranded on a blank page
      returnToDashboardView();
    })
    .finally(() => {
      if (btnApproveDispatch) {
        btnApproveDispatch.innerText = "🚀 Approve & Dispatch to CEO";
        btnApproveDispatch.disabled = false;
      }
    });
};

// ==========================================
// 🛣️ GLOBAL REPAIR TRACKING STATE
// ==========================================
window.rawTrackedReports = [];
window.currentFilteredTrackedReports = [];

// ==========================================
// ADMIN DASHBOARD: LOAD REPAIR TRACKING (STRICT ACTIVE QUEUE)
// ==========================================
function loadTrackingData() {
  const trackingTableBody = document.querySelector('#view-tracking .data-table tbody');
  if (!trackingTableBody) return;

  apiFetch(`/api/reports`)
    .then(reports => {
      trackingTableBody.innerHTML = '';

      // 🚀 OPTION A: STRICT ACTIVE QUEUE ONLY
      // Excludes 'closed' and 'archived' — they belong in Archive & Reports
      const trackedReports = (Array.isArray(reports) ? reports : []).filter(r => {
        const status = String(r.status || '').toLowerCase().trim();
        return status === 'dispatched to ceo' ||
          status === 'in progress' ||
          status === 'completed' ||
          status === 'pending budget';
      });

      if (trackedReports.length === 0) {
        window.rawTrackedReports = [];
        window.currentFilteredTrackedReports = [];
        trackingTableBody.innerHTML = `
          <tr>
            <td colspan="7" style="text-align: center; padding: 40px; color: #64748b;">
              <div style="font-size: 28px; margin-bottom: 8px;">🛠️</div>
              <strong>No active repair projects to track.</strong>
              <p style="font-size: 13px; margin: 4px 0 0 0; color: #94a3b8;">
                All verified and closed projects are securely stored in <strong>Archive & Reports</strong>.
              </p>
            </td>
          </tr>
        `;
        return;
      }

      trackedReports.forEach(report => {
        const status = String(report.status || '').toLowerCase().trim();
        if (status === 'completed') report.statusScore = 4;
        else if (status === 'in progress') report.statusScore = 3;
        else if (status === 'dispatched to ceo') report.statusScore = 2;
        else if (status === 'pending budget') report.statusScore = 1;
        else report.statusScore = 0;

        const rawSeverity = String(report.severity || '').toLowerCase().trim();
        const importance = String(report.roadImportance || '').toLowerCase().trim();

        if (rawSeverity === 'high' || (rawSeverity === 'medium' && importance.includes('core'))) {
          report.priorityScore = 3;
        } else if (rawSeverity === 'medium' || (rawSeverity === 'low' && importance.includes('core'))) {
          report.priorityScore = 2;
        } else if (rawSeverity === 'low') {
          report.priorityScore = 1;
        } else {
          report.priorityScore = 0;
        }
      });

      trackedReports.sort((a, b) => {
        if (b.statusScore !== a.statusScore) return b.statusScore - a.statusScore;
        if (b.priorityScore !== a.priorityScore) return b.priorityScore - a.priorityScore;
        const idA = parseInt(a.id) || 0;
        const idB = parseInt(b.id) || 0;
        return idB - idA;
      });

      // Sync for report generator & export
      window.rawTrackedReports = trackedReports;
      window.currentFilteredTrackedReports = [...trackedReports];

      trackedReports.forEach(report => {
        const formatId = `#PRJ-${String(report.id).padStart(4, '0')}`;
        const formatBrgy = (report.barangay && report.barangay.barangayName) ? report.barangay.barangayName : 'Unknown';
        const roadName = report.cityRoadName || 'Unknown Road';
        const currentStatus = String(report.status || '').toLowerCase().trim();

        const rawSeverity = String(report.severity || '').toLowerCase().trim();
        const importance = String(report.roadImportance || '').toLowerCase().trim();

        // Priority Badge
        let badgeHtml = '';
        let borderStyle = '4px solid #10b981';

        if (rawSeverity === 'high' || (rawSeverity === 'medium' && importance.includes('core'))) {
          badgeHtml = `<span class="badge high">HIGH</span>`;
          borderStyle = '4px solid #dc3545';
        } else if (rawSeverity === 'medium' || (rawSeverity === 'low' && importance.includes('core'))) {
          badgeHtml = `<span class="badge medium">MEDIUM</span>`;
          borderStyle = '4px solid #ffc107';
        } else if (rawSeverity === 'low') {
          badgeHtml = `<span class="badge low">LOW</span>`;
          borderStyle = '4px solid var(--accent-blue, #2563eb)';
        } else {
          badgeHtml = `<span class="badge" style="background-color: #dcfce7; color: #15803d; border: 1px solid #86efac; font-weight: 700;">UNASSESSED</span>`;
          borderStyle = '4px solid #10b981';
        }

        let statusHtml = '';
        let checkboxHtml = '';

        if (currentStatus === 'completed') {
          statusHtml = `<span class="status-badge validated" style="background-color: #d4edda; color: #155724;">Completed (Pending QA)</span>`;
          borderStyle = '4px solid #28a745';
        } else if (currentStatus === 'in progress') {
          statusHtml = `<span class="status-badge" style="background-color: #cce5ff; color: #004085;">In Progress</span>`;
        } else if (currentStatus === 'pending budget') {
          statusHtml = `<span class="status-badge" style="background-color: #fef08a; color: #854d0e;">⚠️ Pending Budget</span>`;
          borderStyle = '4px solid #eab308';
          checkboxHtml = `<input type="checkbox" class="archive-checkbox" value="${report.id}" onclick="updateBatchArchiveUI()" style="cursor: pointer; transform: scale(1.2);">`;
        } else {
          statusHtml = `<span class="status-badge pending" style="background-color: #e2e3e5; color: #383d41;">Dispatched to CEO</span>`;
        }

        const row = document.createElement('tr');
        row.style.borderLeft = borderStyle;
        if (currentStatus === 'completed') row.style.backgroundColor = '#fafafa';

        row.innerHTML = `
          <td style="text-align: center;">${checkboxHtml}</td>
          <td><strong>${formatId}</strong></td>
          <td>${formatBrgy}</td>
          <td>${roadName}</td>
          <td>${badgeHtml}</td>
          <td>${statusHtml}</td>
          <td><button class="btn-small track-btn" onclick="openTrackingModal(${report.id})">Track</button></td>
        `;
        trackingTableBody.appendChild(row);
      });

      if (typeof window.filterTrackingReports === 'function') {
        window.filterTrackingReports();
      }
    })
    .catch(error => {
      console.error("Error loading tracking data:", error);
      trackingTableBody.innerHTML = `<tr><td colspan="7" style="text-align: center; color: red;">Failed to load tracking data.</td></tr>`;
    });
}


// ==========================================
// 7 & 8. TRACKING MODAL ENGINE (RELIABLE GLOBAL HANDLERS)
// ==========================================
let currentTrackingReportId = null;
let currentTrackingReportStatus = null; // 🚀 Tracks status for dynamic rework vs deferral rejection

// 🗺️ 1. MAP STATE VARIABLES
let currentTrackLat = 0;
let currentTrackLng = 0;
let trackModalMap = null;
let trackModalMarker = null;

// 📜 TRACKING TIMELINE ACCORDION TOGGLE
window.toggleTrackTimeline = function() {
  const container = document.getElementById('track-modal-timeline-container');
  const arrow = document.getElementById('track-timeline-arrow');
  if (!container) return;

  const isHidden = container.classList.toggle('hidden');
  if (arrow) arrow.style.transform = isHidden ? 'rotate(0deg)' : 'rotate(180deg)';

  // 🚀 RESET INNER TIMELINE SCROLL UPON OPENING
  if (!isHidden) {
    container.scrollTop = 0;
    requestAnimationFrame(() => { container.scrollTop = 0; });
  }
};

// ==========================================
// 🚀 OPEN TRACKING MODAL & POPULATE DATA
// ==========================================
window.openTrackingModal = function(reportId) {
  currentTrackingReportId = reportId;
  currentTrackingReportStatus = null;

  const trackingModal = document.getElementById('tracking-modal');
  if (!trackingModal) return;

  // 1. Force map container closed on open
  const mapContainer = document.getElementById('track-modal-map-container');
  if (mapContainer) mapContainer.style.display = 'none';

  // 2. Reset rework form & actions state
  const primaryActions = document.getElementById('tracking-primary-actions');
  const reworkForm = document.getElementById('tracking-rework-form');
  const reworkInput = document.getElementById('rework-remarks-input');

  if (primaryActions) primaryActions.classList.remove('hidden');
  if (reworkForm) reworkForm.classList.add('hidden');
  if (reworkInput) reworkInput.value = '';

  // 3. Reset reminder, scheduling, & extension review rows before fetch
  const reminderContainer = document.getElementById('track-modal-reminder-container');
  if (reminderContainer) reminderContainer.style.display = 'none';

  const scheduleInfo = document.getElementById('track-modal-schedule-info');
  if (scheduleInfo) scheduleInfo.style.display = 'none';

  const turnaroundInfo = document.getElementById('track-modal-turnaround-info');
  if (turnaroundInfo) turnaroundInfo.style.display = 'none';

  const targetBadge = document.getElementById('track-modal-target-badge');
  if (targetBadge) targetBadge.style.display = 'none';

  const reworkAlert = document.getElementById('track-modal-rework-alert');
  if (reworkAlert) reworkAlert.style.display = 'none';

  const deferredAlert = document.getElementById('track-modal-deferred-alert');
  if (deferredAlert) deferredAlert.style.display = 'none';

  const extensionAlert = document.getElementById('track-modal-extension-alert');
  if (extensionAlert) extensionAlert.style.display = 'none';
  if (typeof toggleExtensionRemarksForm === 'function') {
    toggleExtensionRemarksForm(false);
  }

  // 4. Reset accordion to collapsed state and zero out timeline scroll
  const timelineContainer = document.getElementById('track-modal-timeline-container');
  if (timelineContainer) {
    timelineContainer.classList.add('hidden');
    timelineContainer.scrollTop = 0;
  }
  const arrow = document.getElementById('track-timeline-arrow');
  if (arrow) arrow.style.transform = 'rotate(0deg)';

  // 5. Reveal modal & reset scroll
  trackingModal.classList.remove('hidden');
  if (typeof resetModalScroll === 'function') {
    resetModalScroll(trackingModal);
  }

  // 6. Fetch Project Details
  apiFetch(`/api/reports/${reportId}`)
    .then(report => {
      currentTrackingReportStatus = report.status ? report.status.toLowerCase() : '';
      currentTrackLat = parseFloat(report.latitude) || 0;
      currentTrackLng = parseFloat(report.longitude) || 0;

      const setText = (id, text) => {
        const el = document.getElementById(id);
        if (el) el.textContent = text;
      };

      // Road Details
      setText('track-modal-id', `#PRJ-${String(report.id).padStart(4, '0')}`);
      setText('track-modal-brgy', report.barangay?.barangayName || 'Unknown');
      setText('track-modal-road', report.cityRoadName || 'Unknown Road');
      setText('track-modal-road-id', report.cityRoadId || 'N/A');
      setText('track-modal-importance', report.roadImportance || 'N/A');
      setText('track-modal-terrain', report.terrainType || 'N/A');
      setText('track-modal-road-type', report.roadType || 'N/A');
      setText('track-modal-road-length', report.length ? `${report.length} km` : '0 km');
      setText('track-modal-road-width', report.width ? `${report.width} m` : '0 m');
      setText('track-modal-culverts', report.lengthOfCulverts ? `${report.lengthOfCulverts} m` : '0 m');
      setText('track-modal-gps', (report.latitude && report.longitude) ? `${report.latitude.toFixed(5)}° N, ${report.longitude.toFixed(5)}° E` : 'No GPS data');

      // Submitter Info
      let submitterText = `Barangay Official (${report.barangay?.barangayName || 'Unknown'})`;
      if (report.user && report.user.firstName && report.user.lastName) {
        submitterText = `${report.user.firstName} ${report.user.lastName} (${report.barangay?.barangayName || 'Unknown'})`;
      } else if (report.reportedBy) {
        submitterText = report.reportedBy;
      }
      setText('track-modal-submitter', submitterText);

      // AI Severity Badge
      const sevBox = document.getElementById('track-modal-severity');
      if (sevBox) {
        const rawSev = String(report.severity || '').trim().toLowerCase();
        const confScore = report.cvConfidenceScore || report.cvconfidenceScore;
        const confText = confScore ? ` (${confScore}%)` : '';

        if (rawSev === 'high') {
          sevBox.innerHTML = `<span style="background-color: #dc2626; color: #ffffff; border: 1px solid #b91c1c; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11px; display: inline-block; letter-spacing: 0.4px;">HIGH${confText}</span>`;
        } else if (rawSev === 'medium') {
          sevBox.innerHTML = `<span style="background-color: #f59e0b; color: #ffffff; border: 1px solid #d97706; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11px; display: inline-block; letter-spacing: 0.4px;">MEDIUM${confText}</span>`;
        } else if (rawSev === 'low') {
          sevBox.innerHTML = `<span style="background-color: #16a34a; color: #ffffff; border: 1px solid #15803d; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11px; display: inline-block; letter-spacing: 0.4px;">LOW${confText}</span>`;
        } else {
          sevBox.innerHTML = `<span style="background-color: #ecfdf5; color: #047857; border: 1.5px solid #34d399; padding: 4px 10px; border-radius: 6px; font-weight: 800; font-size: 11px; display: inline-block; letter-spacing: 0.4px;">UNASSESSED</span>`;
        }
      }

      // Damage Specifications & Area
      const dmgType = report.damageType || 'Not specified';
      const dmgLen = parseFloat(report.damageLength) || 0;
      const dmgWid = parseFloat(report.damageWidth) || 0;
      const dmgArea = dmgLen * dmgWid;

      setText('track-modal-damage-type', dmgType);
      setText('track-modal-damage-dimensions', (dmgLen > 0 || dmgWid > 0) ? `${dmgLen}m (L) × ${dmgWid}m (W)` : 'Not specified');
      setText('track-modal-damage-area', dmgArea > 0 ? `${dmgArea.toFixed(1)} sq.m` : '0 sq.m');
      setText('track-modal-desc', report.damageDescription || 'No description provided.');

      if (typeof window.loadSecureImage === 'function') {
        window.loadSecureImage('track-modal-image', report.damageImage);
      }

      // ========================================================
      // 🎯 RESOLUTION, SCHEDULING & ACTION BUTTON CONTROLS
      // ========================================================
      const statusBox = document.getElementById('track-modal-status');
      const statusText = document.getElementById('track-modal-status-text');

      const targetDateEl = document.getElementById('track-modal-target-date');
      const targetDaysEl = document.getElementById('track-modal-target-days');
      const reworkInstructions = document.getElementById('track-modal-rework-instructions');
      const deferredRemarks = document.getElementById('track-modal-deferred-remarks');

      // ⏱️ Extension Request Review Elements
      const extAlert = document.getElementById('track-modal-extension-alert');
      const extDate = document.getElementById('track-modal-extension-date');
      const extReason = document.getElementById('track-modal-extension-reason');

      // 🔔 Reminder Elements
      const reminderHint = document.getElementById('track-modal-reminder-hint');
      const btnReminder = document.getElementById('btn-send-deadline-reminder');
      const btnReminderText = document.getElementById('btn-reminder-text');
      const btnReminderIcon = document.getElementById('btn-reminder-icon');

      const completedDateEl = document.getElementById('track-modal-completed-date');
      const turnaroundBadge = document.getElementById('track-modal-turnaround-badge');

      const proofPlaceholder = document.getElementById('track-modal-proof-placeholder');
      const placeholderText = document.getElementById('track-modal-placeholder-text');
      const resolutionData = document.getElementById('track-modal-resolution-data');
      const proofRemarks = document.getElementById('track-modal-proof-remarks');

      const approveBtn = document.getElementById('btn-approve-project');
      const approveText = document.getElementById('btn-approve-text');
      const reworkBtn = document.getElementById('btn-rework-project');
      const reworkText = document.getElementById('btn-rework-text');

      const status = currentTrackingReportStatus;
      const isRework = status === 'in progress' && report.adminRemarks && report.adminRemarks.trim() !== '';

      // ⚠️ ACTIVE REWORK CHECK
      if (isRework) {
        if (reworkAlert) reworkAlert.style.display = 'block';
        if (reworkInstructions) reworkInstructions.textContent = report.adminRemarks;
      } else {
        if (reworkAlert) reworkAlert.style.display = 'none';
      }

      // ⏱️ EXTENSION REQUEST CHECK (If CEO has submitted a pending extension)
      if (report.extensionStatus === 'PENDING') {
        if (extAlert) extAlert.style.display = 'block';
        if (extDate) extDate.textContent = report.extensionTargetDate || 'N/A';
        if (extReason) extReason.textContent = report.extensionReason || 'No justification provided.';
      } else {
        if (extAlert) extAlert.style.display = 'none';
      }

      // ⏳ LIVE COUNTDOWN & REMINDER CONTROLLER
      const calculateCountdown = () => {
        if (!report.targetCompletionDate) {
          if (targetBadge) targetBadge.style.display = 'none';
          if (scheduleInfo) scheduleInfo.style.display = 'none';
          if (reminderContainer) reminderContainer.style.display = 'none';
          return;
        }

        if (scheduleInfo) scheduleInfo.style.display = 'block';
        if (targetDateEl) targetDateEl.textContent = report.targetCompletionDate;

        const target = new Date(report.targetCompletionDate + 'T23:59:59');
        const now = new Date();
        const diffMs = target - now;
        const diffDays = Math.ceil(diffMs / (1000 * 60 * 60 * 24));

        // Render countdown header badge
        if (targetBadge) {
          targetBadge.style.display = 'inline-block';
          if (diffDays > 1) {
            targetBadge.textContent = `⏳ Target: ${report.targetCompletionDate} (${diffDays} days left)`;
            targetBadge.style.cssText = 'background: #e0f2fe; color: #0369a1; border: 1px solid #7dd3fc; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 700; display: inline-block;';
            if (targetDaysEl) targetDaysEl.textContent = `${diffDays} days remaining`;
          } else if (diffDays === 1) {
            targetBadge.textContent = `⏳ Target: Due Tomorrow (${report.targetCompletionDate})`;
            targetBadge.style.cssText = 'background: #fef3c7; color: #b45309; border: 1px solid #fde047; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 700; display: inline-block;';
            if (targetDaysEl) targetDaysEl.textContent = `1 day remaining (Due Tomorrow)`;
          } else if (diffDays === 0) {
            targetBadge.textContent = `⚠️ Target: Due Today (${report.targetCompletionDate})`;
            targetBadge.style.cssText = 'background: #fef3c7; color: #b45309; border: 1px solid #fde047; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 700; display: inline-block;';
            if (targetDaysEl) targetDaysEl.textContent = `Due Today`;
          } else {
            const overdue = Math.abs(diffDays);
            targetBadge.textContent = `🚨 Overdue by ${overdue} day${overdue > 1 ? 's' : ''} (${report.targetCompletionDate})`;
            targetBadge.style.cssText = 'background: #fee2e2; color: #b91c1c; border: 1px solid #fca5a5; padding: 4px 10px; border-radius: 6px; font-size: 12px; font-weight: 700; display: inline-block;';
            if (targetDaysEl) targetDaysEl.textContent = `Overdue by ${overdue} day${overdue > 1 ? 's' : ''}`;
          }
        }

        // 🔔 REMINDER BUTTON & 12-HOUR DATABASE COOLDOWN LOGIC
        if (reminderContainer && btnReminder) {
          if (diffDays <= 2) {
            reminderContainer.style.display = 'flex';

            let inCooldown = false;
            let lastSentFormatted = '';

            if (report.lastReminderSent) {
              const lastSentTime = new Date(report.lastReminderSent);
              const cooldownHours = 12;
              const cooldownMs = cooldownHours * 60 * 60 * 1000;
              const timeSinceSent = now - lastSentTime;

              if (timeSinceSent < cooldownMs) {
                inCooldown = true;
                lastSentFormatted = lastSentTime.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
              }
            }

            if (inCooldown) {
              btnReminder.disabled = true;
              btnReminder.style.backgroundColor = '#94a3b8';
              btnReminder.style.color = '#ffffff';
              btnReminder.style.cursor = 'not-allowed';
              btnReminder.style.opacity = '0.85';
              if (btnReminderIcon) btnReminderIcon.textContent = '✔️';
              if (btnReminderText) btnReminderText.textContent = 'Reminder Issued Today';
              if (reminderHint) {
                reminderHint.textContent = `Notice already issued to CEO at ${lastSentFormatted}. (12h cooldown active)`;
              }
            } else {
              btnReminder.disabled = false;
              btnReminder.style.cursor = 'pointer';
              btnReminder.style.opacity = '1';

              if (diffDays < 0) {
                btnReminder.style.backgroundColor = '#dc2626';
                btnReminder.style.color = '#ffffff';
                if (btnReminderIcon) btnReminderIcon.textContent = '🚨';
                if (btnReminderText) btnReminderText.textContent = 'Issue Overdue Notice';
                if (reminderHint) reminderHint.textContent = 'Project is overdue. Dispatch an urgent escalation notice to the City Engineer.';
              } else if (diffDays === 0) {
                btnReminder.style.backgroundColor = '#ea580c';
                btnReminder.style.color = '#ffffff';
                if (btnReminderIcon) btnReminderIcon.textContent = '⚠️';
                if (btnReminderText) btnReminderText.textContent = 'Alert CEO: Due Today';
                if (reminderHint) reminderHint.textContent = 'Deadline is today. Send a priority alert to the engineering team.';
              } else {
                btnReminder.style.backgroundColor = '#f59e0b';
                btnReminder.style.color = '#ffffff';
                if (btnReminderIcon) btnReminderIcon.textContent = '⚠️';
                if (btnReminderText) btnReminderText.textContent = 'Send Due Reminder';
                if (reminderHint) reminderHint.textContent = 'Impending deadline. Notify the City Engineer to prioritize on-site completion.';
              }
            }
          } else {
            reminderContainer.style.display = 'none';
          }
        }
      };

      // 🏁 ACTUAL TURNAROUND CALCULATOR
      const calculateTurnaround = () => {
        const completedTimeStr = report.actualCompletionDate || report.dateArchived;
        if (!completedTimeStr) {
          if (turnaroundInfo) turnaroundInfo.style.display = 'none';
          return;
        }

        if (turnaroundInfo) turnaroundInfo.style.display = 'block';
        const compDateObj = new Date(completedTimeStr);
        const compFormatted = compDateObj.toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
        if (completedDateEl) completedDateEl.textContent = compFormatted;

        if (turnaroundBadge) {
          if (report.targetCompletionDate) {
            const targetObj = new Date(report.targetCompletionDate + 'T23:59:59');
            const diffTime = targetObj - compDateObj;
            const diffDays = Math.round(diffTime / (1000 * 60 * 60 * 24));

            if (diffDays > 0) {
              turnaroundBadge.textContent = `⚡ Finished ${diffDays} day${diffDays > 1 ? 's' : ''} ahead of schedule`;
              turnaroundBadge.style.cssText = 'background: #dcfce7; color: #15803d; border: 1px solid #86efac;';
            } else if (diffDays === 0) {
              turnaroundBadge.textContent = `🎯 Completed on schedule`;
              turnaroundBadge.style.cssText = 'background: #e0f2fe; color: #0369a1; border: 1px solid #7dd3fc;';
            } else {
              const lateDays = Math.abs(diffDays);
              turnaroundBadge.textContent = `⚠️ Completed ${lateDays} day${lateDays > 1 ? 's' : ''} behind schedule`;
              turnaroundBadge.style.cssText = 'background: #fee2e2; color: #b91c1c; border: 1px solid #fca5a5;';
            }
          } else {
            turnaroundBadge.textContent = `✅ Completed`;
            turnaroundBadge.style.cssText = 'background: #dcfce7; color: #15803d; border: 1px solid #86efac;';
          }
        }
      };

      // --- STATE 1: COMPLETED (Awaiting Admin QA) ---
      if (status.includes('complet') || status.includes('repair')) {
        if (statusBox) {
          statusBox.textContent = 'Repaired (Pending Approval)';
          statusBox.style.backgroundColor = '#d4edda';
          statusBox.style.color = '#155724';
        }
        if (statusText) statusText.textContent = 'CEO has completed the repair and submitted proof. Awaiting your QA decision.';

        if (deferredAlert) deferredAlert.style.display = 'none';
        if (proofPlaceholder) proofPlaceholder.style.display = 'none';
        if (resolutionData) resolutionData.style.display = 'block';
        if (scheduleInfo) scheduleInfo.style.display = 'none';
        if (targetBadge) targetBadge.style.display = 'none';
        if (reminderContainer) reminderContainer.style.display = 'none';
        if (extAlert) extAlert.style.display = 'none';

        calculateTurnaround();

        if (proofRemarks) proofRemarks.textContent = report.repairRemarks || "No official remarks provided.";
        if (typeof window.loadSecureImage === 'function') {
          window.loadSecureImage('track-modal-proof-image', report.proofOfRepairImage);
        }

        if (reworkBtn) {
          reworkBtn.classList.remove('hidden');
          if (reworkText) reworkText.textContent = 'Require Rework';
          reworkBtn.style.backgroundColor = '#dc3545';
        }
        if (approveBtn) {
          approveBtn.disabled = false;
          approveBtn.style.backgroundColor = '#28a745';
          approveBtn.style.cursor = 'pointer';
          if (approveText) approveText.textContent = 'Approve & Close Project';
        }

        // --- STATE 2: PENDING BUDGET (Deferred by CEO) ---
      } else if (status === 'pending budget' || status.includes('defer')) {
        if (statusBox) {
          statusBox.textContent = 'Deferred (Pending Budget)';
          statusBox.style.backgroundColor = '#fef08a';
          statusBox.style.color = '#854d0e';
        }
        if (statusText) statusText.textContent = 'Project is on fiscal hold. Review deferral remarks below.';

        if (deferredAlert) {
          deferredAlert.style.display = 'block';
          if (deferredRemarks) {
            deferredRemarks.textContent = report.repairRemarks || report.adminRemarks || 'Deferred due to budget allocation constraints.';
          }
        }
        if (proofPlaceholder) proofPlaceholder.style.display = 'none';
        if (resolutionData) resolutionData.style.display = 'none';
        if (scheduleInfo) scheduleInfo.style.display = 'none';
        if (turnaroundInfo) turnaroundInfo.style.display = 'none';
        if (targetBadge) targetBadge.style.display = 'none';
        if (reminderContainer) reminderContainer.style.display = 'none';
        if (extAlert) extAlert.style.display = 'none';

        if (reworkBtn) {
          reworkBtn.classList.remove('hidden');
          if (reworkText) reworkText.textContent = 'Reject Deferral';
          reworkBtn.style.backgroundColor = '#dc3545';
        }
        if (approveBtn) {
          approveBtn.disabled = false;
          approveBtn.style.backgroundColor = '#475569';
          approveBtn.style.cursor = 'pointer';
          if (approveText) approveText.textContent = 'Acknowledge & Archive';
        }

        // --- STATE 3: IN PROGRESS / REWORK ---
      } else if (status.includes('progress')) {
        if (statusBox) {
          statusBox.textContent = 'In Progress';
          statusBox.style.backgroundColor = '#cce5ff';
          statusBox.style.color = '#004085';
        }
        if (statusText) {
          statusText.textContent = isRework
            ? 'Engineering crew is actively working on your rework instructions.'
            : 'Engineering crew is currently on-site executing repairs.';
        }

        if (deferredAlert) deferredAlert.style.display = 'none';
        if (proofPlaceholder) {
          proofPlaceholder.style.display = 'block';
          if (placeholderText) {
            placeholderText.textContent = isRework
              ? 'Awaiting Corrected Proof Photo from Engineering'
              : 'Awaiting Completion Photo from Engineering';
          }
        }
        if (resolutionData) resolutionData.style.display = 'none';
        if (turnaroundInfo) turnaroundInfo.style.display = 'none';

        calculateCountdown();

        if (reworkBtn) reworkBtn.classList.add('hidden');
        if (approveBtn) {
          approveBtn.disabled = true;
          approveBtn.style.backgroundColor = '#ccc';
          approveBtn.style.cursor = 'not-allowed';
          if (approveText) approveText.textContent = 'Approve & Close Project';
        }

        // --- STATE 4: CLOSED / ARCHIVED ---
      } else if (status === 'closed' || status === 'archived' || status === 'resolved') {
        if (statusBox) {
          statusBox.textContent = status === 'archived' ? 'Archived' : 'Closed';
          statusBox.style.backgroundColor = '#e2e8f0';
          statusBox.style.color = '#334155';
        }
        if (statusText) statusText.textContent = 'Project ticket is officially closed and finalized.';

        if (deferredAlert) deferredAlert.style.display = 'none';
        if (proofPlaceholder) proofPlaceholder.style.display = 'none';
        if (resolutionData) resolutionData.style.display = 'block';
        if (scheduleInfo) scheduleInfo.style.display = 'none';
        if (targetBadge) targetBadge.style.display = 'none';
        if (reminderContainer) reminderContainer.style.display = 'none';
        if (extAlert) extAlert.style.display = 'none';

        calculateTurnaround();

        if (proofRemarks) proofRemarks.textContent = report.repairRemarks || "No official remarks provided.";
        if (typeof window.loadSecureImage === 'function') {
          window.loadSecureImage('track-modal-proof-image', report.proofOfRepairImage);
        }

        if (reworkBtn) reworkBtn.classList.add('hidden');
        if (approveBtn) {
          approveBtn.disabled = true;
          approveBtn.style.backgroundColor = '#ccc';
          approveBtn.style.cursor = 'not-allowed';
          if (approveText) approveText.textContent = 'Project Closed';
        }

        // --- STATE 5: DEFAULT / DISPATCHED ---
      } else {
        if (statusBox) {
          statusBox.textContent = report.status || 'Dispatched to CEO';
          statusBox.style.backgroundColor = '#e2e3e5';
          statusBox.style.color = '#383d41';
        }
        if (statusText) statusText.textContent = 'Awaiting crew dispatch by the City Engineer.';

        if (deferredAlert) deferredAlert.style.display = 'none';
        if (proofPlaceholder) {
          proofPlaceholder.style.display = 'block';
          if (placeholderText) placeholderText.textContent = 'Awaiting Completion Photo from Engineering';
        }
        if (resolutionData) resolutionData.style.display = 'none';
        if (scheduleInfo) scheduleInfo.style.display = 'none';
        if (turnaroundInfo) turnaroundInfo.style.display = 'none';
        if (targetBadge) targetBadge.style.display = 'none';
        if (reminderContainer) reminderContainer.style.display = 'none';
        if (extAlert) extAlert.style.display = 'none';

        if (reworkBtn) reworkBtn.classList.add('hidden');
        if (approveBtn) {
          approveBtn.disabled = true;
          approveBtn.style.backgroundColor = '#ccc';
          approveBtn.style.cursor = 'not-allowed';
          if (approveText) approveText.textContent = 'Approve & Close Project';
        }
      }

      // 🕒 Load Timeline into the Accordion
      if (typeof loadReportTimeline === 'function') {
        loadReportTimeline(report.id, 'track-modal-timeline');
      }

      // Final Scroll Reset
      if (typeof resetModalScroll === 'function') {
        resetModalScroll(trackingModal);
      }
    })
    .catch(err => {
      console.error("Error loading tracking details:", err);
      if (typeof showToast === 'function') showToast("Error loading project details.", "error");
    });
};

// ==========================================
// ⏱️ CPDO ADMIN: TOGGLE EXTENSION REJECT REMARKS FORM
// ==========================================
window.toggleExtensionRemarksForm = function(show) {
  const form = document.getElementById('track-modal-extension-reject-form');
  const actions = document.getElementById('track-modal-extension-actions');
  const input = document.getElementById('extension-reject-remarks-input');

  if (show) {
    if (form) form.classList.remove('hidden');
    if (actions) actions.style.display = 'none';
    if (input) input.focus();
  } else {
    if (form) form.classList.add('hidden');
    if (actions) actions.style.display = 'flex';
    if (input) input.value = '';
  }
};

// ==========================================
// ⏱️ CPDO ADMIN: REVIEW EXTENSION (APPROVE / REJECT)
// ==========================================
window.handleReviewExtension = function(decision) {
  if (!currentTrackingReportId) return;

  const isApproved = decision === 'APPROVED';
  const remarksInput = document.getElementById('extension-reject-remarks-input');
  const remarks = (!isApproved && remarksInput) ? remarksInput.value.trim() : '';

  if (!isApproved && !remarks) {
    if (typeof showToast === 'function') showToast("Please provide a reason for rejecting the extension.", "error");
    return;
  }

  const btnApprove = document.getElementById('btn-approve-extension');
  const btnConfirmReject = document.getElementById('btn-confirm-reject-extension');

  if (isApproved && btnApprove) {
    btnApprove.disabled = true;
    btnApprove.textContent = "⏳ Approving...";
  } else if (!isApproved && btnConfirmReject) {
    btnConfirmReject.disabled = true;
    btnConfirmReject.textContent = "⏳ Rejecting...";
  }

  const currentUserId = sessionStorage.getItem("userId");

  fetch(`${API_BASE_URL}/api/reports/${currentTrackingReportId}/review-extension`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'ngrok-skip-browser-warning': 'true'
    },
    body: JSON.stringify({
      decision: decision,
      adminRemarks: remarks,
      userId: currentUserId
    })
  })
    .then(async res => {
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Failed to process extension review.");
      return data;
    })
    .then(data => {
      const toastMsg = isApproved
        ? "Schedule extension approved! Target completion date updated."
        : "Schedule extension rejected. The active target date remains unchanged.";

      if (typeof showToast === 'function') showToast(toastMsg, isApproved ? "success" : "warning");

      toggleExtensionRemarksForm(false);

      // Re-populate modal to show updated countdown and audit trail
      openTrackingModal(currentTrackingReportId);

      if (typeof loadTrackingData === 'function') loadTrackingData();
      if (typeof loadAdminDashboardData === 'function') loadAdminDashboardData();
    })
    .catch(err => {
      console.error("Extension Review Error:", err);
      if (typeof showToast === 'function') {
        showToast(err.message || "Error processing extension decision.", "error");
      }
    })
    .finally(() => {
      if (btnApprove) {
        btnApprove.disabled = false;
        btnApprove.textContent = "✅ Approve Extension";
      }
      if (btnConfirmReject) {
        btnConfirmReject.disabled = false;
        btnConfirmReject.textContent = "Confirm Rejection";
      }
    });
};

// ==========================================
// 🔔 SEND DEADLINE REMINDER / OVERDUE NOTICE
// ==========================================
window.sendDeadlineReminder = function() {
  if (!currentTrackingReportId) return;

  const btnReminder = document.getElementById('btn-send-deadline-reminder');
  const btnText = document.getElementById('btn-reminder-text');
  const originalText = btnText ? btnText.textContent : "Send Due Reminder";

  if (btnReminder) {
    btnReminder.disabled = true;
    btnReminder.style.opacity = '0.7';
    if (btnText) btnText.textContent = "Sending Alert...";
  }

  const currentUserId = sessionStorage.getItem("userId");

  fetch(`${API_BASE_URL}/api/reports/${currentTrackingReportId}/remind-due`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'ngrok-skip-browser-warning': 'true'
    },
    body: JSON.stringify({ userId: currentUserId })
  })
    .then(async res => {
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Failed to send deadline reminder.");
      return data;
    })
    .then(data => {
      if (typeof showToast === 'function') {
        showToast(data.message || "Reminder successfully issued to the City Engineer!", "success");
      }
      openTrackingModal(currentTrackingReportId);
    })
    .catch(err => {
      console.error("Reminder Error:", err);
      if (typeof showToast === 'function') {
        showToast(err.message || "Failed to issue reminder.", "error");
      }
      if (btnReminder) {
        btnReminder.disabled = false;
        btnReminder.style.opacity = '1';
        if (btnText) btnText.textContent = originalText;
      }
    });
};

// ==========================================
// 🗺️ TOGGLE MAP FUNCTION
// ==========================================
window.toggleTrackMap = function() {
  const mapContainer = document.getElementById('track-modal-map-container');
  if (!mapContainer) return;

  if (!currentTrackLat || !currentTrackLng || (currentTrackLat === 0 && currentTrackLng === 0)) {
    showToast("No GPS coordinates were provided for this report.", "error");
    return;
  }

  if (mapContainer.style.display === 'none' || mapContainer.style.display === '') {
    mapContainer.style.display = 'block';

    if (typeof L === 'undefined') {
      console.error("Leaflet library (L) is not loaded.");
      return;
    }

    const redIcon = new L.Icon({
      iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-red.png',
      shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
      iconSize: [25, 41],
      iconAnchor: [12, 41],
      popupAnchor: [1, -34],
      shadowSize: [41, 41]
    });

    if (!trackModalMap) {
      trackModalMap = L.map('track-modal-map').setView([currentTrackLat, currentTrackLng], 17);

      L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
        attribution: 'Tiles &copy; Esri &mdash; Source: Esri'
      }).addTo(trackModalMap);

      trackModalMarker = L.marker([currentTrackLat, currentTrackLng], { icon: redIcon }).addTo(trackModalMap);
    } else {
      trackModalMap.setView([currentTrackLat, currentTrackLng], 17);
      trackModalMarker.setLatLng([currentTrackLat, currentTrackLng]);
    }

    setTimeout(() => {
      trackModalMap.invalidateSize();
    }, 200);

  } else {
    mapContainer.style.display = 'none';
  }
};

// ==========================================
// 🚪 CLOSE TRACKING MODAL
// ==========================================
window.closeTrackingModal = function() {
  const trackingModal = document.getElementById('tracking-modal');
  if (trackingModal) trackingModal.classList.add('hidden');

  const mapContainer = document.getElementById('track-modal-map-container');
  if (mapContainer) mapContainer.style.display = 'none';

  const reworkForm = document.getElementById('tracking-rework-form');
  const primaryActions = document.getElementById('tracking-primary-actions');
  if (reworkForm) reworkForm.classList.add('hidden');
  if (primaryActions) primaryActions.classList.remove('hidden');
};

// ==========================================
// 🔄 REWORK / DEFERRAL REJECTION CONTROLLERS
// ==========================================
window.openReworkForm = function() {
  const primaryActions = document.getElementById('tracking-primary-actions');
  const reworkForm = document.getElementById('tracking-rework-form');
  const reworkInput = document.getElementById('rework-remarks-input');
  const formTitle = document.getElementById('rework-form-title');
  const btnConfirm = document.getElementById('btn-confirm-rework');

  if (currentTrackingReportStatus === 'pending budget' || currentTrackingReportStatus.includes('defer')) {
    if (formTitle) formTitle.textContent = "Reason for Rejecting Deferral";
    if (reworkInput) {
      reworkInput.placeholder = "Explain why this project must be repaired immediately (e.g., Emergency contingency budget authorized, high public hazard)...";
    }
    if (btnConfirm) btnConfirm.textContent = "Reject & Return to CEO";
  } else {
    if (formTitle) formTitle.textContent = "Reason for Rework";
    if (reworkInput) {
      reworkInput.placeholder = "Explain what the engineering crew missed or needs to fix...";
    }
    if (btnConfirm) btnConfirm.textContent = "Submit to CEO";
  }

  if (primaryActions) primaryActions.classList.add('hidden');
  if (reworkForm) reworkForm.classList.remove('hidden');
  if (reworkInput) reworkInput.focus();
};

window.cancelReworkForm = function() {
  const primaryActions = document.getElementById('tracking-primary-actions');
  const reworkForm = document.getElementById('tracking-rework-form');
  const reworkInput = document.getElementById('rework-remarks-input');
  if (reworkForm) reworkForm.classList.add('hidden');
  if (primaryActions) primaryActions.classList.remove('hidden');
  if (reworkInput) reworkInput.value = '';
};

// ==========================================
// 🏁 APPROVE / CLOSE OR ARCHIVE HANDLER
// ==========================================
window.handleApproveProject = function() {
  if (!currentTrackingReportId) return;
  const btnApprove = document.getElementById('btn-approve-project');
  if (!btnApprove || btnApprove.disabled) return;

  const isArchiving = btnApprove.innerText.includes('Archive');
  const targetStatus = isArchiving ? "Archived" : "Closed";
  const loadingText = isArchiving ? "⏳ Archiving..." : "⏳ Approving...";
  const successMsg = isArchiving ? "Project safely acknowledged and archived!" : "Project officially approved and closed!";

  const originalText = btnApprove.innerHTML;
  btnApprove.innerHTML = loadingText;
  btnApprove.disabled = true;

  const currentUserId = sessionStorage.getItem("userId");

  fetch(`${API_BASE_URL}/api/reports/${currentTrackingReportId}/status`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'ngrok-skip-browser-warning': 'true'
    },
    body: JSON.stringify({
      status: targetStatus,
      userId: currentUserId
    })
  })
    .then(res => {
      if (!res.ok) throw new Error("Failed to update project status");
      return res.text();
    })
    .then(() => {
      if (typeof showToast === 'function') showToast(successMsg, "success");
      closeTrackingModal();
      if (typeof loadTrackingData === 'function') loadTrackingData();
      if (typeof loadAdminDashboardData === 'function') loadAdminDashboardData();
    })
    .catch(err => {
      console.error(err);
      if (typeof showToast === 'function') showToast(`Error ${isArchiving ? 'archiving' : 'closing'} project.`, "error");
    })
    .finally(() => {
      btnApprove.innerHTML = originalText;
      btnApprove.disabled = false;
    });
};

// ==========================================
// ↩️ SUBMIT REWORK FEEDBACK OR REJECT DEFERRAL
// ==========================================
window.submitReworkFeedback = function() {
  if (!currentTrackingReportId) return;
  const reworkInput = document.getElementById('rework-remarks-input');
  const btnConfirmRework = document.getElementById('btn-confirm-rework');

  const remarks = reworkInput ? reworkInput.value.trim() : '';
  if (!remarks) {
    if (typeof showToast === 'function') showToast("Please provide a reason before submitting.", "error");
    return;
  }

  const isRejectingDeferral = currentTrackingReportStatus === 'pending budget' || currentTrackingReportStatus.includes('defer');
  const targetStatus = isRejectingDeferral ? "Dispatched to CEO" : "In Progress";
  const successToast = isRejectingDeferral
    ? "Deferral rejected! Project returned to the CEO active queue."
    : "Project bounced back to CEO with your rework instructions!";

  const originalText = btnConfirmRework ? btnConfirmRework.innerHTML : "Submit";
  if (btnConfirmRework) {
    btnConfirmRework.innerHTML = "⏳ Sending...";
    btnConfirmRework.disabled = true;
  }

  const currentUserId = sessionStorage.getItem("userId");

  fetch(`${API_BASE_URL}/api/reports/${currentTrackingReportId}/status`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'ngrok-skip-browser-warning': 'true'
    },
    body: JSON.stringify({
      status: targetStatus,
      adminRemarks: remarks,
      userId: currentUserId
    })
  })
    .then(res => {
      if (!res.ok) throw new Error("Failed to process feedback");
      return res.text();
    })
    .then(() => {
      if (typeof showToast === 'function') showToast(successToast, "success");
      closeTrackingModal();
      if (typeof loadTrackingData === 'function') loadTrackingData();
      if (typeof loadAdminDashboardData === 'function') loadAdminDashboardData();
    })
    .catch(err => {
      console.error(err);
      if (typeof showToast === 'function') showToast("Error submitting feedback.", "error");
    })
    .finally(() => {
      if (btnConfirmRework) {
        btnConfirmRework.innerHTML = originalText;
        btnConfirmRework.disabled = false;
      }
    });
};

// ==========================================
// 🗺️ ADMIN GLOBAL MAP: MULTIPLE MARKERS
// ==========================================
let adminGlobalMap = null;
let globalMarkerLayer = null;
let adminBoundaryLayer = null;
let adminCityMaskLayer = null;
let adminGeoJsonData = null;

function ensureAdminGeoJsonLoaded() {
  if (typeof sjdmGeoJsonData !== 'undefined' && sjdmGeoJsonData) {
    return Promise.resolve(sjdmGeoJsonData);
  }
  if (adminGeoJsonData) {
    return Promise.resolve(adminGeoJsonData);
  }
  return fetch('sjdm_barangays.geojson')
    .then(res => res.json())
    .then(data => {
      adminGeoJsonData = data;
      return data;
    });
}

window.loadAdminGlobalMap = function() {
  const mapContainer = document.getElementById('admin-global-map');
  if (!mapContainer) return;

  // 1. Initialize Leaflet Map instance
  if (!adminGlobalMap) {
    adminGlobalMap = L.map('admin-global-map', {
      maxZoom: 20,
      maxBoundsViscosity: 1.0 // Rigid boundary wall
    });

    // 🛰️ Google Hybrid Satellite Tiles (subdivisions, streets, and place labels)
    L.tileLayer('https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
      maxZoom: 20,
      subdomains: ['mt0', 'mt1', 'mt2', 'mt3'],
      attribution: '&copy; Google Maps'
    }).addTo(adminGlobalMap);

    globalMarkerLayer = L.layerGroup().addTo(adminGlobalMap);
  }

  // 2. Load Boundaries & City-Level Overlay
  ensureAdminGeoJsonLoaded()
    .then(geoJson => {
      if (adminBoundaryLayer) adminGlobalMap.removeLayer(adminBoundaryLayer);
      if (adminCityMaskLayer) adminGlobalMap.removeLayer(adminCityMaskLayer);

      // Collect all polygon rings across all 62 barangays to mask outside cities
      const innerRings = [];
      geoJson.features.forEach(f => {
        if (f.geometry.type === 'Polygon') {
          innerRings.push(f.geometry.coordinates[0].map(c => [c[1], c[0]]));
        } else if (f.geometry.type === 'MultiPolygon') {
          f.geometry.coordinates.forEach(poly => {
            innerRings.push(poly[0].map(c => [c[1], c[0]]));
          });
        }
      });

      const worldOuter = [
        [90, -180],
        [90, 180],
        [-90, 180],
        [-90, -180]
      ];

      // A. Darken everything outside SJDM city limits
      adminCityMaskLayer = L.polygon([worldOuter, ...innerRings], {
        color: 'transparent',
        fillColor: '#000000',
        fillOpacity: 0.50,
        interactive: false
      }).addTo(adminGlobalMap);

      // B. Render interactive mesh for all 62 barangays
      adminBoundaryLayer = L.geoJSON(geoJson, {
        style: () => ({
          color: '#38bdf8',       // Light sky-blue border lines
          weight: 1.5,
          dashArray: '4, 4',
          fillColor: '#0284c7',
          fillOpacity: 0.05
        }),
        onEachFeature: (feature, layer) => {
          const brgyName = feature.properties.name || feature.properties.adm4_name || 'Barangay';

          // Tooltip on hover
          layer.bindTooltip(`<b>${brgyName}</b>`, {
            sticky: true,
            direction: 'top',
            className: 'admin-brgy-tooltip'
          });

          // Highlight effect on mouse hover
          layer.on({
            mouseover: (e) => {
              const l = e.target;
              l.setStyle({
                weight: 2.5,
                color: '#facc15', // Gold border on hover
                dashArray: '',
                fillOpacity: 0.20
              });
              if (!L.Browser.ie && !L.Browser.opera && !L.Browser.edge) {
                l.bringToFront();
              }
            },
            mouseout: (e) => {
              adminBoundaryLayer.resetStyle(e.target);
            }
          });
        }
      }).addTo(adminGlobalMap);

      // Lock camera to the official SJDM city bounds
      const cityBounds = adminBoundaryLayer.getBounds();
      adminGlobalMap.fitBounds(cityBounds, { padding: [15, 15] });
      adminGlobalMap.setMaxBounds(cityBounds.pad(0.08));
      adminGlobalMap.setMinZoom(adminGlobalMap.getZoom());
    })
    .catch(err => console.error("Error loading SJDM boundaries for Admin map:", err));

  setTimeout(() => { adminGlobalMap.invalidateSize(); }, 300);

  // 3. Fetch all active hazard reports across the city
  apiFetch(`/api/reports`, { cache: 'no-store' })
    .then(reports => {
      globalMarkerLayer.clearLayers();

      // Filter out fixed/archived roads so the map focuses on actionable hazards
      const activeHazards = reports.filter(r => {
        const s = String(r.status || '').toLowerCase();
        return !s.includes('complet') && !s.includes('clos') && !s.includes('reject') && !s.includes('archiv');
      });

      activeHazards.forEach(report => {
        const lat = parseFloat(report.latitude);
        const lng = parseFloat(report.longitude);

        if (isNaN(lat) || isNaN(lng) || (lat === 0 && lng === 0)) return;

        const pinRed = new L.Icon({ iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-red.png', shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png', iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41] });
        const pinOrange = new L.Icon({ iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-orange.png', shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png', iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41] });
        const pinGreen = new L.Icon({ iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-green.png', shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png', iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41] });
        const pinGrey = new L.Icon({ iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-grey.png', shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png', iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41] });

        const severity = String(report.severity || 'Unassessed').toLowerCase();
        let selectedIcon = pinGrey;

        if (severity === 'high') selectedIcon = pinRed;
        else if (severity === 'medium') selectedIcon = pinOrange;
        else if (severity === 'low') selectedIcon = pinGreen;

        const popupHtml = `
          <div style="font-family: sans-serif; min-width: 220px; text-align: center;">
              <h4 style="margin: 0 0 5px 0; color: #1e40af; font-size: 16px;">#RPT-${String(report.id).padStart(4, '0')}</h4>
              <p style="margin: 0 0 5px 0; font-size: 13px;"><b>Road:</b> ${report.cityRoadName || 'Unknown'}</p>
              <p style="margin: 0 0 5px 0; font-size: 13px;"><b>Status:</b> ${report.status || 'Pending'}</p>
              <span style="display: inline-block; padding: 4px 8px; border-radius: 4px; font-size: 11px; font-weight: bold; margin-bottom: 10px; background-color: ${selectedIcon === pinRed ? '#dc3545' : selectedIcon === pinOrange ? '#ff8c00' : selectedIcon === pinGreen ? '#28a745' : '#6c757d'}; color: white;">
                  SEVERITY: ${severity.toUpperCase()}
              </span>

              <button class="btn-small validate-btn" style="width: 100%; margin-top: 5px;" onclick="reviewReport(${report.id})">
                  Review Full Report
              </button>
          </div>
        `;

        L.marker([lat, lng], { icon: selectedIcon })
          .bindPopup(popupHtml)
          .addTo(globalMarkerLayer);
      });
    })
    .catch(err => console.error("Error loading Admin map data:", err));
};

// ==========================================
// 🚀 THE MAP WATCHDOG (Connects to sidebar tab)
// ==========================================
document.addEventListener("DOMContentLoaded", () => {
  const adminMapSection = document.getElementById('view-map');
  if (adminMapSection) {
    const mapObserver = new MutationObserver((mutations) => {
      mutations.forEach((mutation) => {
        if (mutation.attributeName === 'class' && !adminMapSection.classList.contains('hidden')) {
          if (typeof loadAdminGlobalMap === 'function') loadAdminGlobalMap();
        }
      });
    });
    mapObserver.observe(adminMapSection, { attributes: true });
  }
});


// ==========================================
// 🗺️ CEO GLOBAL MAP: DISPATCHED PROJECTS (SAFE & FLUID ZOOM)
// ==========================================
window.ceoGlobalMap = window.ceoGlobalMap || null;
window.ceoGlobalMarkerLayer = window.ceoGlobalMarkerLayer || null;
window.ceoBoundaryLayer = window.ceoBoundaryLayer || null;
window.ceoCityMaskLayer = window.ceoCityMaskLayer || null;
window.ceoGeoJsonData = window.ceoGeoJsonData || null;

function ensureCEOGeoJsonLoaded() {
  if (typeof sjdmGeoJsonData !== 'undefined' && sjdmGeoJsonData) {
    return Promise.resolve(sjdmGeoJsonData);
  }
  if (window.ceoGeoJsonData) {
    return Promise.resolve(window.ceoGeoJsonData);
  }
  return fetch('sjdm_barangays.geojson')
    .then(res => res.json())
    .then(data => {
      window.ceoGeoJsonData = data;
      return data;
    });
}

window.loadCEOGlobalMap = function() {
  const mapContainer = document.getElementById('ceo-global-map');
  if (!mapContainer) return;

  // 1. Initialize Leaflet Map with smooth zoom boundaries
  if (!window.ceoGlobalMap) {
    window.ceoGlobalMap = L.map('ceo-global-map', {
      minZoom: 12,             // 🛑 Stops zooming out past CSJDM scale
      maxZoom: 19,             // 🛑 Stops over-zooming past satellite resolution
      maxBoundsViscosity: 0.6  // 🌊 Soft boundary buffer prevents camera freezes
    });

    // 🛰️ Google Hybrid Satellite Tiles (Road lines, subdivisions, street names, & landmarks)
    L.tileLayer('https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
      maxZoom: 19,
      subdomains: ['mt0', 'mt1', 'mt2', 'mt3'],
      attribution: '&copy; Google Maps'
    }).addTo(window.ceoGlobalMap);

    window.ceoGlobalMarkerLayer = L.layerGroup().addTo(window.ceoGlobalMap);
  }

  // 2. Load Boundaries & City-Level Overlay
  ensureCEOGeoJsonLoaded()
    .then(geoJson => {
      if (window.ceoBoundaryLayer) window.ceoGlobalMap.removeLayer(window.ceoBoundaryLayer);
      if (window.ceoCityMaskLayer) window.ceoGlobalMap.removeLayer(window.ceoCityMaskLayer);

      // Collect all polygon rings across all barangays to mask outside CSJDM
      const innerRings = [];
      geoJson.features.forEach(f => {
        if (f.geometry.type === 'Polygon') {
          innerRings.push(f.geometry.coordinates[0].map(c => [c[1], c[0]]));
        } else if (f.geometry.type === 'MultiPolygon') {
          f.geometry.coordinates.forEach(poly => {
            innerRings.push(poly[0].map(c => [c[1], c[0]]));
          });
        }
      });

      const worldOuter = [
        [90, -180],
        [90, 180],
        [-90, 180],
        [-90, -180]
      ];

      // A. Darken areas outside SJDM city limits
      window.ceoCityMaskLayer = L.polygon([worldOuter, ...innerRings], {
        color: 'transparent',
        fillColor: '#000000',
        fillOpacity: 0.50,
        interactive: false
      }).addTo(window.ceoGlobalMap);

      // B. Render interactive barangay borders
      window.ceoBoundaryLayer = L.geoJSON(geoJson, {
        style: () => ({
          color: '#38bdf8',       // Light sky-blue border
          weight: 1.5,
          dashArray: '4, 4',
          fillColor: '#0284c7',
          fillOpacity: 0.05
        }),
        onEachFeature: (feature, layer) => {
          const brgyName = feature.properties.name || feature.properties.adm4_name || 'Barangay';

          layer.bindTooltip(`<b>${brgyName}</b>`, {
            sticky: true,
            direction: 'top',
            className: 'ceo-brgy-tooltip'
          });

          layer.on({
            mouseover: (e) => {
              const l = e.target;
              l.setStyle({
                weight: 2.5,
                color: '#facc15', // Gold hover border
                dashArray: '',
                fillOpacity: 0.20
              });
              if (!L.Browser.ie && !L.Browser.opera && !L.Browser.edge) {
                l.bringToFront();
              }
            },
            mouseout: (e) => {
              window.ceoBoundaryLayer.resetStyle(e.target);
            }
          });
        }
      }).addTo(window.ceoGlobalMap);

      // 💡 CAMERA FIT & SMOOTH BOUNDS (No more zoom-out lockup)
      const cityBounds = window.ceoBoundaryLayer.getBounds();

      window.ceoGlobalMap.setMinZoom(12);
      window.ceoGlobalMap.setMaxBounds(cityBounds.pad(0.40)); // 40% margin allows full zoom-out without clipping
      window.ceoGlobalMap.fitBounds(cityBounds, { padding: [25, 25], maxZoom: 15 });
    })
    .catch(err => console.error("Error loading SJDM boundaries for CEO map:", err));

  setTimeout(() => {
    if (window.ceoGlobalMap) {
      window.ceoGlobalMap.invalidateSize();
      if (window.ceoBoundaryLayer) {
        window.ceoGlobalMap.fitBounds(window.ceoBoundaryLayer.getBounds(), { padding: [25, 25] });
      }
    }
  }, 300);

  // 3. Fetch all active projects dispatched to CEO
  apiFetch(`/api/reports`, { cache: 'no-store' })
    .then(reports => {
      window.ceoGlobalMarkerLayer.clearLayers();

      const activeCEOProjects = reports.filter(r => {
        const s = String(r.status || '').toLowerCase();
        return s === 'dispatched to ceo' || s === 'in progress';
      });

      const pinRed = new L.Icon({ iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-red.png', shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png', iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41] });
      const pinOrange = new L.Icon({ iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-orange.png', shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png', iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41] });
      const pinGreen = new L.Icon({ iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-green.png', shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png', iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41] });
      const pinGrey = new L.Icon({ iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-grey.png', shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png', iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41] });

      activeCEOProjects.forEach(report => {
        const lat = parseFloat(report.latitude);
        const lng = parseFloat(report.longitude);

        if (isNaN(lat) || isNaN(lng) || (lat === 0 && lng === 0)) return;

        const severity = String(report.severity || 'Unassessed').toLowerCase();
        let selectedIcon = pinGrey;

        if (severity === 'high') selectedIcon = pinRed;
        else if (severity === 'medium') selectedIcon = pinOrange;
        else if (severity === 'low') selectedIcon = pinGreen;

        const popupHtml = `
          <div style="font-family: sans-serif; min-width: 220px; text-align: center;">
              <h4 style="margin: 0 0 5px 0; color: #1e40af; font-size: 16px;">#PRJ-${String(report.id).padStart(4, '0')}</h4>
              <p style="margin: 0 0 5px 0; font-size: 13px;"><b>Road:</b> ${report.cityRoadName || 'Unknown'}</p>
              <p style="margin: 0 0 5px 0; font-size: 13px;"><b>Status:</b> ${report.status || 'Pending'}</p>
              <span style="display: inline-block; padding: 4px 8px; border-radius: 4px; font-size: 11px; font-weight: bold; margin-bottom: 10px; background-color: ${selectedIcon === pinRed ? '#dc3545' : selectedIcon === pinOrange ? '#ff8c00' : selectedIcon === pinGreen ? '#28a745' : '#6c757d'}; color: white;">
                  SEVERITY: ${severity.toUpperCase()}
              </span>

              <button class="btn-small validate-btn" style="width: 100%; margin-top: 5px; background-color: #1e40af; border-color: #1e40af;" onclick="openCEOManageModal(${report.id})">
                  Manage Project
              </button>
          </div>
        `;

        L.marker([lat, lng], { icon: selectedIcon })
          .bindPopup(popupHtml)
          .addTo(window.ceoGlobalMarkerLayer);
      });
    })
    .catch(err => console.error("Error loading CEO map data:", err));
};

// ==========================================
// 🚀 THE CEO MAP WATCHDOG
// ==========================================
document.addEventListener("DOMContentLoaded", () => {
  const ceoMapSection = document.getElementById('view-ceo-map');
  if (ceoMapSection) {
    const ceoMapObserver = new MutationObserver((mutations) => {
      mutations.forEach((mutation) => {
        if (mutation.attributeName === 'class' && !ceoMapSection.classList.contains('hidden')) {
          if (typeof loadCEOGlobalMap === 'function') loadCEOGlobalMap();
        }
      });
    });
    ceoMapObserver.observe(ceoMapSection, { attributes: true });
  }
});


// ==========================================
// 🗺️ BARANGAY LOCAL MAP: TERRITORY FILTERED
// ==========================================
let barangayLocalMap = null;
let barangayMarkerLayer = null;
let localBoundaryLayer = null;
let localMaskLayer = null;
let localGeoJsonData = null;

// Pre-fetch SJDM boundaries if not already loaded globally
function ensureGeoJsonLoaded() {
  if (typeof sjdmGeoJsonData !== 'undefined' && sjdmGeoJsonData) {
    return Promise.resolve(sjdmGeoJsonData);
  }
  if (localGeoJsonData) {
    return Promise.resolve(localGeoJsonData);
  }
  return fetch('sjdm_barangays.geojson')
    .then(res => res.json())
    .then(data => {
      localGeoJsonData = data;
      return data;
    });
}

// Normalizer between database names and GeoJSON attributes
function normalizeBrgyName(str) {
  return (str || "")
    .toLowerCase()
    .replace(/[\u2013\u2014\u2212-]/g, "-")        // Harmonize en-dash and hyphens
    .replace(/^sto\.\s*|^santo\s*/, "santo ")      // Harmonize Sto. and Santo
    .replace(/^sta\.\s*|^santa\s*/, "santa ")      // Harmonize Sta. and Santa
    .replace(/\s+/g, " ")                          // Trim excess whitespace
    .trim();
}

window.loadBarangayLocalMap = function() {
  const mapContainer = document.getElementById('barangay-local-map');
  if (!mapContainer) return;

  // 🔒 Security checks
  const loggedInBarangayId = sessionStorage.getItem("barangayId");
  const loggedInBarangayName = sessionStorage.getItem("barangayName") || "Kaypian";

  if (!loggedInBarangayId) {
    console.error("Cannot load map: No Barangay ID found in session.");
    return;
  }

  // 1. Initialize Map instance if not created
  if (!barangayLocalMap) {
    barangayLocalMap = L.map('barangay-local-map', {
      maxZoom: 20,
      maxBoundsViscosity: 1.0 // Rigid boundary lock
    });

    // 🛰️ Google Hybrid Satellite Tiles
    L.tileLayer('https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
      maxZoom: 20,
      subdomains: ['mt0', 'mt1', 'mt2', 'mt3'],
      attribution: '&copy; Google Maps'
    }).addTo(barangayLocalMap);

    barangayMarkerLayer = L.layerGroup().addTo(barangayLocalMap);
  }

  // 2. Load GeoJSON, spotlight the territory, and lock camera
  ensureGeoJsonLoaded()
    .then(geoJson => {
      if (localBoundaryLayer) barangayLocalMap.removeLayer(localBoundaryLayer);
      if (localMaskLayer) barangayLocalMap.removeLayer(localMaskLayer);

      const targetClean = normalizeBrgyName(loggedInBarangayName);

      const feature = geoJson.features.find(f => {
        const geoNameClean = normalizeBrgyName(f.properties.name || f.properties.adm4_name || "");
        if (geoNameClean === targetClean) return true;
        if (targetClean === "sapang palay proper" && geoNameClean === "sapang palay") return true;
        if (targetClean === "sapang palay" && geoNameClean === "sapang palay proper") return true;
        return false;
      }) || geoJson.features.find(f => normalizeBrgyName(f.properties.name) === "kaypian") || geoJson.features[0];

      if (feature) {
        const coords = feature.geometry.type === 'MultiPolygon'
          ? feature.geometry.coordinates[0][0]
          : feature.geometry.coordinates[0];

        // Inverted mask covering everything outside the territory
        const worldOuter = [
          [90, -180],
          [90, 180],
          [-90, 180],
          [-90, -180]
        ];
        const innerRing = coords.map(c => [c[1], c[0]]);

        localMaskLayer = L.polygon([worldOuter, innerRing], {
          color: 'transparent',
          fillColor: '#000000',
          fillOpacity: 0.60,
          interactive: false
        }).addTo(barangayLocalMap);

        // Official red dashed boundary
        localBoundaryLayer = L.geoJSON(feature, {
          style: {
            color: '#ef4444',
            weight: 3,
            dashArray: '6, 6',
            fillOpacity: 0
          },
          interactive: false
        }).addTo(barangayLocalMap);

        // Clamp camera strictly to this barangay
        const bounds = localBoundaryLayer.getBounds();
        barangayLocalMap.fitBounds(bounds, { padding: [25, 25] });
        barangayLocalMap.setMaxBounds(bounds.pad(0.06));
        barangayLocalMap.setMinZoom(barangayLocalMap.getZoom());
      }
    })
    .catch(err => console.error("Error loading territory boundaries:", err));

  setTimeout(() => { barangayLocalMap.invalidateSize(); }, 300);

  // 3. Fetch reports strictly belonging to this Barangay
  apiFetch(`/api/reports/barangay/${loggedInBarangayId}`, { cache: 'no-store' })
    .then(reports => {
      barangayMarkerLayer.clearLayers();

      // Filter out finished projects to keep the map focused on active hazards
      const activeLocalHazards = reports.filter(r => {
        const s = String(r.status || '').toLowerCase();
        return !s.includes('complet') && !s.includes('clos') && !s.includes('archiv');
      });

      activeLocalHazards.forEach(report => {
        const lat = parseFloat(report.latitude);
        const lng = parseFloat(report.longitude);

        if (isNaN(lat) || isNaN(lng) || (lat === 0 && lng === 0)) return;

        const pinRed = new L.Icon({ iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-red.png', shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png', iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41] });
        const pinOrange = new L.Icon({ iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-orange.png', shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png', iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41] });
        const pinGreen = new L.Icon({ iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-green.png', shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png', iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41] });
        const pinGrey = new L.Icon({ iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-grey.png', shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png', iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41] });

        const severity = String(report.severity || 'Unassessed').toLowerCase();
        const status = String(report.status || '').toLowerCase();
        let selectedIcon = pinGrey;

        if (severity === 'high') selectedIcon = pinRed;
        else if (severity === 'medium') selectedIcon = pinOrange;
        else if (severity === 'low') selectedIcon = pinGreen;

        let buttonHtml = `<button class="btn-small validate-btn" style="width: 100%; margin-top: 5px; background-color: #6c757d; border-color: #6c757d;" onclick="openViewModal(${report.id})">View Status</button>`;

        if (status.includes('reject')) {
          buttonHtml = `<button class="btn-small validate-btn" style="width: 100%; margin-top: 5px; background-color: #dc3545; border-color: #dc3545;" onclick="openEditModal(${report.id})">Edit & Resubmit</button>`;
        }

        const popupHtml = `
          <div style="font-family: sans-serif; min-width: 220px; text-align: center;">
              <h4 style="margin: 0 0 5px 0; color: #1e40af; font-size: 16px;">#RPT-${String(report.id).padStart(4, '0')}</h4>
              <p style="margin: 0 0 5px 0; font-size: 13px;"><b>Road:</b> ${report.cityRoadName || 'Unknown'}</p>
              <p style="margin: 0 0 5px 0; font-size: 13px;"><b>Status:</b> ${report.status || 'Pending'}</p>
              <span style="display: inline-block; padding: 4px 8px; border-radius: 4px; font-size: 11px; font-weight: bold; margin-bottom: 10px; background-color: ${selectedIcon === pinRed ? '#dc3545' : selectedIcon === pinOrange ? '#ff8c00' : selectedIcon === pinGreen ? '#28a745' : '#6c757d'}; color: white;">
                  SEVERITY: ${severity.toUpperCase()}
              </span>
              ${buttonHtml}
          </div>
        `;

        L.marker([lat, lng], { icon: selectedIcon })
          .bindPopup(popupHtml)
          .addTo(barangayMarkerLayer);
      });
    })
    .catch(err => console.error("Error loading local map data:", err));
};

// ==========================================
// 🚀 THE BARANGAY MAP WATCHDOG
// ==========================================
document.addEventListener("DOMContentLoaded", () => {
  const brgyMapSection = document.getElementById('view-barangay-map');
  if (brgyMapSection) {
    const brgyMapObserver = new MutationObserver((mutations) => {
      mutations.forEach((mutation) => {
        if (mutation.attributeName === 'class' && !brgyMapSection.classList.contains('hidden')) {
          if (typeof loadBarangayLocalMap === 'function') loadBarangayLocalMap();
        }
      });
    });
    brgyMapObserver.observe(brgyMapSection, { attributes: true });
  }
});

// ==========================================
// FEATURE STARTUP (composition)
// ==========================================
document.addEventListener('DOMContentLoaded', () => {
  initNotifications();
  initImageLightbox();
  initLiveClock();
  initAuth();
  initSecureImage();
  initActivityLog();
  initProfileAccount();

  // Compatibility bridge: the report-generation feature calls this tracking
  // loader after a batch archive, which stays in app.js with the tracking
  // engine (same-scope bare call before extraction).
  window.loadTrackingData = loadTrackingData;
});

