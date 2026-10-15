/**
 * Which live-update channel a dashboard listens on.
 *
 * A pure function in its own module, free of sessionStorage and of the socket, so the mapping
 * can actually be tested. It has to agree with the server's rule, and the failure when it does
 * not is silent in the worst way: the server refuses a channel the caller is not entitled to,
 * so a mismatch here stops a dashboard from updating without raising anything a user would see.
 *
 * The channel names are duplicated from the server's `LiveUpdateChannels` on purpose -- there
 * is no shared source between the two languages, so the duplication is unavoidable and is
 * pinned by a test on each side instead.
 */

export const ADMIN_CHANNEL = '/topic/updates/admin';
export const ENGINEERING_CHANNEL = '/topic/updates/engineering';
export const BARANGAY_CHANNEL_PREFIX = '/topic/updates/barangay/';

/**
 * @param {string} role      the signed-in user's role, as stored at login
 * @param {string} barangayId the signed-in user's barangay, for the official role
 * @returns {string|null} the channel to subscribe to, or null when there is nothing to listen
 *                        to; the caller must not subscribe to null
 */
export function liveUpdateChannel(role, barangayId) {
  const normalized = String(role || '').toUpperCase();

  if (normalized.includes('BARANGAY')) {
    const id = String(barangayId || '');
    // Canonical form only, matching what the server accepts and what the publisher produces:
    // digits with no leading zero. "007" names a channel nothing is ever published to, so the
    // server refuses it -- and accepting it here would have subscribed to a channel that can
    // never deliver, which looks exactly like everything being fine.
    return /^[1-9]\d*$/.test(id) ? BARANGAY_CHANNEL_PREFIX + id : null;
  }
  if (normalized.includes('CEO') || normalized.includes('ENGINEER')) {
    return ENGINEERING_CHANNEL;
  }
  if (normalized.includes('ADMIN') || normalized.includes('CPDO')) {
    return ADMIN_CHANNEL;
  }
  return null;
}
