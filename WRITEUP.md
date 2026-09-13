# Wallet Service — Design Write-Up

## Data Model

Two tables, both using PostgreSQL's native UUID generation:

| Table | Key Constraints | Purpose |
|-------|----------------|---------|
| `wallets` | `UNIQUE(user_id)`, `CHECK(balance_paise >= 0)` | One wallet per user, integer paise, DB-enforced non-negative |
| `transfers` | `UNIQUE(idempotency_key)`, `CHECK(amount_paise > 0)`, `CHECK(from ≠ to)` | Immutable transfer record with status + request hash |

Money is always `BIGINT` paise — never floats, never rupees-as-decimal.

## Simplest-Correct Mechanism: Conservation + No Overdraft

**Chosen: Sorted-order `SELECT … FOR UPDATE` in a single transaction.**

1. Both wallets are locked in **sorted UUID order** within one transaction — this makes deadlock impossible even when A→B and B→A execute simultaneously.
2. Balance is checked **after** locking — no TOCTOU race.
3. Debit, credit, and transfer record insert happen **in the same transaction** — conservation is guaranteed atomically.
4. A `CHECK (balance_paise >= 0)` constraint on the wallets table is a **belt-and-suspenders DB-level backstop**.

**Rejected alternatives:**

- **`UPDATE … WHERE balance >= amount`** — Simpler syntax, but can't distinguish "wallet not found" from "insufficient funds" and doesn't inherently prevent deadlocks without explicit ordering.
- **Serializable isolation** — Prevents all anomalies but carries much higher abort/retry overhead under contention. Row-level pessimistic locking is sufficient and more predictable for this workload.
- **Application-level distributed locks (Redis)** — Adds unnecessary infrastructure. PostgreSQL row locks are sufficient for single-DB architecture.

## Where Idempotency Lives

- `UNIQUE(idempotency_key)` on the `transfers` table, enforced at DB level.
- The transfer record is inserted **in the same transaction** as the balance mutations — either both commit or neither does.
- A SHA-256 `request_hash` of `(from, to, amount_paise)` is stored. On replay: same key + same hash → return original result. Same key + different hash → `409 Conflict`.
- **Race handling:** Two concurrent requests with the same key both pass the pre-check. The UNIQUE constraint catches the race — the loser's transaction rolls back (via `DuplicateKeyException`), and we fall back to returning the winner's result.

## Consistency vs Availability

**Chose: Strong consistency (CP).** This is money — we cannot tolerate double-spends or phantom balances.

**Gave up:** Availability under partition. If Postgres is unreachable, the service returns errors rather than serving stale or inconsistent data.

**Rationale:** For a wallet/payment system, incorrectly completing a transfer is far worse than temporarily refusing one. Users can retry; lost money cannot be recovered.

## AI Disclosure

| Category | Details |
|----------|---------|
| **Directed by me** (approach decided, AI typed) | Data model design, concurrency mechanism (sorted-order FOR UPDATE), idempotency strategy (same-txn insert + hash + DuplicateKeyException fallback), consistency/availability tradeoff, overall architecture |
| **AI implemented under direction** | HTTP handler boilerplate, Dockerfile multi-stage build, docker-compose, Prometheus wiring, structured logging setup, burst test script |

## Deployment & Cost

- **Platform:** Render.com free tier (Web Service + managed PostgreSQL)
- **Total cost: ₹0** — no credit card required
- **Limits:** 750 hrs/month compute, 1 GB Postgres, cold starts (~30s) on free tier
- **Health check:** `/health` (Actuator), used by Docker `HEALTHCHECK` and Render
- **Metrics:** `/metrics` (Prometheus format) with domain counters
- **Logs:** Structured JSON with `correlation_id` per request, viewable in Render dashboard
