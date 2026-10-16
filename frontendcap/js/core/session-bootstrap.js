/**
 * Restores a remembered session before anything reads identity.
 *
 * This module exists to be imported *first* in js/app.js, and its position in that
 * import list is load-bearing. ES modules are evaluated depth-first in import order, so
 * being first means it runs before the feature modules whose module-scope code reads
 * identity straight out of sessionStorage — js/features/notifications/index.js reads
 * `sessionStorage.getItem("userId")` at module scope. Move this import down the list and
 * those modules quietly see a signed-out tab on every page load of a remembered session.
 *
 * A no-op for a session the user did not ask to remember, and for anyone already signed
 * in: see restoreRememberedSession() in ./auth-storage.js.
 */
import { restoreRememberedSession } from './auth-storage.js';

restoreRememberedSession();
