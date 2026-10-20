---
date: 2026-10-20T08:25:53+0800
author: Admin
commit: 9054524
branch: main
repository: Roadwise-SRIMS-Rework-
topic: "Security hardening gaps — lockout DoS, password-change lockout reuse, barangay PII exposure, AI-service config/auth, actuator authorization"
tags: [research, security, auth, rate-limiting, actuator, ai-service, pii]
status: ready
last_updated: 2026-10-20T08:25:53+0800
last_updated_by: Admin
---

# Research: Security hardening gaps at `9054524`

## Research Question

Six security findings were re-verified as still-present at `main` @ `9054524`, having originally been catalogued in `SCHEMA_AND_BUGS.md` against `02ee75e`. Scope the fix for each: what the current code actually does, what the blast radius of changing it is, and what the design stage must decide.

The six, with their `SCHEMA_AND_BUGS.md` identifiers:

| ID | Finding | Primary site |
|---|---|---|
| BK3 | Account-lockout DoS keyed on a known username | `controller/AuthController.java:226-231` |
| BK4 | Password change reuses the login lockout counter | `controller/UserController.java:277-291` |
| BK5 | Barangay detail endpoint leaks the official's phone/email | `controller/BarangayController.java:118-142` |
| CF6b | AI service returns an unauthenticated config payload | `roadwise-ai-service-main/roadwise-ai-service-main/main.py:234-240` |
| CF8b | API key compared with `!=` (not constant-time) | `roadwise-ai-service-main/roadwise-ai-service-main/main.py:64` |
| CF8c | Actuator `metrics`/`info` reachable by any authenticated role | `resources/application.properties:38`, `config/SecurityConfig.java:87` |

Deliberately out of scope: CSP / inline event handlers, `localStorage` token storage, git-history purge of committed upload images, deployed WebSocket two-instance E2E, step-up auth policy. Those remain `PRODUCTION_READINESS.md` open items.

## Summary

The six findings are real, but the catalogue's framing of three of them is wrong in ways that change the fix.

**BK3 and BK4 share one root cause, not two.** Two independent brute-force stores exist: `users.failed_login_attempts` / `users.lockout_until`, and the `login_rate_limits` table driven by `RateLimitService`. The `users` columns are written by the login path *and* by the password-change path, which is BK4. Worse, they never decay — no code path zeroes `failedLoginAttempts` when `lockoutUntil` lapses, so from the 15th failure onward every wrong password re-locks the account for the full 900 s on a counter that never resets. That is a defect independent of BK3 and independent of attackers.

**BK3's premise is understated.** `SCHEMA_AND_BUGS.md` says usernames are "enumerable (user listings)". They do not need to be: `AuthController.java:181-189` performs no bcrypt work when the user does not exist, so a single timed request distinguishes the two. Two further response branches answer before the password is verified. The enumeration channel is therefore independent of the lockout, which is why this research treats them as separable slices rather than one fix.

**BK5 is a real leak with no working consumer.** The in-place entity mutation does not persist (the entity is detached under `open-in-view=false`), `UserResponseDto` already reads `NULL` columns for the same data, and the only frontend reader sits behind a modal that exists in no template. The precedent for this exact endpoint (`64339b8`) chose "gate, don't strip" and was later recorded as the leak — so the fix must include a real authorization rule, not just removed fields.

**CF6b and CF8b are small and genuinely local.** No consumer of the AI service root endpoint exists anywhere in the repo; only its status code matters. `hmac` is never imported, and no commit has ever attempted a constant-time comparison. Both fixes are near-zero blast radius, with one caveat: `/docs`, `/redoc` and `/openapi.json` are also unauthenticated and already publish the `SoilType` enum, so dropping `csjdm_profile` does not by itself close the soil-name disclosure.

**CF8c is smaller than it looks but has a trap.** A filter-chain matcher is the only place the change can express "403 for non-admins", because the route lists can only express `.authenticated()`. The trap is that the role vocabulary has three incompatible interpreters, and `/actuator/health/liveness|readiness` do not match the exact `/actuator/health` entry — enabling probes later would silently start requiring a session.

