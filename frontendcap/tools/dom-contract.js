'use strict';

/**
 * DOM contract auditing for the RoadWise frontend.
 *
 * The frontend is a set of static HTML pages plus a webpack bundle whose
 * feature modules reach into the DOM by **string id** (`getElementById('x')`,
 * `setText('x', ...)`, `loadSecureImage('x', ...)`). Nothing type-checks those
 * strings, and every module guards its lookups (`if (!el) return;`), so a
 * missing element fails *silently* at runtime instead of throwing.
 *
 * That is exactly how `#tracking-modal` disappeared from `admin_dashboard.html`
 * in commit 6e02540 and the entire repair-tracking workflow quietly stopped
 * working. These checks turn that class of regression into a test failure.
 *
 * Dependency-free by design: run with plain `node`.
 */

const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..');

/** Inline-handler identifiers that are legitimately not project functions. */
const NATIVE_HANDLER_GLOBALS = new Set([
  'alert', 'confirm', 'prompt', 'open', 'close', 'print', 'focus', 'blur',
  'history', 'location', 'submit', 'reset', 'void', 'return', 'this', 'event',
  'console', 'window', 'document', 'Number', 'String', 'Boolean', 'parseInt',
  'parseFloat', 'encodeURIComponent', 'decodeURIComponent', 'JSON', 'Math',
  'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'fetch'
]);

function walk(dir, filter, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === 'dist' || entry.name === '.git') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, filter, out);
    else if (filter(full)) out.push(full);
  }
  return out;
}

function read(file) {
  return fs.readFileSync(file, 'utf8');
}

/** Relative, forward-slashed path for stable reporting. */
function rel(file) {
  return path.relative(ROOT, file).split(path.sep).join('/');
}

function lineOf(source, index) {
  let line = 1;
  for (let i = 0; i < index && i < source.length; i++) {
    if (source.charCodeAt(i) === 10) line++;
  }
  return line;
}

/* ------------------------------------------------------------------ *
 * HTML
 * ------------------------------------------------------------------ */

/** Strips <script> blocks so tag counting is not confused by JS strings. */
function stripScripts(html) {
  return html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '');
}

/** All `id="..."` values with their line numbers, plus duplicates. */
function extractIds(html) {
  const ids = [];
  const seen = new Map();
  const duplicates = [];
  const re = /\bid\s*=\s*"([^"]+)"|\bid\s*=\s*'([^']+)'/g;
  let match;
  while ((match = re.exec(html)) !== null) {
    const value = match[1] || match[2];
    const line = lineOf(html, match.index);
    ids.push({ id: value, line });
    if (seen.has(value)) duplicates.push({ id: value, lines: [seen.get(value), line] });
    else seen.set(value, line);
  }
  return { ids, duplicates };
}

/** Unbalanced block-level tag check (a coarse but useful structural smoke test). */
function tagBalance(html, tag) {
  const source = stripScripts(html);
  const open = (source.match(new RegExp(`<${tag}\\b`, 'gi')) || []).length;
  const close = (source.match(new RegExp(`</${tag}\\s*>`, 'gi')) || []).length;
  return { tag, open, close, balanced: open === close };
}

