/**
 * Centralized Report Status Definitions and Utilities
 */

export const REPORT_STATUS = Object.freeze({
  PENDING_REVIEW: 'Pending Validation',
  PENDING: 'Pending',
  VALIDATED: 'Validated',
  DISPATCHED: 'Dispatched to CEO',
  IN_PROGRESS: 'In Progress',
  COMPLETED: 'Completed',
  REPAIRED: 'Repaired',
  CLOSED: 'Closed',
  RESOLVED: 'Resolved',
  ARCHIVED: 'Archived',
  PENDING_BUDGET: 'Pending Budget',
  AWAITING_QA: 'Awaiting QA',
  REWORK: 'Rework Required',
  DEFERRED: 'Deferred'
});

export function normalizeStatus(status) {
  if (!status || typeof status !== 'string') return '';
  const s = status.trim().toLowerCase();

  if (s.includes('archiv')) return REPORT_STATUS.ARCHIVED;
  if (s.includes('complet') || s.includes('repair') || s.includes('resolv')) return REPORT_STATUS.COMPLETED;
  if (s.includes('progress')) return REPORT_STATUS.IN_PROGRESS;
  if (s.includes('dispatch')) return REPORT_STATUS.DISPATCHED;
  if (s.includes('validat')) return REPORT_STATUS.VALIDATED;
  if (s.includes('budget')) return REPORT_STATUS.PENDING_BUDGET;
  if (s.includes('defer')) return REPORT_STATUS.DEFERRED;
  if (s.includes('rework')) return REPORT_STATUS.REWORK;
  if (s.includes('qa')) return REPORT_STATUS.AWAITING_QA;
  if (s.includes('close')) return REPORT_STATUS.CLOSED;

  return status.trim();
}

export function getStatusBadgeClass(status) {
  const norm = normalizeStatus(status);
  switch (norm) {
    case REPORT_STATUS.COMPLETED:
    case REPORT_STATUS.RESOLVED:
    case REPORT_STATUS.CLOSED:
      return 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-400 border-emerald-200 dark:border-emerald-800';
    case REPORT_STATUS.IN_PROGRESS:
    case REPORT_STATUS.DISPATCHED:
      return 'bg-blue-100 text-blue-800 dark:bg-blue-950/40 dark:text-blue-400 border-blue-200 dark:border-blue-800';
    case REPORT_STATUS.VALIDATED:
      return 'bg-indigo-100 text-indigo-800 dark:bg-indigo-950/40 dark:text-indigo-400 border-indigo-200 dark:border-indigo-800';
    case REPORT_STATUS.PENDING_BUDGET:
    case REPORT_STATUS.DEFERRED:
      return 'bg-amber-100 text-amber-800 dark:bg-amber-950/40 dark:text-amber-400 border-amber-200 dark:border-amber-800';
    case REPORT_STATUS.REWORK:
    case REPORT_STATUS.AWAITING_QA:
      return 'bg-purple-100 text-purple-800 dark:bg-purple-950/40 dark:text-purple-400 border-purple-200 dark:border-purple-800';
    case REPORT_STATUS.ARCHIVED:
      return 'bg-slate-100 text-slate-800 dark:bg-slate-800 dark:text-slate-300 border-slate-200 dark:border-slate-700';
    default:
      return 'bg-amber-100 text-amber-800 dark:bg-amber-950/40 dark:text-amber-400 border-amber-200 dark:border-amber-800';
  }
}

if (typeof window !== 'undefined') {
  window.REPORT_STATUS = REPORT_STATUS;
  window.normalizeStatus = normalizeStatus;
  window.getStatusBadgeClass = getStatusBadgeClass;
}
