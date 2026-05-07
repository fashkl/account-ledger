package com.mohamedali.ledger.ledger.adapter.in.web.withdrawal.dto;

import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.CashMovementCommand;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.CashMovementEventType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record CashMovementRequest(
        @NotNull CashMovementEventType eventType,
        UUID eventId,
        UUID withdrawalId,
        UUID callbackId,
        UUID customerId,
        UUID settledCashAccountId,
        UUID settlementPendingAccountId,
        UUID brokerageOmnibusAccountId,
        @DecimalMin("0.00000001") BigDecimal amount,
        Currency currency
) {
    public CashMovementCommand toCommand() {
        return new CashMovementCommand(
                eventType,
                eventId,
                withdrawalId,
                callbackId,
                customerId,
                settledCashAccountId,
                settlementPendingAccountId,
                brokerageOmnibusAccountId,
                amount,
                currency
        );
    }
}
