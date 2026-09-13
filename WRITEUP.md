# Wallet Service — Design Write-Up

## Data Model

```
┌─────────────────────────────────────────────────────────┐
│  wallets                                                │
│─────────────────────────────────────────────────────────│
│  id             UUID  PK  DEFAULT gen_random_uuid()     │
│  user_id        TEXT  NOT NULL  UNIQUE                  │
│  balance_paise  BIGINT  NOT NULL  DEFAULT 0             │
│  created_at     TIMESTAMPTZ  DEFAULT now()              │
│  updated_at     TIMESTAMPTZ  DEFAULT now()              │
│─────────────────────────────────────────────────────────│
│  UNIQUE(user_id)               ← race-free get-or-create│
│  CHECK(balance_paise >= 0)     ← DB-level overdraft guard│
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  transfers                                              │
│─────────────────────────────────────────────────────────│
│  id              UUID  PK  DEFAULT gen_random_uuid()    │
│  idempotency_key TEXT  NOT NULL  UNIQUE                 │
│  from_wallet_id  UUID  NOT NULL  FK → wallets(id)       │
│  to_wallet_id    UUID  NOT NULL  FK → wallets(id)       │
│  amount_paise    BIGINT  NOT NULL                       │
│  status          TEXT  NOT NULL  DEFAULT 'PENDING'      │
│  decline_reason  TEXT                                   │
│  request_hash    TEXT  NOT NULL                         │
│  created_at      TIMESTAMPTZ  DEFAULT now()             │
│─────────────────────────────────────────────────────────│
│  UNIQUE(idempotency_key)         ← exactly-once         │
│  CHECK(amount_paise > 0)         ← positive transfers   │
│  CHECK(from_wallet_id != to_wallet_id)  ← no self-send  │
└─────────────────────────────────────────────────────────┘
```

All monetary values are **`BIGINT` paise** — never floats, never rupees-as-decimal. The schema has zero application-managed sequences; PostgreSQL's `gen_random_uuid()` handles primary keys.

### Rejected Alternatives

| Alternative | Why Rejected |
|---|---|
| **Sequential primary keys** (`BIGINT`/`SERIAL`, or time-ordered UUIDv7) | Random UUIDv4 costs some insert-path locality (B-tree page splits, larger index) versus a sequential key, but this workload's write volume doesn't make that cost material. Sequential IDs would also leak wallet/transfer creation order and count externally — an information disclosure a money-movement API shouldn't have. Point lookups (`findById`, `findByIdempotencyKey`) are unaffected either way, since both are O(log n) B-tree lookups. UUIDv4 was kept for opacity; UUIDv7 is the fallback if insert throughput ever becomes the bottleneck. |

---

## Simplest-Correct Mechanism: Conservation + No Overdraft

**Chosen: Sorted-order `SELECT … FOR UPDATE` + single-transaction debit/credit/record.**

The transfer flow in `TransferService.executeTransfer()`:

1. **Sort wallet UUIDs** — both wallets are always locked in ascending UUID order (`compareTo`). This deterministic ordering makes **deadlock impossible**, even when `A→B` and `B→A` execute simultaneously on different connections.
2. **`SELECT … FOR UPDATE` on both rows** — `findAllByIdsForUpdate(firstId, secondId)` issues `SELECT * FROM wallets WHERE id IN (?, ?) ORDER BY id FOR UPDATE`, acquiring row-level exclusive locks.
3. **Balance check after lock** — the sender's balance is read under lock, eliminating the TOCTOU window. If `balance < amount`, the transfer is declined and a `DECLINED` record is inserted (still inside the transaction, so the idempotency key is consumed).
4. **Debit + credit + transfer record in one commit** — `UPDATE` on both wallet rows and `INSERT` into transfers all happen inside a single `TransactionTemplate.execute()` callback. Either everything commits or nothing does → **conservation is atomic**.
5. **Belt-and-suspenders**: `CHECK (balance_paise >= 0)` on the `wallets` table acts as a DB-level backstop. Even if the application logic has a bug, Postgres will reject a negative balance.

### Rejected Alternatives

