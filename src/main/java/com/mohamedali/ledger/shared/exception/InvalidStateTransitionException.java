package com.mohamedali.ledger.shared.exception;

import com.mohamedali.ledger.ledger.domain.exception.DomainValidationException;
import java.util.UUID;

public final class InvalidStateTransitionException extends DomainValidationException {

    public InvalidStateTransitionException(UUID referenceId, String message) {
        super("LEDGER_INVALID_STATE_TRANSITION", "Order " + referenceId + ": " + message);
    }
}
