package com.ledgercore.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Atomically marks an unused, non-revoked, non-expired refresh token as used.
     * Returns 1 if successful, 0 if token is already used, revoked, expired, or non-existent.
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.usedAt = :now WHERE r.tokenHash = :tokenHash AND r.usedAt IS NULL AND r.revokedAt IS NULL AND r.expiresAt > :now")
    int markUsedIfValid(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.replacedBy = :replacedBy WHERE r.id = :id")
    int setReplacedBy(@Param("id") UUID id, @Param("replacedBy") UUID replacedBy);

    /**
     * Revokes all tokens in the given family (theft detection or logout).
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now WHERE r.familyId = :familyId AND r.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /**
     * Revokes all refresh tokens belonging to a user (logout-all or account disable).
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now WHERE r.userId = :userId AND r.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
