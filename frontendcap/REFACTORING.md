# Incremental frontend refactoring

## First extraction

`js/domain/repair-estimates.js` now owns `calculateEstimatedDays`; `app.js`
imports it at the existing call site. Its implementation is unchanged. This
module has no browser globals, requests, listeners, initialization, or mutable
state. No HTML compatibility bridge is needed for this private function.

Before moving it, characterization tests ran against the actual function in
`app.js`, isolated from application startup. The same assertions now import the
extracted production module. They cover damage aliases, first-match precedence,
severity precedence, measurement boundaries, labels, and input coercion. In
particular, `area > 5` returns seven days: the former test-local implementation
omitted this production branch.

The error-message tests now import `getErrorMessage` from `js/core/api.js`
instead of testing a copy. `config.js` has not been consolidated with that
module. The test-local `validateReportStep1` remains a testing gap; its passing
test is not evidence of production report-validation behavior.

Validation from `frontendcap/` using Node 24.21.0:

- `npm test`: 15 tests pass, before and after the estimator move.
- `npm run build`: succeeds with three Webpack bundle-size warnings.
- Node reports the existing unspecified-module-type warning for ES modules.
  Do not add a root `"type": "module"` just to suppress it: the test entry and
  Webpack configuration currently use CommonJS.

## Second extraction: notifications

`js/core/time-ago.js` now owns the pure `timeAgo` formatter, and
`js/features/notifications/index.js` owns the bell dropdown, unread badge,
mark-as-read action, and activity history. `app.js` no longer contains any
notification workflow code; it calls the feature entry point from one
DOMContentLoaded listener and imports one refresh operation for the WebSocket.

State and ownership:

- The feature owns `currentUserId` (captured at module evaluation, unchanged)
  and its idempotency flag. No universal state object was introduced.
- `initNotifications()` is the only entry point. It is idempotent, assigns the
  temporary window compatibility bridge, binds the bell listeners once, and
  performs the initial load.
- `refreshNotifications()` is the explicit operation used by the live WebSocket
  `REFRESH_DASHBOARDS` message. It replaced the old `window.loadNotifications`
  lookup.
- Refresh triggers are now: startup (once), the WebSocket message, and a
  successful `markAllAsRead` mutation. No navigation or observer trigger was
  added.

Temporary compatibility bridge for inline HTML handlers (names unchanged):
`window.loadNotifications`, `window.fetchUnreadBadgeCount`,
`window.openSingleNotification`, `window.markAllAsRead`,
`window.viewAllActivity`.

Characterization notes:

- Clicking a dropdown notification calls `openSingleNotification`, which
  references `single-notif-modal`, `single-notif-title`, `single-notif-message`,
  and `single-notif-time`. Those elements do not exist in any dashboard
  template, so the click currently throws before the read request is sent.
  `viewAllActivity` guards for its missing modal and silently does nothing.
  This pre-existing gap was preserved, not fixed, in the mechanical move; it
  needs a separate UI/template change.
- The global Escape-key handler in `app.js` still hides the dropdown directly.
  It does not reference feature functions and was left unchanged.
- `timeAgo` preserves UTC handling for timezone-less strings and Jackson
  LocalDateTime arrays. Tests cover thresholds, pluralization, clock-skew
  clamping, timezone equivalence, and invalid input.

Validation from `frontendcap/` using Node 24.21.0:

- `npm test`: 18 tests pass (3 new for `timeAgo`, imported from production).
- `npm run build`: succeeds with the same three Webpack bundle-size warnings.
- A manual Node smoke check (with only `sessionStorage` stubbed) imports
  `initNotifications` and `refreshNotifications` without touching the DOM.

No browser baseline was run for this extraction. It is behavior-preserving by
construction, but not browser-verified; the runtime constraints below still
apply.

## Third extraction: pure formatters and calculations

Four modules now own pure helpers that previously lived in `app.js`. Their
implementations are unchanged and they have no listeners, requests, rendering,
or mutable state:

- `js/core/format-pst.js` — `formatPST`. Timestamp display in Asia/Manila;
  timezone-less strings are tagged `+08:00`.
- `js/core/audit-time.js` — `parseAuditTimestamp`, `formatAuditTimestamp`.
  Timezone-less strings are treated as UTC, intentionally different from
  `formatPST`.
- `js/domain/inventory.js` — `parseToKilometers`, `formatTerrainType`,
  `getReportYear`, `formatInventoryDate`, `getReportYearSettings`. Calendar
  dates use the browser's local time zone, matching the original code.
