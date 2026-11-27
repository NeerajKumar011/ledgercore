package com.ledgercore.api.controller;

import com.ledgercore.auth.AuthService;
import com.ledgercore.security.AuthenticatedUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin controller for user lifecycle management (enable/disable accounts).
 */
@RestController
@RequestMapping(path = "/api/v1/admin/users", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminUserController {

    private final AuthService authService;

    public AdminUserController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<Void> disableUser(@PathVariable UUID id, Authentication authentication) {
        AuthenticatedUser caller = AuthenticatedUser.from(authentication);
        authService.disableUser(id, caller);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<Void> enableUser(@PathVariable UUID id, Authentication authentication) {
        AuthenticatedUser caller = AuthenticatedUser.from(authentication);
        authService.enableUser(id, caller);
        return ResponseEntity.noContent().build();
    }
}
