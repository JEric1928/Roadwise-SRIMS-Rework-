const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('path');
const { pathToFileURL } = require('url');

const utilsUrl = pathToFileURL(path.join(__dirname, 'js/core/utils.js')).href;
const statusUrl = pathToFileURL(path.join(__dirname, 'js/core/status.js')).href;
const apiUrl = pathToFileURL(path.join(__dirname, 'js/core/api.js')).href;
const repairEstimatesUrl = pathToFileURL(path.join(__dirname, 'js/domain/repair-estimates.js')).href;
const timeAgoUrl = pathToFileURL(path.join(__dirname, 'js/core/time-ago.js')).href;
const formatPstUrl = pathToFileURL(path.join(__dirname, 'js/core/format-pst.js')).href;
const auditTimeUrl = pathToFileURL(path.join(__dirname, 'js/core/audit-time.js')).href;
const inventoryUrl = pathToFileURL(path.join(__dirname, 'js/domain/inventory.js')).href;
const activityLogUrl = pathToFileURL(path.join(__dirname, 'js/domain/activity-log.js')).href;
const imageLightboxUrl = pathToFileURL(path.join(__dirname, 'js/features/image-lightbox/index.js')).href;
const liveClockUrl = pathToFileURL(path.join(__dirname, 'js/features/live-clock/index.js')).href;
const notificationsUrl = pathToFileURL(path.join(__dirname, 'js/features/notifications/index.js')).href;
const authUrl = pathToFileURL(path.join(__dirname, 'js/features/auth/index.js')).href;
const adminDashboardUrl = pathToFileURL(path.join(__dirname, 'js/features/admin-dashboard/index.js')).href;
const barangayDashboardUrl = pathToFileURL(path.join(__dirname, 'js/features/barangay-dashboard/index.js')).href;
const reportSubmissionUrl = pathToFileURL(path.join(__dirname, 'js/features/report-submission/index.js')).href;
const priorityDispatchUrl = pathToFileURL(path.join(__dirname, 'js/features/priority-dispatch/index.js')).href;
const repairTrackingUrl = pathToFileURL(path.join(__dirname, 'js/features/repair-tracking/index.js')).href;
const mapEngineUrl = pathToFileURL(path.join(__dirname, 'js/features/map-engine/index.js')).href;

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

// Test 17: Admin dashboard feature window compatibility bridges
test('admin dashboard module exposes exports and sets up window compatibility bridges', async () => {
  const originals = { window: globalThis.window, document: globalThis.document };
  globalThis.window = {};
  globalThis.document = { getElementById: () => null, querySelector: () => null, addEventListener: () => {} };
  try {
    const mod = await import(adminDashboardUrl);
    assert.strictEqual(typeof mod.loadAdminDashboardData, 'function');
    assert.strictEqual(typeof globalThis.window.reviewReport, 'function');
    assert.strictEqual(typeof globalThis.window.loadAdminReports, 'function');
    assert.strictEqual(typeof globalThis.window.loadAdminDashboardData, 'function');
    assert.strictEqual(typeof globalThis.window.jumpToReportsAndReview, 'function');
    assert.strictEqual(typeof globalThis.window.jumpToAllReports, 'function');
    assert.strictEqual(typeof globalThis.window.jumpToArchiveTab, 'function');
  } finally {
    for (const [key, value] of Object.entries(originals)) {
      if (value === undefined) delete globalThis[key];
      else globalThis[key] = value;
    }
  }
});

// Test 23: Integration connections regression tests
test('integration connections expose generatePriorityList and global GeoJSON cache', async () => {
  const originals = { window: globalThis.window, document: globalThis.document };
  globalThis.window = {};
  globalThis.document = { getElementById: () => null, addEventListener: () => {} };
  try {
    await import(mapEngineUrl);
    assert.strictEqual('sjdmGeoJsonData' in globalThis.window, true, 'window.sjdmGeoJsonData property should exist');
    assert.strictEqual('ceoGeoJsonData' in globalThis.window, true, 'window.ceoGeoJsonData property should exist');

    await import(pathToFileURL(path.join(__dirname, 'js/features/priority-report/index.js')).href);
    assert.strictEqual(typeof globalThis.window.generatePriorityList, 'function', 'generatePriorityList should be exposed on window');
  } finally {
    for (const [key, value] of Object.entries(originals)) {
      if (value === undefined) delete globalThis[key];
      else globalThis[key] = value;
    }
  }
});