## Detailed Findings

### The two brute-force stores (BK3 / BK4 root cause)

`RateLimitService` is the newer, correct store: rows in `login_rate_limits` keyed by `keyIdentifier`, with `SELECT ... FOR UPDATE` for cross-instance atomicity and `TransactionTemplate` with `REQUIRES_NEW` to make the first-attempt INSERT race recoverable. Its documented consumers are `STEPUP:`, `HANDOVER_OTP:`, `HANDOVER_EMAIL:`, `EMAIL_CHANGE_PWD:` and the recovery-code paths.

The `users` columns are the older store and predate it. Both login and password-change write them. They differ from `login_rate_limits` on every axis that matters:

- **Decay.** `login_rate_limits` rows expire 15 minutes after `firstAttemptAt` (`RateLimitService.java:56-59`, `isWindowExpired`) and are then deleted. `users.failed_login_attempts` never expires; only a successful login or password reset zeroes it (`AuthController.java:612`, `:906`; `UserController.java:307`, `:604`).
- **Penalty.** `login_rate_limits` caps at `MAX_PENALTY_SECONDS = 900` computed from the attempt count. `UserController.java:283-284` hard-sets a flat `now + 1 hour`.
- **Concurrency.** `login_rate_limits` is locked per key. The `users` columns are a plain read-modify-write on the user row.
- **Threshold.** Login locks at `attempts > 5` (the sixth failure, `AuthController.java:229`). Password change locks at `attempts >= 5` (`UserController.java:282`).

The never-decaying counter is the finding the catalogue missed. Because `lockoutUntil` is a timestamp and `failedLoginAttempts` is a separate count, the guard at `AuthController.java:194-202` short-circuits while the lock is live, but once it lapses the counter is still at 6, 7, 8 — so the next single wrong password immediately re-locks for the capped 900 s. Any design that keeps the `users` columns inherits this.

### BK4 — the password-change path

`UserController.java:273-296` is a near-verbatim copy of the login lockout logic, but it treats a mistyped *current password* — a legitimate re-authentication prompt — as a credential-stuffing signal against the same counter the login path reads.

The path is not reachable by an unauthenticated attacker, which limits BK3-style remote exploitation: it requires an authenticated session. That does not make it benign. A stolen or borrowed session, or an admin acting deliberately, can lock any account out of login for an hour per five attempts, and because the counter never decays, each subsequent attempt re-locks.

The retarget target already exists in the same file: `UserController.java:955-967` uses `RateLimitService.isBlocked` / `recordFailure` / `clear` on an `EMAIL_CHANGE_PWD:` key for the email-change flow. A `PASSWORD_CHANGE:<id>` key needs no new service surface.

Two migration costs are real. First, the maximum denial an attacker can impose shrinks from "1 hour, renewable indefinitely" to "≤15 minutes measured from the first attempt" — a security improvement that is also a behaviour change worth stating in the plan. Second, `isBlocked` deletes expired rows, so it is a write on a read path; a caller that invokes it before any validation still issues a locked `SELECT` plus possible `DELETE`.

### Rate-limit key namespacing

The `keyIdentifier` formats are constructed at each call site — there is no key-builder class. Observed prefixes: `STEPUP:`, `HANDOVER_OTP:`, `HANDOVER_EMAIL:`, `EMAIL_CHANGE_PWD:`, `RESET_EMAIL:`, `RESET_IP:`, and a bare `USER:` used by login.

Two properties matter for adding keys:

- **No normalization.** The `USER:` keys are derived from the raw submitted identifier, so `Admin`, `admin` and ` admin` produce three distinct rows for one account, and logging in by email rather than username produces a second row. The `clear` calls, by contrast, key off the canonical username. This asymmetry means a successful login does not necessarily clear the row a failed login created.
- **No collision risk for new prefixes.** A `PASSWORD_CHANGE:<userId>` key would be unique against every existing prefix, and userId is already the stable identifier used by `EMAIL_CHANGE_PWD:`.

