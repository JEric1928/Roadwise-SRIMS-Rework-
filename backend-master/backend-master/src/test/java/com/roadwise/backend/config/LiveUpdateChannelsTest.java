package com.roadwise.backend.config;

import com.roadwise.backend.model.Barangay;
import com.roadwise.backend.model.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may subscribe to what.
 *
 * <p>The rule under test is a function of the destination and the authenticated account, so it
 * is settled here rather than over a socket. Every case is stated as an allowed/refused pair
 * where one exists, so a rule that refuses everything fails just as loudly as one that refuses
 * nothing.
 */
class LiveUpdateChannelsTest {

    private static final long OWN_BARANGAY = 7L;
    private static final long OTHER_BARANGAY = 99L;

    @Test
    void adminRole_readsAdminChannel() {
        assertTrue(LiveUpdateChannels.permits(admin(), LiveUpdateChannels.ADMIN));
        assertFalse(LiveUpdateChannels.permits(engineer(), LiveUpdateChannels.ADMIN));
        assertFalse(LiveUpdateChannels.permits(barangayOfficial(OWN_BARANGAY), LiveUpdateChannels.ADMIN));
    }

    /** The CPDO administrator role is an administrator for this purpose, as it is elsewhere. */
    @Test
    void cpdoAdminRole_readsAdminChannel() {
        User cpdo = user("CPDO Admin");
        cpdo.setBarangay(null);

        assertTrue(LiveUpdateChannels.permits(cpdo, LiveUpdateChannels.ADMIN));
    }

    /** The dashboards present the engineer role as the CEO view; it is the same role. */
    @Test
    void engineerRole_readsEngineeringChannel() {
        assertTrue(LiveUpdateChannels.permits(engineer(), LiveUpdateChannels.ENGINEERING));
        assertFalse(LiveUpdateChannels.permits(admin(), LiveUpdateChannels.ENGINEERING));
        assertFalse(LiveUpdateChannels.permits(barangayOfficial(OWN_BARANGAY), LiveUpdateChannels.ENGINEERING));
    }

    @Test
    void aRolePrefixIsTolerated() {
        User prefixed = barangayOfficial(OWN_BARANGAY);
        prefixed.setRole("ROLE_BARANGAY");

        assertTrue(LiveUpdateChannels.permits(prefixed, LiveUpdateChannels.forBarangay(OWN_BARANGAY)));
    }

    @Test
    void official_readsOwnBarangayChannel() {
        assertTrue(LiveUpdateChannels.permits(
                barangayOfficial(OWN_BARANGAY), LiveUpdateChannels.forBarangay(OWN_BARANGAY)));
    }

    /** The case the scoping exists for. */
    @Test
    void official_cannotReadAnotherBarangaysChannel() {
        assertFalse(LiveUpdateChannels.permits(
                barangayOfficial(OWN_BARANGAY), LiveUpdateChannels.forBarangay(OTHER_BARANGAY)));
    }

    @Test
    void adminAndEngineer_readEveryBarangayChannel() {
        assertTrue(LiveUpdateChannels.permits(admin(), LiveUpdateChannels.forBarangay(OTHER_BARANGAY)));
        assertTrue(LiveUpdateChannels.permits(engineer(), LiveUpdateChannels.forBarangay(OTHER_BARANGAY)));
    }

    /**
     * An official with no barangay has no own channel. Worth pinning because the alternative --
     * treating a missing barangay as "any" -- would hand the whole city to an unassigned account.
     */
    @Test
    void officialWithNoAssignedBarangay_readsNoBarangayChannel() {
        User unassigned = user("BARANGAY");
        unassigned.setBarangay(null);

        assertFalse(LiveUpdateChannels.permits(unassigned, LiveUpdateChannels.forBarangay(OWN_BARANGAY)));
        assertFalse(LiveUpdateChannels.permits(unassigned, LiveUpdateChannels.forBarangay(OTHER_BARANGAY)));
    }

    @Test
    void malformedBarangayDestinations_areRefused() {
        User official = barangayOfficial(OWN_BARANGAY);

        for (String malformed : new String[]{
                LiveUpdateChannels.BARANGAY_PREFIX,
                LiveUpdateChannels.BARANGAY_PREFIX + "abc",
                LiveUpdateChannels.BARANGAY_PREFIX + "7x",
                LiveUpdateChannels.BARANGAY_PREFIX + "7/extra",
                LiveUpdateChannels.BARANGAY_PREFIX + "-7",
                LiveUpdateChannels.BARANGAY_PREFIX + "7.0",
                LiveUpdateChannels.BARANGAY_PREFIX + "007",
                LiveUpdateChannels.BARANGAY_PREFIX + "99999999999999999999"}) {
            assertFalse(LiveUpdateChannels.permits(official, malformed), "should be refused: " + malformed);
        }
    }

    /** "007" is refused even for the matching id, so a subscriber cannot hold a channel nothing publishes to. */
    @Test
    void nonCanonicalBarangayId_isRefusedEvenWhenTheNumberMatches() {
        assertFalse(LiveUpdateChannels.permits(
                barangayOfficial(7L), LiveUpdateChannels.BARANGAY_PREFIX + "007"));
    }

    @Test
    void unknownDestinations_areRefusedForEveryRole() {
        for (User anyone : new User[]{admin(), engineer(), barangayOfficial(OWN_BARANGAY)}) {
            assertFalse(LiveUpdateChannels.permits(anyone, "/topic/updates"));
            assertFalse(LiveUpdateChannels.permits(anyone, "/topic/other"));
            assertFalse(LiveUpdateChannels.permits(anyone, "/app/reports"));
            assertFalse(LiveUpdateChannels.permits(anyone, "/topic/*"));
            assertFalse(LiveUpdateChannels.permits(anyone, ""));
        }
    }

    @Test
    void nullUserOrDestination_isRefused() {
        assertFalse(LiveUpdateChannels.permits(null, LiveUpdateChannels.ADMIN));
        assertFalse(LiveUpdateChannels.permits(admin(), null));
    }

    @Test
    void accountWithNoRole_isRefused() {
        User roleless = user(null);

        assertFalse(LiveUpdateChannels.permits(roleless, LiveUpdateChannels.ADMIN));
        assertFalse(LiveUpdateChannels.permits(roleless, LiveUpdateChannels.forBarangay(OWN_BARANGAY)));
    }

    // ----------------------------------------------------------------------- helpers

    private User user(String role) {
        User user = new User();
        user.setUsername("test-user");
        user.setStatus("Active");
        user.setRole(role);
        return user;
    }

    private User admin() {
        return user("ADMIN");
    }

    private User engineer() {
        return user("ENGINEER");
    }

    private User barangayOfficial(long barangayId) {
        User official = user("BARANGAY");
        Barangay barangay = new Barangay();
        barangay.setId(barangayId);
        official.setBarangay(barangay);
        return official;
    }
}