// Test 18: Barangay dashboard feature window compatibility bridges
test('barangay dashboard module exposes exports and sets up window compatibility bridges', async () => {
  const originals = { window: globalThis.window, document: globalThis.document };
  globalThis.window = {};
  globalThis.document = { getElementById: () => null, addEventListener: () => {} };
  try {
    const mod = await import(barangayDashboardUrl);
    assert.strictEqual(typeof mod.loadBarangayReports, 'function');
    assert.strictEqual(typeof globalThis.window.openViewModal, 'function');
    assert.strictEqual(typeof globalThis.window.openEditModal, 'function');
    assert.strictEqual(typeof globalThis.window.closeBdModals, 'function');
    assert.strictEqual(typeof globalThis.window.submitEditedReport, 'function');
    assert.strictEqual(typeof globalThis.window.loadBarangayReports, 'function');
  } finally {
    for (const [key, value] of Object.entries(originals)) {
      if (value === undefined) delete globalThis[key];
      else globalThis[key] = value;
    }
  }
});

// Test 19: Report submission feature window compatibility bridges
test('report submission module exposes exports and sets up window compatibility bridges', async () => {
  const originals = { window: globalThis.window, document: globalThis.document };
  globalThis.window = {};
  globalThis.document = { getElementById: () => null, addEventListener: () => {} };
  try {
    const mod = await import(reportSubmissionUrl);
    assert.strictEqual(typeof mod.submitRoadReport, 'function');
    assert.strictEqual(typeof mod.executeFinalSubmission, 'function');
    assert.strictEqual(typeof globalThis.window.toggleOtherDamageType, 'function');
    assert.strictEqual(typeof globalThis.window.submitRoadReport, 'function');
    assert.strictEqual(typeof globalThis.window.closeConfirmModal, 'function');
    assert.strictEqual(typeof globalThis.window.executeFinalSubmission, 'function');
    assert.strictEqual(typeof globalThis.window.resetAddReportForm, 'function');
  } finally {
    for (const [key, value] of Object.entries(originals)) {
      if (value === undefined) delete globalThis[key];
      else globalThis[key] = value;
    }
  }
});

// Test 20: Priority dispatch feature window compatibility bridges
test('priority dispatch module exposes exports and sets up window compatibility bridges', async () => {
  const originals = { window: globalThis.window, document: globalThis.document };
  globalThis.window = {};
  globalThis.document = { getElementById: () => null, addEventListener: () => {} };
  try {
    const mod = await import(priorityDispatchUrl);
    assert.strictEqual(typeof mod.executePriorityDispatch, 'function');
    assert.strictEqual(typeof globalThis.window.executePriorityDispatch, 'function');
  } finally {
    for (const [key, value] of Object.entries(originals)) {
      if (value === undefined) delete globalThis[key];
      else globalThis[key] = value;
    }
  }
});

// Test 21: Repair tracking feature window compatibility bridges
test('repair tracking module exposes exports and sets up window compatibility bridges', async () => {
  const originals = { window: globalThis.window, document: globalThis.document };
  globalThis.window = {};
  globalThis.document = { getElementById: () => null, addEventListener: () => {} };
  try {
    const mod = await import(repairTrackingUrl);
    assert.strictEqual(typeof mod.loadTrackingData, 'function');
    assert.strictEqual(typeof globalThis.window.loadTrackingData, 'function');
    assert.strictEqual(typeof globalThis.window.openTrackingModal, 'function');
    assert.strictEqual(typeof globalThis.window.toggleTrackTimeline, 'function');
    assert.strictEqual(typeof globalThis.window.handleApproveProject, 'function');
    assert.strictEqual(typeof globalThis.window.submitReworkFeedback, 'function');
  } finally {
    for (const [key, value] of Object.entries(originals)) {
      if (value === undefined) delete globalThis[key];
      else globalThis[key] = value;
    }
  }
});

