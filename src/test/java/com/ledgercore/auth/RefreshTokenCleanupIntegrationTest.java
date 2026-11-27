package com.ledgercore.auth;

import com.ledgercore.user.User;
import com.ledgercore.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgercore.TestcontainersConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@org.springframework.transaction.annotation.Transactional
public class RefreshTokenCleanupIntegrationTest {

    @Autowired
    private RefreshTokenCleanupService cleanupService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    @DisplayName("Cleanup: purges expired, used or revoked tokens older than 30 days in batches")
    void purgeExpiredAndRevokedTokens() {
        User user = userRepository.saveAndFlush(User.createCustomer(
                "cleanup-test-" + UUID.randomUUID() + "@example.com", "hash"));

        Instant now = clock.instant();
        Instant fortyDaysAgo = now.minus(Duration.ofDays(40));
        Instant thirtyFiveDaysAgo = now.minus(Duration.ofDays(35));
        Instant fiveDaysAgo = now.minus(Duration.ofDays(5));

        // 1. Old expired token (>30d ago) -> should be purged
        RefreshToken oldExpired = new RefreshToken(
                UUID.randomUUID(), user.getId(), UUID.randomUUID(),
                "a".repeat(64), fortyDaysAgo, thirtyFiveDaysAgo
        );
        refreshTokenRepository.save(oldExpired);

        // 2. Old used token (>30d ago) -> should be purged
        RefreshToken oldUsed = new RefreshToken(
                UUID.randomUUID(), user.getId(), UUID.randomUUID(),
                "b".repeat(64), fortyDaysAgo, now.plus(Duration.ofDays(10))
        );
        refreshTokenRepository.save(oldUsed);
        refreshTokenRepository.markUsedIfValid("b".repeat(64), thirtyFiveDaysAgo);

        // 3. Old revoked token (>30d ago) -> should be purged
        RefreshToken oldRevoked = new RefreshToken(
                UUID.randomUUID(), user.getId(), UUID.randomUUID(),
                "c".repeat(64), fortyDaysAgo, now.plus(Duration.ofDays(10))
        );
        refreshTokenRepository.save(oldRevoked);
        refreshTokenRepository.revokeFamily(oldRevoked.getFamilyId(), thirtyFiveDaysAgo);

        // 4. Recent active token -> MUST NOT be purged
        RefreshToken recentActive = new RefreshToken(
                UUID.randomUUID(), user.getId(), UUID.randomUUID(),
                "d".repeat(64), fiveDaysAgo, now.plus(Duration.ofDays(2))
        );
        refreshTokenRepository.save(recentActive);

        // 5. Recent expired token (expired 2 days ago, but created 5 days ago, not older than 30d) -> MUST NOT be purged
        RefreshToken recentExpired = new RefreshToken(
                UUID.randomUUID(), user.getId(), UUID.randomUUID(),
                "e".repeat(64), fiveDaysAgo, now.minus(Duration.ofDays(2))
        );
        refreshTokenRepository.save(recentExpired);

        // Run purge with 30-day retention
        int purged = cleanupService.purgeTokens(now, Duration.ofDays(30), 1000);
        assertThat(purged).isGreaterThanOrEqualTo(3);
        entityManager.clear();

        // Verify recent tokens still exist
        assertThat(refreshTokenRepository.findById(recentActive.getId())).isPresent();
        assertThat(refreshTokenRepository.findById(recentExpired.getId())).isPresent();

        // Verify old tokens are gone
        assertThat(refreshTokenRepository.findById(oldExpired.getId())).isEmpty();
        assertThat(refreshTokenRepository.findById(oldUsed.getId())).isEmpty();
        assertThat(refreshTokenRepository.findById(oldRevoked.getId())).isEmpty();
    }
}
