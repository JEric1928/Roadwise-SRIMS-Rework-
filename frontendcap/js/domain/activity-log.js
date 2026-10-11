/**
 * Activity-log row formatters.
 *
 * Pure presentation helpers extracted from app.js without semantic changes:
 * target-entity labels (with #USR/#PRJ padding), category badges, and status
 * badges. They own no listeners, requests, or mutable state.
 */
import { escapeHtml } from '../core/utils.js';

// Helper: Target Entity Formatter (Handles #USR-0001 padding & Email display)
export function formatTargetEntity(entity) {
  if (!entity || entity === 'N/A' || entity === '-') {
    return {
      html: `<span style="color: #94a3b8;">—</span>`,
      text: '—'
    };
  }

  const str = String(entity).trim();

  // 1. Auto-pad entity numbers (#USR-1 -> #USR-0001, #PRJ-4 -> #PRJ-0004)
  const idMatch = str.match(/^(#(?:USR|PRJ|LOG|RPT)-)(\d+)$/i);
  if (idMatch) {
    const padded = `${idMatch[1].toUpperCase()}${idMatch[2].padStart(4, '0')}`;
    return {
      html: `<span style="font-family: monospace; font-weight: 700; color: #2563eb; background: #eff6ff; border: 1px solid #dbeafe; padding: 2px 7px; border-radius: 4px; font-size: 11px;">${padded}</span>`,
      text: padded
    };
  }

  // 2. Already formatted prefixed entity (e.g., #USR-0001, SYSTEM)
  if (str.startsWith('#')) {
    return {
      html: `<span style="font-family: monospace; font-weight: 700; color: #2563eb; background: #eff6ff; border: 1px solid #dbeafe; padding: 2px 7px; border-radius: 4px; font-size: 11px;">${escapeHtml(str)}</span>`,
      text: str
    };
  }

  // 3. Security Email target (Failed login attempt)
  if (str.includes('@')) {
    return {
      html: `<span style="font-family: monospace; font-size: 10.5px; color: #475569; background: #f8fafc; border: 1px solid #cbd5e1; padding: 2px 6px; border-radius: 4px; word-break: break-all;" title="${escapeHtml(str)}">${escapeHtml(str)}</span>`,
      text: str
    };
  }

  // 4. Default plain text entity
  return {
    html: `<span style="font-family: monospace; font-size: 11px; font-weight: 600; color: #334155;">${escapeHtml(str)}</span>`,
    text: str
  };
}

export function getCategoryBadge(cat) {
  const c = String(cat || 'SYSTEM').toUpperCase();
  if (c === 'PROJECT') return `<span style="background: #eff6ff; color: #2563eb; border: 1px solid #bfdbfe; padding: 2px 7px; border-radius: 4px; font-weight: 700; font-size: 10.5px;">PROJECT</span>`;
  if (c === 'QA') return `<span style="background: #fdf2f8; color: #db2777; border: 1px solid #fbcfe8; padding: 2px 7px; border-radius: 4px; font-weight: 700; font-size: 10.5px;">QA & REVIEW</span>`;
  if (c === 'EXPORT') return `<span style="background: #f0fdf4; color: #16a34a; border: 1px solid #bbf7d0; padding: 2px 7px; border-radius: 4px; font-weight: 700; font-size: 10.5px;">EXPORT</span>`;
  if (c === 'AUTH') return `<span style="background: #faf5ff; color: #9333ea; border: 1px solid #e9d5ff; padding: 2px 7px; border-radius: 4px; font-weight: 700; font-size: 10.5px;">SECURITY</span>`;
  if (c === 'USER') return `<span style="background: #fff7ed; color: #ea580c; border: 1px solid #ffedd5; padding: 2px 7px; border-radius: 4px; font-weight: 700; font-size: 10.5px;">USER MGMT</span>`;
  return `<span style="background: #f1f5f9; color: #475569; border: 1px solid #cbd5e1; padding: 2px 7px; border-radius: 4px; font-weight: 700; font-size: 10.5px;">SYSTEM</span>`;
}

export function getStatusBadge(status) {
  const s = String(status || 'SUCCESS').toUpperCase();
  if (s === 'FAILED') return `<span style="background: #fef2f2; color: #dc2626; border: 1px solid #fecaca; padding: 2px 6px; border-radius: 4px; font-weight: 800; font-size: 10px;">FAILED</span>`;
  if (s === 'WARNING') return `<span style="background: #fffbeb; color: #d97706; border: 1px solid #fde68a; padding: 2px 6px; border-radius: 4px; font-weight: 800; font-size: 10px;">WARNING</span>`;
  return `<span style="background: #f0fdf4; color: #16a34a; border: 1px solid #bbf7d0; padding: 2px 6px; border-radius: 4px; font-weight: 800; font-size: 10px;">SUCCESS</span>`;
}