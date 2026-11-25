package com.ledgercore.auth;

import com.ledgercore.security.JwtService;
import com.ledgercore.security.PasswordPolicy;
import com.ledgercore.user.Role;
import com.ledgercore.user.User;
import com.ledgercore.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * Service managing user registration, authentication, and token generation.
 *
 * <p><b>Timing Attack Defense:</b>
 * When a user attempts to log in with an unknown email address, the service still executes
 * a full BCrypt comparison against a precomputed dummy hash. This ensures that the response
 * latency for unknown emails is indistinguishable from known emails with invalid passwords,
 * preventing email enumeration through timing analysis.
 */
@Service
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final String dummyHash;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        // Precomputed dummy hash for timing-attack mitigation on non-existent users
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-defense");
    }

    public RegisterResponse register(RegisterRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        PasswordPolicy.validate(request.password());

        if (userRepository.findByEmailIgnoreCase(normalizedEmail).isPresent()) {
            throw new EmailAlreadyExistsException("Email already registered: " + normalizedEmail);
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = User.createCustomer(normalizedEmail, passwordHash);

        try {
            User saved = userRepository.saveAndFlush(user);
            return new RegisterResponse(saved.getId(), saved.getEmail(), saved.getRole().name());
        } catch (DataIntegrityViolationException ex) {
            // Handle unique index concurrent registration race
            throw new EmailAlreadyExistsException("Email already registered: " + normalizedEmail);
        }
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(normalizedEmail);

        if (userOpt.isPresent()) {
            User user = userOpt.get();
            boolean matches = passwordEncoder.matches(request.password(), user.getPasswordHash());
            if (!matches || !user.isEnabled()) {
                throw new BadCredentialsException("Invalid email or password");
            }
            String token = jwtService.generateToken(user.getId(), user.getRole().name());
            return AuthResponse.of(token, jwtService.getTokenExpiration().toSeconds());
        } else {
            // Mitigate timing leakage by running BCrypt against dummy hash
            passwordEncoder.matches(request.password(), dummyHash);
            throw new BadCredentialsException("Invalid email or password");
        }
    }
}