- `js/domain/activity-log.js` — `formatTargetEntity`, `getCategoryBadge`,
  `getStatusBadge`. HTML badge builders; `formatTargetEntity` reuses
  `escapeHtml` from `core/utils.js`.

`app.js` imports all of them. No `window` compatibility bridge is needed
because no template or other script references these names.

Characterization notes:

- `getReportYear` rejects the literal strings `"null"` and `"undefined"`;
  `getReportYearSettings` rejects only `"null"` and returns `"undefined"` for
  the latter. Both behaviors are now covered by tests; the functions were not
  consolidated.
- `formatPST('2024-01-15')` (date-only) appends `+08:00`, parses invalid, and
  returns `N/A`. This pre-existing behavior is locked by a test.
- `formatInventoryDate` and `getReportYear`'s date fallback use the local time
  zone; tests compute the expected local output with the same primitives
  instead of asserting a machine-specific string.
- `formatReportDate` and `formatArchiveDate` remain in `app.js`; the former is
  byte-identical to `formatInventoryDate`, but consolidation stays deferred to
  a separate behavior-tested change.

Validation from `frontendcap/` using Node 24.21.0:

- `npm test`: 29 tests pass (11 new across the four modules).
- `npm run build`: succeeds with the same three Webpack bundle-size warnings.

As before, no browser baseline was run; these moves are behavior-preserving by
construction but not browser-verified.

## Fourth extraction: image lightbox (second feature)

`js/features/image-lightbox/index.js` owns the lazily created fullscreen
modal, its overlay click listener, the Escape-key close listener, and the
open/close operations. `initImageLightbox()` is idempotent: it installs the
window compatibility bridge (`openFullscreenImage`, `closeFullscreenImage`)
and binds the Escape listener once. `destroyImageLightbox()` unbinds the
listener; the bridge stays in place for late inline handlers.

- `app.js` imports `closeFullscreenImage` for its global Escape handler and
  calls `initImageLightbox()` from the feature startup block alongside
  `initNotifications()`.
- The bridge is required because dashboard templates and generated table rows
  call `openFullscreenImage(...)` from inline onclick attributes.
- `openArchiveDetailModal` in `app.js` still bulk-hides
  `#fullscreen-image-modal` directly. That external DOM coupling was left
  unchanged and should move to the feature in a separate change.

Validation from `frontendcap/` using Node 24.21.0:

- `npm test`: 30 tests pass. The lightbox test is headless and covers only the
  bridge, idempotent listener binding/cleanup, and placeholder skipping; real
  modal-open behavior still needs the browser baseline.
- `npm run build`: succeeds with the same three Webpack bundle-size warnings.

## Fifth extraction: live clock (third feature)

`js/features/live-clock/index.js` owns the `#live-clock-display` renderer and
its one-second interval. `initLiveClock()` is idempotent: it renders once
immediately and creates the interval once. `destroyLiveClock()` clears it; no
route teardown calls it yet, but the timer now has an explicit cleanup path.

- `app.js` calls `initLiveClock()` from the feature startup block alongside
  `initNotifications()` and `initImageLightbox()`.
- No window bridge is needed; no template or other script calls the clock.
- The previous `window.startLiveClock` global and its second DOMContentLoaded
  listener were removed.

Validation from `frontendcap/` using Node 24.21.0:

- `npm test`: 31 tests pass. The clock test is headless and covers the
  idempotent interval start, immediate render, and cleanup only.
- `npm run build`: succeeds with the same three Webpack bundle-size warnings.

## Sixth extraction: auth (fourth feature)

`js/features/auth/index.js` owns the login/MFA/recovery workflow:
`handleLogin`, `handleVerifyMfa`, `showForgotPassword`,
`handleForgotPasswordRequest`, `handlePasswordReset`, and `backToLogin`.

- `initAuth()` is idempotent: it installs the window compatibility bridge for
  the login.html inline handlers, binds Enter-to-login once, and restores the
  remembered username.
- The feature owns its form state, API calls, step transitions, remember-me
  storage, and the Enter key listeners. `app.js` no longer contains any auth
  workflow code; the feature startup block calls `initAuth()` alongside the
  other features.

Characterization notes:

- The strict password rule is now the exported `STRICT_PASSWORD_REGEX`
  constant (same regex), so tests assert the production rule instead of a
  copy.
- The original `app.js` registered the "remember me" auto-fill twice with
  equivalent conditions. The second registration is collapsed into
  `restoreRememberedUsername()`, which uses the superset condition (username
  input present). Observable behavior on login.html is unchanged.
