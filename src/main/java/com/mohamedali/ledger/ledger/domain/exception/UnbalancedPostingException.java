package com.mohamedali.ledger.ledger.domain.exception;

public final class UnbalancedPostingException extends DomainValidationException {

    public UnbalancedPostingException(String message) {
        super("LEDGER_UNBALANCED_POSTING", message);
    }
}
