package com.mohamedali.ledger.ledger.adapter.in.web.dto;

import com.mohamedali.ledger.ledger.domain.model.EntryDirection;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesCommand;
import com.mohamedali.ledger.ledger.domain.model.PostingLeg;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PostLedgerEntriesRequest(
        @NotBlank String idempotencyKey,
        @NotBlank String eventType,
        UUID referenceId,
        @NotNull LocalDate effectiveDate,
        @NotEmpty List<@Valid PostingLegRequest> legs
) {
    public PostLedgerEntriesCommand toCommand() {
        return new PostLedgerEntriesCommand(
                idempotencyKey,
                eventType,
                referenceId,
                effectiveDate,
                legs.stream().map(PostingLegRequest::toDomain).toList()
        );
    }

    public record PostingLegRequest(
            @NotNull UUID accountId,
            @NotNull EntryDirection direction,
            @NotNull @DecimalMin(value = "0.00000001") BigDecimal amount,
            @NotNull Currency currency
    ) {
        private PostingLeg toDomain() {
            return new PostingLeg(accountId, direction, amount, currency);
        }
    }
}
