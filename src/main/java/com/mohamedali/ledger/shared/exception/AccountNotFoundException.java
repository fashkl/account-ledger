package com.mohamedali.ledger.shared.exception;

import com.mohamedali.ledger.ledger.domain.exception.DomainValidationException;
import java.util.UUID;

public final class AccountNotFoundException extends DomainValidationException {

    public AccountNotFoundException(UUID accountId) {
        super("LEDGER_ACCOUNT_NOT_FOUND", "Account not found: " + accountId);
    }
}