- `handleLogin` and `handleVerifyMfa` keep plain `fetch` and the existing
  one-second redirect delay; they were not switched to `apiFetch`.
- `enforceSecurity()` (the global security bouncer) and
  `togglePasswordVisibility` remain in `app.js`; they are separate concerns.

Validation from `frontendcap/` using Node 24.21.0:

- `npm test`: 33 tests pass. The auth test is headless and covers exports, the
  password rule, idempotent Enter binding, and the bridge only; request
  payloads and redirects still need the browser baseline.
- `npm run build`: succeeds with the same three Webpack bundle-size warnings.

## Seventh extraction: remaining self-contained modules

Five more modules were extracted, taking `app.js` from 12,346 to 8,196 lines
(13,340 at HEAD):

- `js/features/secure-image/index.js` — `loadSecureImage`; window bridge for
  the profile/sidebar/tracking callers.
- `js/features/activity-log/index.js` — audit state, renderer, filters,
  inspector modal, CSV export, and print preview; explicit
  `initActivityLog()`.
- `js/features/profile-account/index.js` — edit-profile modal, avatar
  upload/viewer, change-password form, and CPDO/CEO turnover; explicit
  `initProfileAccount()`. The profile/handover markup is absent from the
  current templates, so most bindings are inert.
- `js/features/administration/index.js` — user management, barangay
  management, and city-road management. Extracted verbatim; the module
  self-registers its DOMContentLoaded bindings and keeps the same window
  bridges.
- `js/features/reports-archive/index.js` — annual report preview, city road
  inventory, settings/rollover/maintenance, and archive. Extracted verbatim
  with self-registering bindings and window bridges.
- `js/core/password-policy.js` — shared `STRICT_PASSWORD_REGEX`; auth
  re-exports it and profile-account imports it, removing two duplicate
  literals.

Characterization notes:

- The verbatim modules keep the original internal ordering, including the
  later duplicate definitions of `loadDynamicInventoryYears` and
  `openAnnualReportPreview`, so the same implementation still wins.
- `app.js` now exposes `window.loadReportTimeline` as a temporary bridge
  because the reports/archive module calls it. It is assigned in the feature
  startup block.
- The extractions were verified with `npm test` and `npm run build` only.
  No browser workflow was exercised.

Validation from `frontendcap/` using Node 24.21.0:

- `npm test`: 33 tests pass (these extractions added no pure logic worth a new
  unit test).
- `npm run build`: succeeds with the same three Webpack bundle-size warnings.

## Eighth change: complete build output and dead duplicate removal

`webpack.config.prod.js` was rewritten. The previous config generated only
`dist/index.html` — from the admin dashboard template — and its copy rules
omitted `js/config.js`, `js/forecasting_ui.js`, and `sjdm_barangays.geojson`.
The `dist/index.html` in the source tree is a meta-refresh redirect to
`login.html`, so the old build silently replaced the login redirect with a
generated admin dashboard page.

The production build now:

- Copies all six pages verbatim (`index.html`, `login.html`,
  `admin_dashboard.html`, `barangay_dashboard.html`, `ceo_dashboard.html`,
  `404.html`). No bundle injection: every dashboard template keeps its
  original `<script src="js/app.js">` tag, which resolves to the bundle at
  `dist/js/app.js` — single entry, unchanged script order, no double load.
- Copies the classic helper scripts the templates load directly:
  `js/config.js` and `js/forecasting_ui.js` (both plain scripts, no imports).
- Copies `sjdm_barangays.geojson`, which the Leaflet map layers fetch at
  runtime, plus the existing `img`, `css`, `js/vendor`, favicon, robots, and
  manifest assets.

Verified against the emitted `dist/` tree: every local `src`/`href` reference
in all six pages resolves to a file, each dashboard page loads the bundle
exactly once, and `dist/index.html` is the redirect page.

Dead duplicate definitions were also removed. These were sequential top-level
`window.*` assignments, so the later definition won at runtime; every call
site (inline `onclick` handlers and DOMContentLoaded callbacks) runs after
both assignments have executed, making the earlier copy dead:

- `js/app.js`: first copies of `filterBarangayReports` (47 lines),
  `toggleViewTimeline` (14), and `goToAddReport` (12).
- `js/features/reports-archive/index.js`: first copies of
  `loadDynamicInventoryYears` and `openAnnualReportPreview` (160 lines).