### The stale-row INSERT bug in `RateLimitService`

`RateLimitService.java:111-115` (in `recordFailure`) and `:150-154` (in `recordRequest`) both do the same thing when the existing row's window has expired: they construct a **new** `LoginRateLimit` with the same `keyIdentifier` and save it — without deleting the expired row first. `keyIdentifier` carries a UNIQUE constraint (`LoginRateLimit.java:14-15`), so `saveAndFlush` inserts against a live unique key and raises `DataIntegrityViolationException`.

The retry wrapper does not help. `inNewTransactionWithInsertRetry` catches exactly that exception and retries once, but the retry re-enters the same branch — the stale row is still there — so the second attempt fails too and the exception propagates.

This is unreachable on the login path today because login's `USER:` keys are normally cleared on success, but it is reachable from any request-throttle path once a key sits idle past the 15-minute window: `RESET_EMAIL:` / `RESET_IP:` (`AuthController.java:782-789`) and `EMAIL_CHANGE:` (`UserController.java:971-977`). It has no test. Retargeting BK4 onto this service increases the number of callers that can trip it, which is why it belongs in the same change rather than a later one.

### Login enumeration oracles (BK3 scope expansion)

Beyond the lockout, `POST /api/auth/login` exposes four distinguishable branches, and three of them answer before the password is verified.

- **Unknown user vs wrong password.** `AuthController.java:181-189` returns an explicit not-found response and never invokes `passwordEncoder.matches`. No dummy hash is computed anywhere in the backend, so the two paths differ by a full bcrypt-10 verification plus a `users` UPDATE — an easily resolved timing gap.
- **Locked account.** `:194-202` returns 429 with its own body, before any password check. Reachable for an account whose identifier you only half-know: submitting the account's *email* hits the lockout guard (which resolves the user) while consuming a different `USER:` rate-limit key than the username does, so the per-identifier throttle never fills.
- **Temporary password expired.** `:204-212` returns 401 with its own body, again before the password check.
- **Legacy plaintext comparison.** `:215-220` compares non-bcrypt passwords with `String.equals`, which short-circuits on the first differing byte and is therefore both non-constant-time and a length proxy. It also implies plaintext passwords reached the database at some point.

The client is a co-conspirator in the leak: `frontendcap/js/features/auth/index.js:100` and `:141` render the server's `error` string verbatim, so unifying server responses only helps if the client keys off status and a stable `code` instead of echoing text. The success branch at `:105` has no `else`, which constrains how far the 200 shape can move.

Fixing enumeration also means giving the legacy path at `:215-220` a constant-time comparison, which is a distinct decision from the lockout retarget — hence the split into separate slices.

### BK5 — Barangay detail endpoint

`BarangayController.java:118-142` returns a `Barangay` entity after overwriting `brgyCaptain`, `contactNumber` and `emailAddress` in place with values from the first user in `userRepository.findByBarangayId(id)`. The route is `@PreAuthorize("isAuthenticated()")` at `:119` — no role.

Four facts bound the blast radius, and they make it smaller than the catalogue implies in one direction and larger in another.

- **The mutation does not persist.** `application.properties:44` sets `spring.jpa.open-in-view=false`, and the handler carries no `@Transactional`, so `brgy` is detached when `:130-131` write to it. Dirty checking never sees the change and nothing reaches the `barangays` table or the `@Cacheable("barangays")` cache at `:97`. The leak is response-scoped.
- **A latent hazard sits under that.** If anyone adds `@Transactional` to this handler for an unrelated reason, the same lines become a persistent write of official PII into the `barangays` table. The fix should not leave that trap armed.
- **The only frontend reader is dead.** `frontendcap/js/features/administration/index.js:646-651` reads the fields, but the `#barangay-modal` element it writes into exists in no template and is recorded as a known gap in `frontendcap/dom-contract-baseline.json:51`.
- **The data is already `NULL` on its other path.** `dto/UserResponseDto.java:92` reads `barangay.getContactNumber()` from the DB columns, which nothing writes — so the same fields resolve to `NULL` there regardless.

