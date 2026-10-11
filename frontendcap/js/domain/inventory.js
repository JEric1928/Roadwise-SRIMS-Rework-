/**
 * Inventory and report row calculations/formatters.
 *
 * Pure helpers extracted from app.js without semantic changes. They own no
 * listeners, requests, rendering, or mutable state. Calendar-date display
 * here follows the browser's local time zone, matching the original code;
 * timestamp helpers with explicit time zones live in core/.
 */

// Helper: Convert length values to kilometers
export function parseToKilometers(val) {
  if (val == null || isNaN(val) || val === "") return 0;
  const num = parseFloat(val);
  return num > 20 ? (num / 1000) : num;
}

// Helper: Format terrain names cleanly
export function formatTerrainType(terrain) {
  if (!terrain || String(terrain).trim() === "") return "FLAT";
  const t = String(terrain).toUpperCase().trim();
  if (t.includes("MOUNTAIN")) return "MOUNTAINOUS";
  if (t.includes("ROLL")) return "ROLLING";
  return t;
}

// Helper: Extract Inventory Year
export function getReportYear(r) {
  if (!r) return "";
  const yearVal = r.inventory_year || r.inventoryYear;
  if (yearVal && String(yearVal).trim() !== "" && String(yearVal).toLowerCase() !== "null" && String(yearVal).toLowerCase() !== "undefined") {
    return String(yearVal).trim();
  }
  const rawDate = r.dateSubmitted || r.date_submitted || r.createdAt || r.created_at || r.dateReported || r.date;
  if (rawDate) {
    if (Array.isArray(rawDate) && rawDate.length >= 1) return String(rawDate[0]);
    const parsed = new Date(rawDate);
    if (!isNaN(parsed.getTime())) return String(parsed.getFullYear());
  }
  return "";
}

// Helper: Format Date for Display
export function formatInventoryDate(r) {
  if (!r) return "N/A";
  const rawDate = r.dateSubmitted || r.date_submitted || r.createdAt || r.created_at || r.dateReported || r.date;
  if (!rawDate) return "N/A";

  if (Array.isArray(rawDate) && rawDate.length >= 3) {
    const year = rawDate[0];
    const month = String(rawDate[1]).padStart(2, '0');
    const day = String(rawDate[2]).padStart(2, '0');
    return new Date(`${year}-${month}-${day}`).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
  }

  const parsed = new Date(rawDate);
  if (!isNaN(parsed.getTime())) {
    return parsed.toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
  }
  return String(rawDate);
}

// Helper: Extract Year (settings variant; differs from getReportYear only in
// how it treats the literal string "undefined". Preserved as-is.)
export function getReportYearSettings(r) {
  if (!r) return "";
  const yearVal = r.inventory_year || r.inventoryYear;
  if (yearVal && String(yearVal).trim() !== "" && String(yearVal).toLowerCase() !== "null") {
    return String(yearVal).trim();
  }
  const rawDate = r.dateSubmitted || r.date_submitted || r.createdAt || r.created_at || r.dateReported || r.date;
  if (rawDate) {
    if (Array.isArray(rawDate) && rawDate.length >= 1) return String(rawDate[0]);
    const parsed = new Date(rawDate);
    if (!isNaN(parsed.getTime())) return String(parsed.getFullYear());
  }
  return "";
}