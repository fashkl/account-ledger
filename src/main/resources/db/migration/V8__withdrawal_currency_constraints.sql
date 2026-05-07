DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'withdrawal_requests_currency_check'
    ) THEN
        ALTER TABLE withdrawal_requests
            ADD CONSTRAINT withdrawal_requests_currency_check
            CHECK (currency IN ('AED', 'SAR', 'USD', 'KWD', 'EGP'));
    END IF;
END $$;