| Alternative | Why Rejected |
|---|---|
| **`UPDATE … WHERE balance >= amount`** (conditional CAS) | Simpler SQL, but (a) cannot distinguish "wallet not found" from "insufficient funds" for proper error reporting, (b) doesn't inherently prevent deadlocks without explicit ordering — two concurrent `UPDATE` statements on the same two rows in opposite order will still deadlock. |
| **Serializable isolation** | Prevents all anomalies, but carries much higher abort/retry overhead under contention. Row-level pessimistic locking is sufficient for two-row mutations and gives more predictable latency. Serializable would force retry loops and is harder to reason about for reviewers. |
| **Application-level distributed locks (Redis/Redlock)** | Introduces unnecessary infrastructure and a whole new failure domain. PostgreSQL row locks are sufficient for a single-database architecture and are automatically released on crash/disconnect. |
| **Optimistic locking with version columns** | Requires retry loops at the application layer and degrades under high contention on the same wallets. Pessimistic locking is the natural fit for a "move money" workload where conflicts are expected. |

---

## Where Idempotency Lives

1. **`UNIQUE(idempotency_key)`** on the `transfers` table, enforced at the database level.
2. The transfer record is inserted **in the same transaction** as the balance mutations — the idempotency key is committed atomically with the debit and credit. There is no window where money moves but the key isn't recorded, or vice versa.
3. **Same-key / different-body detection**: a SHA-256 hash of `(from, to, amount_paise)` is stored as `request_hash`. On replay:
   - Same key + same hash → return the original result (idempotent replay).
   - Same key + different hash → **`409 Conflict`**, not a second debit.
4. **Race handling**: Two concurrent requests with the same idempotency key both pass the optimistic pre-check (`findByIdempotencyKey`). Only one wins the `INSERT` — the other hits the `UNIQUE` constraint, catches the resulting `DuplicateKeyException`, and falls back to looking up and returning the winner's result. No double-debit is possible.

---

## Consistency vs Availability

**Choice: Strong consistency (CP).**

This is money. The consequences of inconsistency (double-spend, phantom balance, overdraft) are far worse than temporary unavailability.

**What we gave up:** If Postgres is unreachable, the service returns 5xx errors rather than serving stale or optimistic data. Every read and write goes through the single Postgres instance. There is no read replica, no cache, no eventual-consistency path.

**Why this is correct for a wallet:** Users can retry a failed transfer; lost or duplicated money cannot be recovered programmatically. The idempotency key makes retries safe, so temporary unavailability has no lasting impact on the user experience. For a payment/wallet workload, refusing a request is always safer than guessing.

---

## Race-Free Get-or-Create

`POST /wallets` uses PostgreSQL's `INSERT … ON CONFLICT (user_id) DO NOTHING RETURNING *`. If the row was just created, `RETURNING *` gives it back immediately. If a concurrent request already created it (conflict), the `RETURNING` clause returns nothing, and we fall back to a `SELECT`. The `UNIQUE(user_id)` constraint guarantees that N concurrent requests for the same user produce exactly one wallet.

---

## Deploy, Containerize, Observe

### Dockerfile
- **Multi-stage build**: Maven build stage → slim JRE-only runtime (`eclipse-temurin:17-jre-alpine`).
- **Non-root user**: `appuser:appgroup` created and switched to before `ENTRYPOINT`.
- **`HEALTHCHECK`**: `wget -qO- http://localhost:8080/health || exit 1` every 30s.

### docker-compose
One command: `docker compose up --build`. Starts Postgres 16 (Alpine) + the app. Postgres has its own healthcheck; the app waits for `service_healthy` before starting. Data persisted to a named volume (`pgdata`).

### Deployment
- **Platform**: Render.com free tier (Web Service + managed PostgreSQL).
- **Infrastructure-as-code**: `render.yaml` blueprint defines both the web service and the database. Environment variables are wired automatically from the managed Postgres instance.
- **Health check**: Render pings `/health` (Spring Actuator).

