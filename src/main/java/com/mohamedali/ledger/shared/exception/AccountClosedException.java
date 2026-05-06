package com.mohamedali.ledger.shared.exception;

import com.mohamedali.ledger.ledger.domain.exception.DomainValidationException;
import java.util.UUID;

public final class AccountClosedException extends DomainValidationException {

    public AccountClosedException(UUID accountId) {
        super("LEDGER_ACCOUNT_CLOSED", "Account is closed: " + accountId);
    }
}
