# Browser baseline checklist

No authenticated browser workflow has been exercised against this codebase yet.
Every refactoring note in `REFACTORING.md` ends with some variation of "this still
needs the browser baseline", and the DOM contract audit
(`DOM_CONTRACT_FINDINGS.md`) shows why that matters: 277 element references in the
feature modules point at markup that is not on the page.

This is the script for that baseline. Run it once end to end before any
deployment, then re-run the affected section after any change to a dashboard
template.

---

## 0. Preconditions

| Requirement | How to check |
|---|---|
| Backend running with a real database | `curl -s localhost:8080/actuator/health` → `{"status":"UP"}` |
| `SPRING_PROFILES_ACTIVE=dev` (or `prod` + all secrets) | Backend startup log; a missing `JWT_SECRET` under `prod` must abort startup |
| Seeded accounts for all three roles | Barangay official, CPDO admin, CEO |
| A road list and at least one barangay | Admin → Barangay & Road Management |
| Build is current | `npm run verify` (unit tests + DOM contract + a build check) |
| Backend origin known to the build | `ROADWISE_API_BASE_URL` set (see §6). A plain `npm run build` refuses to run without it |

Run the frontend:

```bash
ROADWISE_API_BASE_URL=https://your-backend npm run build   # then serve dist/
# or, for iterative work:
npm start        # webpack dev server; pass --port <n> if the backend already holds :8080
```

Prefer the built `dist/` output for the baseline run: it is what actually
deploys, and it is the only path where the API-base injection (§6) is exercised.
`npm start` serves the source tree instead, so the pages load `js/app.js` as
authored rather than the bundle — useful for iterating, not for verifying.

Open DevTools → Console and Network before logging in, and keep both visible for
the whole run. **A silent no-op is the failure mode here, not a crash** — the
modules guard every lookup, so a missing element produces nothing at all. If a
click does nothing, the console is usually empty; open
`DOM_CONTRACT_FINDINGS.md` and check whether that flow is on the known-missing
list before filing a bug.

Record for each step: **PASS / FAIL / BLOCKED**, the console output, and the
failing request/response if any.

---

## 1. Barangay official — report intake

| # | Step | Expected |
|---|---|---|
| 1.1 | Open `login.html`, sign in | MFA challenge (email OTP or authenticator) appears; after verification, redirect to `barangay_dashboard.html` |
| 1.2 | Sidebar → **Add Report** | Wizard step 1 visible; the City Road dropdown is populated from `/api/roads`, not hardcoded |
| 1.3 | Fill road details, click through the steps | `goToFormStep` advances; no console errors |
| 1.4 | Attach a JPEG under 5 MB, pick a GPS point on the map | Image preview renders; the map marker moves |
| 1.5 | Click **Submit Report** | `#confirm-modal` opens with the entered values (this modal exists — if it does not open, that is a regression) |
| 1.6 | Confirm | `POST /api/reports` returns 200/201; toast confirms; form resets |
| 1.7 | Re-submit the same photo | Duplicate detection responds with a conflict/warning, not a silent second record |
| 1.8 | Reload the page, open **My Reports** | The new report is listed with status *Pending Review* |

Known gap: the barangay *view* and *edit* modals (`#view-modal-*`, `#edit-modal-*`)
are missing — see `DOM_CONTRACT_FINDINGS.md`. If your seed data leads you to
those modals, expect a no-op; it is already tracked.

## 2. CPDO admin — review and dispatch

| # | Step | Expected |
|---|---|---|
| 2.1 | Sign in as admin → **Reports** | Inbox lists pending reports with severity badges |
| 2.2 | Click **Review** on the report from 1.6 | `#review-modal` opens with photo, GPS, audit trail |
| 2.3 | Click **View Map** | Leaflet map initialises inside the modal (not a grey box) |
| 2.4 | Click **Reject & Return** with a reason | `#reject-feedback-form` appears; submitting moves the report to *Rejected* and the barangay sees the reason |
| 2.5 | Reopen the report, click **Approve & Send to CEO** | Status → *Dispatched to CEO*; the row leaves the pending queue |
| 2.6 | Repeat 2.5 for a second report, then **Generate Report → Priority List** | Priority view renders HIGH/CRITICAL roads only |

## 3. CEO — repair execution

| # | Step | Expected |
|---|---|---|
| 3.1 | Sign in as CEO → **Repair Projects** | The dispatched report is listed |
| 3.2 | Click **Manage Repair** | CEO modal opens with the original assessment and submitter |
| 3.3 | **Mark as In Progress** | Status → *In Progress*; the admin dashboard reflects it on next refresh (WebSocket `REFRESH_DASHBOARDS`) |
| 3.4 | Upload the "after" photo, add repair notes, **Mark as Completed** | Status → *Completed*; the after-photo uploads to storage |
| 3.5 | Open **Manage Repair** on a second project and request a schedule extension | Extension request saved with a target date and justification |

Known gap: the CEO manage/extension/deferral modals (`#ceo-manage-*`,
`#ceo-extension-*`, `#ceo-deferred-*`, `#batch-defer-modal`) are missing. Steps
3.2–3.5 will no-op unless those blocks are restored first — check
`DOM_CONTRACT_FINDINGS.md § CEO` before starting this section.

