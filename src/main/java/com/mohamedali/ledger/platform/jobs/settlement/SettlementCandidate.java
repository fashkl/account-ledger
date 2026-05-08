package com.mohamedali.ledger.platform.jobs.settlement;

import com.mohamedali.ledger.ledger.domain.model.Currency;
import java.math.BigDecimal;
import java.util.UUID;

public record SettlementCandidate(
        UUID journalEntryId,
        UUID referenceId,
        UUID customerId,
        String sourceAccountType,
        UUID unsettledCashBuysAccountId,
        UUID unsettledCashSalesAccountId,
        UUID brokerageOmnibusAccountId,
        BigDecimal amount,
        Currency currency
) {
}
