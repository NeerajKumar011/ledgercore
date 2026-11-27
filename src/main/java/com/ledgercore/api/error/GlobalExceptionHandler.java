package com.ledgercore.api.error;

import com.ledgercore.api.filter.RequestIdFilter;
import com.ledgercore.transfer.AccountNotFoundException;
import com.ledgercore.transfer.IdempotencyConflictException;
import com.ledgercore.transfer.InvalidTransferRequestException;
import com.ledgercore.transfer.TransferBusyException;
import com.ledgercore.transfer.TransferNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Global REST exception handler mapping exceptions to RFC 9457 ProblemDetail representations.
 * Never leaks internal implementation details, class names, or SQL fragments to clients.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ── 400 Bad Request ───────────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.BAD_REQUEST, "Validation Failed", "Invalid request payload", request);
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        problem.setProperty("errors", errors);
        return toResponse(problem);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.BAD_REQUEST, "Validation Failed", "Invalid request parameters", request);
        Map<String, String> errors = new LinkedHashMap<>();
        for (var result : ex.getParameterValidationResults()) {
            for (var error : result.getResolvableErrors()) {
                if (error instanceof FieldError fe) {
                    errors.put(fe.getField(), fe.getDefaultMessage());
                } else {
                    errors.put(result.getMethodParameter().getParameterName(), error.getDefaultMessage());
                }
            }
        }
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return toResponse(problem);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.BAD_REQUEST, "Missing Required Header", ex.getMessage(), request);
        return toResponse(problem);
    }

    @ExceptionHandler(InvalidTransferRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidTransferRequest(InvalidTransferRequestException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request);
        return toResponse(problem);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.BAD_REQUEST, "Type Mismatch",
                "Parameter '" + ex.getName() + "' is invalid", request);
        return toResponse(problem);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequest(InvalidRequestException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.BAD_REQUEST, "Invalid Request", ex.getMessage(), request);
        return toResponse(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.warn("Illegal argument encountered on {} [requestId={}]: {}",
                request.getRequestURI(), requestId, ex.getMessage(), ex);
        ProblemDetail problem = createProblemDetail(
                HttpStatus.BAD_REQUEST,
                "Invalid Request",
                "The request contains invalid parameters.",
                request);
        return toResponse(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.BAD_REQUEST, "Malformed JSON",
                "Request body is malformed or unreadable", request);
        return toResponse(problem);
    }

    @ExceptionHandler(com.ledgercore.auth.InvalidPasswordException.class)
    public ResponseEntity<ProblemDetail> handleInvalidPassword(com.ledgercore.auth.InvalidPasswordException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.BAD_REQUEST, "Invalid Password", ex.getMessage(), request);
        return toResponse(problem);
    }

    // ── 401 Unauthorized ──────────────────────────────────────────────────────

    @ExceptionHandler(org.springframework.security.authentication.BadCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleBadCredentials(org.springframework.security.authentication.BadCredentialsException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem);
    }

    // ── 403 Forbidden ─────────────────────────────────────────────────────────

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.FORBIDDEN, "Forbidden",
                "Access denied: insufficient permissions to access this resource", request);
        return toResponse(problem);
    }

    // ── 404 Not Found ─────────────────────────────────────────────────────────

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleAccountNotFound(AccountNotFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.NOT_FOUND, "Account Not Found", ex.getMessage(), request);
        return toResponse(problem);
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleTransferNotFound(TransferNotFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.NOT_FOUND, "Transfer Not Found", ex.getMessage(), request);
        return toResponse(problem);
    }

    @ExceptionHandler(com.ledgercore.user.UserNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleUserNotFound(com.ledgercore.user.UserNotFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.NOT_FOUND, "User Not Found", ex.getMessage(), request);
        return toResponse(problem);
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ProblemDetail> handlePayloadTooLarge(PayloadTooLargeException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.PAYLOAD_TOO_LARGE, "Payload Too Large", ex.getMessage(), request);
        return toResponse(problem);
    }

    // ── 409 Conflict ──────────────────────────────────────────────────────────

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.CONFLICT, "Idempotency Key Conflict", ex.getMessage(), request);
        return toResponse(problem);
    }

    @ExceptionHandler(com.ledgercore.auth.EmailAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateEmail(com.ledgercore.auth.EmailAlreadyExistsException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request);
        return toResponse(problem);
    }

    // ── 405 Method Not Allowed ────────────────────────────────────────────────

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", ex.getMessage(), request);
        return toResponse(problem);
    }

    // ── 415 Unsupported Media Type ───────────────────────────────────────────

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type", ex.getMessage(), request);
        return toResponse(problem);
    }

    // ── 503 Service Unavailable ───────────────────────────────────────────────

    @ExceptionHandler(TransferBusyException.class)
    public ResponseEntity<ProblemDetail> handleTransferBusy(TransferBusyException ex, HttpServletRequest request) {
        ProblemDetail problem = createProblemDetail(HttpStatus.SERVICE_UNAVAILABLE, "Service Busy",
                "Lock timeout exceeded; please retry", request);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    // ── 500 Internal Server Error (Catch-all) ─────────────────────────────────

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Throwable ex, HttpServletRequest request) {
        String requestId = getRequestId(request);
        log.error("Unhandled exception processing request [requestId={}]", requestId, ex);

        ProblemDetail problem = createProblemDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred. Reference ID: " + requestId, request);
        return toResponse(problem);
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private ProblemDetail createProblemDetail(HttpStatus status, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("requestId", getRequestId(request));
        return problem;
    }

    private String getRequestId(HttpServletRequest request) {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return requestId;
    }

    private ResponseEntity<ProblemDetail> toResponse(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
