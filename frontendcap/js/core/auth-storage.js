/**
 * Single owner of every credential and identity read/write.
 *
 * Why this module exists: tokens were written to localStorage unconditionally and
 * identity to sessionStorage, so the two disagreed in both directions. A "remember me"
 * session survived a browser restart but looked signed out in a new tab (no tab-scoped
 * identity), while an unremembered login still parked a 7-day refresh token on disk
 * against the user's explicit choice.
 *
 * The rules here:
 *   - "Remember me" picks the durable scope: localStorage when checked, sessionStorage
 *     when not. Tokens and identity follow the same scope, so they cannot disagree.
 *   - Reads fall back to the other scope, so a session written before the scope existed
 *     is still found.
 *   - sessionStorage stays the *runtime* identity store: roughly twenty feature modules
 *     read it directly (some at module scope, before anything else runs). A remembered
 *     session is copied into it once at boot by restoreRememberedSession(), which keeps
 *     this change out of every one of those consumers.
 *   - Identity is mirrored rather than moved: writeIdentity() always writes the tab
 *     copy the feature modules read, and additionally the durable one when the session
 *     was remembered, so a profile edit is not undone by opening a new tab.
 *   - Logout wipes both scopes. Nothing about a session may survive it.
 *
 * Imported by the API layer, the step-up prompt, the login flow, the profile editor and
 * the boot bootstrap. It imports nothing itself, which is what keeps it out of the
 * api.js <-> step-up.js cycle and out of every other one.
 */

/** Identity written at login; the shape every feature module already reads. */
export const IDENTITY_KEYS = [
  'userId', 'username', 'userRole', 'firstName', 'middleName', 'lastName',
  'email', 'phoneNumber', 'birthday', 'gender', 'profilePicture',
  'barangayId', 'barangayName'
];

/** Token keys, plus the profile blobs login also persists. */
const TOKEN_KEYS = ['accessToken', 'refreshToken'];
const PROFILE_KEYS = ['user', 'currentUser'];

/**
 * Which scope the current session was written to. Kept in localStorage even for a
 * tab-scoped session: it holds no credential, and a new tab needs it to know that the
 * session was deliberately not remembered (so it must not be restored).
 */
const SCOPE_KEY = 'roadwise_session_scope';
const SCOPE_REMEMBERED = 'local';
const SCOPE_TAB = 'session';

/** Profile blob field -> flat identity key, used to rebuild a pre-scope session. */
const PROFILE_TO_IDENTITY = {
  id: 'userId',
  role: 'userRole',
  username: 'username',
  firstName: 'firstName',
  middleName: 'middleName',
  lastName: 'lastName',
  barangayId: 'barangayId',
  barangayName: 'barangayName'
};

/**
 * Storage access can throw rather than fail (Safari private mode, disabled cookies,
 * quota), so every touch is guarded and a missing store reads as "no value".
 */
function durableStore() {
  try {
    return typeof localStorage !== 'undefined' ? localStorage : null;
  } catch (e) {
    return null;
  }
}

function tabStore() {
  try {
    return typeof sessionStorage !== 'undefined' ? sessionStorage : null;
  } catch (e) {
    return null;
  }
}

function readQuietly(store, key) {
  if (!store) return null;
  try {
    return store.getItem(key);
  } catch (e) {
    return null;
  }
}

function writeQuietly(store, key, value) {
  if (!store) return;
  try {
    store.setItem(key, value);
  } catch (e) {
    // Out of quota or storage denied. The request that triggered the write still
    // succeeds; the next read simply finds nothing and the caller re-authenticates.
  }
}

function removeQuietly(store, key) {
  if (!store) return;
  try {
    store.removeItem(key);
  } catch (e) {
    // Nothing else to do: the value was never ours to delete.
  }
}

export function getSessionScope() {
  const durable = durableStore();
  const marker = readQuietly(durable, SCOPE_KEY);
  if (marker === SCOPE_TAB) return SCOPE_TAB;
  if (marker === SCOPE_REMEMBERED) return SCOPE_REMEMBERED;

  // No marker: a session created before the scope existed. Those were all written to
  // localStorage, so treat them as remembered rather than signing anybody out.
  const legacyToken = readQuietly(durable, 'refreshToken') || readQuietly(durable, 'accessToken');
  return legacyToken ? SCOPE_REMEMBERED : SCOPE_TAB;
}

export function setSessionScope(remember) {
  writeQuietly(durableStore(), SCOPE_KEY, remember ? SCOPE_REMEMBERED : SCOPE_TAB);
}

/**
 * Starts a fresh session in the scope "remember me" asked for, discarding any earlier
 * copy. Without the purge a previous remembered login would leave its tokens in
 * localStorage, where they would shadow the new tab-scoped session on the next read.
 */
