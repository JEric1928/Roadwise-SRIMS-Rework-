# Production readiness — second pass

A static code review pass over production configuration, deployment, data handling
and API behaviour. Every item below was checked against the code before being
acted on; **verified** means the described behaviour was confirmed at the cited
location, not inferred from a pattern.

This is not a penetration test. Nothing here was exercised against a running
deployment.

Status legend: **Fixed** · **Open** (tracked, not yet addressed)

| # | Finding | Status |
|---|---|---|
| 1 | Dashboard statistics not scoped by user | **Fixed** |
| 2 | Audit entry trusts a caller-supplied actor id | **Fixed** |
| 3 | Production frontend could silently fall back to a legacy API | **Fixed** |
| 4 | Rate limiter cannot coordinate across instances | **Fixed** |
| 5 | Backend container runs as root | **Fixed** |
| 6 | WebSocket updates are process-local and unscoped | **Open** — designed, not implemented |
| 7 | No Content Security Policy | **Open** — migration sized below |
| A | `getAdminId()` attributes actions to an arbitrary administrator | **Fixed** (found while verifying #2) |
| B | Legacy evidence photos still in git history | **Open** — needs a history rewrite |
| C | 277 element references point at deleted markup | **Open** — see `frontendcap/DOM_CONTRACT_FINDINGS.md` |

---

## 1. Dashboard statistics not scoped by user — Fixed

**Verified.** `GET /api/reports/dashboard-stats` was annotated
`@Cacheable("dashboardStats")` with no scope parameter and no principal check,
while `getAllReports` scopes Barangay Officials to their own barangay
(`RoadReportController`). Any authenticated user could read city-wide totals they
could not enumerate.

**Correction to the original write-up:** the endpoint is not called by the
current frontend at all (`grep -rn "dashboard-stats" frontendcap/js` → no hits).
The exposure was direct API calls, not a UI leak. It was still wrong, and it would
have become a UI leak the moment anything used it.

**Fix.** Scoping and caching moved to `DashboardStatsService`:

- Barangay Officials get counts for their own barangay only; an official with no
  assigned barangay is denied, matching the list endpoint's fail-closed rule.
- The cache key carries the scope (`city-wide` vs `barangay-<id>`), so one
  caller's view can never be served to another. The cache name is unchanged, so
  the existing `@CacheEvict(value = "dashboardStats", allEntries = true)` on every
  report-mutating endpoint still invalidates correctly.
- `@Cacheable` had to leave the controller: a self-invoked method bypasses the
  cache proxy, and a `@Cacheable` on the controller method would have been keyed
  on nothing at all.

Pinned by `RoadReportWorkflowTest#testDashboardStats_AreScopedByRole` (asserts a
barangay official sees `totalReports: 1` while an admin sees `2`, and that the
two responses differ) and `#testDashboardStats_UnassignedOfficialIsDenied`.

## 2. Audit entry trusts a caller-supplied actor id — Fixed

**Verified, and broader than reported.** Two endpoints resolved the audit actor
from `adminId` in the request body:

- `UserController#manageUserRecord`
- `UserController#createUser`

A third, `emergencyPasswordReset`, already resolved the actor from the principal
with a comment saying client-supplied `adminId` "is ignored so audit attribution
cannot be forged". So the correct pattern existed in the same file and two paths
had not been updated. `SecurityUtils` states the rule directly: *"Controllers
must never trust a client-supplied userId for identity or audit purposes."*

**Fix.** All three now use `SecurityUtils.getCurrentUser()`; no backend code path
reads `adminId` from a payload any more (`grep -rn 'get("adminId")' src/main/java`
→ no hits). An absent principal is recorded as *System Automation* rather than
being attributed to somebody.

Pinned by `UserAuditAttributionTest` (4 tests), including one that passes another
admin's id in the body and asserts the captured actor is still the caller —
verified through a Mockito `ArgumentCaptor` on `ActivityLogService`.

## A. `getAdminId()` picked an arbitrary administrator — Fixed

Found while verifying #2. `getAdminId()` returned the **first** Admin/CPDO Admin
row from `findAll().stream().findFirst()`, falling back to user id `1`. It was the
fallback for two audit call sites, so an action could be attributed to whichever
administrator the query happened to return first — including a deactivated one.
The method had no other purpose and was removed.

## 3. Production frontend could silently fall back to a legacy API — Fixed

**Verified** (this one was introduced by the previous pass, as a deliberate
deployment-compatibility measure — the critique was correct that it leaves a
misconfigured deploy able to serve stale data while looking healthy).

**Fix, both halves:**

- `webpack.config.prod.js` now **fails the build** when `ROADWISE_API_BASE_URL`
  is unset, with an actionable message naming the Vercel setting and the CI
  command. A guard, not a convention: the only point at which this mistake is
  cheap is before the artefact exists.
- `js/api-base.js` no longer contains any production host. An unconfigured
  non-loopback deployment resolves to the **same origin** (empty base) and logs a
  console error — visible 404s instead of a silent conversation with a retired
  environment.

`ROADWISE_ALLOW_UNCONFIGURED_API_BASE=1` is a deliberately awkward escape hatch
for throwaway builds; it warns on every use. `tools/build-unconfigured.js` uses
it so `npm run verify` can still catch bundling errors (it caught a syntax error
in a copied helper script during this pass, which no unit test could see).

Pinned by tests asserting the config throws when unset, succeeds when set, and
that no shipped source file contains a hardcoded host.

## 4. Rate limiter cannot coordinate across instances — Fixed

**Verified.** `RateLimitService` used `synchronized` methods over a database
table (`login_rate_limits`). The counters are shared state, so an in-process lock
only serialises threads inside one JVM: two instances could each read
`attempt_count = 3`, each write `4`, and the effective limit would double — under
exactly the conditions a credential-stuffing attempt creates. The methods also
weren't transactional, so the read-modify-write wasn't atomic even in one process.

**Fix.** `SELECT ... FOR UPDATE` through
`LoginRateLimitRepository#findWithLockByKeyIdentifier`, with each method in its
own `REQUIRES_NEW` transaction so the row lock is held only for one counter
update. `synchronized` is removed rather than left in place as false assurance.

The insert race is handled explicitly: `key_identifier` is `UNIQUE`, so two
concurrent first attempts can both find nothing and both insert. The loser's
`DataIntegrityViolationException` is caught and the attempt is counted on the
existing row, so no failure goes unrecorded. That retry runs in a **fresh**
transaction via `TransactionTemplate`: an implementation detail worth stating,
because the first attempt at this fix retried inside the transaction that had
already failed its flush — which is rollback-only, so the retry would have failed
again with an opaque `UnexpectedRollbackException`. `@Transactional` cannot
express "retry in a new transaction"; only the outer template call is retried.

Pinned by `RateLimitServiceConcurrencyTest` (5 tests): 6 threads × 5 attempts from
a shared start gate must produce exactly 31 counted failures, concurrent first
attempts must all be counted through the insert-race path, the threshold must
block, and clearing must leave nothing behind.

**Honest limit:** a single JVM cannot prove the *cross-instance* behaviour that
motivated the change. These tests fail if the serialisation is removed entirely
(the naive refactor), and `AuthControllerTest`'s 11 lockout tests confirm the
semantics are unchanged — but the multi-instance guarantee rests on the row lock,
which is verified by inspection, not by a test.

**Not covered:** a distributed cache (Redis) would also remove the database round
trip per attempt. The lock makes the current design correct, not necessarily
fast under attack. Aggressive per-IP limits at the edge remain worthwhile.

## 5. Backend container runs as root — Fixed

**Verified** — and the AI service already ran as `nobody`, so the backend was the
outlier:

- No `USER` directive → the JVM ran as root.
- Single-stage `COPY . .` + `mvnw clean package` → the runtime image shipped the
  JDK, Maven, and the full source tree.

**Fix.** `Dockerfile` is now two-stage: JDK build (tests still run, as before,
so a broken revision cannot be published) → JRE runtime, `USER roadwise`
(uid 10001, no shell), only the jar copied in. `/app/uploads` is created and
owned by that user because `WebConfig` serves `/uploads/**` from the working
directory. `JAVA_TOOL_OPTIONS` sets `MaxRAMPercentage=75` and
`ExitOnOutOfMemoryError`. A `.dockerignore` keeps `target/`, `.idea/`, and
`uploads/` out of the build context.

**Not verified:** Docker is unavailable in this environment, so the image was not
built. The Maven command it wraps is the one verified locally (90 tests green).

**Behaviour change to be aware of:** evidence photos are no longer baked into the
image. Legacy local images must be served from a mounted volume or migrated to
Supabase Storage.

## 6. WebSocket updates are process-local and unscoped — Open

**Verified.** `WebSocketConfig` uses `enableSimpleBroker("/topic")` — an in-memory
broker, so with more than one API instance a client connected to instance A never
sees an event raised on instance B. `RoadReportController` has exactly one
broadcaster (`convertAndSend("/topic/updates", "REFRESH_DASHBOARDS")`), and the
client subscribes to that one global topic with no token
(`features/live-updates/index.js`), while `/ws-live/**` is `permitAll`.

Two consequences worth separating:

- **Today, low severity.** The payload is a literal ping with no data, so
  subscribing reveals only that reports changed and roughly how often. The
  dashboard refresh is then role-scoped client-side by whatever the session
  claims.
- **The thing to fix before it matters.** Spring's simple broker accepts client
  `SEND` frames on broker destinations by default, and this config installs no
  inbound-channel authorization — so an unauthenticated client can very likely
  publish `REFRESH_DASHBOARDS` themselves and trigger a refresh storm across every
  connected dashboard. That is cheap amplification. Any future event carrying
  real data (a report id, a status change, a name) would be readable by anyone who
  can open the socket.

**Recommended shape:** authenticate the STOMP handshake with the existing JWT,
authorize `SUBSCRIBE`/`SEND` on the inbound channel, and move from one global
topic to per-role or per-barangay destinations. A multi-instance deployment also
needs either a sticky session at the load balancer or a real broker relay
(`enableStompBrokerRelay`) — the in-memory broker cannot fan out across pods.

## 7. No Content Security Policy — Open

**Verified.** `SecurityHeadersFilter` documents the omission and the reason: the
frontend relies on inline handlers, which `script-src` blocks unless
`'unsafe-inline'` is allowed — and allowing it defeats most of the point while
tokens live in `localStorage`.

**Migration size, measured:** 104 inline event-handler attributes (admin 41,
barangay 31, CEO 21, login 11) and **zero** inline `<script>` blocks — every
script is external. So the blocker is specifically `onclick="fn()"`-style
attributes, each of which becomes an `addEventListener` in the already-extracted
feature module that owns it. That is mechanical, reviewable work, and it shrinks
as the DOM contract work in `DOM_CONTRACT_FINDINGS.md` proceeds.

Recommended order: (a) convert inline handlers module by module; (b) ship
`Content-Security-Policy-Report-Only` with a collector to find anything missed;
(c) enforce. Moving tokens to `HttpOnly` cookies (see the first pass) is the
change that actually removes the exfiltration path a CSP would be mitigating.

---

## Verification performed

| Check | Result |
|---|---|
| `./mvnw -o test` (backend, full suite) | 95 tests, 0 failures |
| `npm test` (frontend) | 65 tests, 0 failures |
| `npm run audit:dom` | no new DOM contract violations |
| `npm run build` without `ROADWISE_API_BASE_URL` | fails with an actionable message (intended) |
| `ROADWISE_API_BASE_URL=… npm run build` | succeeds; the value is injected and the placeholder is gone |
| `npm run verify` | green end to end |
| Docker image build | **not run** — Docker unavailable in this environment |
| Browser/pentest | **not run** — see `frontendcap/BROWSER_BASELINE.md` |

## Not reviewed in this pass

The AI service was covered in the first pass only; `application-prod.properties`,
Flyway migration content, database-level constraints, and the Supabase bucket's
own access policy were not re-examined here. Object-storage authorization in
particular is worth a dedicated look: the backend holds a `service-key`, and
nothing in this repository shows the bucket's public/private posture.