The repo's stated convention points away from returning entities at all: `UserResponseDto.java:9-14` warns that returning entities invites accidental disclosure. Precedent `64339b8` ("Fix PII leakage, LFI, temp password rotation, and proxy flaws") chose to gate rather than strip this endpoint, and the list endpoint's hard `null` at `:107-109` was added without touching the detail path — which is precisely how the inconsistency survived.

Adjacent, unvalidated writes exist on the admin-gated `POST`/`PUT` (`:62-73`, `:188`, `:213`), which bind `contactNumber`/`emailAddress` from the request body with no validation and return the raw entity. Those are not part of BK5's reachable surface but they are the source that would populate the columns if the detail write ever persisted.

### CF6b — AI service unauthenticated root endpoint

`main.py:234-240` (`@app.get("/")`) carries no `Depends(require_api_key)` and returns `service`, `model_loaded`, `auth_configured`, and `csjdm_profile` (the full `CSJDM_GEOMORPHIC_PROFILE` imported at `:17`).

- **No consumer exists.** Nothing in the Spring backend (`service/RoadInspectionService.java`, `service/ForecastingOrchestratorController.java`) and nothing in `frontendcap/` calls the AI service root path. Only the status code matters to a probe.
- **`csjdm_profile` and `auth_configured` are both droppable** with zero breakage. `auth_configured` is additionally a small information leak of its own: it tells an unauthenticated caller whether the shared secret is set.
- **The degraded signal must survive.** `main.py:243-245` sets `status: degraded` when `session is None`, which is the model-aware readiness signal. That is the part worth keeping.
- **Closing the payload does not close the disclosure.** FastAPI's default `docs_url`/`redoc_url`/`openapi_url` are never overridden (`main.py:22-26`), so `/docs`, `/redoc` and `/openapi.json` are also unauthenticated and publish the `SoilType` enum. An attacker loses the profile JSON but keeps the soil taxonomy.

### CF8b — constant-time shared-secret comparison

`main.py:60-70` implements `require_api_key` correctly on both failure axes: it returns 503 when `AI_SERVICE_API_KEY` is unset (fail-closed), and 401 when the header is missing or wrong. The comparison at `:64` is `x_api_key != AI_SERVICE_API_KEY` — byte-wise with early exit.

`hmac` is never imported in the service, and `git log -S compare_digest` returns nothing, so this has never been attempted. No test exercises it: the `test_forecasting_engines.py` fixture sends a valid key on every request, so both the wrong-key path and the root endpoint are untested.

The route split is 8 keyed routes against 1 unkeyed (`/predict`, `/predict-multi`, and six `/api/v1/forecasting/*` endpoints depend on the key; only `/` does not).

On the Spring side, `application.properties:123` sets `ai.service.api-key=${AI_SERVICE_API_KEY:}`. An empty value causes the header to be **omitted entirely** rather than sent empty — and nothing validates at startup that the two services' env vars agree. A whitespace or case mismatch produces a permanent 401 with no diagnostic.

### CF8c — actuator authorization and the role vocabulary

`application.properties:38` exposes `health,metrics,info`; `:39` sets `show-details=when-authorized`. In `SecurityConfig`, `/actuator/health` is in `PUBLIC_PATHS` (`:47`) and `/actuator/**` is the last entry of `AUTHENTICATED_PREFIXES` (`:87`), with a comment stating the choice was deliberate — "keep that rather than turning a monitoring scrape into a 403". First-match resolution in `filterChain` means `/actuator/health` is public and `metrics`/`info` require a session with no role check.

Three traps sit in this area.

