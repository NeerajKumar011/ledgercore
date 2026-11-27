package com.ledgercore.api.error;

/**
 * Thrown when an incoming client request contains invalid parameters that are meant
 * to be communicated back to the client in an RFC 9457 ProblemDetail response.
 */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
