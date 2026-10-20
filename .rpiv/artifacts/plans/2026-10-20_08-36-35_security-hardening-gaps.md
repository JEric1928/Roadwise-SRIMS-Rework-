---
date: 2026-10-20T08:36:35+0800
author: Admin
commit: 9054524
branch: main
repository: Roadwise-SRIMS-Rework-
topic: "Security hardening — lockout DoS, password-change lockout reuse, barangay PII, AI-service surface, actuator authorization"
tags: [plan, security, auth, rate-limiting, actuator, ai-service, pii, flyway]
status: in-progress
parent: ".rpiv/artifacts/research/2026-10-20_08-25-53_security-hardening-gaps.md"
phase_count: 7
phases:
  - { n: 0, title: Test harness alignment, files: [backend-master/backend-master/pom.xml], depends_on: [] }
  - { n: 1, title: RateLimitService expiry correctness, files: [backend-master/backend-master/src/main/java/com/roadwise/backend/service/RateLimitService.java, backend-master/backend-master/src/test/java/com/roadwise/backend/service/RateLimitServiceExpiryTest.java], depends_on: [0] }
  - { n: 2, title: BK4 password-change retarget, files: [backend-master/backend-master/src/main/java/com/roadwise/backend/controller/UserController.java, backend-master/backend-master/src/test/java/com/roadwise/backend/controller/UserControllerPasswordChangeTest.java], depends_on: [1, 0] }
  - { n: 3, title: BK3 lockout retirement, enumeration unification, column drop, files: [backend-master/backend-master/src/main/java/com/roadwise/backend/controller/AuthController.java, backend-master/backend-master/src/main/java/com/roadwise/backend/model/User.java, backend-master/backend-master/src/main/resources/db/migration/V25__drop_login_lockout_columns.sql, backend-master/backend-master/src/test/java/com/roadwise/backend/controller/AuthLoginEnumerationTest.java, backend-master/backend-master/src/main/java/com/roadwise/backend/dto/UserResponseDto.java], depends_on: [2, 1, 0] }
  - { n: 4, title: BK5 rank-gated barangay projection, files: [backend-master/backend-master/src/main/java/com/roadwise/backend/security/RoleCapability.java, backend-master/backend-master/src/main/java/com/roadwise/backend/security/RoleCapabilities.java, backend-master/backend-master/src/main/java/com/roadwise/backend/dto/BarangayResponseDto.java, backend-master/backend-master/src/main/java/com/roadwise/backend/controller/BarangayController.java, backend-master/backend-master/src/test/java/com/roadwise/backend/controller/BarangayContactExposureTest.java], depends_on: [0] }
  - { n: 5, title: Actuator authorization and health role gap, files: [backend-master/backend-master/src/main/java/com/roadwise/backend/config/SecurityConfig.java, backend-master/backend-master/src/main/resources/application-prod.properties, backend-master/backend-master/src/test/java/com/roadwise/backend/config/ActuatorAuthorizationTest.java], depends_on: [0] }
  - { n: 6, title: AI service surface hardening, files: [roadwise-ai-service-main/roadwise-ai-service-main/main.py, roadwise-ai-service-main/roadwise-ai-service-main/test_auth_surface.py], depends_on: [] }
unresolved_phase_count: 3
last_updated: 2026-10-20T08:36:35+0800
last_updated_note: "Paused mid-generation at a clean boundary. Phases 0-3 approved and fully written (code + Success Criteria). Phases 4-6 have prose skeletons with EMPTY code fences — do not run `implement` until `unresolved_phase_count` is 0. Resume by re-running the blueprint against this file and generating Phase 4 first; Phases 4-6 each still need a `slice-verifier` round before micro-checkpoint. Phases 4 and 5 have not had `RoleCapabilities`/`RoleCapability` (Phase 4) or `application-prod.properties` (Phase 5) read in full yet."
last_updated_by: Admin
---

# Security Hardening Implementation Plan

## Overview

Close nine security findings, six of them re-verified as still-present at `9054524` and three surfaced during research. The approach retires the duplicated `users` brute-force store in favour of the existing `RateLimitService`, replaces an entity return with a rank-gated DTO, and applies deny-by-default to two unauthenticated surfaces (the AI service root plus its generated docs, and the actuator).

Source research: `.rpiv/artifacts/research/2026-10-20_08-25-53_security-hardening-gaps.md`.

## Requirements

- **BK3** — an unauthenticated caller must not be able to mutate account lockout state, and the login path must stop disclosing account existence.
- **BK4** — a mistyped `currentPassword` must not consume the login lockout budget, and an admin must not be able to lock a target account out of login.
- **BK5** — `GET /api/barangays/{id}` must not disclose the assigned official's phone, email, or name to non-admin callers.
- **CF6b** — `GET /` on the AI service must not return `csjdm_profile` or `auth_configured`.
- **CF8b** — the AI service must compare its shared secret in constant time.
- **CF8c** — `/actuator/metrics` and `/actuator/info` must not be reachable by every authenticated role.
- **X1** (new) — `RateLimitService` must not throw on an expired counter row.
- **X2** (new) — the AI service's `/docs`, `/redoc`, `/openapi.json` must not publish the `SoilType` enum in production.
- **X3** (new) — `management.endpoint.health.show-details=when-authorized` must not admit any authenticated principal.

## Current State Analysis

Two independent brute-force stores exist. `RateLimitService` (rows in `login_rate_limits`, `SELECT ... FOR UPDATE`, `REQUIRES_NEW`) is the newer and correct one. The `users.failed_login_attempts` / `users.lockout_until` columns are older and are written by **two** paths — login and password change — which is BK4. They also never decay: no code path zeroes the counter when `lockoutUntil` lapses, so from the sixth failure onward every wrong password re-locks at the full penalty. Login already rate-limits `IP:` and `USER:` keys at `AuthController.java:173`, so the account lockout is an additive second layer rather than the only control.

`BarangayController`'s detail endpoint overwrites three fields on a managed entity in place and returns it. Under `open-in-view=false` with no `@Transactional` on the handler the entity is detached, so nothing persists — but the response discloses the values, and the same lines become a persistent PII write if anyone adds a transaction to that handler later.

The AI service guards nine routes with `require_api_key` and leaves `/` open, alongside FastAPI's default `/docs`, `/redoc` and `/openapi.json`. Actuator exposure is deliberate-but-unrestricted: `/actuator/health` is public and `/actuator/**` requires only a session.

### Key Discoveries

- `controller/AuthController.java:173` — `checkRateLimit("IP:" + clientIp)` and `checkRateLimit("USER:" + username)` already exist, returning a generic 429 at `:174-181`. The account lockout is additive, not primary.
- `controller/AuthController.java:188` and `:262` — unknown-user and wrong-password responses are **already identical** (`401`, `"Invalid username or password."`). The remaining oracles are timing (no bcrypt on the unknown branch), the lockout 429 at `:189-199`, and the temp-password 401 at `:202-210`.
- `controller/AuthController.java:212-223` — non-bcrypt passwords compared with `String.equals`, then re-hashed on match.
- `controller/UserController.java:263-266` — `isSelfOrAdmin(id)`, so an admin can target any account id; combined with the flat 1-hour lock at `:282`, that is an admin-triggered login lockout.
- `controller/UserController.java:955-967` — the `EMAIL_CHANGE_PWD:` retarget pattern to model after.
- `service/RateLimitService.java:111-115` and `:150-154` — expired-row branches construct a **new** `LoginRateLimit` with the same `keyIdentifier` and never delete the stale row, so `saveAndFlush` raises `DataIntegrityViolationException` against the UNIQUE constraint at `model/LoginRateLimit.java:14`. The retry wrapper at `:71-79` re-enters the same branch and fails again.
- `config/SecurityConfig.java:47` and `:87` — `/actuator/health` public, `/actuator/**` authenticated; first-match wins, so a role matcher must sit between `:106` and `:107`.
- `config/SessionAuthenticator.java:71-84` — authorities are `"ROLE_" + users.role` verbatim, case and space preserved, so `CPDO Admin` yields `ROLE_CPDO Admin`.
- `resources/application-prod.properties` — exists, carries one `management.*` key, never overrides `exposure.include`.
- **No Jackson inclusion override.** `spring.jackson.*` appears in neither `application.properties` nor `application-prod.properties`, so Spring Boot's default `JsonInclude.Include.ALWAYS` applies: a null DTO field is serialized as `"field": null`, not omitted. Phase 4's test can assert `null` directly.
- `PerformanceBenchmarkTest:59` — pins anonymous `GET /actuator/health` → 200. This is the regression guard for any `SecurityConfig` change.
- Migration head is `V24`; the next file is `V25`.
- `model/User.java:53`, `:56`, `:153-165`, `:161`, `:197` — the only lockout field and accessor declarations. Only two writers exist (`AuthController.java:231`, `UserController.java:282`) plus **eleven** clearer/initialiser sites: `AuthController:194`, `:199` (reads), `:612`, `:613`, `:906`, `:907`, and `UserController:273`, `:274`, `:307`, `:308`, `:604`, `:605`, `:741`, `:878`. Phase 2 removes all eleven `UserController` sites; Phase 3 removes the six in `AuthController` and then drops the columns. No test source references any of them.

