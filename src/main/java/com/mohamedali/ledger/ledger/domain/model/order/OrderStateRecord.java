package com.mohamedali.ledger.ledger.domain.model.order;

import com.mohamedali.ledger.ledger.domain.model.Currency;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record OrderStateRecord(
        UUID referenceId,
        UUID customerId,
        UUID settledCashAccountId,
        UUID reservedCashAccountId,
        UUID unsettledCashBuysAccountId,
        UUID unsettledCashSalesAccountId,
        OrderState state,
        BigDecimal heldAmount,
        BigDecimal filledAmount,
        Currency currency,
        OffsetDateTime updatedAt
) {
    public BigDecimal remainingAmount() {
        return heldAmount.subtract(filledAmount);
    }
}
