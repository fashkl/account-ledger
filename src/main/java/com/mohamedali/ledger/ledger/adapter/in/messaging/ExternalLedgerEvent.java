package com.mohamedali.ledger.ledger.adapter.in.messaging;

import com.mohamedali.ledger.ledger.domain.model.Currency;
import java.math.BigDecimal;
import java.util.UUID;

public record ExternalLedgerEvent(
        UUID eventId,
        String eventType,
        UUID referenceId,
        UUID customerId,
        UUID settledCashAccountId,
        UUID reservedCashAccountId,
        UUID unsettledCashBuysAccountId,
        UUID unsettledCashSalesAccountId,
        UUID settlementPendingAccountId,
        UUID brokerageOmnibusAccountId,
        UUID withdrawalId,
        UUID callbackId,
        UUID fillId,
        BigDecimal amount,
        BigDecimal heldAmount,
        BigDecimal fillAmount,
        BigDecimal releaseAmount,
        Currency currency,
        Long occurredAtEpochMs
) {
}
