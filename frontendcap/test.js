const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('path');
const { pathToFileURL } = require('url');

const utilsUrl = pathToFileURL(path.join(__dirname, 'js/core/utils.js')).href;
const statusUrl = pathToFileURL(path.join(__dirname, 'js/core/status.js')).href;
const apiUrl = pathToFileURL(path.join(__dirname, 'js/core/api.js')).href;
const repairEstimatesUrl = pathToFileURL(path.join(__dirname, 'js/domain/repair-estimates.js')).href;

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

// Test 2: Production API error formatter
test('getErrorMessage returns appropriate status messages', async () => {
  const { getErrorMessage } = await import(apiUrl);
  assert.strictEqual(getErrorMessage(401), 'Authentication required. Please log in again.');
  assert.strictEqual(getErrorMessage(403), 'Access denied. You do not have permission for this action.');
  assert.strictEqual(getErrorMessage(404), 'Requested resource or endpoint was not found.');
  assert.strictEqual(getErrorMessage(409), 'A conflict occurred with the existing data.');
  assert.strictEqual(getErrorMessage(500), 'Server error. Please try again or contact system support.');
  assert.strictEqual(getErrorMessage(418, 'Custom error'), 'Custom error');
  assert.strictEqual(getErrorMessage(418), 'Request failed');
  assert.strictEqual(getErrorMessage('401', 'String status'), 'String status');
});

// Test 3: Production repair estimator, without app startup.

const damageGroups = [
  [['pothole', 'lubak'], 3, 'Pothole Patching (3 days)'],
  [['surface', 'cracking', 'alligator', 'longitudinal'], 7, 'Surface Crack Sealing & Resurfacing (7 days)'],
  [['edge', 'deformation', 'rutting'], 10, 'Edge Rebuilding & Base Compaction (10 days)'],
  [['washout', 'sinkhole', 'slab', 'concrete'], 21, 'Major Structural Restoration & Curing (21 days)']
];

for (const [aliases, days, label] of damageGroups) {
  test(`calculateEstimatedDays recognizes ${aliases.join(', ')} before severity and area`, async () => {
    const { calculateEstimatedDays } = await import(repairEstimatesUrl);
    for (const alias of aliases) {
      assert.deepStrictEqual(calculateEstimatedDays(`Road ${alias.toUpperCase()} damage`, 'high', 100), { days, label });
    }
  });
}

test('calculateEstimatedDays preserves first-match damage precedence', async () => {
  const { calculateEstimatedDays } = await import(repairEstimatesUrl);
  assert.deepStrictEqual(calculateEstimatedDays('Concrete pothole surface', 'high', 100), {
    days: 3, label: 'Pothole Patching (3 days)'
  });
  assert.deepStrictEqual(calculateEstimatedDays('Edge surface cracking', 'high', 100), {
    days: 7, label: 'Surface Crack Sealing & Resurfacing (7 days)'
  });
  assert.deepStrictEqual(calculateEstimatedDays('Concrete edge deformation', 'high', 100), {
    days: 10, label: 'Edge Rebuilding & Base Compaction (10 days)'
  });
});

test('calculateEstimatedDays uses severity before measurement fallback', async () => {
  const { calculateEstimatedDays } = await import(repairEstimatesUrl);
  for (const [severity, days, label] of [
    ['HIGH', 14, 'AI High Severity Scope (14 days)'],
    ['Medium', 7, 'AI Medium Severity Scope (7 days)'],
    ['low', 3, 'AI Low Severity Scope (3 days)']
  ]) {
    for (const area of [0, 100]) {
      assert.deepStrictEqual(calculateEstimatedDays('Other', severity, area), { days, label });
    }
  }
});

test('calculateEstimatedDays preserves strict measurement thresholds and labels', async () => {
  const { calculateEstimatedDays } = await import(repairEstimatesUrl);
  for (const [area, days, label] of [
    [5, 5, 'Standard Maintenance Window (5 days)'],
    [5.01, 7, 'Moderate Damage Area (7 days)'],
    [20, 7, 'Moderate Damage Area (7 days)'],
    [20.01, 14, 'Large Damage Area > 20 sq.m (14 days)'],
    ['6.5 sq.m', 7, 'Moderate Damage Area (7 days)'],
    ['21', 14, 'Large Damage Area > 20 sq.m (14 days)']
  ]) {
    assert.deepStrictEqual(calculateEstimatedDays('Other', 'unknown', area), { days, label });
  }
});

test('calculateEstimatedDays preserves missing inputs and untrimmed severity', async () => {
  const { calculateEstimatedDays } = await import(repairEstimatesUrl);
  const standard = { days: 5, label: 'Standard Maintenance Window (5 days)' };
  assert.deepStrictEqual(calculateEstimatedDays(), standard);
  for (const area of [null, '', 'invalid', 0, -10]) {
    assert.deepStrictEqual(calculateEstimatedDays(null, null, area), standard);
  }
  assert.deepStrictEqual(calculateEstimatedDays(123, ' high ', 6), {
    days: 7, label: 'Moderate Damage Area (7 days)'
  });
});

// Test 4: Official Name Formatter
test('formatOfficialName formats name with middle initial and prefix', async () => {
  const { formatOfficialName } = await import(utilsUrl);
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

// Test 6: escapeHtml sanitizes special characters from core module
test('escapeHtml escapes potentially harmful HTML characters', async () => {
  const { escapeHtml } = await import(utilsUrl);
  assert.strictEqual(escapeHtml('<script>alert(1)</script>'), '&lt;script&gt;alert(1)&lt;/script&gt;');
  assert.strictEqual(escapeHtml('Road & Street "Name"'), 'Road &amp; Street &quot;Name&quot;');
  assert.strictEqual(escapeHtml("Barangay's Zone"), 'Barangay&#039;s Zone');
  assert.strictEqual(escapeHtml(null), '');
});

// Test 7: Centralized status normalization and badge mapping logic
test('normalizeStatus maps diverse status variations to canonical REPORT_STATUS', async () => {
  const { REPORT_STATUS, normalizeStatus } = await import(statusUrl);
  assert.strictEqual(normalizeStatus('Completed Repair'), REPORT_STATUS.COMPLETED);
  assert.strictEqual(normalizeStatus('in_progress'), REPORT_STATUS.IN_PROGRESS);
  assert.strictEqual(normalizeStatus('dispatched to ceo'), REPORT_STATUS.DISPATCHED);
  assert.strictEqual(normalizeStatus('Pending Validation'), REPORT_STATUS.VALIDATED);
  assert.strictEqual(normalizeStatus('archived item'), REPORT_STATUS.ARCHIVED);
});

test('getStatusBadgeClass returns correct styling classes for report statuses', async () => {
  const { REPORT_STATUS, getStatusBadgeClass } = await import(statusUrl);
  assert.ok(getStatusBadgeClass(REPORT_STATUS.COMPLETED).includes('emerald'));
  assert.ok(getStatusBadgeClass(REPORT_STATUS.IN_PROGRESS).includes('blue'));
  assert.ok(getStatusBadgeClass(REPORT_STATUS.ARCHIVED).includes('slate'));
});
