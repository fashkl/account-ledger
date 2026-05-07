CREATE TABLE IF NOT EXISTS withdrawal_requests (
    id                            UUID PRIMARY KEY,
    customer_id                   UUID NOT NULL,
    settled_cash_account_id       UUID NOT NULL REFERENCES accounts(id),
    settlement_pending_account_id UUID NOT NULL REFERENCES accounts(id),
    brokerage_omnibus_account_id  UUID NOT NULL REFERENCES accounts(id),
    amount                        NUMERIC(20, 8) NOT NULL CHECK (amount > 0),
    currency                      TEXT NOT NULL,
    status                        TEXT NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'TIMED_OUT')),
    pending_since                 TIMESTAMPTZ NOT NULL,
    updated_at                    TIMESTAMPTZ NOT NULL
);

-- Partial index: only PENDING rows are ever queried by the timeout job
CREATE INDEX IF NOT EXISTS idx_withdrawal_requests_pending
    ON withdrawal_requests(pending_since)
    WHERE status = 'PENDING';
