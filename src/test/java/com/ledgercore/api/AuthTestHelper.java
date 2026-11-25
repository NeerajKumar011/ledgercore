package com.ledgercore.api;

import com.ledgercore.security.JwtService;
import com.ledgercore.user.Role;
import com.ledgercore.user.User;
import com.ledgercore.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Test helper for integration tests to provision users and generate valid JWT authorization tokens.
 */
@Component
public class AuthTestHelper {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthTestHelper(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public record TestUser(User user, String rawPassword, String token) {
        public UUID id() {
            return user.getId();
        }

        public String email() {
            return user.getEmail();
        }

        public String authHeader() {
            return "Bearer " + token;
        }
    }

    public TestUser createCustomer(String emailPrefix) {
        String email = emailPrefix.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        String rawPassword = "password123456";
        User user = User.createCustomer(email, passwordEncoder.encode(rawPassword));
        User saved = userRepository.saveAndFlush(user);
        String token = jwtService.generateToken(saved.getId(), Role.CUSTOMER.name());
        return new TestUser(saved, rawPassword, token);
    }

    public TestUser createAdmin(String emailPrefix) {
        String email = emailPrefix.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        String rawPassword = "adminpassword1234";
        User user = User.createAdmin(email, passwordEncoder.encode(rawPassword));
        User saved = userRepository.saveAndFlush(user);
        String token = jwtService.generateToken(saved.getId(), Role.ADMIN.name());
        return new TestUser(saved, rawPassword, token);
    }

    public String generateToken(UUID userId, Role role) {
        return jwtService.generateToken(userId, role.name());
    }
}
