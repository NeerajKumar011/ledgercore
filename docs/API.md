# LedgerCore REST API Specification (Phase 4B: Refresh Tokens, Revocation & Security Hardening)

> [!NOTE]
> **AUTHENTICATION ENABLED**: All endpoints outside the public whitelist require a valid JSON Web Token (JWT) passed in the `Authorization: Bearer <token>` header. Public endpoints are `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout`, and `GET /actuator/health`.

---

## 1. Overview & Conventions

- **Base Path**: `/api/v1`
- **Content Type**: `application/json` (Error responses: `application/problem+json` per [RFC 9457](https://datatracker.ietf.org/doc/html/rfc9457))
- **Monetary Representation**: Integer minor units (`amountMinor` as 64-bit integer / `long`). Never floating point.
  - Capped at `9007199254740991` (`2^53 - 1`, JavaScript `Number.MAX_SAFE_INTEGER`). Capping prevents IEEE-754 double precision float rounding errors when balances or transaction amounts are processed in JavaScript / web / mobile client environments.
- **Currency Code**: 3-character uppercase ISO 4217 standard matching `^[A-Z]{3}$`.
- **Idempotency Scoping**: The required `Idempotency-Key` header (`^[\x21-\x7E]{1,128}$`) is scoped per user. The server calculates an internal 64-character hex key = `SHA-256(callerUserId + "|" + clientKey)`. The raw client key is never stored in the database. This guarantees that multiple users can safely send identical client keys (e.g. `KEY-1`) without collision or cross-tenant conflict. Replays and 409 conflicts are strictly isolated to the caller who initiated the original key.
- **Request Tracing**: All requests accept or auto-assign an `X-Request-Id` header (validated against `^[A-Za-z0-9._-]{1,64}$` to prevent log injection), included in the mapped MDC context, echoed back on every HTTP response header, and attached to error problem details.
- **Authentication**: JWT HS256 Bearer tokens with 15-minute TTL. Per-request security verification validates caller status against database: disabled users and tokens with outdated `token_version` are instantly rejected (`401 Unauthorized`).
- **Refresh Tokens & Rotation**: 32-byte cryptographically random opaque tokens (SHA-256 hashed in database). 7-day TTL (`ledgercore.security.refresh-token-ttl`). Every refresh operation rotates the token within its family. Reuse of an already-used or revoked refresh token triggers theft detection, instantly revoking the entire token family.
- **Rate Limiting**: In-memory, bounded-memory per-IP rate limiter executing early in the filter chain before any BCrypt hashing. Limits: 10 requests/min for `/auth/login`, 5 requests/min for `/auth/register`, 20 requests/min for `/auth/refresh`. Evaluates `request.getRemoteAddr()` only (`X-Forwarded-For` is ignored to prevent client spoofing). Returns `429 Too Many Requests` with a standard `Retry-After` header.
- **Payload Size Limitation**: Requests with request bodies exceeding 16 KB (16,384 bytes) are rejected early with `413 Payload Too Large` ProblemDetail.
- **Security Response Headers**: Every HTTP response emits defensive headers:
  - `X-Content-Type-Options: nosniff`
  - `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`
  - `Referrer-Policy: no-referrer`
  - `X-Frame-Options: DENY`
  - `Cache-Control: no-store` on all `/api/**` paths.
- **Account Ownership & Concealment**: Customers can only view and manage accounts they own. Accessing accounts, statements, or transfers belonging to other users or internal `SYSTEM` accounts returns `404 Not Found` (identical ProblemDetail to a non-existent UUID), preventing user enumeration and resource probing. Furthermore, `TransferResponse` objects null out `fromAccountId` or `toAccountId` when referencing an internal `SYSTEM` funding account.

---

## 2. Authentication & User Management

### 1. Register User
`POST /api/v1/auth/register` (Public)

Registers a new user account with default `CUSTOMER` role. Emails are normalized to lower-case.

**Constraints:**
- `email`: Valid RFC 5322 format, max 254 chars, case-insensitive uniqueness.
- `password`: Minimum 12 characters, maximum 256 characters (and max 72 UTF-8 bytes for registration, enforcing BCrypt algorithmic boundary).

**Request:**
```http
POST /api/v1/auth/register HTTP/1.1
Content-Type: application/json

{
  "email": "alice@example.com",
  "password": "CorrectHorseBatteryStaple123!"
}
```

**Response (`201 Created`):**
```http
HTTP/1.1 201 Created
Location: /api/v1/users/550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json
Cache-Control: no-store
X-Request-Id: 78e1ab7d-943e-4e65-8a90-c328f5d3e17f

{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "alice@example.com",
  "role": "CUSTOMER",
  "createdAt": "2026-11-23T18:00:00Z"
}
```

---

### 2. Login
`POST /api/v1/auth/login` (Public)

Authenticates user credentials and issues a signed JWT access token. Includes constant-time dummy hashing for unknown emails to mitigate timing-based user enumeration.

**Request:**
```http
POST /api/v1/auth/login HTTP/1.1
Content-Type: application/json

{
  "email": "alice@example.com",
  "password": "CorrectHorseBatteryStaple123!"
}
```

**Response (`200 OK`):**
```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store
X-Request-Id: 91b2c3d4-e5f6-7890-abcd-ef1234567890

{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshToken": "wK3m8...32bytes-base64url",
  "refreshExpiresInSeconds": 604800
}
```

---

### 3. Refresh Access Token
`POST /api/v1/auth/refresh` (Public)

Rotates a refresh token: issues a new access token and a new refresh token within the same rotation family.
- **Theft Detection**: If an already used or revoked refresh token is presented, the server detects potential token theft and revokes the entire token family.
- **Uniform 401**: Non-existent, expired, already used, or revoked refresh tokens return an identical `401 Unauthorized` RFC 9457 ProblemDetail.
- **Current DB State**: Access token claims reflect the user's current role and database state. Disabled users are rejected.

**Request:**
```http
POST /api/v1/auth/refresh HTTP/1.1
Content-Type: application/json

{
  "refreshToken": "wK3m8...32bytes-base64url"
}
```

**Response (`200 OK`):**
```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store

{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshToken": "xP4n9...new-raw-refresh-token",
  "refreshExpiresInSeconds": 604800
}
```

---

### 4. Logout Single Session
`POST /api/v1/auth/logout` (Public)

Revokes the entire refresh token family associated with the presented refresh token. Always returns `204 No Content` to avoid information leakage.

**Request:**
```http
POST /api/v1/auth/logout HTTP/1.1
Content-Type: application/json

{
  "refreshToken": "xP4n9...refresh-token"
}
```

**Response (`204 No Content`)**

---

### 5. Logout All Sessions
`POST /api/v1/auth/logout-all` (Authenticated)

Revokes all refresh tokens belonging to the caller and increments `token_version` in the database.
- **Instant Revocation**: Any outstanding access tokens (even before their 15-minute expiration) are immediately rejected by the per-request security converter because their embedded `token_version` claim no longer matches the database.

**Request:**
```http
POST /api/v1/auth/logout-all HTTP/1.1
Authorization: Bearer <jwt-token>

```

**Response (`204 No Content`)**

---

## 3. Account Endpoints

### 1. Open Account
`POST /api/v1/accounts` (Authenticated)

Creates an account owned by the authenticated caller.
- `ownerRef`: Ignored if provided in request body. The account's owner is always strictly derived from the caller's authenticated user ID.
- `currency`: Required, uppercase 3-character ISO currency.
- `initialDepositMinor`: Optional, >= 0. Requires `ROLE_ADMIN` unless `ledgercore.demo.self-service-deposits=true`.

**Request:**
```http
POST /api/v1/accounts HTTP/1.1
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "currency": "USD",
  "initialDepositMinor": 0
}
```

**Response (`201 Created`):**
```http
HTTP/1.1 201 Created
Location: /api/v1/accounts/602f9e42-1234-4567-89ab-cdef01234567
Content-Type: application/json
X-Request-Id: a1b2c3d4-e5f6-7890-abcd-ef1234567890

{
  "id": "602f9e42-1234-4567-89ab-cdef01234567",
  "accountNumber": "ACC-4F8A12BC",
  "ownerRef": "550e8400-e29b-41d4-a716-446655440000",
  "currency": "USD",
  "status": "ACTIVE",
  "balanceMinor": 0,
  "createdAt": "2026-11-23T18:05:00Z",
  "updatedAt": "2026-11-23T18:05:00Z"
}
```

---

### 2. List Caller's Accounts
`GET /api/v1/accounts?page=0&size=20` (Authenticated)

Returns a paginated list (`Slice`) of accounts owned by the caller.

**Response (`200 OK`):**
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "items": [
    {
      "id": "602f9e42-1234-4567-89ab-cdef01234567",
      "accountNumber": "ACC-4F8A12BC",
      "ownerRef": "550e8400-e29b-41d4-a716-446655440000",
      "currency": "USD",
      "status": "ACTIVE",
      "balanceMinor": 50000,
      "createdAt": "2026-11-23T18:05:00Z",
      "updatedAt": "2026-11-23T18:05:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "hasNext": false
}
```

---

### 3. Get Account Details
`GET /api/v1/accounts/{id}` (Authenticated)

Retrieves account details by ID. Only the account owner or an admin can access it. Accessing another user's account or a `SYSTEM` account returns `404 Not Found`.

---

### 4. Deposit Funds
`POST /api/v1/accounts/{id}/deposits` (Authenticated, Idempotent)

Credits money to the account from the currency funding account.
- **Authorization**: Requires `ROLE_ADMIN` by default. Returns `403 Forbidden` if called by a Customer, unless `ledgercore.demo.self-service-deposits=true` (in which case a Customer can deposit to their own account).
- **Headers**: `Idempotency-Key` required.

**Request:**
```http
POST /api/v1/accounts/602f9e42-1234-4567-89ab-cdef01234567/deposits HTTP/1.1
Authorization: Bearer <admin-jwt-token>
Content-Type: application/json
Idempotency-Key: DEP-20261123-0001

{
  "amountMinor": 100000
}
```

**Response (`201 Created`):**
```http
HTTP/1.1 201 Created
Location: /api/v1/transfers/88888888-4444-4444-4444-121212121212
Content-Type: application/json

{
  "transferId": "88888888-4444-4444-4444-121212121212",
  "status": "POSTED"
}
```

---

### 5. Account Statement
`GET /api/v1/accounts/{id}/entries?page=0&size=20` (Authenticated)

Retrieves paginated ledger entries in newest-first order. Only the account owner or an admin can view the statement; unauthorized access returns `404 Not Found`. `size` is capped at 100 (`> 100` returns `400 Bad Request`).

---

## 4. Transfers

### 1. Execute Transfer
`POST /api/v1/transfers` (Authenticated, Idempotent)

Transfers funds between accounts.
- **Authorization**: The authenticated caller must own `fromAccountId` (or be `ROLE_ADMIN`). If the caller does not own `fromAccountId`, `404 Not Found` is returned.
- Destination account must not be an internal `SYSTEM` account.
- **Headers**: `Idempotency-Key` required (`^[\x21-\x7E]{1,128}$`).

**Request:**
```http
POST /api/v1/transfers HTTP/1.1
Authorization: Bearer <customer-jwt-token>
Content-Type: application/json
Idempotency-Key: TX-20261123-A01

{
  "fromAccountId": "602f9e42-1234-4567-89ab-cdef01234567",
  "toAccountId": "703a1f53-2345-6789-01cd-ef0123456789",
  "amountMinor": 2500,
  "currency": "USD"
}
```

**Response (`201 Created`):**
```http
HTTP/1.1 201 Created
Location: /api/v1/transfers/b1c2d3e4-f5a6-7890-1234-56789abcdef0
Content-Type: application/json

{
  "transferId": "b1c2d3e4-f5a6-7890-1234-56789abcdef0",
  "status": "POSTED"
}
```

---

### 2. Get Transfer
`GET /api/v1/transfers/{id}` (Authenticated)

Retrieves transfer details. The caller must be the sender, recipient, or `ROLE_ADMIN`. Unauthorized access returns `404 Not Found`.

---

## 5. Administration

### 1. Ledger Reconciliation
`GET /api/v1/admin/reconciliation` (Admin Only)

Requires `ROLE_ADMIN`. Customers receive `403 Forbidden`. Verifies zero-sum journal conservation and account balance equality across all currencies.

**Response (`200 OK`):**
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "consistent": true,
  "checkedAt": "2026-11-23T18:10:00Z",
  "problems": []
}
```

---

### 2. Disable User Account
`POST /api/v1/admin/users/{id}/disable` (Admin Only)

Disables the target user account, revokes all outstanding refresh tokens, and increments `token_version`.
- **Instant Cutoff**: Any existing access token is rejected immediately on the next request.
- **Self-Disable Guard**: An admin cannot disable their own account (`400 Bad Request`).
- Non-existent user returns `404 Not Found`.

**Response (`204 No Content`)**

---

### 3. Enable User Account
`POST /api/v1/admin/users/{id}/enable` (Admin Only)

Re-enables a previously disabled user account, allowing login. Non-existent user returns `404 Not Found`.

**Response (`204 No Content`)**
