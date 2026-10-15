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
| 6 | WebSocket updates are process-local and unscoped | **Implemented; infrastructure verification open** — inbound authorization and role/barangay channels are in place; production relay and post-commit socket revocation are configured for RabbitMQ, pending real two-instance verification |
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

## 6. WebSocket updates are process-local and unscoped — Partly fixed

The third bullet below is now fixed and the second is proven rather than suspected.
Two parts remain open, both unchanged.

**Verified — and now demonstrated, not inferred.** `WebSocketConfig` used
`enableSimpleBroker("/topic")` with no `configureClientInboundChannel`, so nothing
inspected an inbound frame before the broker handled it. That this let a client
publish onto a broker destination was the previous pass's hedge ("very likely");
it is now a measurement. Disabling the interceptor added below makes
`WebSocketInboundChannelWiringTest` fail with *"an unauthenticated SEND to a broker
destination was accepted by the real inbound channel"* — the frame goes into the
genuine `clientInboundChannel`, is accepted, and reaches `SimpleBrokerMessageHandler`,
which broadcasts it to every subscriber exactly as if application code had sent it.
One cheap frame in, every connected dashboard refetching out.

**Fixed.** `WebSocketAuthInterceptor` now runs on the inbound channel and applies
three rules before the broker sees anything:

- `CONNECT` must carry a valid access token as an `Authorization` native header.
  The handshake itself stays `permitAll`, and that is required rather than sloppy:
  a browser cannot set a header on a WebSocket upgrade, so the token arrives on the
  STOMP frame. A refused CONNECT produces an ERROR frame and a close.
- `SEND` is limited to `/app/**`. Clients may address the application; they may not
  publish onto broker destinations. This is the rule that closes the amplification
  path above.
- `SUBSCRIBE` is limited to the destinations `LiveUpdateChannels` recognizes and to the
  ones the authenticated account is entitled to. An unrecognized destination is refused,
  so a new one is readable only once a rule for it exists. The barangay is taken from
  the authenticated account and never from the frame, so a guessed id reaches nothing.

The account behind an established socket is re-checked on every `SUBSCRIBE` and every
`SEND`, so a session revoked -- or a credential that expired -- after the socket opened
can neither subscribe nor keep sending. `SEND` originally checked only that a principal
was attached, which meant a revoked session could keep publishing for as long as the
socket stayed open; the test that pins this fails if the re-check is removed. The
credential's expiry is carried on the session at CONNECT, because nothing re-reads the
token afterwards and a session with no known deadline is refused rather than trusted
indefinitely.

The client (`features/live-updates/index.js`) now sends the token on CONNECT and backs
off instead of retrying every 5 s.

The token-status and session-version rules themselves moved into `SessionAuthenticator`,
shared with `JwtAuthenticationFilter`, so the HTTP and socket paths cannot drift apart.
The HTTP path's statuses and bodies are unchanged and its tests still pass.

**Implemented, infrastructure verification still open — shared broker relay.** Local and
test profiles retain the simple broker. The production profile enables Spring's STOMP
broker relay to RabbitMQ, with host, port, virtual host, username, and password supplied
through deployment configuration/secrets. Do not disable the relay for a multi-instance
deployment: sticky sessions alone do not provide cross-instance fan-out. The actual
RabbitMQ-backed two-instance delivery test remains a deployment gate.

**Fixed — the single global topic is gone.** `/topic/updates` is retired, and a change now
notifies `/topic/updates/admin`, `/topic/updates/engineering` and one
`/topic/updates/barangay/{id}` per affected barangay. Previously one report filed in one
barangay refetched every connected dashboard in the city; now the refresh is proportional
to who is actually affected. All fourteen broadcast sites in `RoadReportController` pass
the barangays they touched, which for the two batch endpoints is the set of distinct
barangays across the affected reports.

The rule that matters — one barangay cannot read another's channel — is settled in
`LiveUpdateChannelsTest`, with the interceptor applying it to the authenticated account.

