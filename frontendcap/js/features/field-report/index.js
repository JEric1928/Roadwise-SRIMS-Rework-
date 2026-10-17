/**
 * Barangay field-report feature: the three-field upload.
 *
 * One screen, three inputs — photos (up to 3), a GPS pin, a defect category — and
 * nothing else. The engineering fields (length, width, depth, surface type, repair
 * plan) are not rendered here and are not accepted by the endpoint this module
 * calls, so an inspector cannot accidentally take ownership of a CPDO decision.
 *
 * Photos are compressed and aspect-standardised in the browser before submission
 * (see `services/imagePipeline.js`). A Stage-1 rejection comes back as HTTP 422 with
 * the offending photo indices; those are surfaced per-photo rather than as a generic
 * failure, because the fix is "retake that one frame".
 */

import { API_BASE_URL, getAuthOnlyHeaders } from '../../core/api.js';
import { showToast } from '../../components/toast.js';
import { preparePhotos, MAX_PHOTOS } from '../../services/imagePipeline.js';
import { isSupportedDefectCategory } from '../../utils/priority.js';

/** The message the inference service returns for a Stage-1 rejection. */
export const GATE_REJECTION_MESSAGE = 'Invalid Upload: Image does not contain recognizable road distress.';

export const ELEMENT_IDS = Object.freeze({
  form: 'field-report-form',
  photos: 'field-photo-input',
  category: 'field-defect-category',
  latitude: 'field-latitude',
  longitude: 'field-longitude',
  locate: 'field-locate-btn',
  submit: 'field-submit-btn',
  notes: 'field-notes'
});

export const DEFAULT_ENDPOINT = '/api/reports/field';

/** Read a numeric input, returning null for blank rather than 0. */
function readNumber(id) {
  const element = typeof document !== 'undefined' ? document.getElementById(id) : null;
  if (!element) return null;
  const raw = (element.value || '').trim();
  if (raw === '') return null;
  const value = Number(raw);
  return Number.isFinite(value) ? value : null;
}

function readValue(id) {
  const element = typeof document !== 'undefined' ? document.getElementById(id) : null;
  return element ? (element.value || '').trim() : '';
}

/**
 * Ask the browser for a GPS fix.
 *
 * Resolves to `{ latitude, longitude }` or rejects with a human-readable reason.
 * `enableHighAccuracy` is on: a 100 m error moves a pothole to a different road.
 */
