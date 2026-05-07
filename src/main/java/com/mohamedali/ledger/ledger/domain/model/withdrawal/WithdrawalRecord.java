package com.mohamedali.ledger.ledger.domain.model.withdrawal;

import com.mohamedali.ledger.ledger.domain.model.Currency;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record WithdrawalRecord(
        UUID id,
        UUID customerId,
        UUID settledCashAccountId,
        UUID settlementPendingAccountId,
        UUID brokerageOmnibusAccountId,
        BigDecimal amount,
        Currency currency,
        WithdrawalStatus status,
        OffsetDateTime pendingSince,
        OffsetDateTime updatedAt
) {
}