/** Inline `onclick="fn(...)"`-style handlers and the function names they call. */
function extractInlineHandlerCalls(html) {
  const calls = [];
  const attrRe = /\son([a-z]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/gi;
  let match;
  while ((match = attrRe.exec(html)) !== null) {
    const value = match[2] !== undefined ? match[2] : match[3];
    if (!value) continue;
    const line = lineOf(html, match.index);
    const callRe = /(?:^|[^.\w$])([A-Za-z_$][\w$]*)\s*\(/g;
    let call;
    while ((call = callRe.exec(value)) !== null) {
      const name = call[1];
      if (!name.startsWith('this')) calls.push({ name, line, event: match[1] });
    }
  }
  return calls;
}

/** Functions declared inside inline <script> blocks of a page. */
function extractInlineScriptFunctions(html) {
  const names = new Set();
  const blocks = html.match(/<script\b(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/gi) || [];
  for (const block of blocks) {
    let match;
    const fnRe = /function\s+([A-Za-z_$][\w$]*)\s*\(/g;
    while ((match = fnRe.exec(block)) !== null) names.add(match[1]);
    const globalRe = /(?:window|globalThis)\.([A-Za-z_$][\w$]*)\s*=/g;
    while ((match = globalRe.exec(block)) !== null) names.add(match[1]);
    const varRe = /(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:function|\()/g;
    while ((match = varRe.exec(block)) !== null) names.add(match[1]);
  }
  return names;
}

/* ------------------------------------------------------------------ *
 * JS
 * ------------------------------------------------------------------ */

/** String-literal element ids referenced from a JS module. */
function extractJsElementRefs(source) {
  const refs = [];
  const patterns = [
    { kind: 'getElementById', re: /getElementById\(\s*(['"])([^'"]+)\1\s*\)/g },
    { kind: 'setText', re: /\bsetText\(\s*(['"])([^'"]+)\1\s*,/g },
    { kind: 'loadSecureImage', re: /\bloadSecureImage\(\s*(['"])([^'"]+)\1\s*,/g },
    { kind: 'querySelector', re: /querySelector(?:All)?\(\s*(['"])(#[A-Za-z][\w-]*)/g }
  ];
  for (const { kind, re } of patterns) {
    let match;
    while ((match = re.exec(source)) !== null) {
      // querySelector captures '#id' — normalise to a bare id.
      const id = match[2].startsWith('#') ? match[2].slice(1) : match[2];
      refs.push({ id, kind, line: lineOf(source, match.index) });
    }
  }
  return refs;
}

/**
 * Ids the JS creates for itself: markup injected through innerHTML template
 * literals (`<div id="foo">`), explicit `.id = 'foo'` assignments, and
 * `setAttribute('id', 'foo')`. A reference resolving to one of these is not an
 * orphan — the element only exists once the module has rendered it.
 *
 * Interpolated ids (`id="row-${id}"`) are recorded as prefixes so
 * `getElementById(`row-${id}`)`-style lookups can match too.
 */
function extractDynamicIds(sources) {
  const exact = new Set();
  const prefixes = new Set();
  for (const source of sources) {
    let match;

    const injected = /\bid\s*=\s*(["'`])([^"'`]*)\1/g;
    while ((match = injected.exec(source)) !== null) {
      const value = match[2];
      if (!value) continue;
      if (value.includes('${')) {
        const prefix = value.split('${')[0];
        if (prefix) prefixes.add(prefix);
      } else if (/^[A-Za-z][\w:-]*$/.test(value)) {
        exact.add(value);
      }
    }

    const assigned = /\.id\s*=\s*(["'])([^"']+)\1/g;
    while ((match = assigned.exec(source)) !== null) {
      if (/^[A-Za-z][\w:-]*$/.test(match[2])) exact.add(match[2]);
    }

    const attr = /setAttribute\(\s*['"]id['"]\s*,\s*(['"])([^'"]+)\1/g;
    while ((match = attr.exec(source)) !== null) {
      if (/^[A-Za-z][\w:-]*$/.test(match[2])) exact.add(match[2]);
    }
  }
  return { exact, prefixes };
}

function isSelfProvided(id, dynamic) {
  if (dynamic.exact.has(id)) return true;
  for (const prefix of dynamic.prefixes) {
    if (prefix && id.startsWith(prefix) && id.length > prefix.length) return true;
  }
  return false;
}

/** Every `window.name = ...` assignment across JS and inline page scripts. */
/**
 * Globals contributed by classic (non-module) `<script src>` files. A
 * top-level `function foo()` or `const foo =` in such a file is a real global,
 * so inline handlers may legitimately call it — unlike an ES module, whose
 * scope stays private unless it explicitly assigns to `window`.
 */
function extractClassicScriptGlobals(sources) {
  const names = new Set();
  for (const source of sources) {
    let match;
    const fnRe = /^(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(/gm;
    while ((match = fnRe.exec(source)) !== null) names.add(match[1]);
    const declRe = /^(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=/gm;
    while ((match = declRe.exec(source)) !== null) names.add(match[1]);
  }
  return names;
}

/** Source files a page pulls in as classic scripts (`<script src=...>`). */
function extractClassicScriptSources(html) {
  const srcs = [];
  const re = /<script\b([^>]*)\bsrc\s*=\s*["']([^"']+)["']([^>]*)>/gi;
  let match;
  while ((match = re.exec(html)) !== null) {
    const attrs = `${match[1]} ${match[3]}`;
    if (/type\s*=\s*["']module["']/i.test(attrs)) continue;
    const src = match[2];
    if (/^https?:/i.test(src)) continue;
    srcs.push(src);
  }
  return srcs;
}

function extractWindowGlobals(sources) {
  const names = new Set();
  for (const source of sources) {
    let match;
    const re = /(?:window|globalThis)\.([A-Za-z_$][\w$]*)\s*=/g;
    while ((match = re.exec(source)) !== null) names.add(match[1]);
  }
  return names;
}

/* ------------------------------------------------------------------ *
 * Auditing
 * ------------------------------------------------------------------ */

/** Page → modules that are known to drive that page's DOM. */
const PAGE_MODULE_OWNERSHIP = {
  'admin_dashboard.html': [
    'js/features/admin-dashboard/index.js',
    'js/features/repair-tracking/index.js',
    'js/features/report-generation/index.js',
    'js/features/administration/index.js',
    'js/features/priority-dispatch/index.js',
    'js/features/repair-tracking/index.js',
    'js/features/activity-log/index.js'
  ],
  'barangay_dashboard.html': [
    'js/features/barangay-dashboard/index.js',
    'js/features/report-submission/index.js',
    'js/features/report-wizard/index.js'
  ],
  'ceo_dashboard.html': ['js/features/ceo-dashboard/index.js'],
  'login.html': ['js/features/auth/index.js', 'js/features/passkeys/index.js']
};

function collect() {
  const htmlFiles = fs
    .readdirSync(ROOT)
    .filter((f) => f.endsWith('.html'))
    .map((f) => path.join(ROOT, f));
  const jsFiles = walk(path.join(ROOT, 'js'), (f) => f.endsWith('.js'));

  const pages = new Map();
  for (const file of htmlFiles) {
    const html = read(file);
    const { ids, duplicates } = extractIds(html);
    pages.set(rel(file), {
      file,
      html,
      ids,
      idSet: new Set(ids.map((e) => e.id)),
      duplicates,
      inlineHandlers: extractInlineHandlerCalls(html),
      inlineScriptFns: extractInlineScriptFunctions(html),
      divBalance: tagBalance(html, 'div')
    });
  }

  const modules = new Map();
  for (const file of jsFiles) {
    const source = read(file);
    modules.set(rel(file), { file, source, refs: extractJsElementRefs(source) });
  }

  const jsSources = jsFiles.map(read);
  const windowGlobals = extractWindowGlobals([...jsSources, ...htmlFiles.map((f) => read(f))]);
  const dynamicIds = extractDynamicIds(jsSources);

  // Globals from classic (non-module) scripts, which inline handlers may call.
  const classicSources = new Set();
  for (const [page, info] of pages) {
    for (const src of extractClassicScriptSources(info.html)) {
      const resolved = path.join(ROOT, src.replace(/^\.?\//, ''));
      if (fs.existsSync(resolved) && fs.statSync(resolved).isFile()) {
        // Only scripts served verbatim (not the webpack entry) are classic.
        const relPath = rel(resolved);
        if (relPath.includes('/features/') || relPath.includes('/core/') || relPath.includes('/components/')) continue;
        classicSources.add(resolved);
        info.classicScriptSrcs = info.classicScriptSrcs || new Set();
        info.classicScriptSrcs.add(relPath);
      }
    }
  }
  const classicGlobals = extractClassicScriptGlobals([...classicSources].map(read));

  const allPageIds = new Set();
  for (const page of pages.values()) for (const id of page.idSet) allPageIds.add(id);

  return { pages, modules, windowGlobals, dynamicIds, classicGlobals, allPageIds };
}

/** Stable identity for a finding, so a baseline survives line-number drift. */
function findingKey(finding) {
  const subject = finding.elementId || finding.handler || finding.tag || '';
  return [finding.check, finding.page || '', finding.module || '', subject].join('|');
}

/**
 * Runs every check and returns a flat, severity-tagged finding list.
 * `error` findings should fail CI; `warning` findings are advisory.
 */
function audit() {
  const { pages, modules, windowGlobals, allPageIds, dynamicIds, classicGlobals } = collect();
  const findings = [];

  // 1. Duplicate ids inside a page (getElementById returns only the first).
  for (const [page, info] of pages) {
    for (const dup of info.duplicates) {
      findings.push({
        severity: 'error',
        check: 'duplicate-id',
        page,
        elementId: dup.id,
        line: dup.lines[1],
        message: `id="${dup.id}" appears ${dup.lines.length} times (lines ${dup.lines.join(', ')}); getElementById returns only the first`
      });
    }
  }

  // 2. JS references an id that exists in no page and that no module injects.
  for (const [module, info] of modules) {
    const seen = new Set();
    for (const ref of info.refs) {
      if (allPageIds.has(ref.id)) continue;
      if (isSelfProvided(ref.id, dynamicIds)) continue;
      if (seen.has(ref.id)) continue;
      seen.add(ref.id);
      findings.push({
        severity: 'error',
        check: 'orphan-element-ref',
        module,
        elementId: ref.id,
        line: ref.line,
        message: `${ref.kind}('${ref.id}') targets an id that exists in no HTML page — the lookup silently no-ops`
      });
    }
  }

  // 3. A page's owning modules reference ids missing from that page.
  for (const [page, owners] of Object.entries(PAGE_MODULE_OWNERSHIP)) {
    const info = pages.get(page);
    if (!info) continue;
    for (const owner of owners) {
      const module = modules.get(owner);
      if (!module) continue;
      const reported = new Set();
      for (const ref of module.refs) {
        if (info.idSet.has(ref.id)) continue;
        if (reported.has(ref.id)) continue;
        reported.add(ref.id);
        findings.push({
          severity: 'error',
          check: 'missing-element',
          page,
          module: owner,
          elementId: ref.id,
          line: ref.line,
          message: `${owner} expects #${ref.id} on ${page}, but the page has no such element`
        });
      }
    }
  }

  // 4. Inline handlers with no reachable implementation.
  for (const [page, info] of pages) {
    const seen = new Set();
    for (const call of info.inlineHandlers) {
      if (NATIVE_HANDLER_GLOBALS.has(call.name)) continue;
      if (windowGlobals.has(call.name)) continue;
      if (info.inlineScriptFns.has(call.name)) continue;
      if (classicGlobals.has(call.name)) continue;
      if (seen.has(call.name)) continue;
      seen.add(call.name);
      findings.push({
        severity: 'error',
        check: 'unresolved-inline-handler',
        page,
        handler: call.name,
        line: call.line,
        message: `on${call.event} calls ${call.name}(), which no module exposes on window (and no inline script defines)`
      });
    }
  }

  // 5. Structural smoke test: unbalanced <div>s usually mean a broken edit.
  for (const [page, info] of pages) {
    if (!info.divBalance.balanced) {
      findings.push({
        severity: 'error',
        check: 'unbalanced-tags',
        page,
        tag: 'div',
        message: `<div> open/close mismatch: ${info.divBalance.open} opening vs ${info.divBalance.close} closing`
      });
    }
  }

  const errors = findings.filter((f) => f.severity === 'error');
  return {
    findings,
    errors,
    summary: {
      pages: pages.size,
      modules: modules.size,
      windowGlobals: windowGlobals.size,
      errors: errors.length
    }
  };
}

/* ------------------------------------------------------------------ *
 * Baseline (ratchet)
 * ------------------------------------------------------------------ */

const BASELINE_PATH = path.join(__dirname, 'dom-contract-baseline.json');

function loadBaseline() {
  if (!fs.existsSync(BASELINE_PATH)) return new Set();
  const raw = JSON.parse(fs.readFileSync(BASELINE_PATH, 'utf8'));
  return new Set(raw.keys || []);
}

function saveBaseline(findings) {
  const keys = [...new Set(findings.map(findingKey))].sort();
  fs.writeFileSync(
    BASELINE_PATH,
    `${JSON.stringify(
      {
        note: 'Known DOM contract violations. This file is a ratchet: it may only shrink. Regenerate deliberately with `node tools/dom-contract.js --update-baseline` after fixing findings — never to silence new ones.',
        updatedBy: 'tools/dom-contract.js',
        count: keys.length,
        keys
      },
      null,
      2
    )}\n`
  );
  return keys.length;
}

/** Splits findings into already-known (baselined) and newly introduced. */
function diffAgainstBaseline(findings, baseline = loadBaseline()) {
  const known = [];
  const introduced = [];
  for (const finding of findings) {
    if (baseline.has(findingKey(finding))) known.push(finding);
    else introduced.push(finding);
  }
  return { known, introduced };
}

module.exports = {
  ROOT,
  BASELINE_PATH,
  audit,
  collect,
  diffAgainstBaseline,
  extractClassicScriptGlobals,
  extractClassicScriptSources,
  extractDynamicIds,
  extractIds,
  extractInlineHandlerCalls,
  extractInlineScriptFunctions,
  extractJsElementRefs,
  extractWindowGlobals,
  findingKey,
  loadBaseline,
  saveBaseline,
  tagBalance,
  rel
};

if (require.main === module) {
  const args = new Set(process.argv.slice(2));
  const { findings, summary } = audit();

  if (args.has('--update-baseline')) {
    const count = saveBaseline(findings);
    console.log(`Baseline written with ${count} known finding key(s) → ${rel(BASELINE_PATH)}`);
    process.exit(0);
  }

  if (args.has('--all')) {
    for (const f of findings) {
      const where = [f.page, f.module].filter(Boolean).join(' ← ');
      console.log(`${f.severity.toUpperCase()} [${f.check}] ${where}${f.line ? `:${f.line}` : ''}\n    ${f.message}`);
    }
  }

  const { known, introduced } = diffAgainstBaseline(findings);

  if (introduced.length === 0) {
    console.log(
      `DOM contract: no new violations — ${summary.pages} pages, ${summary.modules} modules checked.`
    );
    if (known.length > 0) {
      console.log(`${known.length} known violation(s) remain baselined (${rel(BASELINE_PATH)}).`);
    }
    process.exit(0);
  }

  for (const f of introduced) {
    const where = [f.page, f.module].filter(Boolean).join(' ← ');
    console.log(`NEW ${f.severity.toUpperCase()} [${f.check}] ${where}${f.line ? `:${f.line}` : ''}\n    ${f.message}`);
  }
  console.log(`\n${introduced.length} new DOM contract violation(s); ${known.length} known.`);
  process.exit(1);
}