- **Three incompatible role interpreters.** `@PreAuthorize("hasAnyRole(...)")` matches the stored string exactly (so `'CPDO Admin'` fails for a user stored as `CPDO_ADMIN`); `@roleCapabilities` is tolerant (strips `ROLE_`, uppercases); `@authz` delegates to `AuthorizationService` and reads DB permissions, ignoring Spring authorities entirely. A gate written in the wrong dialect silently denies or silently allows.
- **Liveness/readiness would break.** `/actuator/health/liveness` and `/actuator/health/readiness` do not match the exact `/actuator/health` string, so they currently require a session. If probes are enabled and those groups turned on, they would start returning 403 on a route that was public a commit earlier.
- **`show-details` has no roles set.** `when-authorized` with an unset `management.endpoint.health.roles` is treated by Boot as "any authenticated principal", so a low-privilege token already gets component detail from the supposedly public health path.

The route lists can only express `.authenticated()`, so the change has to be an inline matcher in `filterChain` ahead of the `/actuator/**` line, or a properties-level narrowing (`exposure.include=health` under the production profile), or both. No in-repo deployment config scrapes `metrics` or `info`; only `PerformanceBenchmarkTest:59` pins the health endpoint.

### Test surface

Zero test files reference `failedLoginAttempts` or `lockoutUntil` — confirmed by grep over `src/test`. The lockout is therefore entirely unpinned: `AuthControllerTest.testLoginFailure_InvalidCredentials` passes whether or not the columns exist, because it asserts the invalid-credentials response, not the counter.

What is pinned:

- `RateLimitServiceConcurrencyTest.exceededThresholdBlocksTheKey` is the only assertion tying a failure count to a block, so it is the one test that would catch a broken retarget. `concurrentFailuresDoNotLoseUpdates` asserts a final `attemptCount` of 31, so a "stop counting once blocked" refactor would break it.
- `EndpointAuthorizationCoverageTest` fails the build for any mapped endpoint not named by a route list, which is what makes `anyRequest().denyAll()` honest.
- `security/FieldScopePolicyTest` pins the field-level redaction policy used at `controller/RoadReportController.java:228-229`.

Assertions the fixes must add, none of which exist today: a wrong `currentPassword` does not set `lockoutUntil`; an unauthenticated `GET /` on the AI service does not return `csjdm_profile`; a low-privilege role gets 403 on `/actuator/metrics`.

### Schema coupling

Dropping `failed_login_attempts` / `lockout_until` needs a new `V25` migration using `DROP COLUMN IF EXISTS`; editing `V1` would break the replay guarantee for existing databases. The test profile runs H2 with `ddl-auto=create-drop` and Flyway disabled, so schema changes are invisible to the test suite — matches the precedent that the test profile breaks first on security changes.

## Code References

Backend paths are relative to `backend-master/backend-master/src/main/java/com/roadwise/backend/`.

