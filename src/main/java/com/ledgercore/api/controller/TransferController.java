package com.ledgercore.api.controller;

import com.ledgercore.api.IdempotencyKeyValidator;
import com.ledgercore.api.dto.TransferRequest;
import com.ledgercore.api.dto.TransferResponse;
import com.ledgercore.api.filter.RequestIdFilter;
import com.ledgercore.security.AccountOwnershipPolicy;
import com.ledgercore.security.AuthenticatedUser;
import com.ledgercore.transfer.TransferCommand;
import com.ledgercore.transfer.TransferQueryService;
import com.ledgercore.transfer.TransferResult;
import com.ledgercore.transfer.TransferService;
import com.ledgercore.transfer.TransferStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * REST controller for customer-to-customer fund transfers.
 */
@RestController
@RequestMapping(path = "/api/v1/transfers", produces = MediaType.APPLICATION_JSON_VALUE)
public class TransferController {

    private final TransferService transferService;
    private final TransferQueryService transferQueryService;
    private final AccountOwnershipPolicy ownershipPolicy;

    public TransferController(TransferService transferService,
                              TransferQueryService transferQueryService,
                              AccountOwnershipPolicy ownershipPolicy) {
        this.transferService = transferService;
        this.transferQueryService = transferQueryService;
        this.ownershipPolicy = ownershipPolicy;
    }

    /**
     * 5. Executes a fund transfer between two customer accounts.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> transfer(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest body,
            HttpServletRequest request,
            Authentication authentication) {

        AuthenticatedUser caller = AuthenticatedUser.from(authentication);
        ownershipPolicy.verifyTransferSourceAccess(body.fromAccountId(), caller);
        ownershipPolicy.verifyTransferDestination(body.toAccountId());

        IdempotencyKeyValidator.validate(idempotencyKey);

        TransferCommand command = new TransferCommand(
                idempotencyKey,
                body.fromAccountId(),
                body.toAccountId(),
                body.amountMinor(),
                body.currency()
        );

        TransferResult result = transferService.execute(command);
        return handleTransferResult(result, request);
    }

    /**
     * 6. Retrieves transfer details by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable UUID id, Authentication authentication) {
        AuthenticatedUser caller = AuthenticatedUser.from(authentication);
        TransferResponse response = transferQueryService.getTransfer(id, caller);
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<?> handleTransferResult(TransferResult result, HttpServletRequest request) {
        if (result.status() == TransferStatus.FAILED) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.UNPROCESSABLE_CONTENT, result.failureReason());
            problem.setTitle("Transfer Failed");
            problem.setInstance(URI.create(request.getRequestURI()));
            String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
            if (requestId != null) {
                problem.setProperty("requestId", requestId);
            }
            problem.setProperty("transferId", result.transferId());
            problem.setProperty("transferStatus", "FAILED");
            problem.setProperty("failureReason", result.failureReason());

            var builder = ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON);
            if (result.replayed()) {
                builder.header("Idempotent-Replayed", "true");
            }
            return builder.body(problem);
        }

        TransferResponse response = transferQueryService.getTransfer(result.transferId());
        var builder = ResponseEntity.created(URI.create("/api/v1/transfers/" + result.transferId()));
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(response);
    }
}
