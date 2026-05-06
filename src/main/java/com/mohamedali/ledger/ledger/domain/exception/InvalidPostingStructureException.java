package com.mohamedali.ledger.ledger.domain.exception;

public final class InvalidPostingStructureException extends DomainValidationException {

    public InvalidPostingStructureException(String message) {
        super("LEDGER_INVALID_POSTING_STRUCTURE", message);
    }
}