export function beginSession(remember) {
  const target = remember ? durableStore() : tabStore();
  const other = remember ? tabStore() : durableStore();

  for (const key of [...TOKEN_KEYS, ...IDENTITY_KEYS, ...PROFILE_KEYS]) {
    removeQuietly(other, key);
  }
  setSessionScope(!!remember);

  return target;
}

/**
 * Writes cleaned identity values into this tab, and mirrors them into durable storage
 * when the session was remembered.
 *
 * The tab copy is written unconditionally: roughly twenty feature modules read identity
 * from sessionStorage, including the screen that just produced the value. The durable
 * copy is what a new tab restores from, so an edit that skipped it would come back as
 * the pre-edit value the next time the user opens a tab.
 */
export function writeIdentity(values) {
  const entries = Object.entries(values || {})
    .filter(([, value]) => value !== undefined && value !== null);
  const durable = getSessionScope() === SCOPE_REMEMBERED ? durableStore() : null;

  for (const [key, value] of entries) {
    writeQuietly(tabStore(), key, String(value));
    writeQuietly(durable, key, String(value));
  }
}

/** Writes the profile blobs ({user, currentUser}) the same way writeIdentity does. */
export function writeProfile(payload) {
  const serialised = JSON.stringify(payload);
  const durable = getSessionScope() === SCOPE_REMEMBERED ? durableStore() : null;

  for (const key of PROFILE_KEYS) {
    writeQuietly(tabStore(), key, serialised);
    writeQuietly(durable, key, serialised);
  }
}

export function writeAuthItem(key, value) {
  const store = getSessionScope() === SCOPE_REMEMBERED ? durableStore() : tabStore();
  writeQuietly(store, key, value);
}

export function writeAuthTokens(tokens) {
  if (!tokens) return;
  if (tokens.accessToken) writeAuthItem('accessToken', tokens.accessToken);
  if (tokens.refreshToken) writeAuthItem('refreshToken', tokens.refreshToken);
}

/**
 * Reads an auth value from whichever scope holds it, preferring the session's own
 * scope so a rehydrated tab copy cannot mask the durable one.
 */
export function readAuthItem(key) {
  const durable = durableStore();
  const tab = tabStore();
  const stores = getSessionScope() === SCOPE_REMEMBERED ? [durable, tab] : [tab, durable];

  for (const store of stores) {
    const value = readQuietly(store, key);
    if (value !== null) return value;
  }
  return null;
}

export const readAuthToken = readAuthItem;

/**
 * Clears every place a session can be persisted. Logout must not leave access or
 * refresh tokens behind in either scope.
 *
 * The remembered username is deliberately kept: it is the one thing "remember me" is
 * supposed to retain, and it is not a credential.
 */
export function clearAuthSession() {
  const tab = tabStore();
  try {
    if (tab) tab.clear();
  } catch (e) {
    // Storage denied; the key-by-key purge below still runs.
  }

  const durable = durableStore();
  for (const key of [SCOPE_KEY, ...TOKEN_KEYS, ...IDENTITY_KEYS, ...PROFILE_KEYS]) {
    removeQuietly(durable, key);
  }
}

function readProfilePayload(store) {
  for (const key of PROFILE_KEYS) {
    const raw = readQuietly(store, key);
    if (!raw) continue;
    try {
      const parsed = JSON.parse(raw);
      if (parsed && typeof parsed === 'object') return parsed;
    } catch (e) {
      // Corrupted blob; fall through to the next key.
    }
  }
  return null;
}

/**
 * Refills this tab's identity from a session the user asked to remember.
 *
 * Returns true when a live identity is now available in sessionStorage. A tab-scoped
 * session returns false by design — not being restored in a new tab is exactly what
 * not ticking "remember me" means.
 */
export function restoreRememberedSession() {
  if (getSessionScope() !== SCOPE_REMEMBERED) return false;

  const durable = durableStore();
  const tab = tabStore();
  if (!durable || !tab) return false;

  try {
    const hasDurableToken = readQuietly(durable, 'accessToken') || readQuietly(durable, 'refreshToken');
    if (!hasDurableToken) return false;

    for (const key of IDENTITY_KEYS) {
      if (readQuietly(tab, key) !== null) continue;
      const value = readQuietly(durable, key);
      if (value !== null) writeQuietly(tab, key, value);
    }

    // Sessions created before identity was persisted durably carry only the profile
    // blob. Rebuild the flat keys from it so the guard and the feature modules see the
    // shape they have always read.
    if (!readQuietly(tab, 'userId') || !readQuietly(tab, 'userRole')) {
      const profile = readProfilePayload(durable);
      if (profile) {
        for (const [source, target] of Object.entries(PROFILE_TO_IDENTITY)) {
          if (readQuietly(tab, target) !== null) continue;
          const value = profile[source];
          if (value !== undefined && value !== null) writeQuietly(tab, target, String(value));
        }
      }
    }

    return !!(readQuietly(tab, 'userId') && readQuietly(tab, 'userRole'));
  } catch (e) {
    return false;
  }
}