- `controller/AuthController.java:181-189` — unknown-user branch; no bcrypt, explicit not-found response
- `controller/AuthController.java:194-202` — live-lockout 429, before password verification
- `controller/AuthController.java:204-212` — temp-password-expired 401, before password verification
- `controller/AuthController.java:215-220` — legacy plaintext `String.equals` comparison and re-hash
- `controller/AuthController.java:226-231` — `failedLoginAttempts` increment, `attempts > 5` threshold, `min(2^(n-5), 900)` backoff
- `controller/AuthController.java:612`, `:906` — the only non-password-change resets of the counter
- `controller/AuthController.java:782-789` — `RESET_EMAIL:` / `RESET_IP:` request throttle
- `controller/UserController.java:273-296` — password change reusing the login lockout; `>= 5` → flat `+1 hour`
- `controller/UserController.java:955-967` — the existing `EMAIL_CHANGE_PWD:` retarget pattern to copy
- `controller/BarangayController.java:96-112` — list endpoint hard-stripping the contact fields
- `controller/BarangayController.java:118-142` — detail endpoint, `isAuthenticated()`, in-place PII write, raw entity return
- `controller/BarangayController.java:59`, `:188` — the admin-gated write endpoints
- `dto/UserResponseDto.java:9-14` — stated convention against returning entities
- `dto/UserResponseDto.java:92` — reads `barangay.getContactNumber()`, always `NULL`
- `service/RateLimitService.java:52-54` — `WINDOW`, `FAILURE_THRESHOLD`, `MAX_PENALTY_SECONDS`
- `service/RateLimitService.java:111-115`, `:150-154` — expired-row branch that inserts without deleting; UNIQUE violation
- `service/RateLimitService.java:71-79` — the INSERT-race retry wrapper that cannot recover this case
- `model/LoginRateLimit.java:14-15` — `keyIdentifier` UNIQUE constraint
- `config/SecurityConfig.java:41-51` — `PUBLIC_PATHS`, incl. exact `/actuator/health`
- `config/SecurityConfig.java:76-87` — `AUTHENTICATED_PREFIXES` ending in `/actuator/**`
- `config/SecurityConfig.java:97-115` — first-match ordering and `anyRequest().denyAll()`
- `config/SecurityConfig.java:131-139` — `STEP_UP_PATHS`
- `resources/application.properties:38-39` — actuator exposure and `show-details`
- `resources/application.properties:44` — `open-in-view=false`
- `resources/application.properties:123` — `ai.service.api-key` env binding
- `roadwise-ai-service-main/roadwise-ai-service-main/main.py:22-26` — app construction, FastAPI docs defaults untouched
- `roadwise-ai-service-main/roadwise-ai-service-main/main.py:60-70` — `require_api_key`, fail-closed on both axes
- `roadwise-ai-service-main/roadwise-ai-service-main/main.py:234-245` — unauthenticated `GET /` payload and the `degraded` signal
- `frontendcap/js/features/auth/index.js:100`, `:141` — echoes server `error` strings verbatim
- `frontendcap/js/features/administration/index.js:646-651` — dead reader behind the missing modal
- `frontendcap/dom-contract-baseline.json:51` — records `#barangay-modal` as absent

## Integration Points

### Inbound References

- `service/RateLimitService.java` ← called from `AuthController` (login, recovery) and `UserController` (email change, handover); six `keyIdentifier` prefixes, all built at call sites.
- `users.failed_login_attempts` / `lockout_until` ← written by `AuthController.java:226-231` **and** `UserController.java:279-284`; read by `AuthController.java:194-202` and `UserController.java:275`.
- `GET /api/barangays/{id}` ← one frontend reader, `frontendcap/js/features/administration/index.js:646-651`, rendered into an element absent from every template.
- `GET /` on the AI service ← no caller in the repo; status code only.
- `/actuator/metrics`, `/actuator/info` ← no in-repo scraper; `PerformanceBenchmarkTest:59` pins `/actuator/health` only.

### Outbound Dependencies

- `UserController` password change → `RateLimitService` (target state), currently → `UserRepository` lockout columns.
- `RateLimitService` → `LoginRateLimitRepository.findWithLockByKeyIdentifier` (row lock), `PlatformTransactionManager` (`REQUIRES_NEW`).
- AI service → `AI_SERVICE_API_KEY` env; backend → `ai.service.api-key`, with no cross-validation.
- `SecurityConfig` → `JwtAuthenticationFilter` for authority population; `@PreAuthorize` → `AuthorizationService` for rank-based checks.

### Infrastructure Wiring

- `application.properties:38-39` — actuator exposure and health detail policy.
- `application.properties:44` — `open-in-view=false`, which is what makes the barangay mutation non-persistent.
- `application.properties:123` — AI service key binding.
- `SecurityConfig.filterChain` — the only place a role-conditional rule can be expressed; the static lists are `.authenticated()`-only.
- Flyway — `V25` needed if the lockout columns are dropped; test profile is H2 with Flyway disabled.

## Architecture Insights

