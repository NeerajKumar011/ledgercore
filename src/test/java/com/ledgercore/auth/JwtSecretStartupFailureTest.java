package com.ledgercore.auth;

import com.ledgercore.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSecretStartupFailureTest {

    @Test
    @DisplayName("Startup fail fast: missing or blank secret throws IllegalStateException")
    void startup_failsOnMissingSecret() {
        assertThatThrownBy(() -> new JwtService(null, Duration.ofMinutes(15), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET must be configured and at least 32 bytes");

        assertThatThrownBy(() -> new JwtService("   ", Duration.ofMinutes(15), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET must be configured and at least 32 bytes");
    }

    @Test
    @DisplayName("Startup fail fast: secret < 32 bytes throws IllegalStateException without leaking secret")
    void startup_failsOnShortSecret_withoutLeakingSecret() {
        String shortSecret = "super-secret-short"; // 18 bytes
        assertThatThrownBy(() -> new JwtService(shortSecret, Duration.ofMinutes(15), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET must be configured and at least 32 bytes")
                .hasMessageNotContaining(shortSecret);
    }
}
