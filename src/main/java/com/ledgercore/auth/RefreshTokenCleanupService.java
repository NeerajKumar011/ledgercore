package com.ledgercore.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Scheduled cleanup service that purges expired, used, or revoked refresh tokens older than the retention period.
 */
@Service
public class RefreshTokenCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;
    private final Duration retentionPeriod;
    private final int batchSize;
    private final boolean enabled;

    public RefreshTokenCleanupService(
            RefreshTokenRepository refreshTokenRepository,
            Clock clock,
            @Value("${ledgercore.security.refresh-token-retention:30d}") Duration retentionPeriod,
            @Value("${ledgercore.security.refresh-token-cleanup.batch-size:1000}") int batchSize,
            @Value("${ledgercore.security.refresh-token-cleanup.enabled:true}") boolean enabled) {
        this.refreshTokenRepository = Objects.requireNonNull(refreshTokenRepository, "refreshTokenRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.retentionPeriod = Objects.requireNonNull(retentionPeriod, "retentionPeriod must not be null");
        this.batchSize = batchSize > 0 ? batchSize : 1000;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${ledgercore.security.refresh-token-cleanup.cron:0 0 * * * *}")
    @Transactional
    public int scheduledPurge() {
        if (!enabled) {
            return 0;
        }
        return purgeExpiredTokens(clock.instant());
    }

    @Transactional
    public int purgeExpiredTokens(Instant now) {
        return purgeTokens(now, this.retentionPeriod, this.batchSize);
    }

    @Transactional
    public int purgeTokens(Instant now, Duration retention, int maxBatch) {
        Instant cutoff = now.minus(retention);
        int deleted = refreshTokenRepository.purgeTokensOlderThan(cutoff, maxBatch);
        if (deleted > 0) {
            log.info("Purged {} expired/revoked refresh tokens older than {}", deleted, cutoff);
        }
        return deleted;
    }
}
