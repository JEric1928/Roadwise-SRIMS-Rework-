/**
 * Philippine Standard Time (PST / UTC+8) display formatter.
 *
 * Semantics are preserved from app.js:
 * - "YYYY-MM-DD HH:mm:ss" is normalized to ISO before parsing.
 * - Strings without a timezone indicator are tagged +08:00, so naive values
 *   are interpreted as Philippine time and never double-shifted.
 * - Formatting uses the Asia/Manila time zone.
 *
 * This is a timestamp formatter; calendar-date helpers in the inventory
 * domain use different timezone semantics and stay separate.
 */
export function formatPST(dateInput, includeTime = true) {
  if (!dateInput) return 'N/A';

  let dateStr = String(dateInput).trim();

  // 1. Standardize "YYYY-MM-DD HH:mm:ss" to ISO format "YYYY-MM-DDTHH:mm:ss"
  if (dateStr.includes(' ') && !dateStr.includes('T')) {
    dateStr = dateStr.replace(' ', 'T');
  }

  // 2. Explicitly tag with +08:00 so the browser treats it as Philippine Time (no double +8 hrs)
  if (!dateStr.endsWith('Z') && !dateStr.includes('+') && !dateStr.slice(10).includes('-')) {
    dateStr += '+08:00';
  }

  const d = new Date(dateStr);
  if (isNaN(d.getTime())) return 'N/A';

  const options = {
    timeZone: 'Asia/Manila',
    year: 'numeric',
    month: 'short',
    day: 'numeric'
  };

  if (includeTime) {
    options.hour = '2-digit';
    options.minute = '2-digit';
    options.hour12 = true;
  }

  return d.toLocaleString('en-US', options);
}