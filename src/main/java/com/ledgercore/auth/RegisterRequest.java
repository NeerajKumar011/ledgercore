package com.ledgercore.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "email must not be blank")
        @Email(message = "Invalid email format")
        @Size(max = 254, message = "email must not exceed 254 characters")
        String email,

        @NotBlank(message = "password must not be blank")
        @Size(max = 256, message = "password must not exceed 256 characters")
        String password
) {}
