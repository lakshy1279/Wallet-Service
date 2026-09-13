# API

All request/response JSON fields use `snake_case` (Jackson `SNAKE_CASE` naming strategy). Money fields are integer paise (no decimals).

## Auth

Bearer token = user identity, no user table.

```
Authorization: Bearer <userId>
```

Required on all `/wallets/*` and `/transfers/*` routes. Not required on `/health`, `/metrics`, `/dashboard`, `/actuator/*`.

Missing/invalid header → `401`:
```json
{"error":"unauthorized","message":"Missing or invalid Authorization header"}
```

## Wallets

### POST /wallets
Get-or-create wallet for calling user (from bearer token).

Response `200`:
```json
{"id":"uuid","user_id":"string","balance_paise":0,"created_at":"instant"}
```

### GET /wallets/{id}
Fetch wallet by id.

Response `200`: `WalletResponse` (see above). `404` if not found.

### POST /wallets/{id}/deposit
Deposit into wallet.

Request:
```json
{"amount_paise": 1000}
```
`amount_paise`: required, min 1.

Response `200`: updated `WalletResponse`.

## Transfers

### POST /transfers
Create transfer between two wallets. Idempotent via `idempotency_key`.

Request:
```json
{
  "from": "uuid",
  "to": "uuid",
  "amount_paise": 1000,
  "idempotency_key": "string"
}
```
All fields required; `amount_paise` min 1, `idempotency_key` non-blank.

Response:
```json
{
  "id": "uuid",
  "idempotency_key": "string",
  "from_wallet_id": "uuid",
  "to_wallet_id": "uuid",
  "amount_paise": 1000,
  "status": "COMPLETED | DECLINED",
  "decline_reason": "string | null",
  "created_at": "instant"
}
```
`201` on completed, `422` on declined (`status: "DECLINED"`, `decline_reason` set), same body shape either way. Separate from thrown `InsufficientFundsException` (400, see Errors) — decline is a normal outcome, not an error.

### GET /transfers/{id}
Fetch transfer by id.

Response `200`: `TransferResponse` (see above). `404` if not found.

## Observability (no auth)

### GET /health
Spring Boot Actuator health check, details shown.

### GET /metrics
Prometheus scrape endpoint (actuator, exposed at root path).

### GET /dashboard
Human-readable JSON metrics: domain counters (transfers completed/declined, idempotent replays, wallets created), per-endpoint HTTP stats (count, avg/max latency), error rate, infra stats (CPU, JVM heap, HikariCP pool).

## Errors

Standard error body (`GlobalExceptionHandler`):
```json
{"error":"string","message":"string","correlation_id":"string"}
```

| error | status |
|---|---|
| `not_found` | 404 |
| `insufficient_funds` | 400 |
| `idempotency_conflict` | 409 |
| `forbidden` | 403 |
| `validation_error` | 400 |
| `internal_error` | 500 |
