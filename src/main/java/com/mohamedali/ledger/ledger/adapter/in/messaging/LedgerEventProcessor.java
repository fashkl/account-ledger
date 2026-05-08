package com.mohamedali.ledger.ledger.adapter.in.messaging;

import com.mohamedali.ledger.ledger.application.port.in.order.OrderLifecycleUseCase;
import com.mohamedali.ledger.ledger.application.port.in.withdrawal.CashMovementUseCase;
import com.mohamedali.ledger.ledger.domain.model.order.OrderEventType;
import com.mohamedali.ledger.ledger.domain.model.order.OrderLifecycleCommand;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.CashMovementCommand;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.CashMovementEventType;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Service;

@Service
public class LedgerEventProcessor {

    private final OrderLifecycleUseCase orderLifecycleUseCase;
    private final CashMovementUseCase cashMovementUseCase;

    public LedgerEventProcessor(OrderLifecycleUseCase orderLifecycleUseCase,
                                CashMovementUseCase cashMovementUseCase) {
        this.orderLifecycleUseCase = orderLifecycleUseCase;
        this.cashMovementUseCase = cashMovementUseCase;
    }

    @Retry(name = "ledgerProcessingRetry")
    public void process(ExternalLedgerEvent event) {
        if (event.eventType() == null) {
            throw new IllegalArgumentException("eventType is required");
        }

        if (event.eventType().startsWith("ORDER_")) {
            orderLifecycleUseCase.handle(new OrderLifecycleCommand(
                    require(event.referenceId(), "referenceId"),
                    OrderEventType.valueOf(event.eventType()),
                    require(event.customerId(), "customerId"),
                    require(event.settledCashAccountId(), "settledCashAccountId"),
                    require(event.reservedCashAccountId(), "reservedCashAccountId"),
                    require(event.unsettledCashBuysAccountId(), "unsettledCashBuysAccountId"),
                    require(event.currency(), "currency"),
                    event.heldAmount(),
                    event.fillAmount(),
                    event.releaseAmount(),
                    event.fillId()
            ));
            return;
        }

        if (event.eventType().startsWith("WITHDRAWAL_") || "VA_CREDITED".equals(event.eventType())) {
            cashMovementUseCase.handle(new CashMovementCommand(
                    CashMovementEventType.valueOf(event.eventType()),
                    event.eventId(),
                    event.withdrawalId(),
                    event.callbackId(),
                    event.customerId(),
                    event.settledCashAccountId(),
                    event.settlementPendingAccountId(),
                    event.brokerageOmnibusAccountId(),
                    event.amount(),
                    event.currency()
            ));
            return;
        }

        throw new IllegalArgumentException("Unsupported eventType: " + event.eventType());
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