This is still routing rather than confidentiality, because the payload remains a one-word
signal. It becomes a confidentiality boundary when a channel carries data, and the rules
were written to still be correct then.

**Implemented in code — active-socket revocation.** Credential changes now go through
`SessionRevocationService.revoke(user, reason)`. The service bumps the persisted session
version and publishes an event inside the caller's transaction; the event handler runs
`AFTER_COMMIT`, closes sockets tracked by the local WebSocket-session decorator, then
fans the user ID out through RabbitMQ so every API instance closes its own sockets. The
main password, logout, account-status, email-change, and office-handover revocation paths
use this entry point. Unit coverage verifies user-scoped closure and the central version
bump. Cross-instance closure still needs verification against the deployed broker.

**Verification limitation, and the one thing to check on a real deployment.**
Server-to-client WebSocket frames are not delivered in the environment this was written
in. A handler that calls `session.sendMessage(...)` on connect has its frame encoded and
"sent" server-side with no exception, and the bytes never reach the socket — confirmed
with a hand-rolled raw TCP client that performs the upgrade itself, so no client library
is implicated. The same failure reproduces in a bare Spring Boot 4.1 application with no
code from this repository, on JDK 21 and JDK 25 (Tomcat 11.0.22 / Spring Framework 7.0.8),
so it is not caused by anything here.

Two consequences. First, the interceptor is verified by unit tests plus a wiring test
that drives the real inbound channel; there is deliberately no end-to-end socket test,
because in this environment a "no message arrived" assertion would pass against a
completely unprotected server. Second, **delivery and revocation still require a deployed
check.** The new relay and socket registry are covered at their code boundaries, but only
a real deployment can prove frames cross between instances and revocation closes a
remote instance's socket.

## 7. No Content Security Policy — Open

**Verified.** `SecurityHeadersFilter` documents the omission and the reason: the
frontend relies on inline handlers, which `script-src` blocks unless
`'unsafe-inline'` is allowed — and allowing it defeats most of the point while
tokens live in `localStorage`.

**Migration size, measured:** 104 inline event-handler attributes (admin 41,
barangay 31, CEO 21, login 11). The earlier claim here that there are **zero**
inline `<script>` blocks was wrong: `login.html` carries one, a 17-line
password-visibility toggle. It does not change the conclusion — one block is not
the blocker, and it is a small piece of the same work — but the claim is corrected
because it was stated as measured and was not. The blocker remains specifically
`onclick="fn()"`-style attributes, each of which becomes an `addEventListener` in
the already-extracted feature module that owns it. That is mechanical, reviewable
work, and it shrinks as the DOM contract work in `DOM_CONTRACT_FINDINGS.md`
proceeds.

Recommended order: (a) convert inline handlers module by module; (b) ship
`Content-Security-Policy-Report-Only` with a collector to find anything missed;
(c) enforce. Moving tokens to `HttpOnly` cookies (see the first pass) is the
change that actually removes the exfiltration path a CSP would be mitigating.

---

## Release criteria

These are gates, not suggestions. Each one names what it is protecting against.

**1. Do not release without the inbound-channel authorization tests passing.**
`WebSocketInboundChannelWiringTest` is the gate — it drives the real
`clientInboundChannel`, so a failure means anonymous frames are reaching the broker.
Supporting it: `WebSocketAuthInterceptorTest` (frame-level rules),
`LiveUpdateChannelsTest` (who may read which channel) and `LiveUpdatePublisherTest`
(where the signal is sent). The wiring test is not meaningful alone, because an
interceptor with the right rules that nobody registered and a registered interceptor
with the wrong rules leave the same open relay. `LiveUpdatePublisherTest` exists
because both of its failure modes are silent: too few destinations means a dashboard
quietly stops updating, too many means one barangay's change refetches the city.

**2. Do not describe live updates as working until the deployed two-account test below
passes.** Nothing in this repository has demonstrated an end-to-end frame reaching a
browser. Per the item 6 note, server-to-client frames are never delivered in the
environment this was developed in, so passing tests here are silent about production.