## Desired End State

An unauthenticated attacker cannot lock an account out of login, and cannot tell whether a username exists from the login response's status, body, or timing:

```
POST /api/auth/login  {"username":"nonexistent","password":"x"}
→ 401 {"error":"Invalid username or password."}      # after a dummy bcrypt compare

POST /api/auth/login  {"username":"nonexistent","password":"x"}   x6, rapid
→ 429 {"error":"Too many attempts. Account or IP temporarily rate-limited."}
                                                     # IP:/USER: window, no account state touched
```

Password change is throttled per-user without touching the login budget, and an admin targeting another account cannot lock it:

```
PUT /api/users/7/password  {"currentPassword":"wrong",...}   x6   (admin, id=7)
→ 401 {"error":"Incorrect current password."}                # attempts 1-6, no count disclosed
→ 429 "Too many failed attempts..." on the next attempt, and POST /api/auth/login
  for user 7 still returns 200 — the account was never locked out of login
```

Barangay detail is rank-gated, with the payload shape unchanged for admins:

```
GET /api/barangays/3   (role=BARANGAY, authenticated)
→ {"id":3,"barangayName":"...","brgyCaptain":null,"contactNumber":null,"emailAddress":null}

GET /api/barangays/3   (role=ADMIN)
→ {"id":3,"barangayName":"...","brgyCaptain":"Juan Cruz","contactNumber":"09...","emailAddress":"..."}
```

Actuator is admin-only where it matters, and health stays public:

```
GET /actuator/health    (anonymous) → 200
GET /actuator/metrics   (role=BARANGAY) → 403
GET /actuator/metrics   (role=ADMIN) → 200
```

The AI service discloses nothing unauthenticated:

```
GET /                      (no key) → 503 or 401, body without csjdm_profile/auth_configured
GET /docs                  (AI_ENV=production) → 404
GET /openapi.json          (AI_ENV=production) → 404
X-API-Key: wrong           → 401, compared via hmac.compare_digest
```

## What We're NOT Doing

- **CSP and inline event handler removal** — `PRODUCTION_READINESS.md` open item, untouched.
- **Moving auth tokens out of `localStorage`** — larger change, requires CSRF design, untouched.
- **Purging uploaded images from git history** — needs a coordinated history rewrite, untouched.
- **Deployed WebSocket two-instance E2E verification** — needs live infrastructure, untouched.
- **Step-up authentication policy beyond existing MFA paths** — untouched.
- **Plaintext-password rotation and migration** — `AuthController.java:215-220` gets a constant-time comparison as part of Phase 3, but the migration/rotation question (what to do about accounts whose passwords reached the DB in plaintext) is deliberately deferred. The `equals` branch survives; only its comparison timing changes.
- **Exact `String.equals` semantics for the legacy path.** Replacing it with `MessageDigest.isEqual` over `getBytes(StandardCharsets.UTF_8)` is constant-time but **not** byte-identical in behaviour: `String.equals` compares UTF-16 code units, while the byte form replaces unpaired surrogates with `?`. A legacy plaintext password containing an unpaired surrogate would stop matching. Accepted — such a password cannot be typed through the JSON login form — but it is a behaviour change, not a pure refactor, and is recorded here rather than buried in the diff.
- **Narrowing the 403 body for actuator denials** — a non-admin hitting `/actuator/metrics` will receive `SecurityConfig`'s maker-checker message. Cosmetically wrong, functionally harmless, and fixing it needs a second `AccessDeniedHandler` keyed on path. Deferred.
- **`/actuator/info` removal** — retained; it is covered by the same role gate. See Decisions.
- **Extending `EndpointAuthorizationCoverageTest` to cover actuator paths** — it scans `controller/**` only. Extending it is a worthwhile follow-up but out of scope here.

## Decisions

### D1: BK3 fixes by deletion, not by adding decoupling logic

**Decision**: login stops reading and writing `failedLoginAttempts` / `lockoutUntil` entirely; the existing `IP:`/`USER:` throttle at `AuthController.java:173` remains the only brute-force control. The columns and their accessors are dropped in `V25`.

**Evidence**: `AuthController.java:173-181` already implements a per-IP and per-identifier window via `RateLimitService`, which ages out. The account lockout at `:189-199` (read) and `:225-262` (write) is layered on top and is what mutates account state pre-authentication. Removing it also removes the lockout enumeration oracle and the never-decaying counter at `:227-231`. Chosen over "defer the penalty past MFA" because a wrong password never reaches MFA, so that option adds branching without removing the state.

### D2: Enumeration unification is response/timing-only, not a contract change

**Decision**: add a dummy bcrypt comparison on the unknown-user branch, move the temp-password check below password verification, and give the legacy plaintext comparison constant-time semantics. Do **not** change the login 200 shape.

**Evidence**: `AuthController.java:188` and `:262` already return identical status and body, so the frontend contract at `frontendcap/js/features/auth/index.js:100`, `:141` sees no difference on the failure path. `frontendcap/js/features/auth/index.js:105` has no `else` on success, which constrains the success shape — leaving it untouched avoids that risk entirely.

### D3: BK3 and BK4 migrate to `RateLimitService`, keyed by userId

**Decision**: password change uses a `PASSWORD_CHANGE:<userId>` key. No key normalization is introduced.

**Evidence**: userId is the stable identifier already used by `EMAIL_CHANGE_PWD:` at `UserController.java:955-967`, so no normalization question arises — the research's open question about `USER:` raw-string keys does not apply, because this key never derives from user input.

### D4: BK5 uses a rank-gated DTO, gating name plus both contact fields

**Decision**: introduce `dto/BarangayResponseDto` with a static `from(Barangay, boolean includeOfficialDetails)` factory plus a `RoleCapability.BARANGAY_CONTACT` capability. Non-admins get `brgyCaptain`, `contactNumber` and `emailAddress` all null; admins get the values. The in-place entity mutation is removed.

**Evidence**: the list endpoint nulls all three at `BarangayController.java:107-109` and its comment at `:102-103` groups them as "official personal contact details" — gating all three makes the two endpoints agree, which is the inconsistency that let BK5 survive `64339b8`. The static-`from(Entity)` factory is the dominant DTO convention (`dto/UserResponseDto.java`), and `RoleCapability` + `RoleCapabilities.BY_ROLE` is the single source of truth for role resolution (`security/RoleCapabilities.java:15-19`).

### D5: Authorization lives in the filter chain *and* the production profile

**Decision**: add the repo's first inline matcher — `.requestMatchers(ACTUATOR_ADMIN_PATHS).hasAnyRole("ADMIN","CPDO Admin")` — placed between `SecurityConfig.java:106` and `:107`, **and** narrow `application-prod.properties` to `management.endpoints.web.exposure.include=health` plus an explicit `management.endpoint.health.roles`.

