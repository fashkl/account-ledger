package com.mohamedali.ledger.shared.exception;

import com.mohamedali.ledger.ledger.domain.exception.DomainValidationException;
import java.util.UUID;

public final class OrderOwnershipMismatchException extends DomainValidationException {

    public OrderOwnershipMismatchException(UUID referenceId) {
        super("LEDGER_ORDER_OWNERSHIP_MISMATCH",
                "Order " + referenceId + ": event account/customer/currency context does not match persisted order");
    }
}
