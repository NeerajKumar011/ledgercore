package com.ledgercore.auth;

import com.ledgercore.security.PasswordPolicy;
import com.ledgercore.user.Role;
import com.ledgercore.user.User;
import com.ledgercore.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Bootstraps the initial ADMIN user on application startup if credentials are provided in the environment.
 */
@Component
public class AdminBootstrapService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public AdminBootstrapService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${ADMIN_EMAIL:${ledgercore.admin.email:}}") String adminEmail,
            @Value("${ADMIN_PASSWORD:${ledgercore.admin.password:}}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            log.info("No admin credentials configured; skipping admin bootstrap");
            return;
        }

        if (userRepository.existsByRole(Role.ADMIN)) {
            log.info("Admin user already exists; skipping bootstrap");
            return;
        }

        String normalizedEmail = adminEmail.trim().toLowerCase(Locale.ROOT);
        PasswordPolicy.validate(adminPassword);

        User admin = User.createAdmin(normalizedEmail, passwordEncoder.encode(adminPassword));
        userRepository.save(admin);
        log.info("Admin user bootstrapped for email: {}", normalizedEmail);
    }
}
