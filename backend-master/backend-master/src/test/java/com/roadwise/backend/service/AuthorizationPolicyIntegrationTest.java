package com.roadwise.backend.service;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.AuthzRepository;
import com.roadwise.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The policy lookup against the real database, on the test profile.
 *
 * <p>Unit tests above mock {@link AuthzRepository}, so they would all still pass if the SQL
 * or the test fixture stopped working -- and a broken fixture shows up as a 500 from every
 * endpoint annotated with {@code @authz}, which is precisely how this class came to exist.
 * These two assertions fail loudly if the reference tables or their seed data go missing.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthorizationPolicyIntegrationTest {

    @Autowired
    private AuthzRepository authzRepository;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    @Test
    void anAdministrativeRoleCarriesItsPermissionsAndRank() {
        User admin = userRepository.save(user("policy.admin", "ADMIN"));
        User official = userRepository.save(user("policy.official", "BARANGAY"));

        List<String> adminPermissions = authzRepository.permissionsFor(admin.getId());
        assertTrue(adminPermissions.contains("settings:maintenance"), "admin: " + adminPermissions);
        assertTrue(adminPermissions.contains("user:manage"), "admin: " + adminPermissions);
        assertEquals(Integer.valueOf(50), authzRepository.rankOf(admin.getId()));

        List<String> officialPermissions = authzRepository.permissionsFor(official.getId());
        assertFalse(officialPermissions.contains("settings:maintenance"), "official: " + officialPermissions);
        assertEquals(Integer.valueOf(10), authzRepository.rankOf(official.getId()));
    }

    /** A role nobody aliased holds nothing and cannot be ordered against anybody. */
    @Test
    void anUnknownRoleResolvesToNothing() {
        User stranger = userRepository.save(user("policy.stranger", "Volunteer"));

        assertTrue(authzRepository.permissionsFor(stranger.getId()).isEmpty());
        assertNull(authzRepository.rankOf(stranger.getId()));
    }

    /** Role text is compared case-insensitively, which is what the alias table exists for. */
    @Test
    void roleSpellingDoesNotMatter() {
        User admin = userRepository.save(user("policy.mixedcase", "CPDO Admin"));

        assertTrue(authzRepository.permissionsFor(admin.getId()).contains("user:approve"));
        assertEquals(Integer.valueOf(50), authzRepository.rankOf(admin.getId()));
    }

    private static User user(String username, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("Password123!");
        user.setEmail(username + "@example.com");
        user.setRole(role);
        user.setStatus("Active");
        return user;
    }
}