A repo-wide scan now shows no duplicate `window.*` function assignments and no
duplicate top-level function declarations. `toggleAdminReviewMap`, previously
listed as duplicated, is not: it has one definition plus one window bridge.
`window.showToast` likewise has a single definition. `app.js` is down to
8,123 lines; the reports-archive module to 1,830.

Deployment wiring is still unconfirmed: the root `vercel.json` contains only
`{"cleanUrls": true}`. If the Vercel project uses this repository root as its
project root, it needs `buildCommand: npm --prefix frontendcap run build` and
`outputDirectory: frontendcap/dist`; if it uses `frontendcap` as the root
directory, those belong in a `frontendcap/vercel.json` (the repo-root file is
ignored in that case). Confirm the dashboard/project settings before relying
on the build output.

Validation: `npm test` 33 pass; `npm run build` succeeds with the same three
bundle-size warnings.

## Ninth extraction: navigation, search, profile header, priority report, WebSockets

Five more feature modules were extracted, taking `app.js` from 8,123 to 7,150
lines:

- `js/features/live-updates/index.js` — the STOMP WebSocket
  (`connectLiveDashboards`) and its DOMContentLoaded connect. Imports
  `API_BASE_URL` and `refreshNotifications`.
- `js/features/search-filters/index.js` — the CEO/admin/tracking table
  filters, the reusable `executeGlobalSearch` engine, the input-listener
  attach block, and the navigation search-reset tail. Window bridges
  unchanged.
- `js/features/navigation/index.js` — sidebar navigation, `switchView`, the
  view dispatcher registry, and the SPA history boot.
- `js/features/profile-header/index.js` — `populateProfileData`, the profile
  tab/menu bindings, and the auto-load header call.
- `js/features/priority-report/index.js` — `openPriorityReportList`, the
  official print/close actions, and the strict priority list generator.

Characterization notes:

- The navigation, profile-header, and priority-report slices lived inside the
  main DOMContentLoaded listener (which closes at former line 1790). Each is
  wrapped in its own DOMContentLoaded listener, preserving the original
  timing. The side effect is listener registration order: these modules
  register at import time, so their listeners run before the remaining main
  listener instead of inside it. All three only bind handlers or populate
  text, so the relative order is behavior-neutral.
- `generatePriorityList` is deliberately not window-bridged.
  `window.openPriorityReportList` (the bare caller) moved into the same
  module scope, so its in-scope guard stays true. The nav dispatcher guard
  `typeof window.generatePriorityList === 'function'` stays false, exactly as
  before.
- `switchView` and `populateProfileData` keep their existing window bridges,
  so the bare calls that remain in `app.js` resolve at runtime as they did.
- No handler behavior was changed.

Validation: `npm test` 33 pass; `npm run build` succeeds with the same three
bundle-size warnings; distinctive strings from all five modules are present in
the bundle; a repo-wide scan still shows no duplicate `window.*` assignments.

## Tenth extraction: report generation and batch archive

`js/features/report-generation/index.js` (492 lines) was extracted verbatim,
taking `app.js` from 7,150 to 6,676 lines. It contains the report preview
modal, the print and CSV engines, the export-dropdown listeners, the active
tracking-record helper, and the batch archive helpers.

Characterization notes:

- The module is top-level code, so no timing wrapper was needed.
- `window.updateBatchArchiveUI` keeps its existing bridge; the inline handler
  in the tracking table HTML still calls it.
- The batch-archive success path calls `loadTrackingData()`, which stays in
  `app.js` with the tracking engine. `app.js` now exposes
  `window.loadTrackingData` as a temporary compatibility bridge in the feature
  startup block. The bare `typeof loadTrackingData` guards that remain in
  `app.js` resolve to the in-scope function, so the bridge does not change
  them.
- `pendingArchiveIds` and the other batch-archive state were block-local and
  moved with the module; the active-record helper reads the existing
  `window.currentFilteredTrackedReports`.

Validation: `npm test` 33 pass; `npm run build` succeeds with the same three
bundle-size warnings; batch-archive strings are present in the bundle; no
duplicate `window.*` assignments.

## Eleventh extraction: UI shell and report wizard

Two more feature modules were extracted, taking `app.js` from 6,681 to 6,419
lines:

- `js/features/ui-shell/index.js` — mobile sidebar, keyboard shortcuts, the
  analytics accordion, and the unified `window.showToast` used by inline
  template handlers. No imports.
- `js/features/report-wizard/index.js` — `currentReportStep`, `goToFormStep`,
  `goToAddReport`, and `loadRoadsToDropdown` (with its immediate call).
  Imports `apiFetch` and the component `showToast`.

