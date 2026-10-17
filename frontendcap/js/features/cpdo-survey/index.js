/**
 * CPDO technical survey feature.
 *
 * This is where the engineering half of a report is captured: measured length,
 * width and depth, the pavement construction, and the official scope of work. The
 * form is only reachable by a caller holding the `technicalSurvey` capability, and
 * the endpoint re-checks that server-side.
 *
 * Partial saves are the normal case — an engineer measures length and width on the
 * street and cores for depth later — so blank inputs are omitted from the payload
 * rather than sent as 0. The server merges non-null fields only, which means a blank
 * input can never erase a measurement someone already recorded.
 */

import { apiFetch } from '../../core/api.js';
import { showToast } from '../../components/toast.js';

export const ELEMENT_IDS = Object.freeze({
  form: 'cpdo-survey-form',
  reportId: 'cpdo-survey-report-id',
  length: 'cpdo-damage-length',
  width: 'cpdo-damage-width',
  depth: 'cpdo-damage-depth',
  surfaceType: 'cpdo-surface-type',
  repairPlan: 'cpdo-repair-plan',
  submit: 'cpdo-survey-submit'
});

export const SURFACE_TYPES = Object.freeze([
  { value: 'PORTLAND_CEMENT_CONCRETE', label: 'Portland Cement Concrete' },
  { value: 'ASPHALT_CONCRETE', label: 'Asphalt Concrete' }
]);

function readValue(id) {
  if (typeof document === 'undefined') return '';
  const element = document.getElementById(id);
  return element ? (element.value || '').trim() : '';
}

/**
 * Turn a form-reading function into a survey payload.
 *
 * Exported with the reader injected so the mapping is testable without a DOM.
 *
 * @param {(id: string) => string} read
 */
export function buildSurveyPayload(read) {
  const payload = {};

  for (const [key, field] of [['damageLength', 'cpdo-damage-length'],
                              ['damageWidth', 'cpdo-damage-width'],
                              ['damageDepth', 'cpdo-damage-depth']]) {
    const raw = read(field);
    if (raw === '') continue;
    const numeric = Number(raw);
    if (!Number.isFinite(numeric) || numeric < 0) {
      throw new Error('Measurements must be zero or a positive number.');
    }
    payload[key] = numeric;
  }

  // Trim before the emptiness test: a whitespace-only input is a blank input, and
  // sending "  " as a repair plan would overwrite a real one on the server.
  const surfaceType = (read(ELEMENT_IDS.surfaceType) || '').trim();
  if (surfaceType) {
    const supported = SURFACE_TYPES.some((option) => option.value === surfaceType);
    if (!supported) {
      throw new Error('Choose Portland Cement Concrete or Asphalt Concrete.');
    }
    payload.surfaceType = surfaceType;
  }

  const repairPlan = (read(ELEMENT_IDS.repairPlan) || '').trim();
  if (repairPlan) payload.cpdoRepairPlan = repairPlan;

  return payload;
}

/**
 * Persist the survey for one report.
 *
 * @returns {Promise<{ok: boolean, report?: object, message?: string}>}
 */
export async function saveCpdoSurvey(event) {
  if (event && typeof event.preventDefault === 'function') event.preventDefault();

  const reportId = readValue(ELEMENT_IDS.reportId);
  if (!reportId) {
    showToast('No report is selected.', 'warning');
    return { ok: false, message: 'missing report id' };
  }

  let payload;
  try {
    payload = buildSurveyPayload(readValue);
  } catch (error) {
    showToast(error.message, 'warning');
    return { ok: false, message: error.message };
  }

  if (Object.keys(payload).length === 0) {
    showToast('Enter at least one measurement, surface type, or repair plan.', 'warning');
    return { ok: false, message: 'empty survey' };
  }

  const submitButton = typeof document !== 'undefined' ? document.getElementById(ELEMENT_IDS.submit) : null;
  const originalLabel = submitButton ? submitButton.innerHTML : '';
  if (submitButton) {
    submitButton.disabled = true;
    submitButton.innerHTML = '⏳ Saving…';
  }

  try {
    const report = await apiFetch(`/api/cpdo/reports/${reportId}/survey`, {
      method: 'POST',
      body: JSON.stringify(payload)
    });
    showToast('Survey saved. Priority re-ranked.', 'success');
    if (typeof window !== 'undefined' && typeof window.loadPriorityMasterlist === 'function') {
      window.loadPriorityMasterlist();
    }
    return { ok: true, report };

  } catch (error) {
    showToast(error.message || 'The survey could not be saved.', 'error');
    return { ok: false, message: error.message };

  } finally {
    if (submitButton) {
      submitButton.disabled = false;
      submitButton.innerHTML = originalLabel || 'Save survey';
    }
  }
}

/**
 * Open the survey form for a report and pre-fill what has already been measured.
 * Accepts the masterlist row shape emitted by `/api/cpdo/priority/*`.
 */
export function openCpdoSurvey(report) {
  if (typeof document === 'undefined' || !report) return;

  const setValue = (id, value) => {
    const element = document.getElementById(id);
    if (element) element.value = value === null || value === undefined ? '' : value;
  };

  setValue(ELEMENT_IDS.reportId, report.id);
  setValue(ELEMENT_IDS.length, report.damageLength);
  setValue(ELEMENT_IDS.width, report.damageWidth);
  setValue(ELEMENT_IDS.depth, report.damageDepth);
  setValue(ELEMENT_IDS.surfaceType, report.surfaceType);
  setValue(ELEMENT_IDS.repairPlan, report.cpdoRepairPlan);

  const modal = document.getElementById('cpdo-survey-modal');
  if (modal) {
    modal.classList.remove('hidden');
    modal.style.display = 'flex';
  }
}

export function closeCpdoSurvey() {
  if (typeof document === 'undefined') return;
  const modal = document.getElementById('cpdo-survey-modal');
  if (modal) {
    modal.classList.add('hidden');
    modal.style.display = 'none';
  }
}

export function initCpdoSurveyForm() {
  if (typeof document === 'undefined') return;
  const form = document.getElementById(ELEMENT_IDS.form);
  if (form) form.addEventListener('submit', saveCpdoSurvey);
}

if (typeof document !== 'undefined') {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initCpdoSurveyForm);
  } else {
    initCpdoSurveyForm();
  }
}

// Window compatibility bridges
if (typeof window !== 'undefined') {
  window.saveCpdoSurvey = saveCpdoSurvey;
  window.openCpdoSurvey = openCpdoSurvey;
  window.closeCpdoSurvey = closeCpdoSurvey;
}