### Structured Logging
- **Format**: JSON via `logstash-logback-encoder`.
- **Correlation ID**: Every request gets a `correlation_id` (from `X-Request-ID` header or auto-generated UUID), injected into SLF4J MDC by `CorrelationIdFilter`. Every log line includes it.
- **Domain events logged**: `wallet_created`, `wallet_found`, `transfer_completed`, `transfer_declined`, `idempotent_replay`, `idempotent_race_resolved`.
- **Viewable**: In Render dashboard → Logs tab (streaming JSON).

### Metrics
Two endpoints, no auth required on either:

**`/metrics`** — raw Prometheus text format (for Grafana / Prometheus scraping):

| Metric | Type | What it measures |
|---|---|---|
| `http_server_requests_seconds_count` | Counter | Request rate (by path, method, status) |
| `http_server_requests_seconds` | Histogram | Latency distribution (p50, p95, p99) |
| `http_server_requests_seconds_count{status=~"5.."}` | Counter | Error rate |
| `wallet_transfers_completed_total` | Counter | Successful transfers |
| `wallet_transfers_declined_total` | Counter | Declined (insufficient funds) |
| `wallet_idempotent_replays_total` | Counter | Idempotent replay hits |
| `wallet_wallets_created_total` | Counter | New wallets created |

**`/dashboard`** — human-readable JSON summary (for quick inspection):

```json
{
  "timestamp": "2026-09-13T13:30:00Z",
  "uptime_seconds": 162,
  "requests": {
    "total_requests": 128,
    "total_errors": 0,
    "error_rate_percent": 0.0,
    "endpoints": {
      "POST /wallets":       { "count": 26, "avg_latency_ms": 295.74, "max_latency_ms": 700.81 },
      "POST /transfers":     { "count": 70, "avg_latency_ms": 2111.62, "max_latency_ms": 4997.53 },
      "GET /wallets/{id}":   { "count": 5,  "avg_latency_ms": 34.8,   "max_latency_ms": 117.86 }
    }
  },
  "domain": {
    "transfers_completed": 51,
    "transfers_declined": 0,
    "idempotent_replays": 19,
    "wallets_created": 11
  },
  "infra": {
    "cpu_usage_percent": 66.9,
    "heap_used_bytes": 34916648,
    "heap_max_bytes": 259522560,
    "hikari_active_connections": 0,
    "hikari_idle_connections": 10,
    "hikari_max_connections": 10
  }
}
```

---

## Burst Test Script

Single command: `./scripts/burst_test.sh [BASE_URL]`

Requires only `curl` and `jq`. Runs three tests:

| Test | What it does | Pass condition |
|---|---|---|
| **Concurrent get-or-create** | 20 simultaneous `POST /wallets` for one user | Exactly 1 unique wallet ID |
| **Idempotent retry storm** | 20 concurrent transfers with the same idempotency key | Exactly 1 debit, all responses identical |
| **Conservation under contention** | 50 concurrent transfers across 4 wallets (including bidirectional) | Total balance unchanged, no negative balances |

---

## AI Disclosure

| Category | Details |
|---|---|
| **Directed by me** (I decided the approach; AI typed the code) | Data model design (two-table, BIGINT paise, CHECK constraints), concurrency mechanism (sorted-order `SELECT … FOR UPDATE` + single-txn commit), idempotency strategy (same-txn insert + SHA-256 request hash + `DuplicateKeyException` fallback → 409 on body mismatch), consistency-vs-availability tradeoff decision, overall architecture choices, error-handling strategy |
| **AI implemented under direction** | HTTP controller/DTO boilerplate, Dockerfile multi-stage build, docker-compose wiring, Prometheus metrics registration, logback-spring.xml structured logging config, burst test shell script, Render blueprint (`render.yaml`), DataSource URL converter for Render's `postgres://` format |

---

## Free-Tier Cost

| Resource | Provider | Plan | Cost |
|---|---|---|---|
| Web Service | Render.com | Free | ₹0 |
| PostgreSQL | Render.com | Free (1 GB) | ₹0 |
| **Total** | | | **₹0** |

No credit card required. Free tier limits: 750 hrs/month compute, 1 GB database, cold starts (~30s after inactivity).
