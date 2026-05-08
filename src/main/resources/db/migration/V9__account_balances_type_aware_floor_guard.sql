CREATE OR REPLACE FUNCTION enforce_account_balance_floor()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    account_type TEXT;
BEGIN
    SELECT type INTO account_type
    FROM accounts
    WHERE id = NEW.account_id;

    IF account_type IN (
        'SETTLED_CASH',
        'RESERVED_CASH',
        'UNSETTLED_CASH_BUYS',
        'UNSETTLED_CASH_SALES',
        'SETTLEMENT_PENDING'
    ) AND NEW.balance < 0 THEN
        RAISE EXCEPTION 'negative balance is not allowed for account type % (account_id=%)', account_type, NEW.account_id;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_enforce_account_balance_floor ON account_balances;

CREATE TRIGGER trg_enforce_account_balance_floor
BEFORE INSERT OR UPDATE ON account_balances
FOR EACH ROW
EXECUTE FUNCTION enforce_account_balance_floor();