Characterization notes:

- Both blocks were top-level, so both run at import evaluation. The
  `loadRoadsToDropdown()` call is the only execution side effect that moved
  earlier; it already ran before DOMContentLoaded and only reads DOM state.
- Bare `showToast(...)` calls in `app.js` keep using the component import;
  `window.showToast` remains the inline-handler override.
- `goToAddReport`'s bare `switchView` guard continues to resolve through the
  navigation module's window bridge.
- Known pre-existing issue recorded, not changed: the barangay dashboard
  View/Edit buttons use inline `onclick="openViewModal(...)"` and
  `onclick="openEditModal(...)"`, but those functions are not exposed on
  `window`, so the handlers fail today. Exposing them is a behavior change and
  is deferred to the browser-baseline pass.

Validation: `npm test` 33 pass; `npm run build` succeeds with the same three
bundle-size warnings; bundle strings verified; no duplicate `window.*`
assignments.

## Twelfth extraction: barangay dashboard engine

`js/features/barangay-dashboard/index.js` (952 lines) was extracted verbatim
from `app.js`, taking it from 6,421 to 5,500 lines. It owns the KPI metrics,
the cycle-year progress bar, the severity doughnut chart, the report cards
and their search/status filter, the view and edit modals, the resubmit
request, the shared report timeline renderer, and the self-registering
DOMContentLoaded boot (security check, initial load, edit-image preview,
`view-dashboard` observer).

Characterization notes:

- `loadReportTimeline` moved with the module and installs its own
  `window.loadReportTimeline` bridge at module evaluation. The CEO, admin
  review, and tracking sections still in `app.js`, plus the reports/archive
  feature, keep calling it unchanged. The app.js feature-startup assignment
  was removed. The bridge now exists before DOMContentLoaded instead of during
  it; every caller runs on user interaction or after DOMContentLoaded, so the
  timing change is inert.
- Shared map state: `renderViewModalMap` used the module-scoped `redIcon` from
  the top of `app.js`. The extracted module now reads `window.redIcon`
  explicitly, and `app.js` publishes `window.redIcon = redIcon` at the one
  point where it builds the icon inside the main DOMContentLoaded listener.
  This is a temporary bridge: the map extraction should absorb `redIcon` and
  drop the global.
- `window.loadBarangayReports` was and remains undefined. The extracted
  module does not create the bridge, so the live-updates WebSocket guard keeps
  silently skipping the barangay refresh exactly as before. `app.js` imports
  `loadBarangayReports` instead, which keeps the post-submit refresh in the
  nested `executeFinalSubmission` helper working without changing the
  WebSocket behavior.
- The view-modal boundary layer still tests
  `typeof sjdmGeoJsonData !== 'undefined'`, which is always false:
  `sjdmGeoJsonData` only exists inside the main DOMContentLoaded scope and was
  never a global. The dead branch was preserved verbatim.
- The DOMContentLoaded boot was top-level code in `app.js` and now registers
  at module evaluation, so it runs earlier in the DOMContentLoaded queue than
  before. It only performs the auth check/redirect, the initial load, the
  edit-image preview binding, and the tab observer.
- Inline handlers keep their pre-extraction status: `filterBarangayReports`,
  `toggleViewTimeline`, and `toggleEditTimeline` were already window bridges
  and remain so; `openViewModal`, `openEditModal`, and `closeBdModals` were
  never bridged and stay unbridged (the view/edit buttons and the modal close
  button remain broken until the browser-baseline pass decides to expose
  them).
- `showToast` is imported from the component, matching the app.js bare calls;
  it is not the `window.showToast` override installed by ui-shell.

Validation from `frontendcap/` using Node 24.21.0:

- `npm test`: 33 tests pass.
- `npm run build`: succeeds with the same three bundle-size warnings (the
  entrypoint grew by the extracted module).
- Bundle checks: `window.loadReportTimeline`, `window.filterBarangayReports`,
  `window.toggleViewTimeline`, `window.toggleEditTimeline`, and
  `window.redIcon` each appear exactly once; `window.loadBarangayReports` is
  absent; distinctive module strings are present.
- Headless Node smoke import (with `window`, `document`, and `sessionStorage`
  stubs) confirms the module exports `loadBarangayReports`, installs the four
  bridges, leaves `window.loadBarangayReports` undefined, and registers one
  DOMContentLoaded listener.
- No browser workflow was run for this extraction; the view/edit modals,
  resubmit request, and live map behavior still need the browser baseline.