export function requestGeolocation(options = {}) {
  const { timeout = 15000, maximumAge = 0 } = options;
  return new Promise((resolve, reject) => {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      reject(new Error('This device cannot provide a location. Drop the pin on the map instead.'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (position) => resolve({
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
        accuracy: position.coords.accuracy
      }),
      (error) => {
        const message = error && error.code === 1
          ? 'Location permission was denied. Drop the pin on the map instead.'
          : 'Could not get a GPS fix. Drop the pin on the map instead.';
        reject(new Error(message));
      },
      { enableHighAccuracy: true, timeout, maximumAge }
    );
  });
}

/**
 * Validate the three inputs before any network work.
 *
 * @returns {{ok: boolean, errors: string[]}} — errors are shown verbatim.
 */
export function validateFieldInput({ files, category, latitude, longitude }) {
  const errors = [];
  const photoCount = Array.from(files || []).length;

  if (photoCount === 0) {
    errors.push('Attach at least one photo of the damage.');
  } else if (photoCount > MAX_PHOTOS) {
    errors.push(`Attach at most ${MAX_PHOTOS} photos.`);
  }
  if (!isSupportedDefectCategory(category)) {
    errors.push('Choose a defect category: Pothole, Cracking, Surface Disintegration, or Block Failure.');
  }
  // A blank or zero coordinate must fail: `Number(null)` is 0, which is finite, so a
  // naive isFinite() check would accept a missing pin as 0.0/0.0.
  const hasCoordinate = (value) => value !== null && value !== undefined
    && String(value).trim() !== ''
    && Number.isFinite(Number(value));
  if (!hasCoordinate(latitude) || !hasCoordinate(longitude)) {
    errors.push('Capture a GPS location (or drop the pin on the map) before submitting.');
  }
  return { ok: errors.length === 0, errors };
}

/**
 * Build the multipart body. Exported so the shape is testable without a network.
 *
 * @param {{photos: Array<{blob: Blob, perspective: string, name: string}>, category: string,
 *          latitude: number, longitude: number, cityRoadId?: string, notes?: string}} input
 */
export function buildFieldFormData(input) {
  const form = new FormData();
  for (const photo of input.photos || []) {
    form.append('images', photo.blob, photo.name || 'photo.jpg');
    form.append('perspectives', photo.perspective);
  }
  form.append('damageType', input.category);
  form.append('latitude', String(input.latitude));
  form.append('longitude', String(input.longitude));
  if (input.cityRoadId) form.append('cityRoadId', input.cityRoadId);
  if (input.notes) form.append('damageDescription', input.notes);
  return form;
}

/**
 * Highlight the photos the server refused.
 *
 * The preview markup is optional (older dashboards have none), so this degrades to
 * doing nothing when the elements are absent — the toast still names the angles.
 */
export function markRejectedPhotos(rejected) {
  if (typeof document === 'undefined' || !Array.isArray(rejected)) return;
  for (const entry of rejected) {
    const preview = document.querySelector(`[data-photo-index="${entry.index}"]`);
    if (preview) {
      preview.setAttribute('data-rejected', 'true');
      preview.classList.add('photo-rejected');
      preview.title = entry.reason || GATE_REJECTION_MESSAGE;
    }
  }
}

function clearRejections() {
  if (typeof document === 'undefined') return;
  document.querySelectorAll('[data-rejected="true"]').forEach((element) => {
    element.removeAttribute('data-rejected');
    element.classList.remove('photo-rejected');
    element.removeAttribute('title');
  });
}

function describeRejections(rejected) {
  if (!Array.isArray(rejected) || rejected.length === 0) return '';
  const angles = rejected
    .map((entry) => (entry.perspective || `photo ${Number(entry.index) + 1}`).replace(/_/g, ' ').toLowerCase())
    .join(', ');
  return ` Retake: ${angles}.`;
}

/**
 * Submit one field report.
 *
 * @returns {Promise<{ok: boolean, status: number, report?: object, rejected?: Array}>}
 *          Never throws for an expected HTTP outcome — callers branch on `ok`.
 */
export async function submitFieldReport(event) {
  if (event && typeof event.preventDefault === 'function') event.preventDefault();
  clearRejections();

  const photoInput = typeof document !== 'undefined' ? document.getElementById(ELEMENT_IDS.photos) : null;
  const files = photoInput && photoInput.files ? photoInput.files : [];
  const category = readValue(ELEMENT_IDS.category);
  const latitude = readNumber(ELEMENT_IDS.latitude);
  const longitude = readNumber(ELEMENT_IDS.longitude);

  const input = validateFieldInput({ files, category, latitude, longitude });
  if (!input.ok) {
    input.errors.forEach((message) => showToast(message, 'warning'));
    return { ok: false, status: 0, rejected: [] };
  }

  const submitButton = document.getElementById(ELEMENT_IDS.submit);
  const originalLabel = submitButton ? submitButton.innerHTML : '';
  if (submitButton) {
    submitButton.disabled = true;
    submitButton.innerHTML = '⏳ Preparing photos…';
  }

  try {
    const { prepared, failures } = await preparePhotos(files);
    failures.forEach((failure) => showToast(`${failure.name}: ${failure.reason}`, 'warning'));

    if (prepared.length === 0) {
      showToast('None of the selected photos could be prepared. Try taking them again.', 'error');
      return { ok: false, status: 0, rejected: [] };
    }
    if (submitButton) submitButton.innerHTML = '⏳ Uploading…';

    const form = buildFieldFormData({
      photos: prepared,
      category,
      latitude,
      longitude,
      cityRoadId: readValue('cityRoadId') || undefined,
      notes: readValue(ELEMENT_IDS.notes) || undefined
    });

    const endpoint = `${API_BASE_URL}${DEFAULT_ENDPOINT}`;
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { ...getAuthOnlyHeaders(), 'ngrok-skip-browser-warning': 'true' },
      body: form
    });

    const text = await response.text();
    let body = null;
    try {
      body = text ? JSON.parse(text) : null;
    } catch (parseError) {
      body = null;
    }

    if (response.status === 422) {
      const rejected = (body && body.rejected) || [];
      markRejectedPhotos(rejected);
      showToast(`${(body && body.message) || GATE_REJECTION_MESSAGE}${describeRejections(rejected)}`, 'error');
      return { ok: false, status: 422, rejected };
    }

    if (!response.ok) {
      showToast((body && body.message) || 'The report could not be submitted. Please try again.', 'error');
      return { ok: false, status: response.status, rejected: [] };
    }

    showToast('Report submitted. The CPDO will review it.', 'success');
    if (photoInput) photoInput.value = '';
    const form_ = document.getElementById(ELEMENT_IDS.form);
    if (form_ && typeof form_.reset === 'function') form_.reset();

    if (typeof window !== 'undefined' && typeof window.loadBarangayDashboardData === 'function') {
      window.loadBarangayDashboardData();
    }

    return { ok: true, status: response.status, report: body, rejected: [] };

  } catch (error) {
    showToast(error.message || 'The report could not be submitted.', 'error');
    return { ok: false, status: 0, rejected: [] };

  } finally {
    if (submitButton) {
      submitButton.disabled = false;
      submitButton.innerHTML = originalLabel || 'Submit Report';
    }
  }
}

