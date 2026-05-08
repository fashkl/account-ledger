ALTER TABLE order_states
    ADD COLUMN IF NOT EXISTS unsettled_cash_sales_account_id UUID REFERENCES accounts(id);

CREATE INDEX IF NOT EXISTS idx_order_states_unsettled_cash_sales_account_id
    ON order_states (unsettled_cash_sales_account_id);

