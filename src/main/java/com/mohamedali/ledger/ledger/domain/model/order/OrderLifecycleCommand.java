package com.mohamedali.ledger.ledger.domain.model.order;

import com.mohamedali.ledger.ledger.domain.model.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record OrderLifecycleCommand(
        @NotNull UUID referenceId,
        @NotNull OrderEventType eventType,
        @NotNull UUID customerId,
        @NotNull UUID settledCashAccountId,
        @NotNull UUID reservedCashAccountId,
        @NotNull UUID unsettledCashBuysAccountId,
        @NotNull Currency currency,
        @DecimalMin(value = "0.00000001") BigDecimal heldAmount,
        @DecimalMin(value = "0.00000001") BigDecimal fillAmount,
        @DecimalMin(value = "0.00000001") BigDecimal releaseAmount,
        UUID fillId
) {
}
