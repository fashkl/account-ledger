ALTER TABLE order_states
    ADD COLUMN IF NOT EXISTS customer_id UUID,
    ADD COLUMN IF NOT EXISTS settled_cash_account_id UUID,
    ADD COLUMN IF NOT EXISTS reserved_cash_account_id UUID,
    ADD COLUMN IF NOT EXISTS unsettled_cash_buys_account_id UUID;

CREATE INDEX IF NOT EXISTS idx_order_states_expiry_scan
    ON order_states (state, updated_at);
