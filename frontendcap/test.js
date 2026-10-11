const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('path');

// Test 1: PWA Webmanifest Validation
test('site.webmanifest has required PWA fields', () => {
  const manifestPath = path.join(__dirname, 'site.webmanifest');
  assert.strictEqual(fs.existsSync(manifestPath), true, 'site.webmanifest should exist');

  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  assert.strictEqual(manifest.short_name, 'RoadWise');
  assert.strictEqual(manifest.name, 'RoadWise City Road Inspection and Monitoring System');
  assert.strictEqual(manifest.start_url, '/');
  assert.strictEqual(manifest.display, 'standalone');
  assert.strictEqual(manifest.background_color, '#f8fafc');
  assert.strictEqual(manifest.theme_color, '#0f172a');
});

// Test 2: Error Message Formatter Logic (from config.js)
function getErrorMessage(status, fallbackText = "Request failed") {
  switch (status) {
    case 401: return "Authentication required. Please log in again.";
    case 403: return "Access denied. You do not have permission for this action.";
    case 404: return "Requested resource or endpoint was not found.";
    case 409: return "A conflict occurred with the existing data.";
    case 500: return "Server error. Please try again or contact system support.";
    default: return fallbackText;
  }
}

test('getErrorMessage returns appropriate status messages', () => {
  assert.strictEqual(getErrorMessage(401), 'Authentication required. Please log in again.');
  assert.strictEqual(getErrorMessage(403), 'Access denied. You do not have permission for this action.');
  assert.strictEqual(getErrorMessage(404), 'Requested resource or endpoint was not found.');
  assert.strictEqual(getErrorMessage(500), 'Server error. Please try again or contact system support.');
  assert.strictEqual(getErrorMessage(418, 'Custom error'), 'Custom error');
});

// Test 3: Estimated Repair Days Calculation Logic
function calculateEstimatedDays(damageType, severity, areaSqM) {
  const dt = String(damageType || '').toLowerCase();
  const sev = String(severity || '').toLowerCase();
  const area = parseFloat(areaSqM) || 0;

  if (dt.includes('pothole') || dt.includes('lubak')) {
    return { days: 3, label: 'Pothole Patching (3 days)' };
  }
  if (dt.includes('surface') || dt.includes('cracking') || dt.includes('alligator') || dt.includes('longitudinal')) {
    return { days: 7, label: 'Surface Crack Sealing & Resurfacing (7 days)' };
  }
  if (dt.includes('edge') || dt.includes('deformation') || dt.includes('rutting')) {
    return { days: 10, label: 'Edge Rebuilding & Base Compaction (10 days)' };
  }
  if (dt.includes('washout') || dt.includes('sinkhole') || dt.includes('slab') || dt.includes('concrete')) {
    return { days: 21, label: 'Major Structural Restoration & Curing (21 days)' };
  }

  if (sev === 'high') return { days: 14, label: 'AI High Severity Scope (14 days)' };
  if (sev === 'medium') return { days: 7, label: 'AI Medium Severity Scope (7 days)' };
  if (sev === 'low') return { days: 3, label: 'AI Low Severity Scope (3 days)' };

  if (area > 20) return { days: 14, label: 'Large Damage Area > 20 sq.m (14 days)' };
  return { days: 5, label: 'Standard Maintenance Window (5 days)' };
}

test('calculateEstimatedDays calculates repair windows based on damage type and severity', () => {
  assert.strictEqual(calculateEstimatedDays('Pothole', 'medium', 2).days, 3);
  assert.strictEqual(calculateEstimatedDays('Surface Cracking', 'low', 5).days, 7);
  assert.strictEqual(calculateEstimatedDays('Washout/Sinkhole', 'high', 10).days, 21);
  assert.strictEqual(calculateEstimatedDays('Other', 'high', 1).days, 14);
});

// Test 4: Official Name Formatter
function formatOfficialName(firstName, middleName, lastName, prefix = '') {
  const first = (firstName || '').trim();
  const last = (lastName || '').trim();

  let mi = '';
  if (middleName && typeof middleName === 'string') {
    const cleaned = middleName.trim().replace(/\./g, '');
    if (cleaned.length > 0 && cleaned.toLowerCase() !== 'null' && cleaned.toLowerCase() !== 'undefined') {
      mi = `${cleaned.charAt(0).toUpperCase()}.`;
    }
  }

  const fullName = [first, mi, last].filter(Boolean).join(' ');
  return prefix ? `${prefix.trim()} ${fullName}` : (fullName || 'Official');
}

test('formatOfficialName formats name with middle initial and prefix', () => {
  assert.strictEqual(formatOfficialName('Juan', 'Dela', 'Cruz'), 'Juan D. Cruz');
  assert.strictEqual(formatOfficialName('Maria', 'Clara', 'Santos', 'Engr.'), 'Engr. Maria C. Santos');
  assert.strictEqual(formatOfficialName('Pedro', null, 'Penduko'), 'Pedro Penduko');
});

// Test 5: Form Validation Rules
function validateReportStep1(roadName, length, width) {
  if (!roadName || typeof roadName !== 'string' || roadName.trim() === '') {
    return { valid: false, error: 'Road name is required' };
  }
  const lenNum = parseFloat(length);
  const widNum = parseFloat(width);
  if (isNaN(lenNum) || lenNum <= 0) {
    return { valid: false, error: 'Road length must be greater than 0' };
  }
  if (isNaN(widNum) || widNum <= 0) {
    return { valid: false, error: 'Road width must be greater than 0' };
  }
  return { valid: true };
}

test('validateReportStep1 enforces valid road name, length, and width', () => {
  assert.deepStrictEqual(validateReportStep1('', '1.5', '6.0').valid, false);
  assert.deepStrictEqual(validateReportStep1('Main St', '-1', '6.0').valid, false);
  assert.deepStrictEqual(validateReportStep1('Main St', '1.5', '0').valid, false);
  assert.deepStrictEqual(validateReportStep1('Main St', '1.5', '6.0').valid, true);
});

// Test 6: escapeHtml sanitizes special characters
function escapeHtml(str) {
  if (str === null || str === undefined) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

test('escapeHtml escapes potentially harmful HTML characters', () => {
  assert.strictEqual(escapeHtml('<script>alert(1)</script>'), '&lt;script&gt;alert(1)&lt;/script&gt;');
  assert.strictEqual(escapeHtml('Road & Street "Name"'), 'Road &amp; Street &quot;Name&quot;');
  assert.strictEqual(escapeHtml("Barangay's Zone"), 'Barangay&#039;s Zone');
  assert.strictEqual(escapeHtml(null), '');
});
