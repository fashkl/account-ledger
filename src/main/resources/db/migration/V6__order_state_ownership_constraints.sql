DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'order_states_customer_id_nn'
    ) THEN
        ALTER TABLE order_states
            ADD CONSTRAINT order_states_customer_id_nn CHECK (customer_id IS NOT NULL) NOT VALID;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'order_states_settled_cash_account_id_nn'
    ) THEN
        ALTER TABLE order_states
            ADD CONSTRAINT order_states_settled_cash_account_id_nn CHECK (settled_cash_account_id IS NOT NULL) NOT VALID;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'order_states_reserved_cash_account_id_nn'
    ) THEN
        ALTER TABLE order_states
            ADD CONSTRAINT order_states_reserved_cash_account_id_nn CHECK (reserved_cash_account_id IS NOT NULL) NOT VALID;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'order_states_unsettled_cash_buys_account_id_nn'
    ) THEN
        ALTER TABLE order_states
            ADD CONSTRAINT order_states_unsettled_cash_buys_account_id_nn CHECK (unsettled_cash_buys_account_id IS NOT NULL) NOT VALID;
    END IF;
END $$;
