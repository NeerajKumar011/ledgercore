package com.ledgercore.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

/**
 * Convenience representation of the currently authenticated caller extracted from JWT claims.
 */
public record AuthenticatedUser(UUID userId, String role) {

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role) || "ROLE_ADMIN".equalsIgnoreCase(role);
    }

    public static AuthenticatedUser from(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            UUID id = UUID.fromString(jwt.getSubject());
            String role = jwt.getClaimAsString("role");
            return new AuthenticatedUser(id, role);
        }
        throw new IllegalStateException("Authentication is not a JwtAuthenticationToken: " + auth);
    }
}
