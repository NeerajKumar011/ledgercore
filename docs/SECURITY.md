# LedgerCore Security Architecture & Threat Model

This document outlines the security architecture, threat model, and protective controls implemented in LedgerCore Phase 4, Step A.

---

## 1. Authentication & Authorization Architecture

### 1.1 Stateless JWT Authentication
- **Mechanism**: OAuth2 Resource Server with JSON Web Tokens (JWT) using HMAC-SHA256 (`HS256`).
- **Signing Key**: Sourced from the `JWT_SECRET` environment variable. The application enforces a strict **fail-fast startup check requiring at least 32 bytes (256 bits)** of entropy. Startup failure messages **never print the secret value**.
- **Claims**:
  - `iss`: Hardcoded to `ledgercore` with strict validation.
  - `sub`: User ID (`UUID`).
  - `iat`: Issue timestamp (UTC).
  - `exp`: Expiration timestamp (default: 15 minutes / 900 seconds, configurable via `ledgercore.security.jwt.expiration-minutes`).
  - `jti`: Unique token identifier (`UUID`) preventing replay confusion.
  - `role`: Role of the user (`CUSTOMER` or `ADMIN`).
- **Transport**: Authorization must be transmitted exclusively via the HTTP standard `Authorization: Bearer <token>` header. Tokens in cookies or query parameters are explicitly ignored and rejected.
- **Cache-Control**: All authentication endpoints (`POST /api/v1/auth/register`, `POST /api/v1/auth/login`) emit `Cache-Control: no-store` to prevent intermediary caching of tokens.

### 1.2 Password Storage & Validation Policy
- **Hashing**: BCrypt with configurable work factor (`ledgercore.security.bcrypt-strength: 12` default, reduced to `4` in integration tests for execution speed).
- **BCrypt 72-Byte Truncation Boundary**: The standard BCrypt algorithm silently truncates input strings at 72 bytes. To prevent attackers or users from relying on characters beyond the 72-byte boundary (which would cause passwords with identical prefixes to hash identically), LedgerCore validates that passwords are:
  1. Minimum 12 characters.
  2. Maximum 72 UTF-8 encoded bytes. Passwords exceeding 72 UTF-8 bytes are rejected with `400 Bad Request`.
- **Timing Attack Mitigation**: On `POST /api/v1/auth/login`, if an email does not exist in the database, a dummy BCrypt comparison is executed against a pre-computed hash. This ensures execution latency is consistent between unknown emails and incorrect passwords, preventing user enumeration via timing analysis.

### 1.3 Role-Based Access Control (RBAC)
- **Roles**:
  - `ROLE_CUSTOMER`: Default role granted upon registration. Allowed to open accounts, view and manage owned accounts, perform transfers from owned accounts, view statements for owned accounts, and query transfers where they are sender or recipient.
  - `ROLE_ADMIN`: Allowed to view non-system accounts, initiate deposits, and execute administrative functions (e.g. `GET /api/v1/admin/reconciliation`).
- **Deny-by-Default Policy**: Spring Security's filter chain denies access to all endpoints by default, explicitly permitting only:
  - `POST /api/v1/auth/register`
  - `POST /api/v1/auth/login`
  - `GET /actuator/health`
- **CORS Disabled**: Cross-Origin Resource Sharing is disabled by default (`cors(AbstractHttpConfigurer::disable)`). Web browsers will not allow cross-origin requests.

### 1.4 Ownership Policy & Information Concealment
- **Horizontal Privilege Escalation Prevention**: Customers cannot view or operate on accounts they do not own.
- **Concealment Behind 404 Not Found**: If a customer requests an account, statement, or transfer belonging to another customer, the service returns `404 Not Found` (identical ProblemDetail to a non-existent UUID), preventing attackers from enumerating valid account IDs.
- **System Account Concealment**: System accounts (e.g. `SYSTEM_FUNDING`) are concealed behind `404 Not Found` for **both** Customers and Admins through API endpoints.
- **Self-Service Deposits Flag**: By default, customer self-deposits are forbidden (only Admins can deposit). A demo property `ledgercore.demo.self-service-deposits=true` can be enabled for test environments, which logs a prominent `WARN` at startup and restricts customer deposits strictly to their own accounts.

---

## 2. STRIDE Threat Model

| Threat | Threat Category | Implemented Countermeasure |
| :--- | :--- | :--- |
| **Spoofing** | Attacker impersonates a valid user | HS256 JWT signature verification with 256-bit secret. Unsigned or `alg:none` tokens rejected. Strict issuer (`ledgercore`) and zero clock skew timestamp validation. |
| **Tampering** | Attacker modifies JWT payload (e.g. elevates role to `ADMIN`) | Cryptographic signature verification fails immediately on altered claims. Idempotency payload hashes protect transfer and deposit payloads from in-flight modification. |
| **Repudiation** | User denies initiating a transfer or deposit | Double-entry journal entries are immutable (enforced by DB triggers and non-updatable entities). Every request is assigned a UUID `X-Request-Id` logged in MDC. |
| **Information Disclosure** | Leakage of account existence, passwords, or system internals | Passwords are never returned in responses. BCrypt hashes stored in DB. Non-owned and SYSTEM accounts concealed via 404. RFC 9457 ProblemDetail sanitizes stack traces and SQL. |
| **Denial of Service** | Resource exhaustion via huge passwords, queries, or lock storms | Passwords capped at 72 UTF-8 bytes (mitigating BCrypt DoS). Query page size capped at 100 items. Transfer locks use account ID ordering and strict 5-second database timeouts. |
| **Elevation of Privilege** | Customer accesses admin or peer endpoints | URL authorization enforces `ROLE_ADMIN` on `/api/v1/admin/**`. Service-layer `AccountOwnershipPolicy` enforces caller ownership on all account operations. |

---

## 3. Deployment & Operational Requirements

1. **TLS Termination**: LedgerCore binds to `127.0.0.1` by default (`server.address=127.0.0.1`). In production, a secure reverse proxy (e.g. NGINX, Envoy, AWS ALB) must terminate HTTPS (TLS 1.3) before forwarding requests.
2. **Environment Secrets**:
   - `JWT_SECRET`: Must be a cryptographically secure random string with at least 32 bytes of entropy.
   - `ADMIN_EMAIL` & `ADMIN_PASSWORD`: Used by `AdminBootstrapService` to provision the initial administrator account. Must be kept confidential and rotated.
3. **Known Limitations & Future Scope**:
   - **No Refresh Tokens**: Tokens expire after 15 minutes. Future phases will introduce refresh token rotation and revocation lists.
   - **No Rate Limiting**: Brute-force protection on `/api/v1/auth/login` must currently be enforced at the API gateway or reverse proxy level.
