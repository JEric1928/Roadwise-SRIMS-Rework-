/**
 * Priority masterlist feature: renders the CPDO/CEO ranked queue.
 *
 * The server owns the ordering (tier descending, then volumetric footprint
 * descending) and the tier assignment; this module only renders what it is given.
 * Nothing here re-sorts, because a client-side re-sort is how two screens end up
 * disagreeing about which pothole is worse.
 *
 * `buildMasterlistHtml` is deliberately a pure string builder so it can be unit
 * tested under `node` without a DOM, and every interpolated value is escaped —
 * road names and repair plans are operator-entered text.
 */

import { apiFetch } from '../../core/api.js';
import { showToast } from '../../components/toast.js';
import {
  tierLabel,
  tierBadgeColor,
  severityLabel,
  formatMetres,
  formatCentimetres,
  formatFootprint,
  formatPriorityScore
} from '../../utils/priority.js';

export const ELEMENT_IDS = Object.freeze({
  tableBody: 'priority-masterlist-body',
  refresh: 'priority-masterlist-refresh',
  weightSummary: 'priority-weight-summary'
});

/** Minimal HTML escaping for text interpolated into markup. */
export function escapeHtml(value) {
  if (value === null || value === undefined) return '';
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** One `<tr>` for one ranked report. */
export function buildMasterlistRowHtml(row, position) {
  const tier = row.tierScore;
  const badgeColor = tierBadgeColor(tier);
  const roadName = row.cityRoadName || 'Unlinked location';
  const depth = row.damageDepth === null || row.damageDepth === undefined
    ? null
    : formatCentimetres(row.damageDepth);

  const measurements = [
    row.damageLength === null || row.damageLength === undefined ? null : formatMetres(row.damageLength),
    row.damageWidth === null || row.damageWidth === undefined ? null : formatMetres(row.damageWidth),
    depth
  ].filter(Boolean).join(' × ') || '<span class="text-muted">not surveyed</span>';

  return `<tr data-report-id="${escapeHtml(row.id)}">
  <td class="priority-rank">${escapeHtml(position)}</td>
  <td class="priority-road">
    <strong>${escapeHtml(roadName)}</strong>
    <div class="text-muted small">${escapeHtml(row.cityRoadCode || '')}</div>
  </td>
  <td>${escapeHtml(row.damageType || '—')}</td>
  <td>${escapeHtml(severityLabel(row.cvSeverityScore))}</td>
  <td><span class="badge" style="background-color:${escapeHtml(badgeColor)}">${escapeHtml(tierLabel(tier))}</span></td>
  <td>${measurements}</td>
  <td>${escapeHtml(formatFootprint(row.areaScore))}</td>
  <td>${escapeHtml(formatPriorityScore(row.priorityScore))}</td>
  <td class="priority-actions">
    <button type="button" class="btn btn-sm btn-outline" onclick="openCpdoSurvey(${escapeHtml(row.id)})">Survey</button>
  </td>
</tr>`;
}

/** Concatenated rows, or an empty-state row when the queue is empty. */
export function buildMasterlistHtml(rows) {
  if (!Array.isArray(rows) || rows.length === 0) {
    return '<tr><td colspan="9" class="text-center text-muted">No validated reports are awaiting ranking.</td></tr>';
  }
  return rows.map((row, index) => buildMasterlistRowHtml(row, index + 1)).join('\n');
}

/** Render into the table body if the page has one. */
export function renderMasterlist(rows) {
  if (typeof document === 'undefined') return;
  const body = document.getElementById(ELEMENT_IDS.tableBody);
  if (body) body.innerHTML = buildMasterlistHtml(rows);
}

function renderWeights(weights) {
  if (typeof document === 'undefined' || !weights) return;
  const summary = document.getElementById(ELEMENT_IDS.weightSummary);
  if (!summary) return;
  summary.textContent = `weights — severity ${weights.severity}, arterial ${weights.coreArterial}, `
    + `footprint ${weights.area}, depth ${weights.depth} (${weights.auditCount} audit`
    + `${weights.auditCount === 1 ? '' : 's'})`;
}

/**
 * Fetch and render the masterlist.
 *
 * Exposed on `window` because the sidebar refresh and the CEO dashboard both call it.
 */
export async function loadPriorityMasterlist() {
  const refreshButton = typeof document !== 'undefined'
    ? document.getElementById(ELEMENT_IDS.refresh) : null;
  const originalLabel = refreshButton ? refreshButton.innerHTML : '';
  if (refreshButton) {
    refreshButton.disabled = true;
    refreshButton.innerHTML = '⏳ Loading…';
  }

  try {
    const payload = await apiFetch('/api/cpdo/priority/masterlist');
    renderMasterlist(payload && payload.reports);
    renderWeights(payload && payload.weights);
    return { ok: true, payload };
  } catch (error) {
    showToast(error.message || 'The priority masterlist could not be loaded.', 'error');
    return { ok: false, message: error.message };
  } finally {
    if (refreshButton) {
      refreshButton.disabled = false;
      refreshButton.innerHTML = originalLabel || 'Refresh';
    }
  }
}

export function initPriorityMasterlist() {
  if (typeof document === 'undefined') return;
  const refreshButton = document.getElementById(ELEMENT_IDS.refresh);
  if (refreshButton) refreshButton.addEventListener('click', loadPriorityMasterlist);
}

if (typeof document !== 'undefined') {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initPriorityMasterlist);
  } else {
    initPriorityMasterlist();
  }
}

// Window compatibility bridges
if (typeof window !== 'undefined') {
  window.loadPriorityMasterlist = loadPriorityMasterlist;
  window.renderPriorityMasterlist = renderMasterlist;
}
