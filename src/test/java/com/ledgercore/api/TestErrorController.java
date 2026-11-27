package com.ledgercore.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only REST controller to verify that unexpected server exceptions produce generic 500 ProblemDetails
 * without leaking internal class names, stack traces, or SQL error messages.
 */
@RestController
@RequestMapping("/api/v1/test-error")
public class TestErrorController {

    @GetMapping("/unexpected")
    public void throwUnexpected() {
        throw new RuntimeException(
                "Simulated failure: org.postgresql.util.PSQLException: ERROR: syntax error at or near 'SELECT * FROM accounts'");
    }

    @GetMapping("/illegal-argument")
    public void throwIllegalArgument() {
        throw new IllegalArgumentException("sensitive-internal-detail");
    }
}
