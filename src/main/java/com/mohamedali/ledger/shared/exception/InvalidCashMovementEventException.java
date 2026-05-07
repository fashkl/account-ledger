package com.mohamedali.ledger.shared.exception;

import com.mohamedali.ledger.ledger.domain.exception.DomainValidationException;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.CashMovementEventType;
import java.util.UUID;

public final class InvalidCashMovementEventException extends DomainValidationException {

    public InvalidCashMovementEventException(CashMovementEventType eventType, UUID referenceId, String message) {
        super("LEDGER_INVALID_CASH_MOVEMENT_EVENT",
                "Cash movement " + eventType + " (" + referenceId + "): " + message);
    }
}