- **The repo converges on one correct pattern and then leaves the old one in place.** `RateLimitService` postdates the `users` lockout columns and is used by five newer flows, but login and password-change were never migrated. BK3/BK4 are the residue of that partial migration, not independent defects.
- **Route authorization and method authorization are deliberately split.** `SecurityConfig` answers only "does this need a session?"; `@PreAuthorize` answers "who may do this". CF8c is awkward precisely because it needs a role question answered in the session-only layer.
- **The role vocabulary has three dialects** (`hasAnyRole` exact-match, `@roleCapabilities` tolerant, `@authz` DB-backed). Any new gate must pick a dialect deliberately.
- **Fail-closed is the house style** — `anyRequest().denyAll()`, `require_api_key` returning 503 when unconfigured, `jwt.secret=${JWT_SECRET:}` with an empty default. Fixes should preserve that posture rather than soften it.
- **Test coverage follows the new path, not the old.** `RateLimitServiceConcurrencyTest` is thorough; the `users` columns have zero tests. Migrating BK4 therefore increases the tests that apply to it.
- **Documentation drift is one-directional.** `SCHEMA_AND_BUGS.md` marks items `✔` for "re-verified line-by-line" but `✔` does not mean "fixed" — four post-audit fixes landed without updating it, and two of the six findings in scope had their remediation status misread as a result.

## Precedents & Lessons

8 commits verified in git history for this research.

### Precedent: PII leakage and proxy flaw sweep
**Commit(s)**: `64339b8` — "Fix PII leakage, LFI, temp password rotation, and proxy flaws" (2026-10-12)

**Takeaway**: this commit chose "gate, don't strip" for the barangay endpoint, added the list-endpoint `null` at `:107-109` without touching the detail path, and was later recorded as the surviving leak. Gating without a detail-path change is how BK5 survived.

### Precedent: rate-limit concurrency hardening
**Commit(s)**: the `RateLimitService` history — `synchronized` → `SELECT ... FOR UPDATE`, and `@Transactional` → explicit `TransactionTemplate`

**Takeaway**: the first attempt was process-local and the second was transaction-scoped-retry. Both failed for the same class of reason — a concurrency guarantee that held inside one JVM or one transaction. The stale-row INSERT bug at `:111-115` is the same failure mode a third time, and it is the one still untested.

### Precedent: fail-closed authorization and coverage test
**Commit(s)**: `448ece4` — "Implement ranked authorization, approvals, and break-glass controls" (2026-10-18)

**Blast radius**: authorization config, `@authz`, `EndpointAuthorizationCoverageTest`, plus the test-profile schema.

**Takeaway**: inverting the default to `denyAll()` broke the test profile first (H2 lacked the authz tables), which is the pattern to expect from any `SecurityConfig` change.

### Precedent: the test suite silently stopped running at build time
**Commit(s)**: `ebdc76a` — "Skip tests during Docker image builds" (2026-10-18), preceded by `626da2c` → `7ca4dbe` (a self-revert of the same Dockerfile change)

**Takeaway**: the fail-closed suite `448ece4` introduced no longer runs during image builds. Any assertion added by these fixes is not a build-time guarantee unless that is revisited.

### Precedent: AI-service hardening applied per surface
**Commit(s)**: `944f17e` / `f69a278` — "Harden AI inference image validation and backend fallback safety" (2026-10-10, the same change re-landed)

**Takeaway**: AI-service auth has been hardened one surface at a time and never as a set, which is why `/predict` is fail-closed while `/` and the FastAPI docs routes are open.

### Precedent: commit messages overstate scope
**Commit(s)**: `3fe21c1` — "Audit codebase and harden service-to-service test authentication" (2026-10-13) = 1 test file, 6 lines; `626da2c` → `7ca4dbe` is a self-revert.

**Takeaway**: mine diffs, not messages, when judging precedent scale.

### Composite Lessons

- **The first attempt is always process-local or order-dependent.** `synchronized`, retry inside a rollback-only transaction, `permitAll` above `authenticated`, a permission named `rank` — the recurring failure is a guarantee that holds only inside one scope. Verify the scope, not the syntax.
- **Docs drift one direction.** Fixes land without updating `SCHEMA_AND_BUGS.md`, and `✔` is re-verification, not remediation. Re-verify against HEAD before trusting any status in that file — this research exists because two of six in-scope findings had been misread in exactly that way.
- **Security changes break the H2 test profile first.** Expect schema gaps, not assertion failures.

