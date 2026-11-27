package com.ledgercore.api.controller;

import com.ledgercore.account.Account;
import com.ledgercore.account.AccountQueryService;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountService;
import com.ledgercore.api.IdempotencyKeyValidator;
import com.ledgercore.api.IdempotencyScope;
import com.ledgercore.api.dto.AccountListResponse;
import com.ledgercore.api.dto.AccountResponse;
import com.ledgercore.api.dto.CreateAccountRequest;
import com.ledgercore.api.dto.DepositRequest;
import com.ledgercore.api.dto.StatementResponse;
import com.ledgercore.api.dto.TransferResponse;
import com.ledgercore.api.filter.RequestIdFilter;
import com.ledgercore.security.AccountOwnershipPolicy;
import com.ledgercore.security.AuthenticatedUser;
import com.ledgercore.transfer.AccountNotFoundException;
import com.ledgercore.transfer.TransferQueryService;
import com.ledgercore.transfer.TransferResult;
import com.ledgercore.transfer.TransferStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * REST controller for customer account management, deposits, and statements.
 */
@RestController
@Validated
@RequestMapping(path = "/api/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
public class AccountController {

    private final AccountService accountService;
    private final AccountQueryService accountQueryService;
    private final TransferQueryService transferQueryService;
    private final AccountRepository accountRepository;
    private final AccountOwnershipPolicy ownershipPolicy;
    private final boolean selfServiceDepositsEnabled;

    public AccountController(AccountService accountService,
                             AccountQueryService accountQueryService,
                             TransferQueryService transferQueryService,
                             AccountRepository accountRepository,
                             AccountOwnershipPolicy ownershipPolicy,
                             @Value("${ledgercore.demo.self-service-deposits:false}") boolean selfServiceDepositsEnabled) {
        this.accountService = accountService;
        this.accountQueryService = accountQueryService;
        this.transferQueryService = transferQueryService;
        this.accountRepository = accountRepository;
        this.ownershipPolicy = ownershipPolicy;
        this.selfServiceDepositsEnabled = selfServiceDepositsEnabled;
    }

    /**
     * 1. Opens a new customer account.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountResponse> openAccount(
            @Valid @RequestBody CreateAccountRequest body,
            Authentication authentication) {
        AuthenticatedUser caller = AuthenticatedUser.from(authentication);
        if (body.initialDepositMinor() != null && body.initialDepositMinor() > 0) {
            if (!caller.isAdmin() && !selfServiceDepositsEnabled) {
                throw new AccessDeniedException("Initial deposit requires ADMIN role unless self-service deposits are enabled");
            }
        }

        Account account = accountService.openAccount(
                caller.userId().toString(),
                body.currency(),
                body.initialDepositMinor(),
                caller.userId()
        );

        AccountResponse response = toResponse(account);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.getId()))
                .body(response);
    }

    /**
     * 2. Retrieves paginated accounts owned by the caller (Slice-based paging).
     */
    @GetMapping
    public ResponseEntity<AccountListResponse> listAccounts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        AuthenticatedUser caller = AuthenticatedUser.from(authentication);
        AccountListResponse response = accountQueryService.getCallerAccounts(caller.userId(), page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * 3. Retrieves account details. Conceals SYSTEM and non-owned accounts behind 404.
     */
    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID id, Authentication authentication) {
        AuthenticatedUser caller = AuthenticatedUser.from(authentication);
        AccountResponse response = accountQueryService.getAccount(id, caller);
        return ResponseEntity.ok(response);
    }

    /**
     * 4. Retrieves paginated ledger statements (newest first).
     */
    @GetMapping("/{id}/entries")
    public ResponseEntity<StatementResponse> getStatement(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        AuthenticatedUser caller = AuthenticatedUser.from(authentication);
        StatementResponse statement = accountQueryService.getStatement(id, page, size, caller);
        return ResponseEntity.ok(statement);
    }

    /**
     * 5. Deposits funds into a customer account via system funding transfer.
     */
    @PostMapping(value = "/{id}/deposits", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> deposit(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DepositRequest body,
            HttpServletRequest request,
            Authentication authentication) {

        AuthenticatedUser caller = AuthenticatedUser.from(authentication);
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        ownershipPolicy.verifyDepositAccess(account, caller, selfServiceDepositsEnabled);

        IdempotencyKeyValidator.validate(idempotencyKey);
        String internalKey = IdempotencyScope.scope(caller.userId(), idempotencyKey);

        TransferResult result = accountService.deposit(id, body.amountMinor(), internalKey);
        return handleTransferResult(result, request);
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

    private AccountResponse toResponse(Account a) {
        return new AccountResponse(
                a.getId(),
                a.getAccountNumber(),
                a.getOwnerRef(),
                a.getCurrency(),
                a.getStatus(),
                a.getBalanceMinor(),
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }
}