**Evidence**: the static lists can only express `.authenticated()`, so a role rule cannot live there. Placement is forced: after `:105` (`PUBLIC_PATHS`, which contains `/actuator/health`) and before `:107` (`AUTHENTICATED_PREFIXES`, which contains `/actuator/**`). The properties narrowing is belt-and-braces against the three role dialects disagreeing (`hasAnyRole` exact-match vs `@roleCapabilities` tolerant). `hasAnyRole("ADMIN","CPDO Admin")` matches the real authority strings produced at `SessionAuthenticator.java:71-84`.

### D6: AI docs disabled only when `AI_ENV == "production"`

**Decision**: pass `docs_url=None`, `redoc_url=None`, `openapi_url=None` to the `FastAPI(...)` constructor when `AI_ENV` is `production`, reusing the variable read at `main.py:30`.

**Evidence**: `AI_ENV` already gates the checksum behaviour at `main.py:32-37`, so the production signal exists and requires no new configuration. Keeps the interactive docs available for local work and for `calibrate_road_gate.py`, which the always-off option would break.

### D7: `RateLimitService` reuses the expired row instead of deleting it

**Decision**: in both `recordFailure` and `recordRequest`, when the existing row's window has expired, reset the **existing** entity's `attemptCount` / `firstAttemptAt` rather than constructing a new `LoginRateLimit`.

**Evidence**: `RateLimitService.java:111-115` and `:150-154` currently build a new entity with the same `keyIdentifier`, colliding with the UNIQUE constraint at `LoginRateLimit.java:14`. Reusing the row avoids the delete-then-insert flush-ordering hazard the pattern agent flagged, and needs no change to the retry wrapper at `:71-79`.

### D8: `/actuator/info` is retained behind the same gate

**Decision**: keep `info` exposed in non-production; it is covered by the role matcher.

**Evidence**: no in-repo scraper of `metrics` or `info` exists, so there is no consumer to break; retaining `info` under the gate costs nothing and avoids a second, independently-decided change. Production narrows to `health` regardless.

## Phase 0: Test harness alignment

### Overview

Restore the backend test suite to a runnable state so that every later phase's Success Criteria are executable. The suite currently fails at discovery, which makes it a prerequisite rather than a nicety. No dependencies; blocks Phases 1-5.

### Changes Required:

#### 1. backend-master/backend-master/pom.xml
**File**: `backend-master/backend-master/pom.xml`
**Changes**: MODIFY — add a test-scoped `org.junit.platform:junit-platform-launcher` dependency with no explicit version, so the Spring Boot 4.1.0 BOM supplies 6.0.3 and the launcher aligns with `junit-platform-engine:6.0.3`. Insert as the **last** entry in the existing `<dependencies>` block (`pom.xml:32-154`), immediately after `spring-boot-starter-test`. Follow the file's convention of a preceding explanatory comment (`pom.xml:159-168`, `:108-112`): state that surefire's bundled launcher is older than the 6.0.3 engine and that this declaration puts an aligned launcher on the test classpath.

```xml
		<!-- Surefire's bundled launcher predates the 6.0.3 engine that the Boot BOM
		     puts on the test classpath, so JUnit fails to discover any test at all
		     ("OutputDirectoryCreator not available"). Declaring the launcher here
		     lets the BOM supply a version aligned with junit-platform-engine,
		     instead of the one that ships inside surefire-junit-platform. -->
		<dependency>
			<groupId>org.junit.platform</groupId>
			<artifactId>junit-platform-launcher</artifactId>
			<scope>test</scope>
		</dependency>
```

### Success Criteria:

#### Automated Verification:
- [ ] Test discovery works again (run from the module directory, where `mvnw` lives): `cd backend-master/backend-master && ./mvnw -B -Dtest=RateLimitServiceConcurrencyTest test` exits 0 and reports a non-zero `Tests run:` count
- [ ] Existing build still compiles: `cd backend-master/backend-master && ./mvnw -B -q compile` exits 0
- [ ] Launcher artifact declared exactly once as a dependency: `grep -c "<artifactId>junit-platform-launcher</artifactId>" backend-master/backend-master/pom.xml` returns 1
- [ ] No explicit version was added: `grep -A3 "<artifactId>junit-platform-launcher</artifactId>" backend-master/backend-master/pom.xml | grep -c "<version>"` returns 0

#### Manual Verification:

---

## Phase 1: RateLimitService expiry correctness

### Overview

Fix the expired-row INSERT collision in `RateLimitService` and pin it with tests, before any caller depends on the service more heavily. Depends on Phase 0 — the two test criteria are Maven commands, which cannot discover a test until the aligned launcher lands.

### Changes Required:

#### 1. backend-master/backend-master/src/main/java/com/roadwise/backend/service/RateLimitService.java
**File**: `backend-master/backend-master/src/main/java/com/roadwise/backend/service/RateLimitService.java`
**Changes**: MODIFY — split the combined `rateLimit == null || isWindowExpired(rateLimit)` guard in `recordFailure` (replace `:111-115`) and `recordRequest` (replace `:151-155`) so an expired row is reset in place instead of re-inserted. Leave `isRequestThrottled` (`:137`) alone: it returns `false` on an expired row and never inserts, so it has no collision.

```java
            // recordFailure — replaces RateLimitService.java:111-115 ONLY.
            // The `LoginRateLimit rateLimit = lockedOrNull(key);` declaration at :110
            // is NOT part of this replacement and must be left in place.
            if (rateLimit == null) {
                rateLimit = new LoginRateLimit(key);
                rateLimit.setAttemptCount(0);
                rateLimit.setFirstAttemptAt(LocalDateTime.now());
            } else if (isWindowExpired(rateLimit)) {
                // Reset in place. key_identifier is UNIQUE, so building a second
                // LoginRateLimit for the same key fails the INSERT on flush — and the
                // insert-retry wrapper cannot recover, because the stale row is still
                // present on the retry.
                rateLimit.setAttemptCount(0);
                rateLimit.setFirstAttemptAt(LocalDateTime.now());
                rateLimit.setBlockedUntil(null);
            }

            // recordRequest — replaces RateLimitService.java:151-155 ONLY
            // (the declaration at :150 stays).
            if (rateLimit == null) {
                rateLimit = new LoginRateLimit(key);
                rateLimit.setAttemptCount(0);
                rateLimit.setFirstAttemptAt(LocalDateTime.now());
            } else if (isWindowExpired(rateLimit)) {
                // Same in-place reset as recordFailure: re-inserting would collide with
                // the UNIQUE key_identifier constraint. blockedUntil is cleared for
                // consistency with recordFailure, even though this path sets no penalty.
                rateLimit.setAttemptCount(0);
                rateLimit.setFirstAttemptAt(LocalDateTime.now());
                rateLimit.setBlockedUntil(null);
            }
```

#### 2. backend-master/backend-master/src/test/java/com/roadwise/backend/service/RateLimitServiceExpiryTest.java
**File**: `backend-master/backend-master/src/test/java/com/roadwise/backend/service/RateLimitServiceExpiryTest.java`
**Changes**: NEW — pins that an expired counter row is reused, for both `recordFailure` and `recordRequest`.

