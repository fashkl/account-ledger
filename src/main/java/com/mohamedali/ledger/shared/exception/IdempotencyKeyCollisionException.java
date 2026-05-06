package com.mohamedali.ledger.shared.exception;

import com.mohamedali.ledger.ledger.domain.exception.DomainValidationException;

public final class IdempotencyKeyCollisionException extends DomainValidationException {

    public IdempotencyKeyCollisionException(String idempotencyKey) {
        super("LEDGER_IDEMPOTENCY_COLLISION", "Idempotency key collision: " + idempotencyKey);
    }
}
