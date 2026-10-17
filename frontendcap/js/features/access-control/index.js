/**
 * Access control: maker-checker approvals and break-glass grants.
 *
 * <p>Two administrator-facing flows that belong together because they answer the same
 * question from opposite ends: "I need to act on an account I am not senior to". The
 * approval queue is the everyday answer (a second administrator agrees); break-glass is the
 * emergency answer (nobody is available, so the action is taken loudly and reviewed after).
 *
 * <p>Why the ticket is kept in sessionStorage: an approval is redeemed by repeating the
 * action with `X-Approval-Id`, and the person who requested it is usually the person who
 * repeats it, in the same tab, minutes later. Remembering the ticket turns "approve it, then
 * find the account again" into "approve it, then click the button again". A stale or wrong
 * ticket is harmless: the server only redeems an APPROVED, unexpired one for the action it
 * names, and refuses everything else.
 */
import { apiFetch } from '../../core/api.js';
import { showToast } from '../../components/toast.js';
import { escapeHtml } from '../../core/utils.js';

const PENDING_KEY = 'roadwise.pendingApprovals';

function readPending() {
  try {
    return JSON.parse(window.sessionStorage.getItem(PENDING_KEY) || '{}') || {};
  } catch (unreadable) {
    return {};
  }
}

function writePending(map) {
  try {
    window.sessionStorage.setItem(PENDING_KEY, JSON.stringify(map));
  } catch (unavailable) {
    // Private-mode storage failures must not break the approval flow itself.
  }
}

function pendingKey(permission, targetUserId) {
  return `${permission}:${targetUserId}`;
}

/** Headers that carry a remembered ticket for this exact action, if there is one. */
export function approvalHeaders(permission, targetUserId) {
  const entry = readPending()[pendingKey(permission, targetUserId)];
  return entry && entry.id ? { 'X-Approval-Id': String(entry.id) } : {};
}

/** Forget a ticket once it has been redeemed, so a later action cannot reuse it. */
export function clearApproval(permission, targetUserId) {
  const map = readPending();
  const key = pendingKey(permission, targetUserId);
  if (map[key]) {
    delete map[key];
    writePending(map);
  }
}

/**
 * Opens an approval request for a peer action the server just refused.
 *
 * <p>Called from the administration feature's 403 handler. Returns true when a ticket is
 * in hand, so the caller can stop showing its own generic error.
 */
export async function requestPrivilegedAction(permission, targetUserId) {
  const reason = window.prompt(
    'This account is a peer or superior, so a second administrator has to approve the action.\n\n'
    + 'Why is it needed? (recorded against the request)');
  if (!reason || !reason.trim()) {
    showToast('No approval request was opened.', 'warning');
    return false;
  }

  try {
    const ticket = await apiFetch('/api/admin/approvals', {
      method: 'POST',
      body: JSON.stringify({ permission, targetUserId, reason: reason.trim() })
    });
    const map = readPending();
    map[pendingKey(permission, targetUserId)] = { id: ticket.id, permission, targetUserId };
    writePending(map);

    showToast(
      `Approval request #${ticket.id} opened. Once a second administrator approves it, `
      + 'repeat this action and the approval will be applied automatically.',
      'success');
    return true;
  } catch (err) {
    showToast(err.message || 'Could not open the approval request.', 'error');
    return false;
  }
}

// ==========================================
// RENDERING
// ==========================================

const STATUS_STYLE = {
  PENDING: 'background:#fef3c7;color:#b45309;',
  APPROVED: 'background:#dcfce7;color:#15803d;',
  DENIED: 'background:#fee2e2;color:#b91c1c;',
  EXECUTED: 'background:#e0e7ff;color:#3730a3;'
};

function badge(status) {
  const style = STATUS_STYLE[status] || 'background:#e5e7eb;color:#374151;';
  return `<span style="${style}padding:4px 10px;border-radius:20px;font-size:11px;font-weight:bold;">`
    + `${escapeHtml(status || 'UNKNOWN')}</span>`;
}

function when(value) {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? escapeHtml(String(value)) : parsed.toLocaleString();
}

function renderApprovals(tbody, requests) {
  if (!tbody) return;
  if (!Array.isArray(requests) || requests.length === 0) {
    tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;padding:20px;color:#64748b;">No approval requests.</td></tr>';
    return;
  }

  tbody.innerHTML = requests.map(request => {
    const ownRequest = String(request.requestedBy) === String(window.sessionStorage.getItem('userId'));
    const canAct = request.status === 'PENDING' && !ownRequest;
    const actions = canAct
      ? `<button type="button" class="btn-primary" data-approval-action="approve" data-id="${escapeHtml(String(request.id))}">Approve</button>
         <button type="button" class="btn-secondary" data-approval-action="deny" data-id="${escapeHtml(String(request.id))}">Deny</button>`
      : (ownRequest && request.status === 'PENDING'
        ? '<span style="color:#64748b;font-size:12px;">Waiting for a second administrator</span>'
        : '');

    return `<tr>
      <td>${when(request.requestedAt)}</td>
      <td><code>${escapeHtml(request.permission || '')}</code></td>
      <td>#USR-${escapeHtml(String(request.targetUserId))}</td>
      <td>#USR-${escapeHtml(String(request.requestedBy))}</td>
      <td style="max-width:280px;">${escapeHtml(request.reason || '')}</td>
      <td>${badge(request.status)}</td>
      <td style="white-space:nowrap;">${actions}</td>
    </tr>`;
  }).join('');
}

