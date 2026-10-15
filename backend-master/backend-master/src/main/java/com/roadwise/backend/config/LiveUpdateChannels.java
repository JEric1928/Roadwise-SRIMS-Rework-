package com.roadwise.backend.config;

import com.roadwise.backend.model.User;

/**
 * The live-update destinations, and who is allowed to subscribe to each.
 *
 * <p>Names and authorization live together deliberately. The rule for a channel is a function
 * of its name -- {@code /topic/updates/barangay/7} is meaningless without knowing that the
 * trailing number is a barangay -- so a table of names in one file and a matcher in another is
 * how the two drift into disagreeing about what a destination means. Anything that constructs
 * a destination goes through here, and anything that authorizes one is answered here.
 *
 * <p>Why scoped at all: one global topic wakes every connected dashboard for every change,
 * so a report filed in one barangay costs a refetch in all of them. Scoping the destination is
 * what makes the refresh proportional to who actually cares.
 *
 * <p>That scoping is routing, not confidentiality, while the payload is a refresh signal with
 * no content. It becomes a confidentiality boundary the moment a channel carries data, and the
 * rules below are written to still be correct then: a barangay official may read only their
 * own assigned barangay, and administrators and engineers read every barangay because they
 * already see every barangay in their dashboards.
 */
public final class LiveUpdateChannels {

    /** Administrators, including the CPDO administrator role. */
    public static final String ADMIN = WebSocketConfig.BROKER_DESTINATION_PREFIX + "/updates/admin";

    /** Engineers, which is what the dashboards present as the CEO view. */
    public static final String ENGINEERING = WebSocketConfig.BROKER_DESTINATION_PREFIX + "/updates/engineering";

    /** Prefix for a single barangay's channel; complete it with {@link #forBarangay}. */
    public static final String BARANGAY_PREFIX = WebSocketConfig.BROKER_DESTINATION_PREFIX + "/updates/barangay/";

    private LiveUpdateChannels() {
    }

    public static String forBarangay(long barangayId) {
        return BARANGAY_PREFIX + barangayId;
    }

    /**
     * Whether this user may subscribe to this destination.
     *
     * <p>Fails closed on everything it does not recognize: an unknown destination, a malformed
     * barangay id, a user with no role. A new destination is therefore unreadable until someone
     * adds a rule for it here, which is the intended cost -- the opposite default cannot be
     * reviewed, because nobody knows what the unintended destinations are until they are read.
     *
     * <p>The barangay is taken from the authenticated account, never from the request. A client
     * cannot reach another barangay's channel by naming it, whether by guessing the id or by
     * sending a malformed one.
     */
    public static boolean permits(User user, String destination) {
        if (user == null || destination == null) {
            return false;
        }
        if (ADMIN.equals(destination)) {
            return isAdmin(user);
        }
        if (ENGINEERING.equals(destination)) {
            return isEngineer(user);
        }
        if (destination.startsWith(BARANGAY_PREFIX)) {
            Long requested = parseBarangayId(destination);
            if (requested == null) {
                return false;
            }
            if (isAdmin(user) || isEngineer(user)) {
                return true;
            }
            return isBarangay(user)
                    && user.getBarangay() != null
                    && requested.equals(user.getBarangay().getId());
        }
        return false;
    }

    /**
     * Parses the trailing id, accepting only the canonical spelling this class produces.
     *
     * <p>{@code /topic/updates/barangay/007} is refused rather than normalized: nothing is ever
     * published there, so authorizing it would hand out a subscription that can never receive
     * anything while looking like it worked. Refusing is the honest answer.
     */
    private static Long parseBarangayId(String destination) {
        String suffix = destination.substring(BARANGAY_PREFIX.length());
        if (suffix.isEmpty()) {
            return null;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) {
                return null;
            }
        }
        try {
            long id = Long.parseLong(suffix);
            return destination.equals(forBarangay(id)) ? id : null;
        } catch (NumberFormatException overflowed) {
            return null;
        }
    }

    private static boolean isAdmin(User user) {
        return hasRole(user, "ADMIN") || hasRole(user, "CPDO Admin");
    }

    private static boolean isEngineer(User user) {
        return hasRole(user, "ENGINEER");
    }

    private static boolean isBarangay(User user) {
        return hasRole(user, "BARANGAY");
    }

    /** Tolerates the {@code ROLE_} prefix, the same way the authentication filter does. */
    private static boolean hasRole(User user, String role) {
        String actual = user.getRole();
        if (actual == null) {
            return false;
        }
        if (actual.startsWith("ROLE_")) {
            actual = actual.substring("ROLE_".length());
        }
        return actual.equalsIgnoreCase(role);
    }
}
