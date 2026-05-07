package com.mohamedali.ledger.shared.exception;

import com.mohamedali.ledger.ledger.domain.exception.DomainValidationException;
import java.util.UUID;

public final class InvalidWithdrawalStateException extends DomainValidationException {
    public InvalidWithdrawalStateException(UUID withdrawalId, String reason) {
        super("LEDGER_INVALID_WITHDRAWAL_STATE",
                "Invalid withdrawal state for " + withdrawalId + ": " + reason);
    }
}
