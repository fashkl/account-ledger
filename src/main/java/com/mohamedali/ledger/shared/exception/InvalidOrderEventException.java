package com.mohamedali.ledger.shared.exception;

import com.mohamedali.ledger.ledger.domain.exception.DomainValidationException;
import java.util.UUID;

public final class InvalidOrderEventException extends DomainValidationException {

    public InvalidOrderEventException(UUID referenceId, String message) {
        super("LEDGER_INVALID_ORDER_EVENT", "Order " + referenceId + ": " + message);
    }
}
