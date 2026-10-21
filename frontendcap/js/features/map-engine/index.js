/**
 * Map engine feature module: handles Leaflet maps, custom pins, boundary isolation spotlight,
 * Google-style geocoding search, and role-based global maps (Admin, CEO, Barangay).
 */
import { apiFetch } from '../../core/api.js';
import { showToast } from '../../components/toast.js';

export let map;
export let mapMarker;
export let selectedLat = 14.8139;
export let selectedLng = 121.0453;
export let redIcon;

let sjdmGeoJsonData = null;

// Expose boundary GeoJSON on window for cross-module map caching (CEO & Barangay overlays)
Object.defineProperty(window, 'sjdmGeoJsonData', {
  get() { return sjdmGeoJsonData; },
  set(val) { sjdmGeoJsonData = val; window.ceoGeoJsonData = val; },
  configurable: true,
  enumerable: true
});
window.ceoGeoJsonData = window.ceoGeoJsonData || null;
let currentBoundaryLayer = null;
let currentMaskLayer = null;
let activePolygonCoords = null;

export function isPointInsidePolygon(lat, lng, polyCoords) {
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

export function applySpotlightAndLock(targetBarangay) {
  if (currentBoundaryLayer && map) map.removeLayer(currentBoundaryLayer);
  if (currentMaskLayer && map) map.removeLayer(currentMaskLayer);

  if (!sjdmGeoJsonData || !map) return null;

  function normalizeBrgyName(str) {
    return (str || "")
      .toLowerCase()
      .replace(/[\u2013\u2014\u2212-]/g, "-")
      .replace(/^sto\.\s*|^santo\s*/, "santo ")
      .replace(/^sta\.\s*|^santa\s*/, "santa ")
      .replace(/\s+/g, " ")
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

  const worldOuter = [
    [90, -180],
    [90, 180],
    [-90, 180],
    [-90, -180]
  ];
  const innerRing = coords.map(c => [c[1], c[0]]);
  activePolygonCoords = coords;

  currentMaskLayer = L.polygon([worldOuter, innerRing], {
    color: 'transparent',
    fillColor: '#000000',
    fillOpacity: 0.65,
    interactive: false
  }).addTo(map);

  currentBoundaryLayer = L.geoJSON(feature, {
    style: {
      color: '#ef4444',
      weight: 3,
      dashArray: '6, 6',
      fillOpacity: 0
    },
    interactive: false
  }).addTo(map);

  const bounds = currentBoundaryLayer.getBounds();
  map.setMinZoom(12);
  map.setMaxBounds(bounds.pad(0.8));
  map.fitBounds(bounds, { padding: [40, 40], maxZoom: 16 });

  return bounds;
}

// Admin Global Map
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

// ==========================================
// SHARED GLOBAL-MAP MARKER RENDERER
// ==========================================
// Severity filter + lightweight zoom-aware grid clustering. Clusters collapse
// nearby reports into one numbered circle marker at low zoom and split apart as
// the user zooms in — no external clustering plugin needed. Filtering keeps the
// same renderer path, so filters and clustering stay in sync.
let adminMapReports = [];
let ceoMapReports = [];
let adminMapSeverityFilter = 'all';
let ceoMapSeverityFilter = 'all';

const HAZARD_ICONS = { red: null, orange: null, green: null, grey: null };
function ensureHazardIcons() {
  if (HAZARD_ICONS.red) return;
  const makePin = (color) => new L.Icon({
    iconUrl: `https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-${color}.png`,
    shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
    iconSize: [25, 41], iconAnchor: [12, 41], popupAnchor: [1, -34], shadowSize: [41, 41]
  });
  HAZARD_ICONS.red = makePin('red');
  HAZARD_ICONS.orange = makePin('orange');
  HAZARD_ICONS.green = makePin('green');
  HAZARD_ICONS.grey = makePin('grey');
}

function iconForSeverity(sev) {
  ensureHazardIcons();
  const s = String(sev || '').toLowerCase();
  if (s === 'high') return HAZARD_ICONS.red;
  if (s === 'medium') return HAZARD_ICONS.orange;
  if (s === 'low') return HAZARD_ICONS.green;
  return HAZARD_ICONS.grey;
}

function severityTier(sev) {
  const s = String(sev || '').toLowerCase();
  if (s === 'high' || s === 'medium' || s === 'low') return s;
  return 'unassessed';
}

function passesSeverityFilter(report, filterValue) {
  if (!filterValue || filterValue === 'all') return true;
  return severityTier(report.severity) === filterValue;
}

function isUsableCoordinate(report) {
  const lat = parseFloat(report.latitude);
  const lng = parseFloat(report.longitude);
  return !isNaN(lat) && !isNaN(lng) && !(lat === 0 && lng === 0);
}

function escapeHtmlProp(value) {
  return String(value == null ? '' : value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function popupHtmlForReport(report, openFn) {
  const sev = severityTier(report.severity);
  const bg = sev === 'high' ? '#dc3545' : sev === 'medium' ? '#ff8c00' : sev === 'low' ? '#28a745' : '#6c757d';
  return `
    <div style="font-family: sans-serif; min-width: 220px; text-align: center;">
      <h4 style="margin: 0 0 5px 0; color: #1e40af; font-size: 16px;">#RPT-${String(report.id).padStart(4, '0')}</h4>
      <p style="margin: 0 0 5px 0; font-size: 13px;"><b>Road:</b> ${escapeHtmlProp(report.cityRoadName) || 'Unknown'}</p>
      <p style="margin: 0 0 5px 0; font-size: 13px;"><b>Status:</b> ${escapeHtmlProp(report.status) || 'Pending'}</p>
      <span style="display: inline-block; padding: 4px 8px; border-radius: 4px; font-size: 11px; font-weight: bold; margin-bottom: 10px; background-color: ${bg}; color: white;">
        SEVERITY: ${sev.toUpperCase()}
      </span>
      <button type="button" class="btn-small validate-btn" style="width: 100%; margin-top: 5px;" onclick="${openFn}(${report.id})">Open Report</button>
    </div>`;
}

function clusterCellSize(zoom) {
  return Math.max(0.0008, 0.004 / Math.pow(2, Math.max(0, zoom - 12)));
}

function debounceMap(fn, ms) {
  let timer = null;
  return function(...args) {
    clearTimeout(timer);
    timer = setTimeout(() => fn.apply(this, args), ms);
  };
}

function renderClusteredMarkers({ reports, layer, map, statusEl, openFn, zoomTarget }) {
  layer.clearLayers();
  const valid = reports.filter(isUsableCoordinate);

  if (statusEl) {
    statusEl.textContent = valid.length === 0
      ? 'No active hazards to show.'
      : `${valid.length} active hazard${valid.length === 1 ? '' : 's'} on this view.`;
  }
  if (valid.length === 0) return;

  const zoom = map.getZoom();
  const cell = clusterCellSize(zoom);
  const buckets = new Map();

  for (const r of valid) {
    const key = `${Math.round(parseFloat(r.latitude) / cell)},${Math.round(parseFloat(r.longitude) / cell)}`;
    if (!buckets.has(key)) buckets.set(key, []);
    buckets.get(key).push(r);
  }

  for (const group of buckets.values()) {
    const latAvg = group.reduce((sum, r) => sum + parseFloat(r.latitude), 0) / group.length;
    const lngAvg = group.reduce((sum, r) => sum + parseFloat(r.longitude), 0) / group.length;

    if (group.length === 1) {
      const report = group[0];
      L.marker([latAvg, lngAvg], { icon: iconForSeverity(report.severity) })
        .bindPopup(popupHtmlForReport(report, openFn))
        .addTo(layer);
      continue;
    }

    const radius = Math.min(20, 9 + Math.sqrt(group.length) * 3.2);
    L.circleMarker([latAvg, lngAvg], {
      radius,
      color: '#ffffff',
      weight: 2,
      fillColor: '#0284c7',
      fillOpacity: 0.85
    })
      .bindPopup(`
        <div style="font-family: sans-serif; min-width: 200px; text-align: center;">
          <strong style="font-size: 16px; color:#0f172a;">${group.length} hazards here</strong>
          <p style="font-size: 11.5px; color: #64748b; margin: 4px 0 8px;">Zoom in or click to expand.</p>
          <button type="button" class="btn-small validate-btn" style="width: 100%; background:#0284c7; border-color:#0284c7;"
            onclick="mapJumpTo(${latAvg}, ${lngAvg})">Focus area</button>
        </div>`)
      .on('click', () => {
        map.setView([latAvg, lngAvg], Math.min(map.getZoom() + 1, (zoomTarget || 18)));
      })
      .addTo(layer);
  }
}

window.mapJumpTo = function(lat, lng) {
  const m = window.adminGlobalMap || window.ceoGlobalMap || null;
  if (m) m.setView([lat, lng], Math.min(Math.max(m.getZoom() + 1, 15), 19));
};

export function loadAdminGlobalMap() {
  const mapContainer = document.getElementById('admin-global-map');
  if (!mapContainer) return;

  if (!adminGlobalMap) {
    adminGlobalMap = L.map('admin-global-map', {
      maxZoom: 20,
      maxBoundsViscosity: 1.0
    });

    L.tileLayer('https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
      maxZoom: 20,
      subdomains: ['mt0', 'mt1', 'mt2', 'mt3'],
      attribution: '&copy; Google Maps'
    }).addTo(adminGlobalMap);

    globalMarkerLayer = L.layerGroup().addTo(adminGlobalMap);
  }

  ensureAdminGeoJsonLoaded()
    .then(geoJson => {
      if (adminBoundaryLayer) adminGlobalMap.removeLayer(adminBoundaryLayer);
      if (adminCityMaskLayer) adminGlobalMap.removeLayer(adminCityMaskLayer);

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

      adminCityMaskLayer = L.polygon([worldOuter, ...innerRings], {
        color: 'transparent',
        fillColor: '#000000',
        fillOpacity: 0.50,
        interactive: false
      }).addTo(adminGlobalMap);

      adminBoundaryLayer = L.geoJSON(geoJson, {
        style: () => ({
          color: '#38bdf8',
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
            className: 'admin-brgy-tooltip'
          });

          layer.on({
            mouseover: (e) => {
              const l = e.target;
              l.setStyle({
                weight: 2.5,
                color: '#facc15',
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

      const cityBounds = adminBoundaryLayer.getBounds();
      adminGlobalMap.fitBounds(cityBounds, { padding: [15, 15] });
      adminGlobalMap.setMaxBounds(cityBounds.pad(0.08));
      adminGlobalMap.setMinZoom(adminGlobalMap.getZoom());
    })
    .catch(err => console.error("Error loading SJDM boundaries for Admin map:", err));

  setTimeout(() => { adminGlobalMap.invalidateSize(); }, 300);

  window.adminGlobalMap = adminGlobalMap;

  apiFetch(`/api/reports`, { cache: 'no-store' })
    .then(reports => {
      const activeHazards = reports.filter(r => {
        const s = String(r.status || '').toLowerCase();
        return !s.includes('complet') && !s.includes('clos') && !s.includes('reject') && !s.includes('archiv');
      });
      adminMapReports = activeHazards;
      const statusEl = document.getElementById('admin-map-status');
      const filterSelect = document.getElementById('admin-map-severity-filter');

      const renderAdminMarkers = () => renderClusteredMarkers({
        reports: adminMapReports.filter(r => passesSeverityFilter(r, adminMapSeverityFilter)),
        layer: globalMarkerLayer,
        map: adminGlobalMap,
        statusEl,
        openFn: 'reviewReport',
        zoomTarget: 18
      });

      if (filterSelect && !filterSelect.dataset.bound) {
        filterSelect.dataset.bound = '1';
        filterSelect.addEventListener('change', (e) => {
          adminMapSeverityFilter = e.target.value;
          renderAdminMarkers();
        });
      }

      if (!adminGlobalMap._clusterBound) {
        adminGlobalMap._clusterBound = true;
        adminGlobalMap.on('zoomend', debounceMap(renderAdminMarkers, 120));
      }

      renderAdminMarkers();
    })
    .catch(err => console.error("Error loading Admin map data:", err));
}

// CEO Global Map
let ceoGlobalMap = null;
let ceoGlobalMarkerLayer = null;
let ceoBoundaryLayer = null;
let ceoCityMaskLayer = null;
let ceoGeoJsonData = null;

function ensureCEOGeoJsonLoaded() {
  if (typeof sjdmGeoJsonData !== 'undefined' && sjdmGeoJsonData) {
    return Promise.resolve(sjdmGeoJsonData);
  }
  if (ceoGeoJsonData) {
    return Promise.resolve(ceoGeoJsonData);
  }
  return fetch('sjdm_barangays.geojson')
    .then(res => res.json())
    .then(data => {
      ceoGeoJsonData = data;
      return data;
    });
}

export function loadCEOGlobalMap() {
  const mapContainer = document.getElementById('ceo-global-map');
  if (!mapContainer) return;

  if (!ceoGlobalMap) {
    ceoGlobalMap = L.map('ceo-global-map', {
      minZoom: 12,
      maxZoom: 19,
      maxBoundsViscosity: 0.6
    });

    L.tileLayer('https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
      maxZoom: 19,
      subdomains: ['mt0', 'mt1', 'mt2', 'mt3'],
      attribution: '&copy; Google Maps'
    }).addTo(ceoGlobalMap);

    ceoGlobalMarkerLayer = L.layerGroup().addTo(ceoGlobalMap);
  }

  ensureCEOGeoJsonLoaded()
    .then(geoJson => {
      if (ceoBoundaryLayer) ceoGlobalMap.removeLayer(ceoBoundaryLayer);
      if (ceoCityMaskLayer) ceoGlobalMap.removeLayer(ceoCityMaskLayer);

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

      ceoCityMaskLayer = L.polygon([worldOuter, ...innerRings], {
        color: 'transparent',
        fillColor: '#000000',
        fillOpacity: 0.50,
        interactive: false
      }).addTo(ceoGlobalMap);

      ceoBoundaryLayer = L.geoJSON(geoJson, {
        style: () => ({
          color: '#38bdf8',
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
                color: '#facc15',
                dashArray: '',
                fillOpacity: 0.20
              });
              if (!L.Browser.ie && !L.Browser.opera && !L.Browser.edge) {
                l.bringToFront();
              }
            },
            mouseout: (e) => {
              ceoBoundaryLayer.resetStyle(e.target);
            }
          });
        }
      }).addTo(ceoGlobalMap);

      const cityBounds = ceoBoundaryLayer.getBounds();
      ceoGlobalMap.setMinZoom(12);
      ceoGlobalMap.setMaxBounds(cityBounds.pad(0.40));
      ceoGlobalMap.fitBounds(cityBounds, { padding: [25, 25], maxZoom: 15 });
    })
    .catch(err => console.error("Error loading SJDM boundaries for CEO map:", err));

  setTimeout(() => {
    if (ceoGlobalMap) {
      ceoGlobalMap.invalidateSize();
      if (ceoBoundaryLayer) {
        ceoGlobalMap.fitBounds(ceoBoundaryLayer.getBounds(), { padding: [25, 25] });
      }
    }
  }, 300);

  window.ceoGlobalMap = ceoGlobalMap;

  apiFetch(`/api/reports`, { cache: 'no-store' })
    .then(reports => {
      const activeCEOProjects = reports.filter(r => {
        const s = String(r.status || '').toLowerCase();
        return s === 'dispatched to ceo' || s === 'in progress';
      });
      ceoMapReports = activeCEOProjects;
      const statusEl = document.getElementById('ceo-map-status');
      const filterSelect = document.getElementById('ceo-map-severity-filter');

      const renderCeoMarkers = () => renderClusteredMarkers({
        reports: ceoMapReports.filter(r => passesSeverityFilter(r, ceoMapSeverityFilter)),
        layer: ceoGlobalMarkerLayer,
        map: ceoGlobalMap,
        statusEl,
        openFn: 'openCEOManageModal',
        zoomTarget: 18
      });

      if (filterSelect && !filterSelect.dataset.bound) {
        filterSelect.dataset.bound = '1';
        filterSelect.addEventListener('change', (e) => {
          ceoMapSeverityFilter = e.target.value;
          renderCeoMarkers();
        });
      }

      if (!ceoGlobalMap._clusterBound) {
        ceoGlobalMap._clusterBound = true;
        ceoGlobalMap.on('zoomend', debounceMap(renderCeoMarkers, 120));
      }

      renderCeoMarkers();
    })
    .catch(err => console.error("Error loading CEO map data:", err));
}

// Barangay Local Map
let barangayLocalMap = null;
let barangayMarkerLayer = null;
let localBoundaryLayer = null;
let localMaskLayer = null;
let localGeoJsonData = null;

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

function normalizeBrgyName(str) {
  return (str || "")
    .toLowerCase()
    .replace(/[\u2013\u2014\u2212-]/g, "-")
    .replace(/^sto\.\s*|^santo\s*/, "santo ")
    .replace(/^sta\.\s*|^santa\s*/, "santa ")
    .replace(/\s+/g, " ")
    .trim();
}

export function loadBarangayLocalMap() {
  const mapContainer = document.getElementById('barangay-local-map');
  if (!mapContainer) return;

  const loggedInBarangayId = sessionStorage.getItem("barangayId");
  const loggedInBarangayName = sessionStorage.getItem("barangayName") || "Kaypian";

  if (!loggedInBarangayId) {
    console.error("Cannot load map: No Barangay ID found in session.");
    return;
  }

  if (!barangayLocalMap) {
    barangayLocalMap = L.map('barangay-local-map', {
      maxZoom: 20,
      maxBoundsViscosity: 1.0
    });

    L.tileLayer('https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
      maxZoom: 20,
      subdomains: ['mt0', 'mt1', 'mt2', 'mt3'],
      attribution: '&copy; Google Maps'
    }).addTo(barangayLocalMap);

    barangayMarkerLayer = L.layerGroup().addTo(barangayLocalMap);
  }

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

        localBoundaryLayer = L.geoJSON(feature, {
          style: {
            color: '#ef4444',
            weight: 3,
            dashArray: '6, 6',
            fillOpacity: 0
          },
          interactive: false
        }).addTo(barangayLocalMap);

        const bounds = localBoundaryLayer.getBounds();
        barangayLocalMap.fitBounds(bounds, { padding: [25, 25] });
        barangayLocalMap.setMaxBounds(bounds.pad(0.06));
        barangayLocalMap.setMinZoom(barangayLocalMap.getZoom());
      }
    })
    .catch(err => console.error("Error loading territory boundaries:", err));

  setTimeout(() => { barangayLocalMap.invalidateSize(); }, 300);

  apiFetch(`/api/reports/barangay/${loggedInBarangayId}`, { cache: 'no-store' })
    .then(reports => {
      barangayMarkerLayer.clearLayers();

      const activeLocalHazards = reports.filter(r => {
        const s = String(r.status || '').toLowerCase();
        return !s.includes('complet') && !s.includes('clos') && !s.includes('archiv');
      });

      activeLocalHazards.forEach(report => {
        const lat = parseFloat(report.latitude);
        const lng = parseFloat(report.longitude);

        if (isNaN(lat) || isNaN(lng) || (lat === 0 && lng === 0)) return;

        const severity = String(report.severity || 'Unassessed').toLowerCase();
        const status = String(report.status || '').toLowerCase();
        const selectedIcon = iconForSeverity(severity);

        let buttonHtml = `<button class="btn-small validate-btn" style="width: 100%; margin-top: 5px; background-color: #6c757d; border-color: #6c757d;" onclick="openViewModal(${report.id})">View Status</button>`;

        if (status.includes('reject')) {
          buttonHtml = `<button class="btn-small validate-btn" style="width: 100%; margin-top: 5px; background-color: #dc3545; border-color: #dc3545;" onclick="openEditModal(${report.id})">Edit & Resubmit</button>`;
        }

        const popupHtml = `
          <div style="font-family: sans-serif; min-width: 220px; text-align: center;">
              <h4 style="margin: 0 0 5px 0; color: #1e40af; font-size: 16px;">#RPT-${String(report.id).padStart(4, '0')}</h4>
              <p style="margin: 0 0 5px 0; font-size: 13px;"><b>Road:</b> ${report.cityRoadName || 'Unknown'}</p>
              <p style="margin: 0 0 5px 0; font-size: 13px;"><b>Status:</b> ${report.status || 'Pending'}</p>
              <span style="display: inline-block; padding: 4px 8px; border-radius: 4px; font-size: 11px; font-weight: bold; margin-bottom: 10px; background-color: ${severityTier(report.severity) === 'high' ? '#dc3545' : severityTier(report.severity) === 'medium' ? '#ff8c00' : severityTier(report.severity) === 'low' ? '#28a745' : '#6c757d'}; color: white;">
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
}

document.addEventListener('DOMContentLoaded', () => {
  if (typeof L !== 'undefined') {
    redIcon = new L.Icon({
      iconUrl: 'https://raw.githubusercontent.com/pointhi/leaflet-color-markers/master/img/marker-icon-2x-red.png',
      shadowUrl: 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/images/marker-shadow.png',
      iconSize: [25, 41],
      iconAnchor: [12, 41],
      popupAnchor: [1, -34],
      shadowSize: [41, 41]
    });
    window.redIcon = redIcon;

    const mapModal = document.getElementById('map-modal');
    const btnCloseMap = document.getElementById('close-map-btn');

    fetch('sjdm_barangays.geojson')
      .then(res => res.json())
      .then(data => {
        sjdmGeoJsonData = data;
        window.ceoGeoJsonData = data;
      })
      .catch(err => console.error("Could not load sjdm_barangays.geojson:", err));

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
      searchInput.addEventListener('input', debounce(function() {
        const query = this.value.trim();

        if (query.length < 2) {
          suggestionsList.classList.add('hidden');
          suggestionsList.innerHTML = '';
          if (clearSearchBtn) clearSearchBtn.classList.add('hidden');
          return;
        }

        if (clearSearchBtn) clearSearchBtn.classList.remove('hidden');

        if (!currentBoundaryLayer) return;
        const bounds = currentBoundaryLayer.getBounds();
        const sw = bounds.getSouthWest();
        const ne = bounds.getNorthEast();

        const url = `https://nominatim.openstreetmap.org/search?format=json&q=${encodeURIComponent(query)}&countrycodes=ph&viewbox=${sw.lng},${ne.lat},${ne.lng},${sw.lat}&bounded=1&limit=6&addressdetails=1`;

        fetch(url)
          .then(res => res.json())
          .then(results => {
            suggestionsList.innerHTML = '';

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

      if (clearSearchBtn) {
        clearSearchBtn.addEventListener('click', () => {
          resetSearchUI();
          searchInput.focus();
        });
      }

      document.addEventListener('click', (e) => {
        if (!searchInput.contains(e.target) && !suggestionsList.contains(e.target)) {
          suggestionsList.classList.add('hidden');
        }
      });
    }

    const btnDefineMap = document.getElementById('btn-define-map');

    if (btnDefineMap && mapModal) {
      btnDefineMap.addEventListener('click', () => {

        const loggedInBarangay = sessionStorage.getItem("barangayName") || "Kaypian";

        mapModal.classList.remove('hidden');
        resetSearchUI();

        if (!map) {
          map = L.map('roadwiseMap', {
            minZoom: 12,
            maxZoom: 19,
            maxBoundsViscosity: 0.5
          });

          L.tileLayer('https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
            maxZoom: 19,
            subdomains: ['mt0', 'mt1', 'mt2', 'mt3'],
            attribution: '&copy; Google Maps'
          }).addTo(map);

          map.on('click', function(e) {
            if (!activePolygonCoords) {
              showToast("Barangay boundary is still loading or unavailable. Please wait...", "warning");
              return;
            }
            if (!isPointInsidePolygon(e.latlng.lat, e.latlng.lng, activePolygonCoords)) {
              showToast(`You cannot drop a pin outside Barangay ${loggedInBarangay}!`, "error");
              return;
            }
            selectedLat = e.latlng.lat;
            selectedLng = e.latlng.lng;
            if (mapMarker) map.removeLayer(mapMarker);
            mapMarker = L.marker([selectedLat, selectedLng], { icon: redIcon }).addTo(map);
          });
        }

        applySpotlightAndLock(loggedInBarangay);

        if (mapMarker) {
          map.removeLayer(mapMarker);
          mapMarker = null;
        }

        const liveSaveBtn = document.getElementById('btn-save-coords');
        const newSaveBtn = liveSaveBtn.cloneNode(true);
        liveSaveBtn.parentNode.replaceChild(newSaveBtn, liveSaveBtn);

        newSaveBtn.addEventListener('click', () => {
          if (!mapMarker) {
            showToast(" Please click on the map or place a pin first!", "warning");
            return;
          }
          document.getElementById('latitude').value = selectedLat;
          document.getElementById('longitude').value = selectedLng;
          document.getElementById('coords-display').textContent = `Locked: ${selectedLat.toFixed(5)}, ${selectedLng.toFixed(5)}`;

          mapModal.classList.add('hidden');
          showToast("Location locked successfully!", "success");
        });

        const liveCenterBtn = document.getElementById('btn-center-pin');
        if (liveCenterBtn) {
          const newCenterBtn = liveCenterBtn.cloneNode(true);
          liveCenterBtn.parentNode.replaceChild(newCenterBtn, liveCenterBtn);

          newCenterBtn.addEventListener('click', () => {
            if (!map) return;
            const center = map.getCenter();
            if (!activePolygonCoords) {
              showToast("Barangay boundary is still loading or unavailable. Please wait...", "warning");
              return;
            }
            if (!isPointInsidePolygon(center.lat, center.lng, activePolygonCoords)) {
              showToast(`Map center is outside Barangay ${loggedInBarangay}! Pan into the boundary first.`, "error");
              return;
            }
            selectedLat = center.lat;
            selectedLng = center.lng;
            if (mapMarker) map.removeLayer(mapMarker);
            mapMarker = L.marker([selectedLat, selectedLng], { icon: redIcon }).addTo(map);
            showToast(`Pin placed at map center (${selectedLat.toFixed(5)}, ${selectedLng.toFixed(5)})`, "info");
          });
        }

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

    const btnEditDefineMap = document.getElementById('btn-edit-define-map');

    if (btnEditDefineMap && mapModal) {
      btnEditDefineMap.addEventListener('click', () => {

        const loggedInBarangay = sessionStorage.getItem("barangayName") || "Kaypian";
        const currentLat = parseFloat(document.getElementById('edit-latitude').value);
        const currentLng = parseFloat(document.getElementById('edit-longitude').value);

        mapModal.classList.remove('hidden');
        resetSearchUI();

        if (!map) {
          map = L.map('roadwiseMap', {
            minZoom: 12,
            maxZoom: 19,
            maxBoundsViscosity: 0.5
          });

          L.tileLayer('https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}', {
            maxZoom: 19,
            subdomains: ['mt0', 'mt1', 'mt2', 'mt3'],
            attribution: '&copy; Google Maps'
          }).addTo(map);

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

        applySpotlightAndLock(loggedInBarangay);

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

        setTimeout(() => {
          map.invalidateSize();
          if (currentBoundaryLayer) {
            map.fitBounds(currentBoundaryLayer.getBounds(), { padding: [40, 40] });
          }
        }, 200);
      });
    }
  }
});

// Window Compatibility Bridges
window.loadAdminGlobalMap = loadAdminGlobalMap;
window.loadCEOGlobalMap = loadCEOGlobalMap;
window.loadBarangayLocalMap = loadBarangayLocalMap;
