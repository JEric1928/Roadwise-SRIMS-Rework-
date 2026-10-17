package com.roadwise.backend.repository;

import com.roadwise.backend.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // Looks up a user by their username for logging in
    Optional<User> findByUsername(String username);

    List<User> findByBarangayId(Long barangayId);

    // 🚀 NEW: Finds a massive list of users based on their role!
    List<User> findByRole(String role);
    Optional<User> findByEmail(String email);

    Optional<User> findFirstByRoleInIgnoreCase(List<String> roles);

    /**
     * Case-insensitive lookup by role, used to reach every administrator (for break-glass
     * alerts) without depending on how the role happened to be spelled in the row.
     */
    @Query("select u from User u where lower(u.role) in :roles")
    List<User> findByRoles(@Param("roles") Collection<String> roles);
}