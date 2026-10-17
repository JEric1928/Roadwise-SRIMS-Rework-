/**
 * Priority/severity presentation helpers.
 *
 * Dependency-free and DOM-free on purpose: every value here is derived from API
 * payloads, so these functions are unit-testable under plain `node` (see test.js)
 * and can be reused by the barangay, CPDO and CEO dashboards alike.
 *
 * The tier numbers and badge colours mirror the backend contract
 * (`PriorityRankingService.TIER_*` / `BADGE_*`). Keep them in step: the server is
 * authoritative for the tier value, this module only renders it.
 */

/** Tier codes as emitted by `/api/cpdo/priority/*`. */
export const TIER = Object.freeze({
  HIGH: 3,
  MEDIUM: 2,
  LOW: 1
});

/** Bootstrap-ish badge colours, matching the values named in the specification. */
export const TIER_BADGE_COLOR = Object.freeze({
  [TIER.HIGH]: '#dc3545',
  [TIER.MEDIUM]: '#ff8c00',
  [TIER.LOW]: '#28a745'
});

export const TIER_LABEL = Object.freeze({
  [TIER.HIGH]: 'HIGH PRIORITY',
  [TIER.MEDIUM]: 'MEDIUM PRIORITY',
  [TIER.LOW]: 'LOW PRIORITY'
});

/** One report carries at most three photos. */
export const MAX_REPORT_IMAGES = 3;

/**
 * Capture angles, in the order the upload widget presents them. The order is also
 * the positional fallback the inference service applies when no label is sent.
 */
export const PERSPECTIVES = Object.freeze(['CLOSE_UP', 'WIDE_ANGLE', 'PERPENDICULAR']);

export const PERSPECTIVE_LABEL = Object.freeze({
  CLOSE_UP: 'Close-up',
  WIDE_ANGLE: 'Wide angle',
  PERPENDICULAR: 'Perpendicular'
});

/** The four field-reportable distress categories (server-validated). */
export const DEFECT_CATEGORIES = Object.freeze([
  'Pothole',
  'Cracking',
  'Surface Disintegration',
  'Block Failure'
]);

export const SEVERITY_LABEL = Object.freeze({
  1: 'Low',
  2: 'Medium',
  3: 'High'
});

/** Clamp an unknown tier to LOW rather than rendering an empty badge. */
export function normalizeTier(tier) {
  const numeric = Number(tier);
  return numeric === TIER.HIGH || numeric === TIER.MEDIUM ? numeric : TIER.LOW;
}

export function tierLabel(tier) {
  return TIER_LABEL[normalizeTier(tier)];
}

export function tierBadgeColor(tier) {
  return TIER_BADGE_COLOR[normalizeTier(tier)];
}

/**
 * Aggregated vision severity (1-3) as a display string.
 * 0, null and undefined all mean "inference has not run" — not "no damage".
 */
export function severityLabel(score) {
  const numeric = Number(score);
  return SEVERITY_LABEL[numeric] || 'Unassessed';
}

export function perspectiveLabel(perspective) {
  return PERSPECTIVE_LABEL[perspective] || PERSPECTIVE_LABEL.CLOSE_UP;
}

/**
 * Coerce a possibly-absent value to a finite number.
 *
 * `Number(null)` is 0 and `Number('')` is 0, so a plain isFinite check would render a
 * missing survey measurement as a real "0.0" — which reads as a measured zero rather
 * than "not surveyed". Blank-ish input maps to null instead.
 */
function toMeasure(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === 'string' && value.trim() === '') return null;
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : null;
}

/** Metres, one decimal — the precision the survey form collects. */
export function formatMetres(value) {
  const numeric = toMeasure(value);
  if (numeric === null) return '—';
  return `${numeric.toFixed(1)} m`;
}

/** Centimetres, one decimal (damage depth). */
export function formatCentimetres(value) {
  const numeric = toMeasure(value);
  if (numeric === null) return '—';
  return `${numeric.toFixed(1)} cm`;
}

/**
 * Volumetric footprint from the ranking engine, in `m²·cm`.
 *
 * Rendered with an explicit unit suffix because the value is a comparison key, not
 * a physical volume: the server multiplies metres by centimetres by design, and a
 * label of "m³" would be wrong.
 */
export function formatFootprint(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return '—';
  if (numeric === 0) return '0';
  if (numeric >= 1000) return `${Math.round(numeric).toLocaleString()} m²·cm`;
  return `${numeric.toFixed(1)} m²·cm`;
}

/** Analytic priority index from the RLS calibration, 4 decimal places. */
export function formatPriorityScore(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return '—';
  return numeric.toFixed(2);
}

/** Confidence percentage; the AI service already returns 0-100. */
export function formatConfidence(percent) {
  const numeric = Number(percent);
  if (!Number.isFinite(numeric)) return '—';
  return `${numeric.toFixed(1)}%`;
}

/**
 * True when a defect category is one the field form is allowed to submit.
 * Case-insensitive so a legacy `damageType` value can be pre-selected.
 */
export function isSupportedDefectCategory(value) {
  if (typeof value !== 'string') return false;
  const normalized = value.trim().toLowerCase();
  return DEFECT_CATEGORIES.some((category) => category.toLowerCase() === normalized);
}
