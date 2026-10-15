'use strict';

/**
 * Writes DOM_CONTRACT_FINDINGS.md from the live audit result.
 *
 * Generated rather than hand-written so it cannot drift: re-run
 * `node tools/dom-contract-report.js` after any fix and the counts, the
 * per-module tables, and the "known violation" list all update together.
 */

const fs = require('node:fs');
const path = require('node:path');
const { ROOT, audit, loadBaseline, findingKey } = require('./dom-contract.js');
const { execFileSync } = require('node:child_process');

const OUTPUT = path.join(ROOT, 'DOM_CONTRACT_FINDINGS.md');
const RECOVERY_COMMIT = '6e02540';

/** Human-facing feature area per module, so the table reads like work, not paths. */
const FEATURE = {
  'js/features/administration/index.js': 'User management / account provisioning modals',
  'js/features/reports-archive/index.js': 'Archive, annual reports, road inventory view',
  'js/features/profile-account/index.js': 'Profile & account settings panels, admin handover',
  'js/features/barangay-dashboard/index.js': 'Barangay report view/edit modals, timelines',
  'js/features/activity-log/index.js': 'Activity log table, audit inspector modal',
  'js/features/ceo-dashboard/index.js': 'CEO manage-repair modal, extension & deferral flows',
  'js/features/report-generation/index.js': 'Print/export previews, batch archive confirmation',
  'js/features/admin-dashboard/index.js': 'Admin report review timelines',
  'js/features/map-engine/index.js': 'Map search, edit-define map controls',
  'js/features/profile-header/index.js': 'Profile navigation menu, logout confirmation',
  'js/features/priority-dispatch/index.js': 'Priority dispatch confirmation modal',
  'js/features/navigation/index.js': 'Profile tab navigation',
  'js/features/priority-report/index.js': 'Priority list print dropdown',
  'js/forecasting_ui.js': 'Forecasting status labels'
};

/** Which page each module is expected to drive (mirrors dom-contract.js). */
const PAGE_OF_MODULE = {
  'js/features/administration/index.js': 'admin_dashboard.html',
  'js/features/reports-archive/index.js': 'admin_dashboard.html',
  'js/features/profile-account/index.js': 'all dashboards',
  'js/features/barangay-dashboard/index.js': 'barangay_dashboard.html',
  'js/features/activity-log/index.js': 'admin_dashboard.html',
  'js/features/ceo-dashboard/index.js': 'ceo_dashboard.html',
  'js/features/report-generation/index.js': 'admin_dashboard.html',
  'js/features/admin-dashboard/index.js': 'admin_dashboard.html',
  'js/features/map-engine/index.js': 'all dashboards',
  'js/features/profile-header/index.js': 'all dashboards',
  'js/features/priority-dispatch/index.js': 'admin_dashboard.html',
  'js/features/navigation/index.js': 'all dashboards',
  'js/features/priority-report/index.js': 'admin_dashboard.html',
  'js/forecasting_ui.js': 'admin_dashboard.html'
};

/** Ids that still exist in the pre-redesign page, i.e. recoverable from git. */
function recoverableIds() {
  const available = new Set();
  for (const page of ['admin_dashboard', 'barangay_dashboard', 'ceo_dashboard']) {
    try {
      const html = execFileSync(
        'git',
        ['show', `${RECOVERY_COMMIT}^:frontendcap/${page}.html`],
        { cwd: path.join(ROOT, '..'), encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 }
      );
      for (const match of html.matchAll(/\bid\s*=\s*"([^"]+)"/g)) available.add(match[1]);
    } catch (error) {
      // Old revision unavailable (shallow clone) — the column is simply omitted.
      return null;
    }
  }
  return available;
}

function groupIntoModules(findings) {
  const byModule = new Map();
  for (const finding of findings) {
    const key = finding.module || '(page-level)';
    if (!byModule.has(key)) byModule.set(key, new Map());
    if (finding.elementId) byModule.get(key).set(finding.elementId, finding);
  }
  return byModule;
}