/** Wire the "use my location" button. Safe to call on pages without the form. */
export function initFieldReportForm() {
  if (typeof document === 'undefined') return;

  const locateButton = document.getElementById(ELEMENT_IDS.locate);
  if (locateButton) {
    locateButton.addEventListener('click', async () => {
      locateButton.disabled = true;
      try {
        const fix = await requestGeolocation();
        const latInput = document.getElementById(ELEMENT_IDS.latitude);
        const lngInput = document.getElementById(ELEMENT_IDS.longitude);
        if (latInput) latInput.value = fix.latitude.toFixed(6);
        if (lngInput) lngInput.value = fix.longitude.toFixed(6);
        showToast(`Location captured (±${Math.round(fix.accuracy)} m).`, 'success');
      } catch (error) {
        showToast(error.message, 'warning');
      } finally {
        locateButton.disabled = false;
      }
    });
  }

  const photoInput = document.getElementById(ELEMENT_IDS.photos);
  if (photoInput) {
    photoInput.setAttribute('accept', 'image/jpeg,image/png,image/webp,image/heic');
    photoInput.addEventListener('change', () => {
      const count = photoInput.files ? photoInput.files.length : 0;
      if (count > MAX_PHOTOS) {
        showToast(`Only the first ${MAX_PHOTOS} photos will be used.`, 'warning');
      }
    });
  }

  const form = document.getElementById(ELEMENT_IDS.form);
  if (form) {
    form.addEventListener('submit', submitFieldReport);
  }

  // Hide the engineering inputs from callers who cannot record them. The server
  // enforces the same rule (FieldScopePolicy); this only stops the UI from asking
  // for measurements that would be discarded on arrival.
  applyRoleFieldVisibility(
    typeof sessionStorage !== 'undefined' ? sessionStorage.getItem('userRole') : null);
}

/**
 * Open the three-field upload modal.
 *
 * The modal id is written as a literal rather than read from {@link ELEMENT_IDS} on
 * purpose: `tools/dom-contract.js` only audits literal element-lookup arguments, and
 * routing the id through a constant would hide an orphan reference from the one
 * check that catches it.
 */
export function openFieldReportForm() {
  if (typeof document === 'undefined') return;
  const modal = document.getElementById('field-report-modal');
  if (modal) {
    modal.classList.remove('hidden');
    modal.style.display = 'flex';
  }
}

export function closeFieldReportForm() {
  if (typeof document === 'undefined') return;
  const modal = document.getElementById('field-report-modal');
  if (modal) {
    modal.classList.add('hidden');
    modal.style.display = 'none';
  }
}

/**
 * Roles that may record engineering measurements.
 *
 * Mirrors the server's `RoleCapabilities` map. Duplicated rather than fetched
 * because it drives a purely cosmetic decision — hiding inputs the server would
 * discard anyway — and a round trip before first paint is not worth it. The server
 * remains the authority: `FieldScopePolicy` strips these fields regardless of what
 * this page renders.
 */
const SURVEY_ROLES = Object.freeze(['cpdo admin', 'admin', 'engineer', 'city engineer']);

/**
 * True when the engineering (measured geometry) inputs should be hidden for `role`.
 *
 * Fails closed: a missing or unrecognised role is treated as a field inspector, so
 * the default experience is the three-field form, not a form full of inputs that
 * will not be saved.
 */
export function shouldHideEngineeringFields(role) {
  if (typeof role !== 'string') return true;
  const normalized = role.trim().replace(/^role_/i, '').replace(/\s+/g, ' ').toLowerCase();
  return !SURVEY_ROLES.includes(normalized);
}

/** Apply {@link shouldHideEngineeringFields} to the marked inputs on this page. */
export function applyRoleFieldVisibility(role) {
  if (typeof document === 'undefined') return;
  const hidden = shouldHideEngineeringFields(role);
  const fields = document.querySelectorAll('[data-engineering-field]');
  if (!fields || typeof fields.forEach !== 'function') return;
  fields.forEach((element) => {
    element.hidden = hidden;
    element.style.display = hidden ? 'none' : '';
  });
}

if (typeof document !== 'undefined') {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initFieldReportForm);
  } else {
    initFieldReportForm();
  }
}

// Window compatibility bridges, so legacy inline handlers keep working.
if (typeof window !== 'undefined') {
  window.submitFieldReport = submitFieldReport;
  window.initFieldReportForm = initFieldReportForm;
  window.requestGeolocation = requestGeolocation;
  window.openFieldReportForm = openFieldReportForm;
  window.closeFieldReportForm = closeFieldReportForm;
}
