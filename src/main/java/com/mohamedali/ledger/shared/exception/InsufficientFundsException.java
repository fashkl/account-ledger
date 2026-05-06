package com.mohamedali.ledger.shared.exception;

import com.mohamedali.ledger.ledger.domain.exception.DomainValidationException;
import java.math.BigDecimal;
import java.util.UUID;

public final class InsufficientFundsException extends DomainValidationException {

    public InsufficientFundsException(UUID accountId, BigDecimal shortfall) {
        super("LEDGER_INSUFFICIENT_FUNDS", "Insufficient funds for account " + accountId + ", shortfall=" + shortfall);
    }
}