```java
package com.roadwise.backend.service;

import com.roadwise.backend.model.LoginRateLimit;
import com.roadwise.backend.repository.LoginRateLimitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A counter row outlives its window: {@link RateLimitService#isWindowExpired} only
 * decides the numbers are stale, it does not delete the row. The update paths must
 * therefore reset that row in place. Building a second {@code LoginRateLimit} for the
 * same key collides with the UNIQUE constraint on {@code key_identifier}, and the
 * insert-retry wrapper cannot recover — the stale row is still there on the retry.
 */
@SpringBootTest
@ActiveProfiles("test")
class RateLimitServiceExpiryTest {

    private static final String KEY = "USER:expired-window-with-live-penalty";

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private LoginRateLimitRepository loginRateLimitRepository;

    @BeforeEach
    void setUp() {
        loginRateLimitRepository.deleteAll();
    }

    /**
     * Window closed an hour ago, but the penalty it produced is still in force. Window
     * expiry is what makes the counters stale, so both the count and the live penalty
     * must be discarded.
     */
    private void seedExpiredWindowWithLivePenalty() {
        LoginRateLimit expired = new LoginRateLimit(KEY);
        expired.setAttemptCount(9);
        expired.setFirstAttemptAt(LocalDateTime.now().minusHours(1));
        expired.setLastAttemptAt(LocalDateTime.now().minusHours(1));
        expired.setBlockedUntil(LocalDateTime.now().plusHours(1));
        loginRateLimitRepository.saveAndFlush(expired);
    }

    @Test
    void expiredFailureWindowIsResetInPlaceRatherThanReinserted() {
        seedExpiredWindowWithLivePenalty();

        assertDoesNotThrow(() -> rateLimitService.recordFailure(KEY),
                "an expired counter must be reset in place; re-inserting collides with the unique key");

        LoginRateLimit stored = loginRateLimitRepository.findByKeyIdentifier(KEY).orElseThrow();
        assertEquals(1, stored.getAttemptCount(),
                "the expired count of 9 must be discarded, leaving a single fresh failure");
        assertNull(stored.getBlockedUntil(),
                "window expiry must discard the live penalty it inherited");
    }

    @Test
    void expiredRequestWindowIsResetInPlaceRatherThanReinserted() {
        seedExpiredWindowWithLivePenalty();

        assertDoesNotThrow(() -> rateLimitService.recordRequest(KEY),
                "the resend path shares the same expired-row branch");

        LoginRateLimit stored = loginRateLimitRepository.findByKeyIdentifier(KEY).orElseThrow();
        assertEquals(1, stored.getAttemptCount(), "the request counter must restart at 1");
    }

    /** Characterization: window expiry already overrides a live penalty on the read path. */
    @Test
    void windowExpiryOverridesALivePenaltyOnTheReadPath() {
        seedExpiredWindowWithLivePenalty();
        assertFalse(loginRateLimitRepository.findByKeyIdentifier(KEY).isEmpty(),
                "precondition: the seeded row must exist before the read path can delete it");

        assertFalse(rateLimitService.isBlocked(KEY),
                "a penalty still in force must not outlive its counting window");
    }
}
```

### Success Criteria:

#### Automated Verification:
- [ ] Expiry tests pass: `cd backend-master/backend-master && ./mvnw -B -Dtest=RateLimitServiceExpiryTest test` exits 0
- [ ] Counter semantics unchanged: `cd backend-master/backend-master && ./mvnw -B -Dtest=RateLimitServiceConcurrencyTest test` exits 0
- [ ] Module compiles: `cd backend-master/backend-master && ./mvnw -B -q compile` exits 0
- [ ] No combined null-or-expired guard remains in the two record paths: `grep -c "rateLimit == null || isWindowExpired" backend-master/backend-master/src/main/java/com/roadwise/backend/service/RateLimitService.java` returns 1 (the survivor is `isRequestThrottled`, which returns false without inserting)

#### Manual Verification:

---

## Phase 2: BK4 password-change retarget

### Overview

Stop the password-change path from writing the login lockout columns; throttle it on a `PASSWORD_CHANGE:<userId>` key instead. Depends on Phase 1.

### Changes Required:

#### 1. backend-master/backend-master/src/main/java/com/roadwise/backend/controller/UserController.java
**File**: `backend-master/backend-master/src/main/java/com/roadwise/backend/controller/UserController.java`
**Changes**: MODIFY — retarget the password-change path onto `RateLimitService` (`PASSWORD_CHANGE:<id>` key, following `:955-967`) and remove **every** lockout-column reference from this file so Phase 3 can drop the columns without touching it. Five patch sites: **A** replace `:273` through the `}` closing the `verifyAndMigratePassword` failure block; **B** delete the success-path clears `:307-308`; **C** delete the emergency-reset clears `:604-605`; **D** delete `newAdmin.setFailedLoginAttempts(0)` `:741`; **E** delete `newCeo.setFailedLoginAttempts(0)` `:878`. **F** rewrite the stale section comment at `:226-229`, which still claims "Lockout state is persisted on the user row (failed_login_attempts / lockout_until)".

```java
        // A — replaces UserController.java:273 through the } that closes the
        // verifyAndMigratePassword failure block (i.e. up to the line before
        // `String policyError = PasswordPolicy.validate(newPassword);`).
        String passwordFailureKey = "PASSWORD_CHANGE:" + id;
        if (rateLimitService.isBlocked(passwordFailureKey)) {
            return ResponseEntity.status(429)
                    .header("Retry-After", "60")
                    .body(Map.of("error", "Too many failed attempts. Please try again in a minute."));
        }

        if (!verifyAndMigratePassword(user, currentPassword)) {
            rateLimitService.recordFailure(passwordFailureKey);
            return ResponseEntity.status(401).body(Map.of("error", "Incorrect current password."));
        }
        rateLimitService.clear(passwordFailureKey);

        // B — delete these two lines from the success path (:307-308):
        //   user.setFailedLoginAttempts(0);
        //   user.setLockoutUntil(null);

        // C — delete these two lines from emergencyPasswordReset (:604-605):
        //   user.setFailedLoginAttempts(0);
        //   user.setLockoutUntil(null);

        // D — delete from the office-handover user construction (:741):
        //   newAdmin.setFailedLoginAttempts(0);

        // E — delete from the CEO-handover user construction (:878):
        //   newCeo.setFailedLoginAttempts(0);

        // F — rewrite the section comment at :226-229. It currently asserts
        // "SECURE PASSWORD UPDATE (1-HOUR LOCKOUT) / Lockout state is persisted on
        // the user row (failed_login_attempts / lockout_until)". Replace with a
        // comment stating that failed current-password attempts are throttled per
        // user through RateLimitService on a PASSWORD_CHANGE:<id> key, and that the
        // account's login state is deliberately left untouched.
```

#### 2. backend-master/backend-master/src/test/java/com/roadwise/backend/controller/UserControllerPasswordChangeTest.java
**File**: `backend-master/backend-master/src/test/java/com/roadwise/backend/controller/UserControllerPasswordChangeTest.java`
**Changes**: NEW — a wrong `currentPassword` does not set `lockoutUntil`, and an admin cannot lock a target account out of login.

```java
package com.roadwise.backend.controller;

import com.roadwise.backend.config.JwtTokenProvider;
import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.LoginRateLimitRepository;
import com.roadwise.backend.repository.MfaChallengeRepository;
import com.roadwise.backend.repository.PasswordResetTokenRepository;
import com.roadwise.backend.repository.RefreshTokenRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import com.roadwise.backend.service.RoadInspectionService;
import com.roadwise.backend.service.SupabaseStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A mistyped current password is a re-authentication prompt, not a credential-stuffing
 * signal. Throttling it must not touch the account's login state — an administrator
 * targeting somebody else's id must not be able to lock that person out of signing in.
 *
 * <p>Every assertion here is made over HTTP rather than against {@code User} accessors,
 * because Phase 3 deletes {@code failedLoginAttempts} / {@code lockoutUntil} and their
 * getters; a getter-based assertion would stop compiling on the finished tree.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class UserControllerPasswordChangeTest {

    private static final String WRONG_BODY =
            "{\"currentPassword\":\"WrongPassword!\",\"newPassword\":\"NewPassword123!\"}";

    private MockMvc mockMvc;

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private LoginRateLimitRepository loginRateLimitRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private MfaChallengeRepository mfaChallengeRepository;
    @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockitoBean private EmailService emailService;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private SupabaseStorageService supabaseStorageService;
    @MockitoBean private RoadInspectionService roadInspectionService;

    private User target;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        // Children first, matching AuthControllerTest's teardown order.
        loginRateLimitRepository.deleteAll();
        mfaChallengeRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        target = seedUser("lockouttarget", "BARANGAY");
    }

    private User seedUser(String username, String role) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("Password123!"));
        u.setEmail(username + "@example.com");
        u.setRole(role);
        u.setFirstName("Test");
        u.setLastName("User");
        u.setStatus("Active");
        return userRepository.save(u);
    }

    private MvcResult attempt(Long id, String token) throws Exception {
        return mockMvc.perform(put("/api/users/" + id + "/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WRONG_BODY))
                .andReturn();
    }

    /**
     * Drives attempts until the retargeted limiter blocks. A hard-coded "the 7th is
     * throttled" would flake: the penalty at attemptCount 6 is only 2 seconds
     * ({@code 2^(6-5)}), so any slower test JVM sees 401 instead.
     */
    private void assertEventuallyThrottled(Long id, String token) throws Exception {
        boolean throttled = false;
        for (int attempt = 0; attempt < 12 && !throttled; attempt++) {
            throttled = attempt(id, token).getResponse().getStatus() == 429;
        }
        assertTrue(throttled, "repeated wrong current passwords must eventually be rate limited");
    }

    private void assertLoginStillWorks(String username) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Password123!\"}"))
                .andReturn();
        assertEquals(200, login.getResponse().getStatus(),
                "the account must remain able to log in — it was never locked out");
    }

    @Test
    void wrongCurrentPasswordThrottlesWithoutTouchingTheLoginLockout() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(target);

        for (int attempt = 1; attempt <= 6; attempt++) {
            mockMvc.perform(put("/api/users/" + target.getId() + "/password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(WRONG_BODY))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("Incorrect current password."));
        }

        assertEventuallyThrottled(target.getId(), token);
        assertLoginStillWorks("lockouttarget");
    }

    @Test
    void adminCannotLockAnotherAccountOutOfLogin() throws Exception {
        User admin = seedUser("lockoutadmin", "ADMIN");
        String adminToken = jwtTokenProvider.generateAccessToken(admin);

        assertEventuallyThrottled(target.getId(), adminToken);
        assertLoginStillWorks("lockouttarget");
    }
}
```

