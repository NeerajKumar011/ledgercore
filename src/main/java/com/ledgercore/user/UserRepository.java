package com.ledgercore.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("SELECT u FROM User u WHERE lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    boolean existsByRole(Role role);

    @Query("SELECT new com.ledgercore.user.UserSecurityState(u.enabled, u.role, u.tokenVersion) FROM User u WHERE u.id = :userId")
    Optional<UserSecurityState> findSecurityStateById(@Param("userId") UUID userId);

    /**
     * Atomically increments failed_login_count and applies progressive lockout:
     * after 5 consecutive failures locks for 1 minute, doubling per failure up to 60 minutes.
     */
    @Modifying
    @Query(value = """
        UPDATE users
        SET failed_login_count = failed_login_count + 1,
            locked_until = CASE
                WHEN failed_login_count + 1 >= 5 THEN
                    CAST(:now AS TIMESTAMPTZ) + LEAST(INTERVAL '60 minutes', INTERVAL '1 minute' * POWER(2, LEAST(failed_login_count + 1 - 5, 6)))
                ELSE locked_until
            END,
            updated_at = CAST(:now AS TIMESTAMPTZ)
        WHERE id = :userId
        """, nativeQuery = true)
    int recordLoginFailure(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE User u SET u.failedLoginCount = 0, u.lockedUntil = NULL, u.updatedAt = :now WHERE u.id = :userId")
    int resetLoginLockout(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE User u SET u.tokenVersion = u.tokenVersion + 1, u.updatedAt = :now WHERE u.id = :userId")
    int incrementTokenVersion(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE User u SET u.enabled = false, u.tokenVersion = u.tokenVersion + 1, u.updatedAt = :now WHERE u.id = :userId")
    int disableUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE User u SET u.enabled = true, u.updatedAt = :now WHERE u.id = :userId")
    int enableUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
