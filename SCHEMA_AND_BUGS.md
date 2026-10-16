# Schema adjustments & bugs to address

Audited against `main` @ `02ee75e` (clean working tree). Produced from four
read-only audit passes — **schema drift** (entities vs Flyway), **backend
defects**, **frontend ↔ API contract**, **config/deploy/AI service** — followed
by an independent verification pass over the cited lines.

**Legend**

- ✔ — re-verified line-by-line in the main session against the cited source
- ◦ — reported by the audit pass with cited `file:line`; not re-read line-by-line

**Baseline at time of audit**: `frontendcap` `npm test` → 70/70 pass ✔;
`node tools/dom-contract.js` → 0 new violations, 410 baselined ✔.
Backend suites were **not** executed (Maven + Postgres required) — treat backend
items as static-analysis findings, not runtime-confirmed.

**Already tracked elsewhere — deliberately not repeated here**:
`PRODUCTION_READINESS.md` open items (CSP, deployed broker/WebSocket E2E
verification, remote-socket revocation), `DOM_CONTRACT_FINDINGS.md`
(277 missing element ids / 410 baselined violations), git-history purge of the
77 committed upload images.

---

## 0. Blockers — fix before the next demo or deploy

### B1. `GET /api/reports` returns a paged envelope; the frontend indexes it as an array ✔

- Backend: `RoadReportController.java:358-424` returns
  `PageResponseDto<ReportListDto>` (`{content:[…], page, size, totalElements, totalPages, last}`),
  default `page=0`, `size=50` (line 360-361, capped at 100).
- Frontend: `frontendcap/js/features/admin-dashboard/index.js:37-38` does
  `apiFetch('/api/reports').then(reports => … reports.filter(…) )` — a `TypeError`
  on every load; the admin inbox never renders. Grep shows the same
  un-unwrapped pattern in `ceo-dashboard`, `map-engine`, `priority-report`,
  `repair-tracking`, `report-generation`, `reports-archive`; several guard with
  `Array.isArray(reports)` and silently render **empty** instead of erroring.
- Impact: admin inbox, CEO dashboard, global maps, priority list, and repair
  tracking all see no data. Only the first 50 reports ever load even after a fix
  — every caller ignores pagination.
- Fix: unwrap once in `js/core/api.js` (if the body has `content` + `totalPages`,
  return `body.content`), or add an explicit `fetchPaged()` helper and convert
  call sites; then add a test that asserts the envelope is unwrapped.

### B2. Report read endpoints touch LAZY associations outside a transaction → 500 ✔

- `application.properties:38` `spring.jpa.open-in-view=false`; `RoadReport.java:106-114`
  maps `barangay` and `user` with `@ManyToOne(fetch = FetchType.LAZY)`.
- `ReportListDto.fromEntity` (`ReportListDto.java:33`) calls
  `r.getBarangay().getBarangayName()`; `RoadReportController.java:415-418`
  (list), `462-469` (`GET /{id}` returns the raw entity → Jackson walks
  `barangay`/`user`), `473-481` (`/barangay/{id}` returns raw entities) — none
  of these paths is `@Transactional` (first `@Transactional` in the file is at
  line 489).
- Impact: `GET /api/reports`, `GET /api/reports/{id}`, and
  `GET /api/reports/barangay/{id}` throw `LazyInitializationException` (500)
  as soon as any returned report has a linked barangay/user. This fires *before*
  B1's frontend bug even matters.
- Fix: annotate the read methods `@Transactional(readOnly = true)` and/or use
  `@EntityGraph(attributePaths = {"barangay", "user"})`, or return DTO
  projections with the join fetched.

### B3. V13 requires PostGIS at migrate time — Flyway failure prevents boot ✔

- `V13__add_postgis_report_geography.sql:16` runs `CREATE EXTENSION IF NOT EXISTS postgis;`
  and line 20-30 declares `geography(Point, 4326)` **unqualified**.