// Test 22: Map engine feature exports and ray casting
test('map engine module exposes point-in-polygon logic and global map loaders', async () => {
  const originals = { window: globalThis.window, document: globalThis.document };
  globalThis.window = {};
  globalThis.document = { getElementById: () => null, addEventListener: () => {} };
  try {
    const mod = await import(mapEngineUrl);
    assert.strictEqual(typeof mod.isPointInsidePolygon, 'function');
    assert.strictEqual(typeof mod.loadAdminGlobalMap, 'function');
    assert.strictEqual(typeof mod.loadCEOGlobalMap, 'function');
    assert.strictEqual(typeof mod.loadBarangayLocalMap, 'function');

    const squarePoly = [[0, 0], [0, 10], [10, 10], [10, 0]];
    assert.strictEqual(mod.isPointInsidePolygon(5, 5, squarePoly), true);
    assert.strictEqual(mod.isPointInsidePolygon(15, 15, squarePoly), false);
  } finally {
    for (const [key, value] of Object.entries(originals)) {
      if (value === undefined) delete globalThis[key];
      else globalThis[key] = value;
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

// Test 8: Production relative-time formatter, extracted from notifications
test('timeAgo returns relative labels with original thresholds', async () => {
  const { timeAgo } = await import(timeAgoUrl);
  const now = Date.now();
  assert.strictEqual(timeAgo(new Date(now).toISOString()), 'Just now');
  assert.strictEqual(timeAgo(new Date(now + 60000).toISOString()), 'Just now'); // clock-skew clamp
  assert.strictEqual(timeAgo(new Date(now - 60000).toISOString()), '1 minute ago');
  assert.strictEqual(timeAgo(new Date(now - 5 * 60000).toISOString()), '5 minutes ago');
  assert.strictEqual(timeAgo(new Date(now - 3600000).toISOString()), '1 hour ago');
  assert.strictEqual(timeAgo(new Date(now - 3 * 3600000).toISOString()), '3 hours ago');
  assert.strictEqual(timeAgo(new Date(now - 25 * 3600000).toISOString()), 'Yesterday');
  assert.strictEqual(timeAgo(new Date(now - 73 * 3600000).toISOString()), '3 days ago');
});

test('timeAgo treats timezone-less timestamps as UTC and supports array serialization', async () => {
  const { timeAgo } = await import(timeAgoUrl);
  assert.strictEqual(timeAgo('2024-01-15T10:30:00'), timeAgo('2024-01-15T18:30:00+08:00'));
  assert.strictEqual(timeAgo('2024-01-15 10:30:00'), timeAgo('2024-01-15T10:30:00Z'));
  assert.strictEqual(timeAgo([2024, 1, 15, 10, 30, 0]), timeAgo('2024-01-15T10:30:00Z'));
});

test('timeAgo returns an empty string for missing or invalid input', async () => {
  const { timeAgo } = await import(timeAgoUrl);
  assert.strictEqual(timeAgo(null), '');
  assert.strictEqual(timeAgo(undefined), '');
  assert.strictEqual(timeAgo(''), '');
  assert.strictEqual(timeAgo('not-a-date'), '');
});

// Test 9: Production PST timestamp formatter
test('formatPST formats timestamps in Asia/Manila without double-shifting', async () => {
  const { formatPST } = await import(formatPstUrl);
  assert.strictEqual(formatPST(null), 'N/A');
  assert.strictEqual(formatPST(''), 'N/A');
  assert.strictEqual(formatPST('not-a-date'), 'N/A');
  assert.strictEqual(formatPST('2024-01-15 10:30:00'), 'Jan 15, 2024, 10:30 AM');
  assert.strictEqual(formatPST('2024-01-15T10:30:00'), 'Jan 15, 2024, 10:30 AM');
  assert.strictEqual(formatPST('2024-01-15T10:30:00+08:00'), 'Jan 15, 2024, 10:30 AM');
  assert.strictEqual(formatPST('2024-01-15T02:30:00Z'), 'Jan 15, 2024, 10:30 AM');
  assert.strictEqual(formatPST('2024-01-15T23:30:00Z'), 'Jan 16, 2024, 07:30 AM');
  assert.strictEqual(formatPST('2024-01-15T05:30:00-05:00'), 'Jan 15, 2024, 06:30 PM');
});

test('formatPST omits time when includeTime is false and preserves date-only behavior', async () => {
  const { formatPST } = await import(formatPstUrl);
  assert.strictEqual(formatPST('2024-01-15T02:30:00Z', false), 'Jan 15, 2024');
  // A date-only string gains +08:00 and parses as invalid, matching production.
  assert.strictEqual(formatPST('2024-01-15', false), 'N/A');
});

// Test 10: Production audit-log timestamp helpers
test('parseAuditTimestamp treats naive timestamps as UTC and supports arrays', async () => {
  const { parseAuditTimestamp } = await import(auditTimeUrl);
  assert.strictEqual(parseAuditTimestamp(null), null);
  assert.strictEqual(parseAuditTimestamp(''), null);
  assert.strictEqual(parseAuditTimestamp('2024-01-15 10:30:00').toISOString(), '2024-01-15T10:30:00.000Z');
  assert.strictEqual(parseAuditTimestamp(' 2024-01-15T10:30:00 ').toISOString(), '2024-01-15T10:30:00.000Z');
  assert.strictEqual(parseAuditTimestamp('2024-01-15T18:30:00+08:00').toISOString(), '2024-01-15T10:30:00.000Z');
  assert.strictEqual(parseAuditTimestamp([2024, 1, 15, 10, 30, 0]).toISOString(), '2024-01-15T10:30:00.000Z');
  assert.strictEqual(parseAuditTimestamp([2024, 1, 15]).toISOString(), '2024-01-15T00:00:00.000Z');
  assert.ok(isNaN(parseAuditTimestamp('not-a-date').getTime()));
});

test('formatAuditTimestamp renders PHT date and time including seconds', async () => {
  const { formatAuditTimestamp } = await import(auditTimeUrl);
  assert.strictEqual(formatAuditTimestamp(null), 'N/A');
  assert.strictEqual(formatAuditTimestamp('not-a-date'), 'N/A');
  assert.strictEqual(formatAuditTimestamp('2024-01-15T02:30:00Z'), 'Jan 15, 2024 10:30:00 AM');
  assert.strictEqual(formatAuditTimestamp('2024-01-15 02:30:00'), 'Jan 15, 2024 10:30:00 AM');
  assert.strictEqual(formatAuditTimestamp([2024, 1, 15, 2, 30, 0]), 'Jan 15, 2024 10:30:00 AM');
});

// Test 11: Production inventory helpers
test('parseToKilometers preserves thresholds and invalid input handling', async () => {
  const { parseToKilometers } = await import(inventoryUrl);
  assert.strictEqual(parseToKilometers(null), 0);
  assert.strictEqual(parseToKilometers(''), 0);
  assert.strictEqual(parseToKilometers('abc'), 0);
  assert.strictEqual(parseToKilometers(15), 15);
  assert.strictEqual(parseToKilometers('20'), 20);
  assert.strictEqual(parseToKilometers(20.5), 0.0205);
  assert.strictEqual(parseToKilometers('1500'), 1.5);
});

test('formatTerrainType normalizes terrain variants', async () => {
  const { formatTerrainType } = await import(inventoryUrl);
  assert.strictEqual(formatTerrainType(null), 'FLAT');
  assert.strictEqual(formatTerrainType('   '), 'FLAT');
  assert.strictEqual(formatTerrainType(' flat '), 'FLAT');
  assert.strictEqual(formatTerrainType('Mountainous'), 'MOUNTAINOUS');
  assert.strictEqual(formatTerrainType('rolling hills'), 'ROLLING');
});

test('getReportYear and getReportYearSettings preserve their fallback differences', async () => {
  const { getReportYear, getReportYearSettings } = await import(inventoryUrl);
  assert.strictEqual(getReportYear(null), '');
  assert.strictEqual(getReportYear({ inventoryYear: 2024 }), '2024');
  assert.strictEqual(getReportYear({ inventory_year: ' 2023 ' }), '2023');
  assert.strictEqual(getReportYear({ inventoryYear: 'undefined', dateSubmitted: [2022, 5, 1] }), '2022');
  assert.strictEqual(getReportYear({ dateSubmitted: '2021-06-15T12:00:00Z' }), '2021');
  assert.strictEqual(getReportYearSettings({ inventoryYear: 'undefined' }), 'undefined');
  assert.strictEqual(getReportYearSettings({ inventoryYear: 'null' }), '');
});

test('formatInventoryDate formats array and string dates with local display rules', async () => {
  const { formatInventoryDate } = await import(inventoryUrl);
  assert.strictEqual(formatInventoryDate(null), 'N/A');
  assert.strictEqual(formatInventoryDate({}), 'N/A');
  assert.strictEqual(formatInventoryDate({ dateSubmitted: 'invalid' }), 'invalid');

  const expectedArray = new Date('2024-01-15').toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
  assert.strictEqual(formatInventoryDate({ dateSubmitted: [2024, 1, 15, 10, 30] }), expectedArray);

  const expectedString = new Date('2024-06-15T12:00:00Z').toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
  assert.strictEqual(formatInventoryDate({ dateSubmitted: '2024-06-15T12:00:00Z' }), expectedString);
});

// Test 12: Production activity-log formatters
test('formatTargetEntity pads IDs, renders emails, and escapes text', async () => {
  const { formatTargetEntity } = await import(activityLogUrl);
  assert.deepStrictEqual(formatTargetEntity(null), { html: '<span style="color: #94a3b8;">—</span>', text: '—' });
  assert.deepStrictEqual(formatTargetEntity('N/A'), { html: '<span style="color: #94a3b8;">—</span>', text: '—' });
  assert.deepStrictEqual(formatTargetEntity('-'), { html: '<span style="color: #94a3b8;">—</span>', text: '—' });
  assert.strictEqual(formatTargetEntity('#USR-1').text, '#USR-0001');
  assert.ok(formatTargetEntity('#USR-1').html.includes('#USR-0001'));
  assert.strictEqual(formatTargetEntity('#prj-42').text, '#PRJ-0042');
  assert.strictEqual(formatTargetEntity('user@example.com').text, 'user@example.com');
  assert.ok(formatTargetEntity('user@example.com').html.includes('user@example.com'));
  assert.strictEqual(formatTargetEntity('SYSTEM').text, 'SYSTEM');
  assert.ok(formatTargetEntity('<b>x</b>').html.includes('&lt;b&gt;x&lt;/b&gt;'));
  assert.ok(formatTargetEntity('#<script>').html.includes('&lt;script&gt;'));
});

test('getCategoryBadge maps activity categories to HTML badges', async () => {
  const { getCategoryBadge } = await import(activityLogUrl);
  assert.ok(getCategoryBadge('PROJECT').includes('PROJECT'));
  assert.ok(getCategoryBadge('qa').includes('QA & REVIEW'));
  assert.ok(getCategoryBadge('export').includes('EXPORT'));
  assert.ok(getCategoryBadge('auth').includes('SECURITY'));
  assert.ok(getCategoryBadge('user').includes('USER MGMT'));
  assert.ok(getCategoryBadge(null).includes('SYSTEM'));
  assert.ok(getCategoryBadge('unknown').includes('SYSTEM'));
});

test('getStatusBadge maps audit statuses to HTML badges', async () => {
  const { getStatusBadge } = await import(activityLogUrl);
  assert.ok(getStatusBadge('FAILED').includes('FAILED'));
  assert.ok(getStatusBadge('warning').includes('WARNING'));
  assert.ok(getStatusBadge('SUCCESS').includes('SUCCESS'));
  assert.ok(getStatusBadge(null).includes('SUCCESS'));
});

// Test 13: Image lightbox feature (headless: bridge + listener lifecycle only)
test('image lightbox installs the bridge once and skips placeholder sources', async () => {
  const originalWindow = globalThis.window;
  const originalDocument = globalThis.document;
  let escapeAdds = 0;
  let escapeRemovals = 0;
  globalThis.window = {};
  globalThis.document = {
    addEventListener: () => { escapeAdds++; },
    removeEventListener: () => { escapeRemovals++; }
  };
  try {
    const { initImageLightbox, destroyImageLightbox, openFullscreenImage } = await import(imageLightboxUrl);
    initImageLightbox();
    initImageLightbox(); // idempotent: must not bind twice
    assert.strictEqual(escapeAdds, 1, 'Escape listener should be bound once');
    assert.strictEqual(globalThis.window.openFullscreenImage, openFullscreenImage);
    assert.strictEqual(typeof globalThis.window.closeFullscreenImage, 'function');
    assert.strictEqual(globalThis.window.openFullscreenImage('https://placehold.co/500x300'), undefined);
    assert.strictEqual(globalThis.window.openFullscreenImage(''), undefined);
    destroyImageLightbox();
    assert.strictEqual(escapeRemovals, 1, 'cleanup should unbind the Escape listener');
    destroyImageLightbox(); // second cleanup is a no-op
    assert.strictEqual(escapeRemovals, 1);
  } finally {
    if (originalWindow === undefined) delete globalThis.window;
    else globalThis.window = originalWindow;
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

// Test 14: Live clock feature (headless: interval lifecycle only)
test('live clock starts once and cleans up its interval', async () => {
  const originalDocument = globalThis.document;
  const originalSetInterval = globalThis.setInterval;
  const originalClearInterval = globalThis.clearInterval;
  let intervalCalls = 0;
  let clearCalls = 0;
  let rendered = '';
  globalThis.document = {
    getElementById: (id) => (id === 'live-clock-display'
      ? { set innerHTML(value) { rendered = value; }, get innerHTML() { return rendered; } }
      : null)
  };
  globalThis.setInterval = () => { intervalCalls++; return 'timer-1'; };
  globalThis.clearInterval = () => { clearCalls++; };
  try {
    const { initLiveClock, destroyLiveClock } = await import(liveClockUrl);
    initLiveClock();
    initLiveClock(); // idempotent: must not start a second interval
    assert.strictEqual(intervalCalls, 1, 'interval should be created once');
    assert.ok(rendered.includes('|'), 'clock markup should render immediately');
    destroyLiveClock();
    assert.strictEqual(clearCalls, 1, 'cleanup should clear the interval');
    destroyLiveClock(); // second cleanup is a no-op
    assert.strictEqual(clearCalls, 1);
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
    globalThis.setInterval = originalSetInterval;
    globalThis.clearInterval = originalClearInterval;
  }
});

// Test 15: Notifications feature entry points (headless smoke test)
test('notifications module exposes its entry points without browser globals', async () => {
  const originalSessionStorage = globalThis.sessionStorage;
  globalThis.sessionStorage = { getItem: () => null };
  try {
    const mod = await import(notificationsUrl);
    assert.deepStrictEqual(Object.keys(mod).sort(), ['initNotifications', 'refreshNotifications']);
    assert.strictEqual(typeof mod.initNotifications, 'function');
    assert.strictEqual(typeof mod.refreshNotifications, 'function');
  } finally {
    if (originalSessionStorage === undefined) delete globalThis.sessionStorage;
    else globalThis.sessionStorage = originalSessionStorage;
  }
});

// Test 16: Auth feature (headless: exports, password rule, idempotent init)
test('auth module exposes entry points, password rule, and idempotent init', async () => {
  const originals = {
    sessionStorage: globalThis.sessionStorage,
    localStorage: globalThis.localStorage,
    window: globalThis.window,
    document: globalThis.document
  };
  let enterAdds = 0;
  const fakeInput = { addEventListener: () => { enterAdds++; }, focus() {} };
  const fakeSteps = Object.fromEntries(
    ['login-step-1', 'login-step-2', 'forgot-step-1', 'forgot-step-2'].map((id) => {
      const classes = new Set(id === 'login-step-1' ? [] : ['hidden']);
      return [id, {
        classList: {
          toggle(name, force) {
            if (force) classes.add(name);
            else classes.delete(name);
          },
          contains: (name) => classes.has(name)
        },
        style: { removeProperty() {} }
      }];
    })
  );
  globalThis.sessionStorage = { getItem: () => null, setItem() {}, removeItem() {} };
  globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
  globalThis.window = { location: { hostname: 'localhost' } };
  globalThis.document = {
    getElementById: (id) => (id === 'username' || id === 'password' ? fakeInput : fakeSteps[id] || null)
  };
  try {
    const mod = await import(authUrl);
    for (const name of [
      'handleLogin', 'handleVerifyMfa', 'showForgotPassword',
      'handleForgotPasswordRequest', 'handlePasswordReset', 'backToLogin', 'initAuth'
    ]) {
      assert.strictEqual(typeof mod[name], 'function', `${name} should be exported`);
    }
    assert.ok(mod.STRICT_PASSWORD_REGEX.test('Passw0rd!'));
    assert.ok(mod.STRICT_PASSWORD_REGEX.test('Abcdef1#'));
    assert.ok(!mod.STRICT_PASSWORD_REGEX.test('password1!'), 'needs an uppercase letter');
    assert.ok(!mod.STRICT_PASSWORD_REGEX.test('Password!!'), 'needs a digit');
    assert.ok(!mod.STRICT_PASSWORD_REGEX.test('Password12'), 'needs a special character');
    assert.ok(!mod.STRICT_PASSWORD_REGEX.test('Pw1!'), 'needs eight characters');

    mod.initAuth();
    mod.initAuth(); // idempotent: must not bind again
    assert.strictEqual(enterAdds, 2, 'username and password bound once each');
    assert.strictEqual(globalThis.window.handleLogin, mod.handleLogin);
    assert.strictEqual(globalThis.window.backToLogin, mod.backToLogin);

    mod.showForgotPassword();
    assert.ok(fakeSteps['login-step-1'].classList.contains('hidden'));
    assert.ok(fakeSteps['forgot-step-1'].classList.contains('hidden') === false);
    assert.ok(fakeSteps['forgot-step-2'].classList.contains('hidden'));

    mod.backToLogin();
    assert.ok(fakeSteps['login-step-1'].classList.contains('hidden') === false);
    assert.ok(fakeSteps['forgot-step-1'].classList.contains('hidden'));
    assert.ok(fakeSteps['forgot-step-2'].classList.contains('hidden'));
  } finally {
    for (const [key, value] of Object.entries(originals)) {
      if (value === undefined) delete globalThis[key];
      else globalThis[key] = value;
    }
  }
});

// ==========================================
// WEBAUTHN / STEP-UP SUPPORT (passkey feature)
// ==========================================

const webauthnUrl = pathToFileURL(path.join(__dirname, 'js/core/webauthn.js')).href;
const stepUpUrl = pathToFileURL(path.join(__dirname, 'js/core/step-up.js')).href;
const passkeysUrl = pathToFileURL(path.join(__dirname, 'js/features/passkeys/index.js')).href;

// Test 17: base64url conversions are the wire contract with the server.
test('webauthn base64url helpers round-trip binary wire fields', async () => {
  const mod = await import(webauthnUrl);

  assert.strictEqual(new TextDecoder().decode(mod.base64UrlToBytes('SGVsbG8')), 'Hello');
  assert.strictEqual(mod.bytesToBase64Url(Uint8Array.from([0, 1, 2, 3, 4, 5, 6, 7])), 'AAECAwQFBgc');

  // Padding is tolerated on input and never emitted: the spec fields the server
  // serialises (challenges, ids) are unpadded base64url.
  const bytes = mod.base64UrlToBytes('AQIDBAUGBwgJCgsMDQ4PEA==');
  assert.strictEqual(bytes.length, 16);
  assert.strictEqual(mod.bytesToBase64Url(bytes), 'AQIDBAUGBwgJCgsMDQ4PEA');
  assert.ok(!mod.bytesToBase64Url(bytes).includes('='), 'output must be unpadded');

  // url-safe alphabet: 62 -> '-', 63 -> '_' (not '+' and '/')
  assert.strictEqual(mod.bytesToBase64Url(Uint8Array.from([251, 255])), '-_8');

  assert.throws(() => mod.base64UrlToBytes('not+standard'), /Invalid base64url character/);
  assert.throws(() => mod.base64UrlToBytes(42), /Expected a base64url string/);
});

// Test 18: option preparation decodes only the binary fields, whitelists the rest.
test('webauthn option preparation decodes binary fields and drops unknown ones', async () => {
  const mod = await import(webauthnUrl);

  const request = mod.prepareRequestOptions({
    rpId: 'localhost',
    challenge: 'AQIDBAUGBwgJCgsMDQ4PEA',
    allowCredentials: [{ type: 'public-key', id: 'AQIDBAUGBwgJCgsMDQ4PEA' }],
    userVerification: 'preferred',
    futureFieldTheBrowserDoesNotKnow: 'ignored'
  });
  assert.ok(request.challenge instanceof ArrayBuffer);
  assert.strictEqual(request.challenge.byteLength, 16);
  assert.strictEqual(request.rpId, 'localhost');
  assert.ok(request.allowCredentials[0].id instanceof ArrayBuffer);
  assert.strictEqual(request.allowCredentials[0].id.byteLength, 16);
  assert.ok(!('futureFieldTheBrowserDoesNotKnow' in request));

  const creation = mod.prepareCreationOptions({
    rp: { id: 'localhost', name: 'RoadWise' },
    user: { id: 'AQIDBAUGBwgJCgsMDQ4PEA', name: 'jdoe', displayName: 'J Doe' },
    challenge: 'AQIDBAUGBwgJCgsMDQ4PEA',
    pubKeyCredParams: [{ type: 'public-key', alg: -7 }],
    authenticatorSelection: { userVerification: 'preferred' }
  });
  assert.strictEqual(creation.challenge.byteLength, 16);
  assert.strictEqual(creation.user.id.byteLength, 16);
  assert.strictEqual(creation.rp.name, 'RoadWise');
  assert.deepStrictEqual(creation.pubKeyCredParams, [{ type: 'public-key', alg: -7 }]);

  assert.throws(() => mod.prepareCreationOptions({ user: { id: 'AQID' } }), /challenge is required/);
  assert.throws(() => mod.prepareCreationOptions({ challenge: 'AQID' }), /user entity/);
  assert.throws(() => mod.prepareRequestOptions(null), /Request options are required/);
});

// Test 19: authenticator responses serialise into the exact shape the server DTO accepts.
test('webauthn serialises authenticator responses into the server wire shape', async () => {
  const mod = await import(webauthnUrl);

  const base = {
    id: 'AQIDBAUGBwgJCgsMDQ4PEA',
    rawId: Uint8Array.from([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16]),
    type: 'public-key',
    getClientExtensionResults: () => ({ credProps: { rk: true } })
  };

  const assertion = mod.assertionToJson({
    ...base,
    response: {
      clientDataJSON: Uint8Array.from([123, 125]),
      authenticatorData: Uint8Array.from([1]),
      signature: Uint8Array.from([255]),
      userHandle: null
    }
  });
  assert.strictEqual(assertion.response.clientDataJSON, 'e30');
  assert.strictEqual(assertion.response.signature, '_w');
  assert.strictEqual(assertion.rawId, 'AQIDBAUGBwgJCgsMDQ4PEA');
  assert.deepStrictEqual(assertion.clientExtensionResults, { credProps: { rk: true } });
  // A null userHandle must not reach the wire: JSON.stringify drops the
  // undefined entry, which is what the server's mapper sees.
  assert.strictEqual(assertion.response.userHandle, undefined);
  assert.ok(!JSON.stringify(assertion).includes('userHandle'), 'a null userHandle must not reach the wire');

  const registration = mod.registrationToJson({
    ...base,
    response: {
      clientDataJSON: Uint8Array.from([123, 125]),
      attestationObject: Uint8Array.from([0]),
      getTransports: () => ['internal']
    }
  });
  assert.strictEqual(registration.response.attestationObject, 'AA');
  assert.deepStrictEqual(registration.response.transports, ['internal']);
  assert.strictEqual(registration.response.signature, undefined, 'attestation carries no signature');

  assert.throws(() => mod.assertionToJson({ id: 'x' }), /no assertion response/);

  assert.match(mod.describeCredentialError({ name: 'NotAllowedError' }), /dismissed or timed out/);
  assert.match(mod.describeCredentialError({ name: 'InvalidStateError' }), /already registered/);
  assert.strictEqual(mod.describeCredentialError({ name: 'UnexpectedError', message: 'boom' }), 'boom');
});

// Test 20: only 403 + STEP_UP_REQUIRED triggers the re-authentication prompt.
test('step-up challenge detection matches only 403 with the STEP_UP_REQUIRED code', async () => {
  const mod = await import(stepUpUrl);

  assert.strictEqual(mod.isStepUpChallenge(403, { code: 'STEP_UP_REQUIRED' }), true);
  assert.strictEqual(mod.isStepUpChallenge('403', { code: 'STEP_UP_REQUIRED' }), true);
  assert.strictEqual(mod.isStepUpChallenge(403, { error: 'Access denied.' }), false);
  assert.strictEqual(mod.isStepUpChallenge(403, null), false);
  assert.strictEqual(mod.isStepUpChallenge(401, { code: 'STEP_UP_REQUIRED' }), false);
  assert.strictEqual(mod.isStepUpChallenge(200, { code: 'STEP_UP_REQUIRED' }), false);
});

// Test 21: apiFetch surfaces structured codes and refuses honestly when the
// step-up prompt cannot be shown (headless: no document to render into).
test('apiFetch carries response codes and never silently drops a step-up demand', async () => {
  const originals = { fetch: globalThis.fetch };
  try {
    const { apiFetch } = await import(apiUrl);

    globalThis.fetch = async () => ({
      ok: false,
      status: 403,
      headers: { get: (name) => (String(name).toLowerCase() === 'content-type' ? 'application/json' : null) },
      clone: () => ({
        json: async () => ({ error: 'Please re-enter your password to continue.', code: 'STEP_UP_REQUIRED' })
      })
    });

    await assert.rejects(
      () => apiFetch('/api/auth/mfa/factors/passkeys/options', { method: 'POST' }),
      (error) => {
        assert.strictEqual(error.status, 403);
        assert.strictEqual(error.code, 'STEP_UP_REQUIRED');
        assert.match(error.message, /re-enter your password/i);
        return true;
      }
    );

    globalThis.fetch = async () => ({
      ok: false,
      status: 401,
      headers: { get: () => 'application/json' },
      clone: () => ({ json: async () => ({ error: 'Session expired.' }) })
    });
    await assert.rejects(
      () => apiFetch('/api/auth/mfa/overview'),
      (error) => {
        assert.strictEqual(error.code, 'SESSION_EXPIRED');
        return true;
      }
    );
  } finally {
    if (originals.fetch === undefined) delete globalThis.fetch;
    else globalThis.fetch = originals.fetch;
  }
});

// Test 22: passkeys feature entry points (headless smoke test)
test('passkeys module exposes its entry points and no-ops without a panel', async () => {
  const originals = { window: globalThis.window, document: globalThis.document };
  globalThis.window = {};
  globalThis.document = { getElementById: () => null };
  try {
    const mod = await import(passkeysUrl);
    assert.deepStrictEqual(Object.keys(mod).sort(), ['initPasskeySettings', 'refreshPasskeys']);
    assert.strictEqual(typeof mod.initPasskeySettings, 'function');
    assert.strictEqual(typeof mod.refreshPasskeys, 'function');

    // No #passkey-settings-card on this page: init must not throw.
    mod.initPasskeySettings();
    assert.strictEqual(globalThis.window.refreshPasskeys, mod.refreshPasskeys);
  } finally {
    for (const [key, value] of Object.entries(originals)) {
      if (value === undefined) delete globalThis[key];
      else globalThis[key] = value;
    }
  }
});