## Thirteenth extraction: admin dashboard engine

`js/features/admin-dashboard/index.js` (1,229 lines) was extracted, taking
`app.js` from 5,500 to 4,303 lines. It owns the report inbox, the review modal
and admin review map, the action queue and compliance charts, the archive and
priority jump helpers, and the self-registering data-engine boot.

Characterization notes:

- Two disjoint ranges moved together: the contiguous engine block
  (`loadAdminReports` through `jumpToArchiveTab`, 1,034 lines, former app.js
  2636-3669) and the neutralized accept/validate/reject `addEventListener`
  handlers (160 lines, former app.js 616-775) that lived inside the main
  app.js DOMContentLoaded listener. The handlers were lifted verbatim into
  their own DOMContentLoaded listener at the bottom of the module, matching
  the navigation/profile-header/priority-report pattern. The module-scope
  `.onclick` controllers still fire first; the confirm paths call
  `stopImmediatePropagation`, so one mutation per click is unchanged (the
  show/cancel reject listeners still run their idempotent DOM toggles).
- `loadAdminReports`, `loadAdminDashboardData`, `reviewReport`,
  `jumpToReportsAndReview`, `jumpToAllReports`, and `jumpToArchiveTab` get no
  `window` bridges. They were unbridged before, so the live-updates and
  navigation guards and the inline `admin_dashboard.html` handlers stay inert
  exactly as they were. `app.js` imports `loadAdminDashboardData` for its
  remaining guarded refresh calls (dispatch return, extension review,
  archive/close, rework feedback).
- Bridges kept unchanged: `window.toggleAdminReviewTimeline`,
  `window.closeReviewModal` (used by `admin_dashboard.html:727`), and
  `window.toggleAdminReviewMap`.
- The eval-time `loadAdminReports()` call and the `adminSjdmGeoJsonData`
  prefetch now run at module evaluation instead of app.js evaluation. Both are
  pre-DOMContentLoaded and network-async; no observable change.
- `toggleAdminReviewMap` builds its own local `redIcon`, so the admin engine
  has no dependency on the temporary `window.redIcon` bridge.
- The vestigial accept-confirm flow (`accept-confirm-modal` and friends are
  absent from the template) is preserved verbatim; its listeners stay
  neutralized by the `.onclick` path.

Validation from `frontendcap/` using Node 24.21.0:

- `npm test`: 33 tests pass.
- `npm run build`: succeeds with the same three bundle-size warnings.
- Mechanical diff against the pre-extraction app.js backup: `app.js` changed
  by exactly one import line and the two removals (1,198 deleted lines, of
  which 1,194 are moved code and 4 are collapsed blank lines); the engine
  block is byte-identical except `export function loadAdminDashboardData`,
  and the 160-line handler block is byte-identical.
- Bundle checks: the three kept admin bridges appear once each;
  `window.loadAdminDashboardData`, `window.loadAdminReports`,
  `window.reviewReport`, and `window.jumpToAllReports` are absent.
- Headless Node smoke import confirms the export, the three bridges, the
  unbridged names, and two DOMContentLoaded registrations.
- No browser workflow was run; the review modal, action queue, charts, and
  review map still need the browser baseline.

## Fourteenth extraction: CEO dashboard engine

`js/features/ceo-dashboard/index.js` (1,565 lines) was extracted, taking
`app.js` from 4,303 to 2,772 lines. It owns the CEO data loader, progress and
status charts, dashboard/deploy tables and filters, the manage modal and its
map, schedule-extension request, the batch defer cluster, repair completion,
and the CEO tab watchdogs lifted out of the main app.js listener.

Characterization notes:

- Three ranges moved together: the CEO watchdog slice inside the main
  DOMContentLoaded listener (former 101-135, lifted into its own
  DOMContentLoaded listener at the top of the module), the contiguous engine
  block (former 731-2166: module globals through `toggleAllCheckboxes`), and
  the isolated `submitCEOCompletion` (former 2579-2637), which sat between
  admin/tracking code. The watchdog listener registers before the engine boot,
  preserving the original main-listener ordering. Both now register before
  app.js's main listener, so the engine boot dispatches earlier than it did
  before; no data dependency was found, only browser-visible ordering.
- `submitCEOCompletion` moved despite the 1,400-line gap so the module owns the
  complete CEO workflow; its `loadCEODashboardData` call resolves in module
  scope.
