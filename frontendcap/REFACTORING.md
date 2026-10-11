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
- `npm run build` generates only `dist/index.html` from the admin dashboard
  template. It does not explicitly emit login, barangay, CEO, or named admin
  dashboard pages. Its copy rules also omit `js/config.js`,
  `js/forecasting_ui.js`, and `sjdm_barangays.geojson`.
- `vercel.json` only enables clean URLs; it does not establish the build command
  or published directory. The root README does not document deployment.

The intended published routes, output directory, and script-loading arrangement
must be confirmed and corrected in a separate change before browser workflow
coverage or feature initialization changes. A passing bundle build alone does
not verify a working deployment.

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

Before touching review registration, exercise both existing handler paths:
`addEventListener` and `.onclick` can coexist. Do not assume that
`stopImmediatePropagation` prevents a listener that already ran from mutating.

## Next feature boundary

Notifications is the next candidate, after the runtime and browser baseline
are established. Move its state, requests, rendering, events, and cleanup
together. Use one idempotent `initNotifications` entry point and an explicit
refresh operation. Document which of startup, navigation, successful mutations,
and WebSocket messages trigger refresh; do not add a second refresh owner.

Retain existing HTML-callable globals through a small compatibility bridge
until their individual inline handlers are migrated. Every listener, observer,
subscription, and timer needs an owner and cleanup path. Keep review, repair,
and tracking workflows unchanged until this migration pattern is validated.

Consolidation of API helpers, toast implementations, map utilities, refresh
mechanisms, templates, and CSS should remain separate behavior-tested changes,
not part of mechanical extraction.
