-- OLAP-style analytics table (flat denormalized for fast aggregation)
CREATE TABLE IF NOT EXISTS payment_analytics (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID NOT NULL UNIQUE,
    sender_id       UUID NOT NULL,
    receiver_id     UUID NOT NULL,
    amount          NUMERIC(19, 4) NOT NULL,
    currency        CHAR(3) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    completed_at    TIMESTAMPTZ NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_analytics_completed_at  ON payment_analytics(completed_at DESC);
CREATE INDEX IF NOT EXISTS idx_analytics_sender_id     ON payment_analytics(sender_id);
CREATE INDEX IF NOT EXISTS idx_analytics_currency      ON payment_analytics(currency);
CREATE INDEX IF NOT EXISTS idx_analytics_status        ON payment_analytics(status);

-- Volume summary view (updated by triggers or refresh manually)
CREATE OR REPLACE VIEW payment_volume_summary AS
SELECT
    date_trunc('hour', completed_at) AS hour,
    currency,
    count(*)                          AS tx_count,
    sum(amount)                       AS total_volume,
    avg(amount)                       AS avg_amount,
    max(amount)                       AS max_amount
FROM payment_analytics
WHERE status = 'COMPLETED'
GROUP BY 1, 2
ORDER BY 1 DESC;