- Every CEO bridge was already a `window.*` assignment inside the moved code,
  so all of them (loadCEODashboardData, renderCEOTable, filterCEODashTable,
  toggleCEOTimeline, openCEOManageModal, toggleCeoExtensionForm,
  submitCeoExtensionRequest, jumpToCEOMasterlistAndManage, deferringSingleId,
  closeBatchDeferModal, markAsPendingBudget, openBatchDeferModal,
  submitBatchDefer, executeBatchDeferral, toggleBatchActionBar,
  toggleAllCheckboxes, submitCEOCompletion, and the ceoManage* map state)
  moved with it. No new bridges were added.
- Because no app.js caller remained, the module needed a side-effect import
  (`import './features/ceo-dashboard/index.js'`). Without an importer the
  module is absent from webpack's dependency graph, so the first build emitted
  no copy of it: the CEO window bridges and watchdog disappeared from `dist/`.
  The import is required; this is the first extraction whose module is not
  referenced by a named import in app.js.
- The CEO manage map builds its own local `redIcon`; only the add-report map
  engine consumes the shared one, so no `window.redIcon` change was needed.
- Known orphaned CEO sub-systems were preserved verbatim: the extension
  form/card IDs, the manage timeline toggle, and the batch-defer modal IDs are
  absent from the current templates. Some of those paths throw today
  (unguarded `classList` on missing modals); the extraction did not change
  them.

Validation from `frontendcap/` using Node 24.21.0:

- `npm test`: 33 tests pass.
- `npm run build`: succeeds with the same three bundle-size warnings.
- Mechanical diff against the pre-extraction app.js backup: one added import
  line and 1,532 deleted lines (35-line watchdog + 1,436-line block + 59-line
  completion function + 2 collapsed blank lines). The block and completion
  function are byte-identical; the watchdog content is byte-identical apart
  from its trailing blank line.
- Bundle checks: the moved CEO bridges each appear once, the earlier
  extraction bridges are unchanged, and the bundle contains the CEO module.
- Headless Node smoke import confirms the CEO bridges and two
  DOMContentLoaded registrations.
- No browser workflow was run; the charts, manage modal/map, defer paths, and
  completion upload still need the browser baseline.

## Runtime and deployment constraints

The checked-in configuration establishes the following, not a verified live
deployment:

- `npm start` runs Webpack Dev Server with `webpack.config.dev.js`, serving the
  project directory as static content and compiling `js/app.js` in memory.
- `webpack.common.js` has one application entry and writes a bundled classic
  script to `dist/js/app.js`.
- Login and all three dashboard templates load `js/app.js` as a classic script.
  The source contains ES imports, so serving the source tree without bundling
  is not equivalent to running the Webpack build.
- `npm run build` emits a complete `dist/` tree (see "Eighth change" above):
  all six pages copied verbatim, the bundle at `dist/js/app.js`, and the
  classic helper scripts and geojson the pages and map layers reference.
- `vercel.json` only enables clean URLs; it does not establish the build command
  or published directory. The root README does not document deployment.

A passing bundle build alone does not verify a working deployment. Confirm the
Vercel project settings (see "Eighth change" above) and run the browser
baseline before changing initialization for the remaining features.

## Browser behavior baseline still required

No authenticated browser workflows have been exercised in this extraction.
Use a controlled backend/test account for each role and reset fixtures between
mutation scenarios. Record request method, endpoint, payload, count, resulting
UI state, and redirect where applicable.

| Role | Scenarios to characterize | Required assertions |
| --- | --- | --- |
| All | Login, MFA success/failure, logout | Session and role redirect; one request per submit action |
| Barangay | Report creation and editing | Validation, payload, rendered result; one mutation per action |
| Admin | Review validation/rejection | One mutation per click, including repeated modal opening |
| CEO | Repair start/completion | Status, dates, result refresh; one mutation per action |
| Admin/CEO | QA approval and rework | Correct transition and one mutation per action |
| Admin | Archive, CSV, print | Correct report set; archive mutates once; exports do not mutate |
| All | Navigation, live refresh, notifications | No multiplied listeners or loads after repeated navigation |

Review-button double registration — static characterization (browser
confirmation still required before removing either path):

- The DOMContentLoaded block starting at `js/app.js` line 367 registers
  `addEventListener` handlers for `btn-accept-validate` (opens the accept
  confirm modal, `js/app.js` lines ~1014-1087), `btn-confirm-accept`
  (PUT `/api/reports/{id}/status`, status "Validated", no `userId`),
  `btn-show-reject`, `btn-cancel-reject`, and `btn-confirm-reject`
  (PUT, status "Rejected", remarks only, no `userId`, lines ~1114-1168).
