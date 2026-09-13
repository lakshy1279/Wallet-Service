-- Wallets table
CREATE TABLE IF NOT EXISTS wallets (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         TEXT        NOT NULL,
    balance_paise   BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_wallets_user_id               UNIQUE (user_id),
    CONSTRAINT chk_wallets_balance_non_negative  CHECK  (balance_paise >= 0)
);

-- Transfers table
CREATE TABLE IF NOT EXISTS transfers (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key TEXT        NOT NULL,
    from_wallet_id  UUID        NOT NULL REFERENCES wallets(id),
    to_wallet_id    UUID        NOT NULL REFERENCES wallets(id),
    amount_paise    BIGINT      NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING',
    decline_reason  TEXT,
    request_hash    TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfers_idempotency_key     UNIQUE (idempotency_key),
    CONSTRAINT chk_transfers_amount_positive     CHECK  (amount_paise > 0),
    CONSTRAINT chk_transfers_different_wallets   CHECK  (from_wallet_id != to_wallet_id)
);

CREATE INDEX IF NOT EXISTS idx_transfers_from_wallet ON transfers (from_wallet_id);
CREATE INDEX IF NOT EXISTS idx_transfers_to_wallet   ON transfers (to_wallet_id);