### Success Criteria:

#### Automated Verification:
- [ ] New tests pass: `cd backend-master/backend-master && ./mvnw -B -Dtest=UserControllerPasswordChangeTest test` exits 0
- [ ] Login flow unaffected: `cd backend-master/backend-master && ./mvnw -B -Dtest=AuthControllerTest test` exits 0
- [ ] Module compiles: `cd backend-master/backend-master && ./mvnw -B -q compile` exits 0
- [ ] `UserController` holds no lockout-column reference: `grep -c "setLockoutUntil\|getLockoutUntil\|setFailedLoginAttempts\|getFailedLoginAttempts" backend-master/backend-master/src/main/java/com/roadwise/backend/controller/UserController.java` returns 0

#### Manual Verification:

---

## Phase 3: BK3 lockout retirement, enumeration unification, column drop

### Overview

Remove the login lockout entirely, close the remaining enumeration oracles, and drop the now-dead columns. Depends on Phase 2 (which removes the last non-login writer).

### Changes Required:

#### 1. backend-master/backend-master/src/main/java/com/roadwise/backend/controller/AuthController.java
**File**: `backend-master/backend-master/src/main/java/com/roadwise/backend/controller/AuthController.java`
**Changes**: MODIFY — delete the lockout read (`:189-199`) and counter write (`:225-262`); delete the lockout clears at `:613` and `:907`; add a dummy bcrypt compare on the unknown-user branch (`:181-189`); move the temp-password check (`:202-210`) below password verification; make the plaintext comparison at `:212-223` constant-time.

