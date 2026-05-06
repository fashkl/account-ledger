package com.mohamedali.ledger.ledger.domain.exception;

public non-sealed abstract class DomainValidationException extends RuntimeException implements DomainException {

    private final String code;

    protected DomainValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