- The migration's own comment (lines 8-11) says a role with database `CREATE`
  privilege is required and the failure is deliberate ("fails fast at deploy
  time"). That is fine on the documented Supabase path; it is a boot blocker
  anywhere the extension/create privilege or `extensions` schema `search_path`
  differs (CI database, fresh container, restricted managed DB).
- Fix (pick one): pre-create the extension in the Supabase dashboard and keep
  V13 as-is; or set `search_path`/qualify `extensions.geography`; or move
  extension creation to a dedicated operator step and make V13 conditional on
  the type existing.

### B4. Flyway settings re-run non-idempotent V1–V19 against a pre-existing database ✔/◦

- `application.properties:41-44`: `baseline-on-migrate=true`,
  `baseline-version=0`, **`repair-on-migrate=true`**.
- With that combination, a database whose schema already exists but has no
  Flyway history gets the whole chain applied from V1. The chain is written for
  a fresh database and is **not** fully idempotent:
  - `V6__align_entity_and_database_schema.sql:64` —
    `ALTER TABLE notifications ALTER COLUMN recipient_id SET NOT NULL;` with no
    backfill (legacy rows can hold NULL; `recipient_id` is the renamed legacy
    `user_id`, line 58-61). ✔
  - `V12__add_unique_email_and_temp_password_fields.sql:4` —
    unguarded `ALTER TABLE users ADD CONSTRAINT uk_users_email UNIQUE (email);` ✔
  - V6 also destructively drops `barangays.name`, `notifications.report_id`,
    `report_status_logs.changed_by_user_id`, `activity_logs.details/created_at/user_id`
    (V6:11, 68, 101-103, 156) — data loss on a legacy DB. ✔
- `repair-on-migrate=true` additionally auto-realigned checksums, which can mask
  edited/misapplied migrations instead of failing loudly. ◦
- Fix: for the existing production DB, baseline at the correct version
  (`baseline-version` = the last applied migration) instead of 0; remove
  `repair-on-migrate=true` from production; make V6/V12 idempotent (guarded
  `DO $$ … END $$;`, backfills before `SET NOT NULL`).

---

## 1. Schema adjustments (Flyway / DB)

### S1. Barangay-name uniqueness was silently dropped ✔

- V1 created `barangays.name VARCHAR(255) NOT NULL UNIQUE` (`V1__baseline_schema.sql:3-5`).
  V6 adds `barangay_name`, backfills, then drops the legacy `name` column
  (`V6:8-11`). No `UNIQUE` replaces it, and V4 (the constraints migration)
  contains no barangay constraint.
- Impact: duplicate barangay rows can be created; "the barangay" lookups by name
  become ambiguous.
- Fix: add a migration `ALTER TABLE barangays ADD CONSTRAINT uk_barangays_name UNIQUE (barangay_name);`
  (after deduplicating any existing rows).

### S2. `users.email` carries two duplicate unique constraints ✔

- `V4__add_constraints_and_status_values.sql:6-10` creates `unique_user_email`;
  `V12__...sql:4` creates `uk_users_email`. Two names, two indexes, same column.
- Fix: drop one index in a new migration (keep the V4 name or rename
  consistently; the guarded form should be `ADD CONSTRAINT IF NOT EXISTS`-style
  `DO $$` block).

### S3. `city_roads.road_id` is neither unique nor indexed, but the repository treats it as a key ✔/◦

- `V6:22` adds `road_id VARCHAR(100)` (no constraint); `CityRoadRepository.java:15`
  declares `Optional<CityRoad> findByRoadId(String)` and `resolveCityRoad`
  matches on it during report submission (`RoadReportController.java:228-237`). ◦
- Impact: two roads can share a code and report creation resolves an arbitrary
  one; lookups are sequential scans.
- Fix: dedupe + `CREATE UNIQUE INDEX … ON city_roads (road_id) WHERE road_id IS NOT NULL;`

### S4. V7's index on `date_submitted` silently no-ops due to a name collision ◦

- `V6:50` already created an index with that name; V7 re-declares it with
  `IF NOT EXISTS` (`V7__add_performance_and_duplicate_indexes.sql:3`), so the
  intended (different) index never gets built.
- Fix: verify the actual index definition with `\d road_reports`, then drop and
  recreate with the wanted sort order under a distinct name.

### S5. Dead columns written by no code ◦

- `road_reports.rejection_reason` / `deferral_reason` (V1:54-55, re-added in
  V6:165-166) — the entity does not map them; rejection text lives in
  `admin_remarks`/status logs.
- Other unmapped columns: `V1:30-35` (city_roads legacy attributes kept "intact"
  by V6), `V1:107-109`, `V6:115` (`system_settings.setting_key`).
- Fix: either map them, or drop them in a migration — dead columns invite future
  writes that go nowhere.

### S6. Entity widths / nullability vs DDL drift ◦

- `User.java` (default 255) vs `V1:12,21` (`role`, `status`, `gender`,
  `phone_number VARCHAR(50)`). `Notification.message` is `length=500` vs `TEXT`.
  `CityRoad.barangay` is required in the entity (`CityRoad.java:21`) but nullable
  in the DB (`V1:35`). Hibernate `validate` does not catch width/nullability, so
  this only shows up as runtime truncation/insert failures.
- Fix: align the entity annotations with the DDL (or vice versa) — cheap now,
  painful later.

### S7. The test profile cannot see any of this drift ◦

- `src/test/resources/application-test.properties:7,10` — H2 `create-drop` with
  Flyway off. Schema findings can never fail CI.
- Fix: add one Flyway-on-Postgres test (or a Testcontainers `validate` boot test)
  so entity↔migration drift is caught.

### S8. Migration hygiene nits

- `V14` and `V15` are near-duplicates of the same `activity_logs` creation ◦ —
  harmless today but they encode the "missing table" incident; consider a
  comment consolidating their intent.
- `MfaChallenge` attempt budget differs between the DB default and the Java
  default (`V2:8` vs `MfaChallenge.java:15,53`) ◦ — pick one.
- `purgeExpiredBefore` deletes every status, but its index is partial on
  `status='CONSUMED'` (`WebAuthnCeremonyRepository.java:47`, V19) ◦.
- Missing functional indexes for case-insensitive derived queries and the yearly
  rollover scan (`RoadReportRepository.java:20,101`, `UserRepository.java:22`,
  `BarangayRepository.java:8`, `CityRoadRepository.java:18`) ◦.
- Non-idempotent DDL contradicts the surrounding "idempotent" comments (V12:4) ◦.

**Schema clean bill (verified)**: every entity field maps to an existing column
(no `ddl-auto=validate` startup blocker) ◦; the V13 `geom` column is
intentionally unmapped and used only via the native query `RoadReportRepository.java:50` ✔;
`@Version` columns exist for `users` and `road_reports` ◦; V14/V15/V2 agree on
`activity_logs` ◦; duplicate detection falls back to the bounding-box query when
PostGIS is absent (`DuplicateDetectionService.java:108-123`) ◦.

---

## 2. Backend bugs

### BK1. `createReport` is not transactional — partial writes on failure ✔

- `RoadReportController.java:200-202` (no `@Transactional`) then `:286`
  `repository.save(report)`, followed by timeline log, notification, activity
  log, and image handling. Any later failure commits the report while the client
  sees an error; a resubmit creates a duplicate.
- Fix: `@Transactional` on the handler (or move the orchestration into a
  transactional service method).

### BK2. Admin email change is unverified and does not revoke sessions ✔

- `UserController.java:498-510` — format + uniqueness check only, then
  `user.setEmail(newEmail)`. No step-up, no notice to the old address, and no
  `revokeUserSessionsAndChallenges` (the status branch at `:521-523` and the
  self-service path at `:1070-1072` both revoke).
- Impact: a compromised admin token can repoint an account's OTP/recovery
  mailbox; the victim's existing sessions stay alive.
- Fix: reuse the self-service `email-change/request`+`confirm` flow (or at
  minimum require step-up, notify the old address, and revoke sessions).

### BK3. Account-lockout DoS keyed on a known username ◦

- `AuthController.java:224-235` increments `failedLoginAttempts` on the *target*
  account on every wrong password and locks it after 5. Usernames are enumerable
  (user listings). The per-IP/`USER:` rate limiter only slows the attack.
- Fix: separate per-IP throttling from account lockout, or require a successful
  factor before extending a lockout.

### BK4. Password change reuses the login lockout counter ◦

- `UserController.java:273-292` — five mistyped "current password" attempts lock
  the user out of **login** for an hour. An admin can deliberately lock any
  account this way.
- Fix: rate-limit password-change attempts with `RateLimitService` instead of the
  login-lockout columns.

### BK5. Barangay detail endpoint leaks the official's phone/email to any authenticated user ✔

- `BarangayController.java:126-132` fills `contactNumber`/`emailAddress` from the
  assigned official and returns the entity to any `isAuthenticated()` caller
  (`:119`), defeating the deliberate stripping in the list endpoint (`:100-108`).
- Fix: return a projection that omits those fields, or gate them to admin roles.

### BK6. Unbounded reads and N+1 dashboard queries ◦

- `ActivityLogController.java:35` (`findAllByOrderByTimestampDesc()`),
  `UserController.java:335-338` (`findAll()`), `RoadReportController.java:481`
  (all reports of a barangay). Dashboard summary issues 1+3N queries.
- Fix: paginate these endpoints and aggregate dashboard stats with `GROUP BY`.

### BK7. Dashboard cache is node-local in a multi-instance deployment ◦

- `CacheConfig.java:23` Caffeine; `@CacheEvict(value = "dashboardStats", …)` is
  process-local (e.g. `RoadReportController.java:202`). The deployment guidance
  (`application-prod.properties`, README of item 6) expects multi-instance
  behind the RabbitMQ relay, so other nodes serve stale dashboards.
- Fix: shared cache (Redis) or broadcast eviction on the existing fan-out.

### BK8. Nits

- `serveImage` uses `||` where `&&` is intended — `RoadReportController.java:1396` ✔.
- `getAdminId()` still exists in three controllers despite `PRODUCTION_READINESS.md`
  item A claiming it was removed (`AuthController.java:106-109`,
  `RoadReportController.java:117-120`, `BarangayController.java:49-52`) ✔ — and it
  picks an arbitrary admin, possibly deactivated. Align doc and code.
- `GET /api/settings` performs a write (`SystemSettingsController.java:19-24`) ✔ —
  seed row `id=1` in a migration instead.
- Deadline reminder writes `last_reminder_sent` but never reads it
  (`RoadReportController.java:1049-1051`; column `RoadReport.java:136`) ✔ — an
  admin can spam the engineer; enforce a cooldown.
- Silent `catch (Exception ignored)` on `targetCompletionDate` parse
  (`RoadReportController.java:563`), not-found `IllegalArgumentException` mapped
  to 400 instead of 404 (`:465`, `:1122`) ◦.
- `GlobalExceptionHandler.java:48` returns raw `ex.getMessage()` in the 400 body ✔ —
  inconsistent with the generic handler at `:91` which returns a fixed string.

---

## 3. Frontend / API-contract bugs

### FE1. Stored XSS in the notification list ✔

- `frontendcap/js/features/notifications/index.js:79,82` interpolate
  `notif.title` / `notif.message` raw into `innerHTML` (while `data-title`/`data-message`
  attributes at `:72-73` do use escaped values). Notification text embeds
  submitter and road names from user input (e.g. `RoadReportController.java:333-336`).
- Fix: escape with `textContent`/the existing safe helper; add a test with a
  `<img onerror>` title.

### FE2. `ReportListDto` omits fields the UI reads ✔/◦

- `ReportListDto.java:8-20` has no `latitude`, `longitude`, `damageDescription`,
  `damageLength/Width`, `adminRemarks`, `rejectionReason`, or
  `proofOfRepairImage`; list/review code reads them
  (`admin-dashboard/index.js:91`, archive/tracking rows) ◦.
- Impact: review modals render blanks (pin shows `(undefined, undefined)`), and
  rejection reasons are invisible in list views, even though the detail endpoint
  has the data.
- Fix: add the missing fields to `ReportListDto`, or fetch `GET /api/reports/{id}`
  for modal rendering (that path is currently broken by BK2-style lazy loading).

### FE3. `NO_REFRESH_ENDPOINTS` excludes post-login MFA endpoints ✔

- `frontendcap/js/core/api.js:104` —
  `/^\/api\/auth\/(login|refresh|logout|verify-mfa|mfa\/|forgot-password|reset-password|step-up)/`
  matches the `mfa/` **prefix**, so `/api/auth/mfa/overview`,
  `/api/auth/mfa/factors/**`, and `/api/auth/mfa/recovery-codes` never trigger a
  refresh; an expired access token during MFA management forces a hard logout.
- Fix: exclude only the pre-session endpoints (`login|refresh|logout|verify-mfa|forgot-password|reset-password|step-up`).

### FE4. Report submission sends fields the create DTO drops ✔

- `frontendcap/js/features/report-submission/index.js:145-170` posts
  `roadImportance`, `roadType`, `terrainType`, `inventoryYear`, `barangayId`,
  `userId`; `ReportCreateDto` has none of them.
- Today the server derives road attributes from the resolved `CityRoad`
  (`RoadReportController.java:235-237`) and defaults `inventoryYear` in the entity
  (`RoadReport.java:130-132`), so values are *ignored, not corrupted*. Keep as a
  nit: remove the dead form fields or wire them intentionally.
- `barangayId`/`userId` in the payload are worth a second look from a security
  standpoint — confirm the controller ignores them entirely (it does ✔) and
  consider stripping them to avoid implying client-trusted scoping.

### FE5. Nits ◦

- Dead fallback to a non-existent endpoint `/api/city-roads`
  (`admin-dashboard/index.js:647`, `reports-archive/index.js:391`).
- `/api/users` called from screens non-admins reach (403 → silently degraded
  output; `reports-archive/index.js:757`).
- Nested `DOMContentLoaded` listener that can never fire
  (`profile-header/index.js:171`).
- Document-level `click` listener accumulates each time the add-user modal opens
  (`administration/index.js:172`).
- Road-dropdown `change` listener re-attached on every load
  (`report-wizard/index.js:210`).
- Leaflet popups inject road/status strings as HTML
  (`map-engine/index.js:250-256`) — same class as FE1.
- Forecasting UI renders a simulated curve labelled `[Demo Fallback - API Offline]`
  when the proxy is down (`forecasting_ui.js:27-31`) — prefer an explicit
  "forecast unavailable" state.

---

## 4. Config, deployment, AI service

### CF1. Committed, working dev JWT signing key ✔

- `application-dev.properties:6` `jwt.secret=45G2FZdH…` — valid 32-byte Base64
  HS256 key (passes `JwtTokenProvider.java:31-39`). If `SPRING_PROFILES_ACTIVE=dev`
  is ever set on a host, everyone who can read the repo can mint tokens.
- Fix: no committed key; require `JWT_SECRET` like prod does.

### CF2. `robots.txt` is corrupted and ships an ngrok host ✔

- `frontendcap/robots.txt` (47 lines) is three pasted code snippets: two copies
  of an old `js/config.js` (one with `https://vitamins-april-unify.ngrok-free.dev`,
  line 40) and a Java `CorsConfig` class (line 19). It is copied verbatim into
  the production build (`webpack.config.prod.js:85`).
- Impact: no crawl directives at all, and a public tunnel hostname + stale config
  are served as `/robots.txt`.
- Fix: replace with a real robots file.

### CF3. `ai.service.url` fallbacks are dead and divergent ✔

- `application.properties:77` defines `ai.service.url=${AI_SERVICE_URL:}` (empty),
  so the `@Value` defaults never fire: `RoadInspectionService.java:50`
  (`https://roadwise-ai-service.onrender.com`) and
  `ForecastingOrchestratorController.java:27` (`http://localhost:8000`).
- Behavior when unset: `RoadInspectionService.java:142-177` fails **safe**
  (`AI_UNAVAILABLE` + `reviewRequired`, no invented severity) ✔; forecasting
  returns 500 and the UI draws the demo fallback (FE5).
- Fix: `${ai.service.url:}` on both defaults, or keep explicit defaults in one
  place (properties).

### CF4. Hardcoded `frontend.url` defaults ✔

- `UserController.java:64` and `WebSocketConfig.java:41` default to
  `https://roadwise-csjdm.com`; `WebConfig.java:52` defaults to
  `http://localhost:63342`. `application.properties:85` sets `frontend.url=${FRONTEND_URL:}`
  (empty) and prod requires it (`application-prod.properties:5`).
- Impact: with no profile and no env var, CORS/WS origins end up empty or mixed;
  email links can point at the wrong host. One source of truth, please.

### CF5. RabbitMQ AMQP defaults to `guest/guest` ✔

- `application.properties:115-116`. The STOMP relay fails startup when creds are
  missing (`WebSocketConfig.java:66-70`), but the AMQP path used for
  cross-instance session revocation does not.
- Fix: empty defaults + fail-fast, matching the relay.

### CF6. AI service leaks raw exceptions and an unauthenticated config payload ✔/◦

- `main.py:280` (and `:295,:310,:324,:339,:354`) return
  `detail=f"Inference error: {str(e)}"`; `GET /` returns `csjdm_profile:
  CSJDM_GEOMORPHIC_PROFILE` without the API-key dependency (`main.py:174`) ◦.
- Fix: fixed detail strings; drop/gate the profile.

### CF7. The documented road-scene gate is not implemented in `/predict` ✔

- `validation.py:284` defines `RoadSceneGate` and the docstring (`validation.py:15-16`)
  claims the road gate runs before severity; `predict_severity` never
  instantiates it (`main.py:198-280`), and `is_non_road_upload` (`main.py:89`) is
  never called.
- The backend even reads a `roadConfidence` field the service never returns
  (`RoadInspectionService.java:164-165`).
- Impact: a non-road image that clears brightness/saturation/blur heuristics is
  classified into `Low/Medium/High` with no OOD gate (low confidence still
  triggers `reviewRequired`, which limits the damage).
- Fix: wire `RoadSceneGate` before classification, or correct the docstring and
  drop `roadConfidence` from the backend.

### CF8. Nits ✔/◦

- Actuator exposes `health,metrics,info` (`application.properties:32`) to **any**
  authenticated role — only `/actuator/health` is public (`SecurityConfig.java:38,44`) ✔.
  Restrict metrics to admins or drop them in prod.
- `AI_ENV=production` in the AI Dockerfile (`Dockerfile:28`) hard-enables the
  `MODEL_SHA256` checksum gate (`main.py:31-36`); a missing checksum silently
  disables all classification (every report becomes `Unassessed`). Make it a
  hard startup failure like the JWT guard. ◦
- `api.service.api-key` (`application.properties:80`) is fail-closed on the
  service side (`main.py:56-63`) but compared with `!=` (not constant-time) ◦.

---

## Suggested order of work

1. **B2** (backend 500s block everything else) → then **B1** (envelope unwrap +
   paging) — these two together restore the core report lists.
2. **B3/B4** (deployment safety) before any fresh-database or legacy-database
   migration run; add the Postgres validate test (S7).
3. **BK1/BK2/BK5** (transactional create, email-change hardening, PII leak) and
   **CF1/CF2** (committed key, robots) — small, high-value.
4. Schema cleanup batch: **S1/S2/S3** plus dropping dead columns (S5).
5. **FE1/FE3/FE2** (XSS, refresh regex, DTO fields) and the remaining nits.
6. Re-run `npm run verify` (tests + DOM ratchet + build) after each batch; run
   the backend suite once a Postgres is available.
