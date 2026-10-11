/**
 * Audit-log timestamp parsing and Philippine time display.
 *
 * Timezone semantics are preserved from app.js and intentionally differ from
 * core/format-pst.js: timezone-less strings are treated as UTC (not Manila
 * time). Keep these semantics separate from other date helpers.
 *
 * This module has no browser globals, requests, listeners, or mutable state.
 */

// Robust UTC Parser (Standardizes Render UTC Timestamps)
export function parseAuditTimestamp(ts) {
  if (!ts) return null;

  if (typeof ts === 'string') {
    let trimmed = ts.trim();
    if (trimmed.includes(' ') && !trimmed.includes('T')) {
      trimmed = trimmed.replace(' ', 'T');
    }

    const hasTz = trimmed.endsWith('Z') ||
      trimmed.includes('+') ||
      (trimmed.length > 10 && trimmed.slice(10).includes('-'));

    return new Date(hasTz ? trimmed : trimmed + 'Z');
  } else if (Array.isArray(ts)) {
    return new Date(Date.UTC(ts[0], ts[1] - 1, ts[2], ts[3] || 0, ts[4] || 0, ts[5] || 0));
  }
  return new Date(ts);
}

// Convert UTC to Philippine Standard Time (PHT: Asia/Manila)
export function formatAuditTimestamp(ts) {
  if (!ts) return "N/A";
  const d = parseAuditTimestamp(ts);
  if (!d || isNaN(d.getTime())) return "N/A";

  const datePart = d.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    timeZone: 'Asia/Manila'
  });

  const timePart = d.toLocaleTimeString('en-US', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: true,
    timeZone: 'Asia/Manila'
  });

  return `${datePart} ${timePart}`;
}