## Historical Context (from `.rpiv/artifacts/`)

`.rpiv/artifacts/` does not exist in this repository — no research, design, or plan artifacts have been produced before this one. The repo's memory store is instead:

- `SCHEMA_AND_BUGS.md` — 443 lines, audited against `main` @ `02ee75e`; the origin of all six in-scope findings. Its own header states it deliberately excludes the `PRODUCTION_READINESS.md` open items.
- `PRODUCTION_READINESS.md` — 579 lines, "second pass"; holds the deferred hardening items (CSP, token storage, history purge, WebSocket E2E, step-up auth).

## Developer Context

**Q (BK3 scope — `AuthController.java:181-189`): Should BK3 cover the lockout mechanics only, or also the enumeration channel?**
A: Mechanics + enumeration unification. Add the dummy bcrypt compare on the unknown-user branch, move `:204-212` below password verification, and give the legacy plaintext path at `:220` a constant-time comparison. Accepted cost: the frontend (`frontendcap/js/features/auth/index.js:100`, `:141`) echoes server strings verbatim, so distinct messages still leak until the client keys off a stable `code`.

**Q (BK3/BK4 target — `UserController.java:277-291`, `RateLimitService.java:52-54`): Retarget onto `RateLimitService`, keep the columns with a purpose discriminator, or retarget plus fix the decay bug?**
A: Retarget onto `RateLimitService` using a `PASSWORD_CHANGE:<id>` key, following the `EMAIL_CHANGE_PWD:` pattern at `UserController.java:955-967`. Accepted costs: maximum denial shrinks from 1 h (renewable) to ≤15 min from first attempt, and the stale-row UNIQUE bug must be fixed in the same change since retargeting widens its reach.

**Q (BK5 shape — `BarangayController.java:118-142`): Rank-gated projection, blanket strip, or move the fields to `/api/users/officials`?**
A: Rank-gated projection — omit `contactNumber`/`emailAddress` for non-admins, retain for `ADMIN` / `CPDO Admin`, matching `UserResponseDto.java:9-14`. Chosen over the blanket strip because the endpoint has no working consumer today but the modal at `dom-contract-baseline.json:51` is recorded as a gap that may be restored.

**Q (extra scope — four issues surfaced during analysis): Which should this research carry into design?**
A: Three of four.
- **Included:** `RateLimitService` stale-row UNIQUE bug (`:111-115`, `:150-154`).
- **Included:** AI service `/docs`, `/redoc`, `/openapi.json` exposure — `csjdm_profile` removal alone does not close the `SoilType` disclosure.
- **Included:** health `show-details` role gap — `when-authorized` with no `management.endpoint.health.roles` admits any authenticated principal.
- **Excluded:** the plaintext-password fallback at `AuthController.java:215-220`. It is still in scope for the *constant-time comparison* part of BK3's unification, but the migration/rotation question it raises is deliberately not carried into this design.

## Related Research

None. This is the first artifact in `.rpiv/artifacts/`.

## Open Questions

- **Where should the `PASSWORD_CHANGE:<id>` clear happen?** `RateLimitService.clear` keys off the canonical identifier while `USER:` keys use the raw submitted string, so a successful password change may not clear a row a failed one created. Whether to normalize keys as part of this change, or accept the asymmetry, is unresolved.
- **Should `failedLoginAttempts` / `lockoutUntil` be dropped or left dormant?** Dropping needs a `V25` migration; leaving them risks a future caller re-introducing the same coupling. The test profile cannot see either outcome (H2, Flyway disabled).
- **Does the enumeration unification change the login success shape?** `frontendcap/js/features/auth/index.js:105` has no `else` branch on success, which constrains how far the 200 response can move. Needs a decision before the frontend contract is touched.
- **Is `/actuator/info` worth keeping at all?** It has no in-repo scraper and no test. Narrowing `exposure.include` to `health` would resolve CF8c without any role-dialect decision.