## 4. CPDO admin — tracking and closure  ← primary verification

This is the section that was fully broken. `#tracking-modal` was deleted in
`6e02540` and restored now, so it is the highest-value thing to verify.

| # | Step | Expected |
|---|---|---|
| 4.1 | Admin → **Repair Tracking** | Table lists *Dispatched to CEO*, *In Progress*, *Completed*, *Pending Budget* rows only |
| 4.2 | Click **Track** on the in-progress project | `#tracking-modal` opens showing project id, road detail grid, submitter, damage type/dimensions/area, damage photo (signed/authenticated fetch, no broken image icon) |
| 4.3 | Expand **View Full Lifecycle & Audit History** | Timeline loads from `/api/reports/{id}/timeline` and lists submitted → reviewed → dispatched → in progress |
| 4.4 | Click **Locate on Map** | `#track-modal-map` initialises with a marker at the report GPS |
| 4.5 | Check the target-date row | `#track-modal-schedule-info` is visible with a target date and days-remaining text |
| 4.6 | Click **Send Due Reminder** (appears within 2 days of target) | Button disables, toast confirms; reopening the modal within 12 h shows the cooldown state |
| 4.7 | Close and reopen the modal | Map is re-hidden on open, timeline is collapsed, no stale values from the previous project |
| 4.8 | Track the *Completed* project | Proof-of-repair photo and CEO remarks render; **Approve & Close Project** is enabled; **Require Rework** is visible |
| 4.9 | Click **Require Rework**, type a reason, submit | Status returns to *In Progress*; the CEO sees the rework banner |
| 4.10 | Track the completed project again → **Approve & Close Project** | Status → *Closed*; row leaves tracking; project appears in **Archive & Reports** |
| 4.11 | Track a *Pending Budget* project | Deferral banner shows the CEO justification; the primary action reads **Acknowledge & Archive** |
| 4.12 | Open the audit trail for the closed project | Timeline shows the close event with actor and PST timestamp |

## 5. Cross-cutting checks

| # | Check | Expected |
|---|---|---|
| 5.1 | Leave a dashboard idle for > 15 min, then click any data action | The request 401s, a refresh is redeemed silently, the action completes. **No redirect to login.** (This is the new refresh-token path — verify in the Network tab that `/api/auth/refresh` is called once, then the original request replays.) |
| 5.2 | Reload a dashboard after > 15 min idle | The session-verify bouncer issues one `/api/auth/refresh` and stays put instead of bouncing to `login.html` |
| 5.3 | Sign out | Returns to `login.html`; `localStorage` has no `accessToken`/`refreshToken`; reopening a dashboard URL redirects to login |
| 5.4 | Log in in a second tab, sign out in the first | The second tab receives the cross-tab logout and redirects |
| 5.5 | Network tab, whole run | Requests go to exactly one host — the one you configured in §6. A second host, or requests hitting the page's own origin, means the build was made unconfigured |
| 5.6 | Console, whole run | No `[RoadWise] No backend base URL is configured` error |
| 5.7 | Trigger an AI severity assessment on a low-confidence photo | Response carries `reviewRequired: true`; the UI shows it as needing human review rather than asserting a severity |

## 6. Deployment configuration checks

| # | Check | Expected |
|---|---|---|
| 6.1 | Run `npm run build` with no `ROADWISE_API_BASE_URL` | The build **fails** with an actionable message; no artefact is produced. This is the guard that stops an unconfigured bundle from shipping |
| 6.2 | Set `ROADWISE_API_BASE_URL` and rebuild | Build succeeds; `grep -c __ROADWISE_BUILD_API_BASE__ dist/js/api-base.js` → `0`, and the configured value appears instead |
| 6.3 | Load `dist/admin_dashboard.html` | `window.RoadWiseApiBase.resolve()` in the console returns the configured host |
| 6.4 | Load a page and check the script order | `js/api-base.js` is requested **before** `js/config.js`. A 404 on it means every call falls back to loopback (dev) or same-origin (deployed) and fails visibly — by design, never to a guessed host |

---

## Exit criteria

The baseline is complete when:

1. Every row above is PASS or explicitly waived in writing.
2. `npm run verify` is green (unit tests + DOM contract + production build).
3. `node tools/dom-contract.js` reports no new violations after the run — if
   the baseline grew, the run found real breakage that must be baselined
   deliberately or fixed.
4. The console log for the full run contains no uncaught errors.

## What to do with a failure

| Symptom | First thing to check |
|---|---|
| Click does nothing, console silent | `DOM_CONTRACT_FINDINGS.md` — the element may already be listed as missing |
| Click does nothing, console shows `TypeError: X is not a function` | `npm run audit:dom` — an inline handler with no window bridge |
| Broken image in a modal | `GET /api/reports/image/...` or the storage URL in the Network tab; 401 means the token was not attached, 404 means the object is gone from storage |
| Redirected to login unexpectedly | §5.1/§5.2 — refresh path; check `/api/auth/refresh` in the Network tab |
| Requests going to an unexpected host | §6 — `ROADWISE_API_BASE_URL` was not set at build time |
