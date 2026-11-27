package com.ledgercore.auth;

import com.ledgercore.api.error.InvalidRequestException;
import com.ledgercore.security.AuthenticatedUser;
import com.ledgercore.security.JwtService;
import com.ledgercore.security.PasswordPolicy;
import com.ledgercore.user.User;
import com.ledgercore.user.UserNotFoundException;
import com.ledgercore.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing user registration, authentication, refresh token rotation,
 * lockout policies, and instant session revocation.
 *
 * <p><b>Transaction & Connection Pool Optimization:</b>
 * BCrypt password hashing and matching are executed completely OUTSIDE of database
 * transactions and connection pool leases to prevent pool starvation under high load.
 *
 * <p><b>Lockout & Timing Parity:</b>
 * Account lockouts execute a dummy BCrypt comparison to maintain strict timing parity,
 * never revealing to an attacker that an account is locked.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final Duration refreshTokenTtl;
    private final String dummyHash;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ledgercore.audit.AuditService auditService;

    @org.springframework.beans.factory.annotation.Autowired
    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TransactionTemplate transactionTemplate,
                       Clock clock,
                       @Value("${ledgercore.security.refresh-token-ttl:7d}") Duration refreshTokenTtl) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.refreshTokenRepository = Objects.requireNonNull(refreshTokenRepository, "refreshTokenRepository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.jwtService = Objects.requireNonNull(jwtService, "jwtService must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.refreshTokenTtl = Objects.requireNonNull(refreshTokenTtl, "refreshTokenTtl must not be null");
        // Precomputed dummy hash for timing-attack mitigation on non-existent or locked users
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-defense");
    }

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TransactionTemplate transactionTemplate) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.refreshTokenRepository = Objects.requireNonNull(refreshTokenRepository, "refreshTokenRepository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.jwtService = Objects.requireNonNull(jwtService, "jwtService must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        this.clock = Clock.systemUTC();
        this.refreshTokenTtl = Duration.ofDays(7);
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-defense");
    }

    public RegisterResponse register(RegisterRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        PasswordPolicy.validate(request.password());

        // 1. Heavy BCrypt hashing executed OUTSIDE of any database transaction
        String passwordHash = passwordEncoder.encode(request.password());

        // 2. Short, scoped database transaction to check and persist user
        User saved;
        try {
            saved = transactionTemplate.execute(status -> {
                if (userRepository.findByEmailIgnoreCase(normalizedEmail).isPresent()) {
                    throw new EmailAlreadyExistsException("Email already registered: " + normalizedEmail);
                }
                User user = User.createCustomer(normalizedEmail, passwordHash);
                try {
                    return userRepository.saveAndFlush(user);
                } catch (DataIntegrityViolationException ex) {
                    throw new EmailAlreadyExistsException("Email already registered: " + normalizedEmail);
                }
            });
        } catch (EmailAlreadyExistsException e) {
            if (auditService != null) {
                auditService.recordAuth(
                        "REGISTER", "FAILURE", null, null,
                        "USER", com.ledgercore.audit.AuditCanonicalizer.hashIdentifier16(normalizedEmail),
                        "{\"reason\":\"DUPLICATE_EMAIL\"}"
                );
            }
            throw e;
        }

        if (auditService != null) {
            auditService.recordAuth(
                    "REGISTER", "SUCCESS", saved.getId(), saved.getRole().name(),
                    "USER", saved.getId().toString(), null
            );
        }

        return new RegisterResponse(saved.getId(), saved.getEmail(), saved.getRole().name());
    }

    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        Instant now = clock.instant();

        // 1. Short repository lookup (no surrounding transaction holding connection)
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(normalizedEmail);

        if (userOpt.isPresent()) {
            User user = userOpt.get();

            // Check if locked: run dummy BCrypt for timing parity and reject without revealing lock state
            if (user.isLocked(now)) {
                transactionTemplate.executeWithoutResult(status -> {
                    userRepository.recordLoginFailure(user.getId(), now);
                });
                passwordEncoder.matches(request.password(), dummyHash);
                if (auditService != null) {
                    auditService.recordAuth(
                            "LOGIN_FAILURE", "FAILURE", user.getId(), user.getRole().name(),
                            "USER", user.getId().toString(), "{\"reason\":\"LOCKED\"}"
                    );
                }
                throw new BadCredentialsException("Invalid email or password");
            }

            // 2. Heavy BCrypt matching executed OUTSIDE of any database transaction
            boolean matches = passwordEncoder.matches(request.password(), user.getPasswordHash());

            if (!matches) {
                // Record failure atomically in database
                transactionTemplate.executeWithoutResult(status -> {
                    userRepository.recordLoginFailure(user.getId(), now);
                });
                if (auditService != null) {
                    auditService.recordAuth(
                            "LOGIN_FAILURE", "FAILURE", user.getId(), user.getRole().name(),
                            "USER", user.getId().toString(), "{\"reason\":\"BAD_CREDENTIALS\"}"
                    );
                }
                throw new BadCredentialsException("Invalid email or password");
            }

            if (!user.isEnabled()) {
                if (auditService != null) {
                    auditService.recordAuth(
                            "LOGIN_FAILURE", "FAILURE", user.getId(), user.getRole().name(),
                            "USER", user.getId().toString(), "{\"reason\":\"DISABLED\"}"
                    );
                }
                throw new BadCredentialsException("Invalid email or password");
            }

            // 3. Reset lockout counters and issue access + refresh tokens
            AuthResponse response = transactionTemplate.execute(status -> {
                userRepository.resetLoginLockout(user.getId(), now);

                String accessToken = jwtService.generateToken(user.getId(), user.getRole().name(), user.getTokenVersion());

                String rawRefreshToken = RefreshTokenGenerator.generateRawToken();
                String tokenHash = RefreshTokenGenerator.hashToken(rawRefreshToken);
                UUID familyId = UUID.randomUUID();
                Instant expiresAt = now.plus(refreshTokenTtl);

                RefreshToken refreshToken = new RefreshToken(
                        UUID.randomUUID(),
                        user.getId(),
                        familyId,
                        tokenHash,
                        now,
                        expiresAt
                );
                refreshTokenRepository.save(refreshToken);

                return AuthResponse.of(accessToken, jwtService.getTokenExpiration().toSeconds(), rawRefreshToken, refreshTokenTtl.toSeconds());
            });

            if (auditService != null) {
                auditService.recordAuth(
                        "LOGIN_SUCCESS", "SUCCESS", user.getId(), user.getRole().name(),
                        "USER", user.getId().toString(), null
                );
            }

            return response;
        } else {
            // Mitigate timing leakage by running BCrypt against dummy hash outside transaction
            passwordEncoder.matches(request.password(), dummyHash);
            if (auditService != null) {
                auditService.recordAuth(
                        "LOGIN_FAILURE", "FAILURE", null, null,
                        "USER", com.ledgercore.audit.AuditCanonicalizer.hashIdentifier16(normalizedEmail),
                        "{\"reason\":\"BAD_CREDENTIALS\"}"
                );
            }
            throw new BadCredentialsException("Invalid email or password");
        }
    }

    public AuthResponse refresh(RefreshTokenRequest request) {
        Instant now = clock.instant();
        String tokenHash = RefreshTokenGenerator.hashToken(request.refreshToken());

        // 1. Atomic update in its own transaction: marks used if valid
        int updated = transactionTemplate.execute(status ->
                refreshTokenRepository.markUsedIfValid(tokenHash, now)
        );

        if (updated == 1) {
            AuthResponse response = transactionTemplate.execute(status -> {
                RefreshToken oldToken = refreshTokenRepository.findByTokenHash(tokenHash)
                        .orElseThrow(() -> new BadCredentialsException("Invalid or revoked refresh token"));

                User user = userRepository.findById(oldToken.getUserId())
                        .orElseThrow(() -> new BadCredentialsException("Invalid or revoked refresh token"));

                if (!user.isEnabled()) {
                    throw new BadCredentialsException("Invalid or revoked refresh token");
                }

                // Issue new tokens in same family
                String newRawRefreshToken = RefreshTokenGenerator.generateRawToken();
                String newHash = RefreshTokenGenerator.hashToken(newRawRefreshToken);
                UUID newId = UUID.randomUUID();
                Instant expiresAt = now.plus(refreshTokenTtl);

                RefreshToken newToken = new RefreshToken(
                        newId,
                        user.getId(),
                        oldToken.getFamilyId(),
                        newHash,
                        now,
                        expiresAt
                );
                refreshTokenRepository.save(newToken);
                refreshTokenRepository.setReplacedBy(oldToken.getId(), newId);

                // Use CURRENT role and token_version from database
                String accessToken = jwtService.generateToken(user.getId(), user.getRole().name(), user.getTokenVersion());

                return AuthResponse.of(accessToken, jwtService.getTokenExpiration().toSeconds(), newRawRefreshToken, refreshTokenTtl.toSeconds());
            });

            if (auditService != null) {
                RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash).orElse(null);
                UUID familyId = (token != null) ? token.getFamilyId() : null;
                auditService.recordAuth(
                        "REFRESH_SUCCESS", "SUCCESS",
                        token != null ? token.getUserId() : null, null,
                        "REFRESH_TOKEN", familyId != null ? familyId.toString() : null, null
                );
            }

            return response;
        } else {
            // If token exists and was already used or revoked: reuse detected -> revoke family
            // Must be committed in a separate transaction so that throwing BadCredentialsException does not roll it back
            transactionTemplate.executeWithoutResult(status -> {
                Optional<RefreshToken> existing = refreshTokenRepository.findByTokenHash(tokenHash);
                if (existing.isPresent()) {
                    RefreshToken token = existing.get();
                    if (token.getUsedAt() != null || token.getRevokedAt() != null) {
                        refreshTokenRepository.revokeFamily(token.getFamilyId(), now);
                        if (auditService != null) {
                            auditService.recordAuth(
                                    "REFRESH_REUSE_DETECTED", "FAILURE",
                                    token.getUserId(), null,
                                    "REFRESH_TOKEN", token.getFamilyId().toString(),
                                    "{\"reason\":\"REUSE_DETECTED\"}"
                            );
                        }
                    }
                }
            });
            throw new BadCredentialsException("Invalid or revoked refresh token");
        }
    }

    public void logout(RefreshTokenRequest request) {
        if (request == null || request.refreshToken() == null || request.refreshToken().isBlank()) {
            return;
        }
        Instant now = clock.instant();
        String tokenHash = RefreshTokenGenerator.hashToken(request.refreshToken());

        transactionTemplate.executeWithoutResult(status -> {
            refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
                refreshTokenRepository.revokeFamily(token.getFamilyId(), now);
                if (auditService != null) {
                    auditService.recordAuth(
                            "LOGOUT", "SUCCESS",
                            token.getUserId(), null,
                            "REFRESH_TOKEN", token.getFamilyId().toString(), null
                    );
                }
            });
        });
    }

    public void logoutAll(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        Instant now = clock.instant();

        transactionTemplate.executeWithoutResult(status -> {
            refreshTokenRepository.revokeAllForUser(userId, now);
            userRepository.incrementTokenVersion(userId, now);
            if (auditService != null) {
                auditService.recordAuth(
                        "LOGOUT_ALL", "SUCCESS",
                        userId, null,
                        "USER", userId.toString(), null
                );
            }
        });
    }

    public void disableUser(UUID targetUserId, AuthenticatedUser caller) {
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        if (caller.userId().equals(targetUserId)) {
            throw new InvalidRequestException("Admin cannot disable their own account");
        }
        Instant now = clock.instant();

        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findById(targetUserId)
                    .orElseThrow(() -> new UserNotFoundException(targetUserId));
            userRepository.disableUser(targetUserId, now);
            refreshTokenRepository.revokeAllForUser(targetUserId, now);
            if (auditService != null) {
                auditService.recordAdmin(
                        "USER_DISABLED", "SUCCESS",
                        caller.userId(), caller.role(),
                        "USER", targetUserId.toString(), null
                );
            }
        });
    }

    public void enableUser(UUID targetUserId) {
        enableUser(targetUserId, null);
    }

    public void enableUser(UUID targetUserId, AuthenticatedUser caller) {
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Instant now = clock.instant();

        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findById(targetUserId)
                    .orElseThrow(() -> new UserNotFoundException(targetUserId));
            userRepository.enableUser(targetUserId, now);
            if (auditService != null) {
                auditService.recordAdmin(
                        "USER_ENABLED", "SUCCESS",
                        caller != null ? caller.userId() : null,
                        caller != null ? caller.role() : null,
                        "USER", targetUserId.toString(), null
                );
            }
        });
    }
}
