package com.ledgercore.security;

import com.ledgercore.user.UserRepository;
import com.ledgercore.user.UserSecurityState;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Validates user security state on every authenticated request to achieve instant revocation.
 * Loads (enabled, role, token_version) by user primary key:
 * - If user does not exist, is disabled, or token_version does not match: rejects with 401.
 * - Overrides the token's role claim with the fresh role from the database.
 */
@Component
public class UserSecurityStateJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository userRepository;

    public UserSecurityStateJwtAuthenticationConverter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String sub = jwt.getSubject();
        UUID userId;
        try {
            userId = UUID.fromString(sub);
        } catch (Exception e) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Invalid subject", null));
        }

        Object verClaim = jwt.getClaim(JwtService.CLAIM_TOKEN_VERSION);
        if (verClaim == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Missing token version", null));
        }

        int tokenVersion;
        if (verClaim instanceof Number n) {
            tokenVersion = n.intValue();
        } else {
            try {
                tokenVersion = Integer.parseInt(verClaim.toString());
            } catch (NumberFormatException e) {
                throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Invalid token version", null));
            }
        }

        UserSecurityState state = userRepository.findSecurityStateById(userId)
                .orElseThrow(() -> new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "User not found", null)));

        if (!state.enabled()) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "User account is disabled", null));
        }

        if (state.tokenVersion() != tokenVersion) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Token has been revoked", null));
        }

        // DATABASE role overrides token's claim
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + state.role().name()));
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
