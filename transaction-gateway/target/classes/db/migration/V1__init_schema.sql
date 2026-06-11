-- Accounts table
CREATE TABLE IF NOT EXISTS accounts (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_name  VARCHAR(255) NOT NULL,
    balance     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency    CHAR(3) NOT NULL DEFAULT 'USD',
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
);

-- Payments table
CREATE TYPE payment_status AS ENUM ('PENDING', 'COMPLETED', 'FAILED');

CREATE TABLE IF NOT EXISTS payments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id        UUID NOT NULL REFERENCES accounts(id),
    receiver_id      UUID NOT NULL REFERENCES accounts(id),
    amount           NUMERIC(19, 4) NOT NULL,
    currency         CHAR(3) NOT NULL,
    status           payment_status NOT NULL DEFAULT 'PENDING',
    idempotency_key  VARCHAR(255) NOT NULL,
    failure_reason   TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_sender_receiver_diff CHECK (sender_id <> receiver_id),
    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key)
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_payments_sender_id ON payments(sender_id);
CREATE INDEX IF NOT EXISTS idx_payments_receiver_id ON payments(receiver_id);
CREATE INDEX IF NOT EXISTS idx_payments_created_at ON payments(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payments_idempotency_key ON payments(idempotency_key);

-- Seed some test accounts
INSERT INTO accounts (id, owner_name, balance, currency)
VALUES
    ('a0000000-0000-0000-0000-000000000001', 'Alice', 10000.00, 'USD'),
    ('a0000000-0000-0000-0000-000000000002', 'Bob',   5000.00, 'USD'),
    ('a0000000-0000-0000-0000-000000000003', 'Carol', 2500.00, 'USD')
ON CONFLICT DO NOTHING;