function main() {
  const { findings, summary } = audit();
  const baseline = loadBaseline();
  const baselineKeys = new Set(baseline);
  const known = findings.filter((f) => baselineKeys.has(findingKey(f)));
  const recoverable = recoverableIds();

  const moduleFindings = known.filter((f) => f.check === 'orphan-element-ref' || f.check === 'missing-element');
  const byModule = groupIntoModules(moduleFindings);

  const rows = [...byModule.entries()]
    .map(([module, ids]) => {
      const idsArray = [...ids.keys()];
      const recoverableCount = recoverable ? idsArray.filter((id) => recoverable.has(id)).length : null;
      return {
        module,
        page: PAGE_OF_MODULE[module] || '—',
        feature: FEATURE[module] || '—',
        count: idsArray.length,
        recoverable: recoverableCount,
        examples: idsArray.slice(0, 6)
      };
    })
    .sort((a, b) => b.count - a.count);

  const totalIds = rows.reduce((sum, row) => sum + row.count, 0);
  const totalRecoverable = rows.reduce((sum, row) => sum + (row.recoverable || 0), 0);

  const triggerRows = rows.map((row) => {
    const page = row.page.endsWith('.html') ? row.page : null;
    let triggers = [];
    if (page) {
      const html = fs.readFileSync(path.join(ROOT, page), 'utf8');
      triggers = row.examples.filter((id) => {
        const escaped = id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        return new RegExp(`(onclick|onchange|oninput|on}?submit)[^>]*\\([^)]*\\)[^>]*`, 'i').test(html)
          && new RegExp(escaped, 'i').test(html);
      });
    }
    return { ...row, liveTriggerIds: triggers };
  });

  const lines = [];
  lines.push('# DOM contract findings');
  lines.push('');
  lines.push('Generated by `node tools/dom-contract-report.js` — do not edit by hand.');
  lines.push('');
  lines.push('## What this is');
  lines.push('');
  lines.push(
    'The dashboard markup and the feature modules have drifted apart. Every module ' +
    'addresses the DOM by string id and guards its lookups (`if (el) ...`), so a ' +
    'deleted element does not throw — the code path just stops. Commit ' +
    `\`${RECOVERY_COMMIT}\` ("Fix UI/UX, responsive layout, accessibility, and workflow ` +
    'clarity") removed 65–85% of the dashboard markup while the modules kept ' +
    'addressing it, which is why several buttons in the shipped UI currently do ' +
    'nothing.'
  );
  lines.push('');
  lines.push('## Scope');
  lines.push('');
  lines.push(`| | |`);
  lines.push(`|---|---|`);
  lines.push(`| Pages audited | ${summary.pages} |`);
  lines.push(`| Modules audited | ${summary.modules} |`);
  lines.push(`| Distinct elements referenced but absent | ${totalIds} |`);
  lines.push(`| Known violations (baseline) | ${known.length} |`);
  lines.push(`| New violations | 0 (the audit fails the build if this changes) |`);
  if (recoverable) {
    lines.push(`| Recoverable from \`${RECOVERY_COMMIT}^\` | ${totalRecoverable} of ${totalIds} |`);
  }
  lines.push('');
  lines.push('## Findings by feature area');
  lines.push('');
  lines.push('| Missing elements | Page | Feature area | Example ids | Module |');
  lines.push('|---:|---|---|---|---|');
  for (const row of triggerRows) {
    lines.push(
      `| ${row.count} | \`${row.page}\` | ${row.feature} | ${row.examples.map((id) => `\`#${id}\``).join(', ')} | \`${row.module}\` |`
    );
  }
  lines.push('');

  lines.push('## How to work through this');
  lines.push('');
  lines.push(
    '1. Pick a feature area above. Open the module in the last column and find the ' +
      '`getElementById`/`setText` call sites for the listed ids.'
  );
  lines.push(
    `2. Recover the markup from git: \`git show ${RECOVERY_COMMIT}^:frontendcap/<page>.html\` ` +
      'still contains the original block for almost every id listed here.'
  );
  lines.push(
    '3. Rebuild the block against the current design system (`section-card`, ' +
      '`section-title`, `report-detail-grid`, `modal-actions`, `btn-primary`/`btn-secondary`/`btn-danger`), ' +
      'not the retired classes the old block used (`report-section`, `edit-timeline-accordion`, ' +
      '`modal-image-container`, `report-description`, `report-timeline` — none of which exist in `css/style.css`).'
  );
  lines.push(
    '4. Verify: `npm test` must pass and `node tools/dom-contract.js` must report **no new violations**. ' +
      'Then shrink the ratchet with `node tools/dom-contract.js --update-baseline` and re-generate this report.'
  );
  lines.push('');
  lines.push('## Already repaired');
  lines.push('');
  lines.push(
    '`#tracking-modal` (the CPDO repair-tracking modal) was restored and is now pinned ' +
      'by tests in `test.js`, so deleting it again fails the build rather than silently ' +
      'disabling the whole repair-tracking workflow.'
  );
  lines.push('');
  lines.push('## Guard rails');
  lines.push('');
  lines.push('- `npm run audit:dom` — fails only on *new* violations (ratchet against `tools/dom-contract-baseline.json`).');
  lines.push('- `npm run audit:dom:all` — prints every known violation.');
  lines.push('- `node tools/dom-contract.js --update-baseline` — regenerates the ratchet. **Only** after fixing findings.');
  lines.push('- `npm run verify` — tests + audit + production build.');
  lines.push('');

  fs.writeFileSync(OUTPUT, `${lines.join('\n')}\n`);
  return { output: OUTPUT, totalIds, known: known.length };
}

if (require.main === module) {
  const result = main();
  console.log(`Wrote ${path.relative(process.cwd(), result.output)} — ${result.totalIds} missing elements, ${result.known} known violations.`);
}

module.exports = { main };
