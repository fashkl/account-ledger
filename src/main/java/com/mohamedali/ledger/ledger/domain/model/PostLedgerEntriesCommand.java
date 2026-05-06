package com.mohamedali.ledger.ledger.domain.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PostLedgerEntriesCommand(
        @NotBlank String idempotencyKey,
        @NotBlank String eventType,
        UUID referenceId,
        @NotNull LocalDate effectiveDate,
        @NotEmpty List<@Valid PostingLeg> legs
) {
}