**3. If that test fails, stop claiming live updates and ship polling instead.** A
per-page refresh timer is a worse experience and an honest one; a dashboard that
silently never updates is worse than both.

**4. Verify broker fan-out and active-socket revocation on real infrastructure before
WebSockets carry anything sensitive or operationally critical.** Both mechanisms are now
configured in code, but the two-instance RabbitMQ delivery and remote socket-close paths
cannot be proven by this environment's tests. Until deployment verification passes, the
payload remains a one-word refresh signal.

### Deployed live-update smoke test

Not run as part of this pass — there is no deployed environment reachable from here,
and the result cannot be inferred from a local one. Use two API instances and two real
accounts:

1. Connect account A to API instance A and account B to API instance B. Confirm the
   instance for each socket from deployment logs/instance identifiers; load-balancer
   stickiness must not accidentally put both sockets on one node.
2. In both, open DevTools → Network → WS and select the `/ws-live` connection. Confirm
   `CONNECT` → `CONNECTED` → `SUBSCRIBE` for the account's channel (`/topic/updates/admin`,
   `/topic/updates/engineering`, or `/topic/updates/barangay/{id}`). Check that CONNECT
   carries the native `Authorization` header.
3. Make a report change handled by the *other* API instance. Confirm the receiving
   browser gets the refresh frame and actually refreshes.
4. Repeat with different roles/barangays. Confirm only affected barangays and authorized
   admin/engineering channels receive the refresh.
5. Keep an account's socket open on instance A, then revoke that account from a request
   handled by instance B (for example, admin suspension or password reset). Confirm the
   socket on A closes, and that reconnecting with the old token is refused.

Record separately whether the server published, RabbitMQ fanned out, the browser got the
frame, the UI refreshed, and remote revocation closed the socket. Those observations
separate broker, transport, client-handler, and revocation failures.

### Scaling decision

Sticky sessions alone are not sufficient. Production now selects the RabbitMQ STOMP
relay when the production profile is active; the same RabbitMQ deployment also carries
fan-out session-revocation events. Provision the STOMP listener and AMQP endpoint, set
`RABBITMQ_HOST`, `RABBITMQ_STOMP_PORT`, `RABBITMQ_AMQP_PORT`, `RABBITMQ_VHOST`,
`RABBITMQ_USERNAME`, and `RABBITMQ_PASSWORD` from deployment configuration/secrets, and
leave `WEBSOCKET_BROKER_RELAY_ENABLED=true`. The application intentionally fails startup
if the STOMP relay is enabled without its required host or credentials.

Before any topic carries report details or other sensitive data:

- keep payloads as refresh signals until deployed fan-out and revocation checks pass;
- preserve role/barangay channel authorization in `LiveUpdateChannels`, enforced on
  the authenticated server principal rather than inferred from the client;
- verify that logout, password reset, account suspension, and other revocations close
  sockets on every API instance, not only the instance that handled the request.


## Verification performed

| Check | Result |
|---|---|
| `./mvnw -o test` (backend, full suite) | 153 tests, 0 failures |
| `npm test` (frontend) | 70 tests, 0 failures |
| `npm run audit:dom` | no new DOM contract violations |
| `npm run build` without `ROADWISE_API_BASE_URL` | fails with an actionable message (intended) |
| `ROADWISE_API_BASE_URL=… npm run build` | succeeds; the value is injected and the placeholder is gone |
| `npm run verify` | green end to end |
| Docker image build | **not run** — Docker unavailable in this environment |
| WebSocket end-to-end delivery | **not verifiable here** — server-to-client frames never reach the socket; reproduced on a bare Spring Boot app with no code from this repo, on JDK 21 and 25. See item 6 |
| Browser/pentest | **not run** — see `frontendcap/BROWSER_BASELINE.md` |

## Not reviewed in this pass

The AI service was covered in the first pass only; `application-prod.properties`,
Flyway migration content, database-level constraints, and the Supabase bucket's
own access policy were not re-examined here. Object-storage authorization in
particular is worth a dedicated look: the backend holds a `service-key`, and
nothing in this repository shows the bucket's public/private posture.