```java
        // I — add these three imports.
        import java.nio.charset.StandardCharsets;
        import java.security.MessageDigest;
        import jakarta.annotation.PostConstruct;

        // A — DELETE the whole block that begins
        //     `if (user.getLockoutUntil() != null && LocalDateTime.now().isBefore(user.getLockoutUntil())) {`
        //     through its closing `}` — the 429 "Account temporarily rate-limited for security"
        //     branch, including its activityLogService.log(...) and Retry-After computation.
        //     Replace with nothing. There is no account lockout to report any more.

        // B — inside `login(...)`, in the `if (userOpt.isEmpty()) {` branch that is followed by
        //     `recordFailedAttempt("IP:" + clientIp);` (NOT the identical-looking branch at :793
        //     in requestPasswordReset, where `password` is not in scope), insert BEFORE the return:
        //
        //     // Equalise timing with the known-user path: without this a missing account
        //     // returns in microseconds while a real one pays for a bcrypt verification.
        //     passwordEncoder.matches(password, dummyPasswordHash);

        // C — MOVE the temporary-password-expiry branch so it runs AFTER password verification:
        //     after the `if (!passwordMatches) { ... }` block and after the legacy-upgrade
        //     `userRepository.save(user);`, but BEFORE the maintenance-mode check. Its text is
        //     unchanged.

        // D — replace the legacy plaintext comparison
        //         passwordMatches = user.getPassword().equals(password);
        //     with
                     passwordMatches = MessageDigest.isEqual(
                             user.getPassword().getBytes(StandardCharsets.UTF_8),
                             password.getBytes(StandardCharsets.UTF_8));

        // E — inside `if (!passwordMatches) {`, DELETE only:
        //         int attempts = user.getFailedLoginAttempts() + 1;
        //         user.setFailedLoginAttempts(attempts);
        //         if (attempts > 5) { ... }          // incl. setLockoutUntil + brute-force notification
        //         userRepository.save(user);         // the one INSIDE this block
        //     KEEP both recordFailedAttempt(...) calls, the AUTH_LOGIN_FAILED log, and the 401.
        //     KEEP the legacy-upgrade `userRepository.save(user);` that follows the block.

        // F — in `completeLogin(...)`: delete `user.setFailedLoginAttempts(0);`,
        //     `user.setLockoutUntil(null);` and the `userRepository.save(user);` that follows them.
        //     KEEP both clearRateLimit(...) calls. Amend the Javadoc to drop the
        //     "failed-password counters and" clause.

        // G — in the password-reset flow: delete `user.setFailedLoginAttempts(0);`
        //     and `user.setLockoutUntil(null);`.

        // H — add the field and initialiser:
        //     private String dummyPasswordHash;
        //
        //     @PostConstruct
        //     void initialiseDummyPasswordHash() {
        //         // A throwaway BCrypt hash at the same cost factor as a real password, used only
        //         // to make the unknown-account branch do the same work as a failed login.
        //         this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
        //     }

        // J — rewrite three comments that assert the deleted store:
        //     AuthController.java:251-253   ("Lockout counters and rate limits are only cleared...")
        //     AuthController.java:935-939   ("does not touch the brute-force lockout counters")
        //     UserResponseDto.java:12       ("failed-login counter, lockout expiry")
        //     Each must stop describing per-user lockout state; rate limits remain in login_rate_limits.
```

#### 2. backend-master/backend-master/src/main/java/com/roadwise/backend/model/User.java
**File**: `backend-master/backend-master/src/main/java/com/roadwise/backend/model/User.java`
**Changes**: MODIFY — remove the **whole** `:51-56` block: the section comment, the `@Column(name = "failed_login_attempts")` annotation, `private Integer failedLoginAttempts = 0;`, the `@Column(name = "lockout_until")` annotation, and `private LocalDateTime lockoutUntil;`. Then remove the four accessors at `:152-161` and `:197`. **Do not delete only the two declaration lines** — the orphaned `@Column` annotations would attach to `sessionVersion` (`:65`) beside its own `@Column` and fail to compile.

```java
    // DELETE the entire block at User.java:51-56 — both @Column annotations, both
    // comments, and both field declarations. Deleting only the two declaration lines
    // would leave the @Column annotations to attach to sessionVersion (:65).
    //
    // Also DELETE the four accessors:
    //   User.java:152-161   getFailedLoginAttempts() / setFailedLoginAttempts(Integer)
    //   User.java:161       getLockoutUntil()
    //   User.java:197       setLockoutUntil(LocalDateTime)
```

#### 3. backend-master/backend-master/src/main/resources/db/migration/V25__drop_login_lockout_columns.sql
**File**: `backend-master/backend-master/src/main/resources/db/migration/V25__drop_login_lockout_columns.sql`
**Changes**: NEW — drops `failed_login_attempts` and `lockout_until` idempotently.

```sql
ALTER TABLE users DROP COLUMN IF EXISTS failed_login_attempts;
ALTER TABLE users DROP COLUMN IF EXISTS lockout_until;
```

#### 4. backend-master/backend-master/src/test/java/com/roadwise/backend/controller/AuthLoginEnumerationTest.java
**File**: `backend-master/backend-master/src/test/java/com/roadwise/backend/controller/AuthLoginEnumerationTest.java`
**Changes**: NEW — an unknown user and a wrong password produce the same status and body; repeated failures return the IP/`USER:` 429 without locking the account.

```java
package com.roadwise.backend.controller;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.LoginRateLimitRepository;
import com.roadwise.backend.repository.MfaChallengeRepository;
import com.roadwise.backend.repository.PasswordResetTokenRepository;
import com.roadwise.backend.repository.RefreshTokenRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.service.ActivityLogService;
import com.roadwise.backend.service.EmailService;
import com.roadwise.backend.service.NotificationService;
import com.roadwise.backend.service.RoadInspectionService;
import com.roadwise.backend.service.SupabaseStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Login must not disclose whether an account exists, and must not be lockable by an
 * unauthenticated caller.
 *
 * <p>Every assertion is made over HTTP. There is deliberately no assertion on
 * {@code User} lockout accessors — Phase 3 deletes them, and a getter-based check
 * would stop compiling on the finished tree.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthLoginEnumerationTest {

    private MockMvc mockMvc;

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private LoginRateLimitRepository loginRateLimitRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private MfaChallengeRepository mfaChallengeRepository;
    @Autowired private PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockitoBean private EmailService emailService;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private SupabaseStorageService supabaseStorageService;
    @MockitoBean private RoadInspectionService roadInspectionService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        // Children first, matching AuthControllerTest's teardown order.
        loginRateLimitRepository.deleteAll();
        mfaChallengeRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        seedUser("testuser", "Password123!");
    }

    private User seedUser(String username, String rawPassword) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setEmail(username + "@example.com");
        u.setRole("BARANGAY");
        u.setFirstName("Test");
        u.setLastName("User");
        u.setStatus("Active");
        return userRepository.save(u);
    }

    private MvcResult login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    @Test
    void unknownAccountAndWrongPasswordAreIndistinguishable() throws Exception {
        MvcResult unknown = login("nosuchuser", "Password123!");
        MvcResult wrong = login("testuser", "WrongPassword!");

        assertEquals(401, unknown.getResponse().getStatus());
        assertEquals(401, wrong.getResponse().getStatus());
        assertEquals(wrong.getResponse().getContentAsString(),
                unknown.getResponse().getContentAsString(),
                "the two failure responses must be byte-identical");
    }

    /**
     * The decisive BK3 regression test: throttling must live entirely in
     * {@code login_rate_limits}. If any lockout survived on the user row, clearing the
     * limiter would not restore access and this would still see 429.
     */
    @Test
    void repeatedFailuresThrottleButNeverLockTheAccount() throws Exception {
        boolean throttled = false;
        for (int attempt = 0; attempt < 12 && !throttled; attempt++) {
            throttled = login("testuser", "WrongPassword!").getResponse().getStatus() == 429;
        }
        assertTrue(throttled, "repeated failures must eventually be throttled");

        // Drops only the IP:/USER: windows — nothing on the user row.
        loginRateLimitRepository.deleteAll();

        assertEquals(200, login("testuser", "Password123!").getResponse().getStatus(),
                "clearing the limiter must be sufficient to log in; no account lockout may remain");
    }

    @Test
    void expiredTemporaryPasswordIsOnlyReportedWithTheCorrectPassword() throws Exception {
        User expired = seedUser("tempuser", "Password123!");
        expired.setPasswordChangeRequired(true);
        expired.setTempPasswordExpiresAt(LocalDateTime.now().minusHours(1));
        userRepository.save(expired);

        MvcResult wrong = login("tempuser", "WrongPassword!");
        assertEquals(401, wrong.getResponse().getStatus());
        assertFalse(wrong.getResponse().getContentAsString().contains("Temporary password has expired"),
                "the expiry message must not be an unauthenticated account-state oracle");

        MvcResult right = login("tempuser", "Password123!");
        assertEquals(401, right.getResponse().getStatus());
        assertTrue(right.getResponse().getContentAsString().contains("Temporary password has expired"),
                "with the correct password the expiry message is appropriate");
    }
}
```

#### 5. backend-master/backend-master/src/main/java/com/roadwise/backend/dto/UserResponseDto.java
**File**: `backend-master/backend-master/src/main/java/com/roadwise/backend/dto/UserResponseDto.java`
**Changes**: MODIFY — comment only. The Javadoc at `:12` still lists "failed-login counter, lockout expiry" among the fields it deliberately does not expose. That rationale becomes vacuous once the fields are gone; rewrite it to name the remaining omitted fields.

```java
    // Rewrite the class Javadoc at UserResponseDto.java:9-14.
    //
    // It currently states that certain fields are deliberately not exposed and names
    // "failed-login counter, lockout expiry" among them. Once Phase 3 drops those
    // columns the clause describes nothing. Replace it with a statement of the rule
    // rather than a list of fields: a user response carries identity and role, never
    // authentication state or credential metadata.
```

### Success Criteria:

#### Automated Verification:
- [ ] New tests pass: `cd backend-master/backend-master && ./mvnw -B -Dtest=AuthLoginEnumerationTest test` exits 0
- [ ] Existing auth tests pass: `cd backend-master/backend-master && ./mvnw -B -Dtest=AuthControllerTest test` exits 0
- [ ] Module compiles: `cd backend-master/backend-master && ./mvnw -B -q compile` exits 0
- [ ] No live lockout-column reference remains in main sources (the migration chain is excluded because `V1__baseline_schema.sql:22-23` names the columns permanently): `grep -rE "failedLoginAttempts|lockoutUntil" backend-master/backend-master/src/main/java` produces no output
- [ ] Migration present at the next sequence position: `ls backend-master/backend-master/src/main/resources/db/migration/V25__drop_login_lockout_columns.sql` succeeds

#### Manual Verification:

---

## Phase 4: BK5 rank-gated barangay projection

### Overview

Replace the entity return with a rank-gated DTO and stop mutating the entity in place. Independent of Phases 1-3 and of Phases 5-6.

### Changes Required:

#### 1. backend-master/backend-master/src/main/java/com/roadwise/backend/security/RoleCapability.java
**File**: `backend-master/backend-master/src/main/java/com/roadwise/backend/security/RoleCapability.java`
**Changes**: MODIFY — add the `BARANGAY_CONTACT` capability constant.

```java
```

#### 2. backend-master/backend-master/src/main/java/com/roadwise/backend/security/RoleCapabilities.java
**File**: `backend-master/backend-master/src/main/java/com/roadwise/backend/security/RoleCapabilities.java`
**Changes**: MODIFY — grant `BARANGAY_CONTACT` to `ADMIN` and `CPDO Admin` in the `BY_ROLE` map, and add the accessor method used by `@PreAuthorize`.

```java
```

#### 3. backend-master/backend-master/src/main/java/com/roadwise/backend/dto/BarangayResponseDto.java
**File**: `backend-master/backend-master/src/main/java/com/roadwise/backend/dto/BarangayResponseDto.java`
**Changes**: NEW — read projection with a static `from(Barangay, boolean)` factory, following the `dto/UserResponseDto` convention.

```java
```

#### 4. backend-master/backend-master/src/main/java/com/roadwise/backend/controller/BarangayController.java
**File**: `backend-master/backend-master/src/main/java/com/roadwise/backend/controller/BarangayController.java`
**Changes**: MODIFY — `getBarangayById` (`:118-142`) returns the DTO and no longer writes to the entity.

```java
```

#### 5. backend-master/backend-master/src/test/java/com/roadwise/backend/controller/BarangayContactExposureTest.java
**File**: `backend-master/backend-master/src/test/java/com/roadwise/backend/controller/BarangayContactExposureTest.java`
**Changes**: NEW — `BARANGAY` role sees three nulls; `ADMIN` sees the values.

```java
```

### Success Criteria:

#### Automated Verification:

#### Manual Verification:

---

## Phase 5: Actuator authorization and health role gap

### Overview

Add the repo's first inline role matcher for actuator metrics/info, narrow production exposure, and close the `show-details` role gap. Independent of Phases 1-4 and 6.

### Changes Required:

#### 1. backend-master/backend-master/src/main/java/com/roadwise/backend/config/SecurityConfig.java
**File**: `backend-master/backend-master/src/main/java/com/roadwise/backend/config/SecurityConfig.java`
**Changes**: MODIFY — add an `ACTUATOR_ADMIN_PATHS` list and a role matcher between `:106` and `:107`; update the now-stale comment at `:85-86`.

```java
```

#### 2. backend-master/backend-master/src/main/resources/application-prod.properties
**File**: `backend-master/backend-master/src/main/resources/application-prod.properties`
**Changes**: MODIFY — narrow `management.endpoints.web.exposure.include` to `health` and set `management.endpoint.health.roles`.

```
```

#### 3. backend-master/backend-master/src/test/java/com/roadwise/backend/config/ActuatorAuthorizationTest.java
**File**: `backend-master/backend-master/src/test/java/com/roadwise/backend/config/ActuatorAuthorizationTest.java`
**Changes**: NEW — anonymous health 200; `BARANGAY` metrics 403; `ADMIN` metrics 200.

```java
```

### Success Criteria:

#### Automated Verification:

#### Manual Verification:

---

## Phase 6: AI service surface hardening

### Overview

Close the unauthenticated root payload, disable generated docs in production, and compare the shared secret in constant time. Independent of Phases 1-5.

### Changes Required:

#### 1. roadwise-ai-service-main/roadwise-ai-service-main/main.py
**File**: `roadwise-ai-service-main/roadwise-ai-service-main/main.py`
**Changes**: MODIFY — import `hmac`; swap the comparison at `:64` for `hmac.compare_digest`; drop `csjdm_profile` and `auth_configured` from the `GET /` payload (`:234-240`); conditionally null the three FastAPI docs URLs (`:22-26`).

```python
```

#### 2. roadwise-ai-service-main/roadwise-ai-service-main/test_auth_surface.py
**File**: `roadwise-ai-service-main/roadwise-ai-service-main/test_auth_surface.py`
**Changes**: NEW — unauthenticated `GET /` omits the profile; a wrong key returns 401; docs routes are absent under production env.

```python
```

### Success Criteria:

#### Automated Verification:

#### Manual Verification:

---

## Ordering Constraints

- **Phase 0 gates Phases 1-5.** The backend test suite cannot execute at HEAD (`junit-platform-launcher` absent while `junit-platform-engine` is 6.0.3), so every backend Success Criteria command is red until Phase 0 lands. Phase 6 is unaffected — it runs `pytest`, not Maven.

- **Phase 1 → Phase 2 → Phase 3** is a hard chain. Phase 1 makes `RateLimitService` safe to call on an expired row; Phase 2 depends on that fix because it adds a new caller; Phase 3 drops the columns that Phase 2 stops writing.
- **Phase 3 must not land before Phase 2.** `UserController.java:273-278` reads `lockoutUntil`; dropping the column while that read survives would not compile.
- **Phases 4, 5 and 6 are independent** of each other and of Phases 1-3, by specific file: Phase 4 touches `security/RoleCapability`, `security/RoleCapabilities`, `dto/BarangayResponseDto` (new), `controller/BarangayController`, and its own test; Phase 5 touches `config/SecurityConfig`, `resources/application-prod.properties`, and its own test; Phase 6 touches `main.py` and its own test. They may run in parallel once Phase 1 has landed, though running them after Phase 3 keeps the reviewer's tree stable. **One shared package:** Phase 3 edits `dto/UserResponseDto.java` (comment only) and Phase 4 creates `dto/BarangayResponseDto.java` — same package, different files, so there is no write conflict, but they are not literally disjoint and the reviewer should expect two `dto/` changes.
- **Phase 4 touches two shared files** (`RoleCapability`, `RoleCapabilities`). No other phase touches them, so no conflict.

## Verification Notes

- **Backend tests require a database.** The `test` profile uses H2 with `ddl-auto=create-drop` and Flyway disabled, so schema changes are invisible to it. Backend suites need Maven and will not run in a bare shell without the test datasource.
- **`FlywaySchemaDriftTest` is skipped by default.** It needs `ROADWISE_TEST_PG_URL`; run it after Phase 3 to prove `V25` matches the entity. Do not treat its skip as a pass.
- **`PerformanceBenchmarkTest:59` pins anonymous `GET /actuator/health` → 200.** Any `SecurityConfig` change must keep it green — this is the guard that Phase 5 did not over-restrict.
- **Phase 4 null serialization — verified, not assumed.** Grep for `spring.jackson` across `src/main/resources/application*.properties` returns nothing, so Boot's default `JsonInclude.Include.ALWAYS` governs the new DTO: gated fields appear as explicit `null` values. Assert `jsonPath("$.contactNumber").value(nullValue())`, not absence. If a later change adds `default-property-inclusion: non_null`, this assertion breaks — that is the tripwire.
- **`EndpointAuthorizationCoverageTest` will not cover Phase 5.** It scans `controller/**` only; an inline matcher in `SecurityConfig` is invisible to it. The coverage guarantee for actuator comes from Phase 5's own test, not from that suite.
- **`RateLimitServiceConcurrencyTest.concurrentFailuresDoNotLoseUpdates` asserts a final `attemptCount` of 31.** Phase 1 must not change the counting behaviour, only the expired-row branch, or that test breaks.
- **AI-service tests need the service's own Python environment**; `main.py` imports `onnxruntime` and `numpy` at module scope, so a bare `pytest` without those installed fails at import, not at assertion.
- **`V25` must be idempotent** — `DROP COLUMN IF EXISTS` — because `B4` in `SCHEMA_AND_BUGS.md` records that Flyway has previously re-run against a pre-existing database.
- **Stale documentation to update:** this plan falsifies more of the fault catalogue than the actuator change alone. Actuator: `PRODUCTION_READINESS.md:418-419`, `SCHEMA_AND_BUGS.md:420-422`, and the comment at `SecurityConfig.java:85-86`. Lockout: `SCHEMA_AND_BUGS.md:229-231` (BK3 mechanism) and `:233-237` (BK4). Test inventory: `PRODUCTION_READINESS.md:144-145` claims "`AuthControllerTest`'s 11 lockout tests" — that suite asserts no lockout behaviour at all. Updating these docs is not a phase deliverable but should not be skipped.

## Performance Considerations

- `RateLimitService.isBlocked` both reads and writes (it deletes expired rows), so it is not a pure read. Phase 2 adds one locked `SELECT` plus a possible `DELETE` per password-change attempt — negligible against a bcrypt verification already in that path.
- Phase 4 removes an in-place entity mutation and adds a DTO allocation per request. Cheaper than the current path, which mutates a managed entity.
- Do **not** add `@Cacheable` to the gated `getBarangayById`. The list endpoint caches at `BarangayController.java:97`; caching a per-role payload would leak an admin response to a non-admin caller. The existing cache is keyed on no role.
- Phase 6's `hmac.compare_digest` is constant-time over the shorter input and costs nothing measurable at this request rate.
- Phase 5's inline matcher adds one string comparison per `/actuator/**` request. No measurable effect.

## Migration Notes

- **`V25` drops two columns.** `failed_login_attempts` and `lockout_until` on `users`. Both must use `DROP COLUMN IF EXISTS`.
- **Rollback**: re-adding the columns is possible, but the historical counter values are lost. Because login no longer reads them, a rollback needs a code revert too, not just a schema revert — the columns would return as dead. Treat this as a one-way door and confirm the deploy order.
- **Deploy order matters**: Phase 3's column drop must ship **after** the application code that stops reading the columns. Deploying the migration first would break `/api/users/{id}/password` on the still-running previous version.
- **No data migration is required** — the columns hold only transient counters.
- **Test profile cannot see this change** (`ddl-auto=create-drop`, Flyway disabled). `FlywaySchemaDriftTest` against Postgres is the only proof the entity and schema agree.

## Pattern References

- `backend-master/backend-master/src/main/java/com/roadwise/backend/controller/UserController.java:955-967` — the `EMAIL_CHANGE_PWD:` `RateLimitService` retarget to model Phase 2 after.
- `backend-master/backend-master/src/main/java/com/roadwise/backend/dto/UserResponseDto.java` — hand-written POJO with a static `from(Entity)` factory; the DTO convention for Phase 4.
- `backend-master/backend-master/src/main/java/com/roadwise/backend/security/FieldScopePolicy.java:29-56` — conditional field exposure, including the fail-closed `capabilities != null` guard to mirror.
- `backend-master/backend-master/src/main/java/com/roadwise/backend/security/RoleCapabilities.java:15-49` — the `BY_ROLE` capability map and `@Component("roleCapabilities")` bean wiring.
- `backend-master/backend-master/src/test/java/com/roadwise/backend/controller/AuthControllerTest.java:45-115` — full-context MockMvc with a real JWT; the test harness for Phases 2-5.
- `backend-master/backend-master/src/test/java/com/roadwise/backend/service/RateLimitServiceConcurrencyTest.java` — real transaction manager plus repository; the harness for Phase 1.
- `backend-master/backend-master/src/main/java/com/roadwise/backend/config/SecurityConfig.java:97-115` — first-match ordering; the insertion point for Phase 5.
- `roadwise-ai-service-main/roadwise-ai-service-main/main.py:32-37` — the `AI_ENV` guard to mirror for the docs-URL decision in Phase 6.

## Developer Context

### Step 4 checkpoint — architectural ambiguities

**Q (BK3+BK4, `AuthController.java:173`, `:189-199`, `:225-262`): login already rate-limits `IP:`/`USER:` and returns a generic 429; the account lockout layered on top is what mutates account state pre-auth. Remove it and drop the columns, remove it and leave the columns dormant, or keep the lockout and defer the penalty past MFA?**
A: Remove from login + drop columns. Login's `IP:`/`USER:` throttle becomes the only brute-force control; `failedLoginAttempts`/`lockoutUntil` are removed from `User.java` and dropped in `V25`. Consequence accepted: `FlywaySchemaDriftTest` must be re-run with `ROADWISE_TEST_PG_URL` set.

**Q (BK5, `BarangayController.java:107` vs `:118-142`): the list endpoint nulls `brgyCaptain` alongside both contact fields and calls all three "official personal contact details"; the detail endpoint shows all three to any authenticated caller. Gate all three, gate only the contact channels, or gate all three and eliminate the in-place mutation?**
A: Null all three, matching the list endpoint exactly.

**Q (CF8c, `SessionAuthenticator.java:71-84`, `application-prod.properties`): no inline role matcher exists in this repo. Inline matcher plus prod narrowing, prod narrowing only, or inline matcher only?**
A: Inline matcher + prod narrowing. Placement between `SecurityConfig.java:106` and `:107`; production narrows `exposure.include` to `health`.

**Q (AI docs, `main.py:22-26`, `:30`): `/docs`, `/redoc`, `/openapi.json` are unauthenticated and publish the `SoilType` enum. Disable in production only, disable everywhere, or gate behind the API key?**
A: Disable in production only, reusing the `AI_ENV` variable already read at `main.py:30`.

### Inherited from research (not re-asked)

- BK3 scope: mechanics + enumeration unification.
- BK3/BK4 target: `RateLimitService`, `PASSWORD_CHANGE:<id>` key.
- BK5 shape: rank-gated projection, not a blanket strip.
- Extra scope in: `RateLimitService` stale-row bug, AI `/docs` exposure, health `show-details` role gap.
- Extra scope out: plaintext-password rotation/migration.

### Step 5 decomposition

**Q: Approve the 6-slice decomposition (3 dependent slices on the shared lockout store, then 3 independent slices)?**
A: Approved as proposed; no merge of slices 2+3, no split of slice 3.

### Step 5 revision — Phase 0 inserted

**Q (raised while pre-validating Phase 1's automated criteria; evidence `backend-master/backend-master/pom.xml:5-9`, `target/surefire-reports/*.dumpstream` dated 2026-10-18): the backend test suite fails at discovery at HEAD — `junit-platform-engine` is 6.0.3 while `junit-platform-launcher` is absent from the graph and surefire 3.5.6 supplies an older one (`org.junit.platform.commons.JUnitException: OutputDirectoryCreator not available`). `./mvnw -B -q compile` is green, `./mvnw -B -Dtest=... test` is red. Five of six phases carry backend test criteria, so those criteria would be permanently red for a file set this plan never touches.**
A: Add Phase 0 to fix the pom.

**This supersedes the "6-slice" wording above.** The decomposition is now **7 slices**: Phase 0 (test harness alignment) inserted as slice 0 with no dependencies, Phases 1-5 depend on it, Phase 6 does not (it runs `pytest`). `phase_count` is 7.

### Slice verification log — Phase 0

`slice-verifier` dispatched before the Phase 0 micro-checkpoint (§6.2). Four VIOLATIONs and two WARNINGs, all addressed in place rather than deferred:

1. **Wrapper path (VIOLATION, fixed).** `./mvnw` does not exist at repo root — only `backend-master/backend-master/mvnw`. Criteria rewritten to `cd backend-master/backend-master && ./mvnw …`. This defect would have broken the automated criteria of **every** backend phase, not just Phase 0.
2. **Phase 0 dependency unrecorded (VIOLATION, fixed).** `depends_on` for Phases 1-5 now includes `0`, and Ordering Constraints carries an explicit "Phase 0 gates Phases 1-5" bullet.
3. **Decomposition record missing (VIOLATION, fixed).** The 6→7 change is now recorded here; `phase_count` is 7.
4. **`grep -c "junit-platform-launcher"` returns 1 (VIOLATION, fixed).** The pom's convention is to name an artifact in a preceding comment (`pom.xml:108-112`), which would have made the count 2. Criterion narrowed to `<artifactId>junit-platform-launcher</artifactId>`, which stays 1 regardless of comments.
5. **Launcher classpath ordering (WARNING, carried as residual risk).** The slice asserts the BOM-supplied 6.0.3 launcher wins over the one bundled inside `surefire-junit-platform:3.5.6`'s plugin classpath. Modern surefire prefers a project-declared launcher, which is why this is the documented remedy, but it is unverified here. **Fallback if Phase 0's criterion 1 still fails:** add the same `junit-platform-launcher` to the surefire plugin's own `<dependencies>` block beside `surefire-junit-platform` (`pom.xml:175-181`), mirroring the symmetric pin the pom already uses for the provider.
6. **New jar must be fetched (WARNING, noted).** `junit-platform-launcher` is absent from `dependency:tree` today, so it must download once before any offline gate passes. `PRODUCTION_READINESS.md:554-555` claims "Maven remains un-runnable in this environment" — that claim is **stale**: `./mvnw -v` returns Maven 3.9.16 and `./mvnw -B -q compile` exits 0 at HEAD.

## Plan History

- Phase 0: Test harness alignment — approved as generated
- Phase 1: RateLimitService expiry correctness — approved as generated (revised twice after `slice-verifier`: patch ranges narrowed to `:111-115`/`:151-155`, two vacuous assertions replaced)
- Phase 2: BK4 password-change retarget — approved as generated (expanded from 2 to 6 patch sites after round 1 found four extra `UserController` lockout sites that would have broken the module at Phase 3; 401-on-429 assertion replaced with a bounded loop after round 1 found the 2-second penalty window)
- Phase 3: BK3 lockout retirement, enumeration unification, column drop — approved as generated (expanded to 5 files after round 2 found patch J needed `UserResponseDto`; `User.java` deletion widened to the whole `:51-56` block after round 2 found the `@Column` annotations would orphan onto `sessionVersion`; grep criterion scoped away from the immutable migration chain after round 1 found it unsatisfiable)
- Phase 4: BK5 rank-gated barangay projection — pending
- Phase 5: Actuator authorization and health role gap — pending
- Phase 6: AI service surface hardening — pending

## References

- Research: `.rpiv/artifacts/research/2026-10-20_08-25-53_security-hardening-gaps.md`
- Findings catalogue: `SCHEMA_AND_BUGS.md` (BK3, BK4, BK5, CF6b, CF8b, CF8c; audited against `02ee75e`)
- Deferred hardening items: `PRODUCTION_READINESS.md`
- Frontend DOM gaps: `frontendcap/DOM_CONTRACT_FINDINGS.md`, `frontendcap/dom-contract-baseline.json`
