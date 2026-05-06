package com.mohamedali.ledger.ledger.adapter.in.web.order.dto;

import com.mohamedali.ledger.ledger.domain.model.order.OrderEventType;
import com.mohamedali.ledger.ledger.domain.model.order.OrderLifecycleCommand;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record OrderLifecycleRequest(
        @NotNull UUID referenceId,
        @NotNull OrderEventType eventType,
        @NotNull UUID customerId,
        @NotNull UUID settledCashAccountId,
        @NotNull UUID reservedCashAccountId,
        @NotNull UUID unsettledCashBuysAccountId,
        @NotNull Currency currency,
        @DecimalMin("0.00000001") BigDecimal heldAmount,
        @DecimalMin("0.00000001") BigDecimal fillAmount,
        @DecimalMin("0.00000001") BigDecimal releaseAmount,
        UUID fillId
) {
    public OrderLifecycleCommand toCommand() {
        return new OrderLifecycleCommand(
                referenceId,
                eventType,
                customerId,
                settledCashAccountId,
                reservedCashAccountId,
                unsettledCashBuysAccountId,
                currency,
                heldAmount,
                fillAmount,
                releaseAmount,
                fillId
        );
    }
}
