package com.mohamedali.ledger.ledger.domain.model.withdrawal;

import com.mohamedali.ledger.ledger.domain.model.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Unified command for all cash-in / cash-out events.
 *
 * <p>Required fields per event type:
 * <ul>
 *   <li>VA_CREDITED        — eventId, customerId, settledCashAccountId, brokerageOmnibusAccountId, amount, currency</li>
 *   <li>WITHDRAWAL_REQUESTED — withdrawalId, customerId, settledCashAccountId, settlementPendingAccountId, brokerageOmnibusAccountId, amount, currency</li>
 *   <li>WITHDRAWAL_CONFIRMED — callbackId, withdrawalId</li>
 *   <li>WITHDRAWAL_REJECTED  — callbackId, withdrawalId</li>
 * </ul>
 * CONFIRMED and REJECTED look up accounts and amount from the stored withdrawal_requests row.
 */
public record CashMovementCommand(
        @NotNull CashMovementEventType eventType,

        /** Stable event identifier — used as idempotency key source for VA_CREDITED. */
        UUID eventId,

        /** Stable withdrawal identifier — used for WITHDRAWAL_REQUESTED, CONFIRMED, REJECTED. */
        UUID withdrawalId,

        /** Stable bank callback identifier — used as idempotency key source for CONFIRMED/REJECTED. */
        UUID callbackId,

        UUID customerId,
        UUID settledCashAccountId,
        UUID settlementPendingAccountId,
        UUID brokerageOmnibusAccountId,

        @DecimalMin(value = "0.00000001") BigDecimal amount,
        Currency currency
) {
}