function renderGrants(tbody, grants) {
  if (!tbody) return;
  if (!Array.isArray(grants) || grants.length === 0) {
    tbody.innerHTML = '<tr><td colspan="6" style="text-align:center;padding:20px;color:#64748b;">No break-glass grants.</td></tr>';
    return;
  }

  const now = Date.now();
  tbody.innerHTML = grants.map(grant => {
    const live = !grant.revokedAt && grant.expiresAt && new Date(grant.expiresAt).getTime() > now;
    const state = grant.revokedAt ? 'REVOKED' : (live ? 'LIVE' : 'EXPIRED');
    const actions = live
      ? `<button type="button" class="btn-secondary" data-grant-action="revoke" data-id="${escapeHtml(String(grant.id))}">Revoke</button>`
      : (grant.reviewedAt
        ? '<span style="color:#64748b;font-size:12px;">Reviewed</span>'
        : `<button type="button" class="btn-primary" data-grant-action="review" data-id="${escapeHtml(String(grant.id))}">Mark reviewed</button>`);

    return `<tr>
      <td>#USR-${escapeHtml(String(grant.grantedTo))}</td>
      <td>${when(grant.grantedAt)}</td>
      <td>${when(grant.expiresAt)}</td>
      <td>${badge(state)}</td>
      <td style="max-width:280px;">${escapeHtml(grant.reason || '')}</td>
      <td style="white-space:nowrap;">${actions}</td>
    </tr>`;
  }).join('');
}

// ==========================================
// DATA + BINDINGS
// ==========================================

async function loadAccessControl() {
  const approvalsBody = document.getElementById('access-approvals-tbody');
  const grantsBody = document.getElementById('break-glass-tbody');
  if (!approvalsBody && !grantsBody) return;

  try {
    const [requests, grants] = await Promise.all([
      approvalsBody ? apiFetch('/api/admin/approvals') : Promise.resolve([]),
      grantsBody ? apiFetch('/api/admin/break-glass') : Promise.resolve([])
    ]);
    renderApprovals(approvalsBody, requests);
    renderGrants(grantsBody, grants);
  } catch (err) {
    if (typeof showToast === 'function') {
      showToast(err.message || 'Could not load access control data.', 'error');
    }
  }
}

async function post(path, body) {
  try {
    const result = await apiFetch(path, { method: 'POST', body: JSON.stringify(body || {}) });
    await loadAccessControl();
    return result;
  } catch (err) {
    if (typeof showToast === 'function') showToast(err.message || 'The action failed.', 'error');
    return null;
  }
}

async function activateBreakGlass() {
  const reasonInput = document.getElementById('break-glass-reason');
  const minutesInput = document.getElementById('break-glass-minutes');
  const reason = (reasonInput?.value || '').trim();
  const minutes = Number.parseInt(minutesInput?.value || '30', 10);

  if (reason.length < 10) {
    showToast('Explain why this is needed (at least 10 characters).', 'warning');
    return;
  }

  const grant = await post('/api/admin/break-glass', { reason, minutes });
  if (grant) {
    if (reasonInput) reasonInput.value = '';
    showToast(`Break-glass active until ${when(grant.expiresAt)}. Every other administrator has been notified.`, 'warning');
  }
}

function bind() {
  const refresh = document.getElementById('access-control-refresh');
  if (refresh) refresh.addEventListener('click', loadAccessControl);

  const approvalsBody = document.getElementById('access-approvals-tbody');
  if (approvalsBody) {
    approvalsBody.addEventListener('click', async (event) => {
      const button = event.target.closest('button[data-approval-action]');
      if (!button) return;
      const id = button.getAttribute('data-id');
      const action = button.getAttribute('data-approval-action');

      if (action === 'approve') {
        const note = window.prompt('Note for the record (optional):') || '';
        await post(`/api/admin/approvals/${id}/approve`, { note });
      } else if (action === 'deny') {
        const note = window.prompt('Why is this request being refused?');
        if (!note || !note.trim()) return;
        await post(`/api/admin/approvals/${id}/deny`, { note: note.trim() });
      }
    });
  }

  const activate = document.getElementById('break-glass-activate');
  if (activate) activate.addEventListener('click', activateBreakGlass);

  const grantsBody = document.getElementById('break-glass-tbody');
  if (grantsBody) {
    grantsBody.addEventListener('click', async (event) => {
      const button = event.target.closest('button[data-grant-action]');
      if (!button) return;
      const id = button.getAttribute('data-id');
      const action = button.getAttribute('data-grant-action');

      if (action === 'revoke') {
        await post(`/api/admin/break-glass/${id}/revoke`, {});
      } else if (action === 'review') {
        const note = window.prompt('Review note (optional):') || '';
        await post(`/api/admin/break-glass/${id}/review`, { note });
      }
    });
  }
}

if (typeof document !== 'undefined') {
  document.addEventListener('DOMContentLoaded', bind);
}

// Called by the navigation module when the view is opened.
window.loadAccessControl = loadAccessControl;
