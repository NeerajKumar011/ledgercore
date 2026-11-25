package com.ledgercore.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * Service for issuing HMAC-SHA256 (HS256) JSON Web Tokens.
 *
 * <p><b>HS256 vs Asymmetric (RS256/ES256) Architecture:</b>
 * HS256 uses a symmetric secret shared between the entity issuing the token and the entity
 * validating it. In a single-service architecture like LedgerCore, the token issuer and validator
 * reside in the same application boundary. The secret is never distributed over network calls
 * or stored on external clients, making HS256 simple, secure, and performant.
 *
 * <p>When transitioning to a distributed microservices architecture where multiple independent services
 * validate tokens, sharing a symmetric secret across all services becomes an anti-pattern: any single
 * service compromise would allow forging tokens for the entire system. In that scenario, asymmetric
 * cryptography (such as RS256 or ES256) is required: the authentication service signs tokens with a
 * private key, while resource servers validate tokens using the corresponding public key (e.g. via JWKS).
 */
@Service
public class JwtService {

    public static final String ISSUER = "ledgercore";
    public static final String CLAIM_ROLE = "role";

    private final byte[] secretBytes;
    private final Duration tokenExpiration;
    private final Clock clock;

    public JwtService(
            @Value("${JWT_SECRET:${ledgercore.jwt.secret:}}") String secret,
            @Value("${ledgercore.jwt.expiration:15m}") Duration tokenExpiration,
            Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.tokenExpiration = Objects.requireNonNull(tokenExpiration, "tokenExpiration must not be null");

        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be configured and at least 32 bytes (256 bits) for HMAC-SHA256");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be configured and at least 32 bytes (256 bits) for HMAC-SHA256");
        }
        this.secretBytes = bytes;
    }

    public String generateToken(UUID userId, String role) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(role, "role must not be null");

        Instant now = clock.instant();
        Instant exp = now.plus(tokenExpiration);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .claim(CLAIM_ROLE, role)
                .jwtID(UUID.randomUUID().toString())
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
        try {
            JWSSigner signer = new MACSigner(secretBytes);
            signedJWT.sign(signer);
            return signedJWT.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign JWT token", e);
        }
    }

    public Duration getTokenExpiration() {
        return tokenExpiration;
    }

    public byte[] getSecretBytes() {
        return secretBytes.clone();
    }
}
