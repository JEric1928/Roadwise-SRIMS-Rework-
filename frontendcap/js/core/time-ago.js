/**
 * Relative "time ago" formatter, originally owned by app.js notifications.
 *
 * Timezone semantics are preserved exactly:
 * - Strings without a timezone indicator are parsed as UTC.
 * - Jackson LocalDateTime arrays ([YYYY, MM, DD, HH, mm, ss]) are parsed as UTC.
 * - Slight server/client clock skew is clamped to "Just now".
 *
 * This module has no browser globals, requests, listeners, or mutable state.
 */
export function timeAgo(dateString) {
  if (!dateString) return '';

  let date;

  if (typeof dateString === 'string') {
    const trimmed = dateString.trim();
    // Check if the string already has timezone indicators ('Z', '+08:00', etc.)
    const hasTimezone = trimmed.endsWith('Z') ||
      trimmed.includes('+') ||
      (trimmed.length > 10 && trimmed.slice(10).includes('-'));

    // If no timezone is specified, append 'Z' so the browser parses it as UTC
    date = new Date(hasTimezone ? trimmed : trimmed + 'Z');
  } else if (Array.isArray(dateString)) {
    // Fallback if Jackson serializes LocalDateTime as [YYYY, MM, DD, HH, mm, ss]
    date = new Date(Date.UTC(
      dateString[0],
      dateString[1] - 1,
      dateString[2],
      dateString[3] || 0,
      dateString[4] || 0,
      dateString[5] || 0
    ));
  } else {
    date = new Date(dateString);
  }

  // Handle invalid dates gracefully
  if (isNaN(date.getTime())) return '';

  const now = new Date();
  let diffMs = now - date;

  // Guard against slight clock skew between server and client
  if (diffMs < 0) diffMs = 0;

  const diffMins = Math.round(diffMs / 60000);

  if (diffMins < 1) return 'Just now';
  if (diffMins < 60) return `${diffMins} minute${diffMins === 1 ? '' : 's'} ago`;

  const diffHrs = Math.floor(diffMins / 60);
  if (diffHrs < 24) return `${diffHrs} hour${diffHrs === 1 ? '' : 's'} ago`;

  const diffDays = Math.floor(diffHrs / 24);
  if (diffDays === 1) return 'Yesterday';
  return `${diffDays} days ago`;
}
