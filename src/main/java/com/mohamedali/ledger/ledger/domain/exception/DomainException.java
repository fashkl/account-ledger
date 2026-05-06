package com.mohamedali.ledger.ledger.domain.exception;

public sealed interface DomainException permits DomainValidationException {
    String code();
}