- Top-level module code (evaluated earlier, before DOMContentLoaded) assigns
  `.onclick` handlers for `btn-show-reject`, `btn-cancel-reject`,
  `btn-confirm-reject`, and `btn-accept-validate` (lines ~3985-4140) with a
  shared `isReviewSubmitting` mutex and PUTs that include `userId`.
- On a click the `.onclick` handler runs first (registered first) and calls
  `e.stopImmediatePropagation()`, which blocks the later `addEventListener`
  handler on the same element. Today that yields exactly one mutation per
  click. The show/cancel reject pairs run both handlers; both only repeat
  idempotent DOM toggles.
- `accept-confirm-modal`, `btn-confirm-accept`, and `btn-cancel-accept` do not
  exist in `admin_dashboard.html`: the confirm-modal flow is vestigial. If the
  `.onclick` path were removed and the `addEventListener` path kept, an accept
  click would throw on the missing element. Removal direction is the
  `addEventListener` block, after browser confirmation.

## Feature migration status and next boundary

The pure-function phase is complete: `calculateEstimatedDays`, `timeAgo`,
`formatPST`, the audit timestamp helpers, the inventory helpers, and the
activity-log formatters all live in `core/` or `domain/` with production tests.
Twenty feature modules are extracted: notifications, image-lightbox,
live-clock, auth, secure-image, activity-log, profile-account, administration,
reports-archive, live-updates, search-filters, navigation, profile-header,
priority-report, report-generation, ui-shell, report-wizard,
barangay-dashboard, admin-dashboard, and ceo-dashboard. `app.js` is down to
2,772 lines (from 13,340) and is now the coupled core workflows. The duplicate `window.*` definitions and
the second `window.showToast` assignment no longer exist (see "Eighth change"
above).

The remaining `app.js` code is the part that needs the browser baseline before
it can move safely:

- Global security bouncer (session validation + role redirect).
- Main DOMContentLoaded listener remainder: Leaflet safety check/add-report
  map, barangay management render/modals, and the admin city road inventory
  print-dropdown opener.
- Barangay report submission and edit/resubmit. **Superseded:** the
  `window.submitRoadReport` bridge and the `#confirm-modal` markup both exist
  today (`js/features/report-submission/index.js`, `barangay_dashboard.html`),
  so the claim below no longer holds. What remains unverified is the browser
  pass, not the wiring. The submission scout still recommends moving submission
  together with the add-report map engine and letting that module absorb
  `map`/`selectedLat`/`selectedLng` and the temporary `window.redIcon` bridge.
- Tracking modal engine (open/populate, extension review, rework/close,
  per-report map). **Repaired:** `#tracking-modal` was restored to
  `admin_dashboard.html` (all 63 element ids the module drives are present and
  pinned by tests in `test.js`), so the engine no longer early-returns.
  Extraction can proceed once the browser baseline confirms the restored modal.
- Admin/CEO/barangay global maps (shared `map`/`selectedLat`/`selectedLng`
  state at the top of `app.js`, plus the temporary `window.redIcon` bridge).

Related documents:

- `BROWSER_BASELINE.md` — the executable script for the browser baseline these
  notes keep deferring to, across all three roles.
- `DOM_CONTRACT_FINDINGS.md` — the 277 element ids the feature modules address
  that no longer exist in any page after `6e02540`, grouped by feature area.
  271 of them are recoverable from `6e02540^`.
- `npm run audit:dom` — the ratchet that stops the list from growing; `test.js`
  fails the build on any new violation.

Next steps, in order:

1. Establish the browser baseline (see table above) and confirm the Vercel
   project settings (see "Eighth change" above).
2. Remove the neutralized `addEventListener` review handlers now housed in
   `js/features/admin-dashboard/index.js` once the browser baseline confirms
   one mutation per click.
3. Extract the remaining engines: restore or pin the tracking-modal markup and
   extract the tracking engine; then extract report submission together with
   the add-report map engine as one module (per the fourteenth-wave scout).
4. Extract the maps last: they share `map`/`selectedLat`/`selectedLng` state
   and five initializers. `redIcon` should move into that module and the
   temporary `window.redIcon` bridge should be removed then.
5. Revisit consolidation as separate changes: API helpers and map utilities.

A literal one-shot rewrite of the remaining coupled workflows is not safe:
those flows own session, request counts, map instances, observers, and
WebSocket refresh, and their behavior is not yet pinned by tests or browser
runs. The module pattern is proven; use it per feature.
