package com.mohamedali.ledger.ledger.domain.model;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record PostingLeg(
        @NotNull UUID accountId,
        @NotNull EntryDirection direction,
        @NotNull @DecimalMin(value = "0.00000001") BigDecimal amount,
        @NotBlank String currency
) {
}
