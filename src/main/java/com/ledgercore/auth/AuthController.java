package com.ledgercore.auth;

import com.ledgercore.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * REST controller for user registration, authentication, token refresh, and logout.
 * All responses carry Cache-Control: no-store to protect sensitive auth tokens.
 */
@RestController
@Validated
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest body) {
        RegisterResponse response = authService.register(body);
        return ResponseEntity.created(URI.create("/api/v1/users/" + response.userId()))
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest body) {
        AuthResponse response = authService.login(body);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping(value = "/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest body) {
        AuthResponse response = authService.refresh(body);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping(value = "/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshTokenRequest body) {
        authService.logout(body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/logout-all")
    public ResponseEntity<Void> logoutAll(Authentication authentication) {
        AuthenticatedUser caller = AuthenticatedUser.from(authentication);
        authService.logoutAll(caller.userId());
        return ResponseEntity.noContent().build();
    }
}
