DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'accounts_currency_check'
    ) THEN
        ALTER TABLE accounts
            ADD CONSTRAINT accounts_currency_check
            CHECK (currency IN ('AED', 'SAR', 'USD', 'KWD', 'EGP'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'journal_entries_currency_check'
    ) THEN
        ALTER TABLE journal_entries
            ADD CONSTRAINT journal_entries_currency_check
            CHECK (currency IN ('AED', 'SAR', 'USD', 'KWD', 'EGP'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'order_states_currency_check'
    ) THEN
        ALTER TABLE order_states
            ADD CONSTRAINT order_states_currency_check
            CHECK (currency IN ('AED', 'SAR', 'USD', 'KWD', 'EGP'));
    END IF;
END $$;
