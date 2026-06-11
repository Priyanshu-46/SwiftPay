-- Mirror of accounts from Service A (source of truth for balances here)
CREATE TABLE IF NOT EXISTS accounts (
    id          UUID PRIMARY KEY,
    owner_name  VARCHAR(255) NOT NULL,
    balance     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency    CHAR(3) NOT NULL DEFAULT 'USD',
    version     BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
);

-- Double-entry ledger
CREATE TYPE entry_type AS ENUM ('DEBIT', 'CREDIT');

CREATE TABLE IF NOT EXISTS ledger_entries (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id     UUID NOT NULL,
    account_id     UUID NOT NULL REFERENCES accounts(id),
    entry_type     entry_type NOT NULL,
    amount         NUMERIC(19, 4) NOT NULL,
    balance_after  NUMERIC(19, 4) NOT NULL,
    currency       CHAR(3) NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_amount_positive CHECK (amount > 0)
);

-- Processed payments tracking (idempotency for Kafka)
CREATE TABLE IF NOT EXISTS processed_payments (
    payment_id    UUID PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_ledger_account_id ON ledger_entries(account_id, recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_ledger_payment_id ON ledger_entries(payment_id);

-- Seed matching test accounts
INSERT INTO accounts (id, owner_name, balance, currency)
VALUES
    ('a0000000-0000-0000-0000-000000000001', 'Alice', 10000.00, 'USD'),
    ('a0000000-0000-0000-0000-000000000002', 'Bob',   5000.00, 'USD'),
    ('a0000000-0000-0000-0000-000000000003', 'Carol', 2500.00, 'USD')
ON CONFLICT DO NOTHING;
