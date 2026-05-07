package com.mohamedali.ledger.shared.exception;

import com.mohamedali.ledger.ledger.domain.exception.DomainValidationException;
import java.util.UUID;

public final class WithdrawalNotFoundException extends DomainValidationException {
    public WithdrawalNotFoundException(UUID withdrawalId) {
        super("LEDGER_WITHDRAWAL_NOT_FOUND", "Withdrawal not found: " + withdrawalId);
    }
}
