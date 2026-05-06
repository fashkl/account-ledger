package com.mohamedali.ledger.ledger.application.usecase.order;

import com.mohamedali.ledger.ledger.application.port.in.order.BuyingPowerQuery;
import com.mohamedali.ledger.ledger.application.port.in.order.OrderLifecycleUseCase;
import com.mohamedali.ledger.ledger.application.port.in.LedgerPostingUseCase;
import com.mohamedali.ledger.ledger.application.port.out.order.OrderStatePersistencePort;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.ledger.domain.model.EntryDirection;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesCommand;
import com.mohamedali.ledger.ledger.domain.model.PostingLeg;
import com.mohamedali.ledger.ledger.domain.model.order.OrderLifecycleCommand;
import com.mohamedali.ledger.ledger.domain.model.order.OrderState;
import com.mohamedali.ledger.ledger.domain.model.order.OrderStateRecord;
import com.mohamedali.ledger.ledger.domain.service.order.OrderStateMachine;
import com.mohamedali.ledger.shared.exception.InvalidOrderEventException;
import com.mohamedali.ledger.shared.exception.InvalidStateTransitionException;
import com.mohamedali.ledger.shared.exception.OrderOwnershipMismatchException;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class OrderLifecycleService implements OrderLifecycleUseCase, BuyingPowerQuery {

    private static final Logger LOG = LoggerFactory.getLogger(OrderLifecycleService.class);

    private final OrderStatePersistencePort statePort;
    private final LedgerPostingUseCase postingUseCase;
    private final OrderStateMachine stateMachine;

    public OrderLifecycleService(OrderStatePersistencePort statePort,
                                 LedgerPostingUseCase postingUseCase,
                                 OrderStateMachine stateMachine) {
        this.statePort = statePort;
        this.postingUseCase = postingUseCase;
        this.stateMachine = stateMachine;
    }

    @Override
    @Transactional
    public void handle(@Valid OrderLifecycleCommand command) {
        OrderStateRecord current = statePort.findForUpdate(command.referenceId());
        stateMachine.validateExistingForEvent(command.referenceId(), command.eventType(), current);

        switch (command.eventType()) {
            case ORDER_CREATED -> handleCreate(command, current);
            case ORDER_FILL -> handleFill(command, current);
            case ORDER_CANCELLED, ORDER_REJECTED -> handleCancelOrReject(command, current);
            default -> throw new InvalidStateTransitionException(command.referenceId(), "unsupported order event");
        }
    }

    private void handleCreate(OrderLifecycleCommand command, OrderStateRecord existing) {
        requireAmount(command.heldAmount(), "heldAmount is required for ORDER_CREATED", command.referenceId());
        if (existing != null) {
            if (isEquivalentCreate(existing, command)) {
                LOG.warn("ORDER_CREATED is idempotent no-op for existing order {}", command.referenceId());
                return;
            }
            stateMachine.validateCreateAbsent(command.referenceId(), existing);
        }

        postHold(command);

        statePort.insert(new OrderStateRecord(
                command.referenceId(),
                command.customerId(),
                command.settledCashAccountId(),
                command.reservedCashAccountId(),
                command.unsettledCashBuysAccountId(),
                OrderState.HOLD,
                command.heldAmount(),
                BigDecimal.ZERO.setScale(8),
                command.currency(),
                OffsetDateTime.now(ZoneOffset.UTC)
        ));
    }

    private void handleFill(OrderLifecycleCommand command, OrderStateRecord current) {
        assertOrderEventContextMatchesCurrent(command, current);
        requireAmount(command.fillAmount(), "fillAmount is required for ORDER_FILL", command.referenceId());
        if (command.fillId() == null) {
            throw new InvalidOrderEventException(command.referenceId(), "fillId is required for ORDER_FILL");
        }

        OrderStateMachine.TransitionResult transition = stateMachine.onFill(current, command.referenceId(), command.fillAmount());

        postingUseCase.post(new PostLedgerEntriesCommand(
                "ORDER_FILL-" + command.referenceId() + "-" + command.fillId(),
                "ORDER_FILL",
                command.referenceId(),
                LocalDate.now(ZoneOffset.UTC),
                List.of(
                        new PostingLeg(command.reservedCashAccountId(), EntryDirection.CREDIT, transition.releaseAmount(), command.currency()),
                        new PostingLeg(command.unsettledCashBuysAccountId(), EntryDirection.DEBIT, transition.releaseAmount(), command.currency())
                )
        ));

        statePort.update(new OrderStateRecord(
                current.referenceId(),
                current.customerId(),
                current.settledCashAccountId(),
                current.reservedCashAccountId(),
                current.unsettledCashBuysAccountId(),
                transition.nextState(),
                current.heldAmount(),
                transition.nextFilledAmount(),
                current.currency(),
                OffsetDateTime.now(ZoneOffset.UTC)
        ));
    }

    private void handleCancelOrReject(OrderLifecycleCommand command, OrderStateRecord current) {
        assertOrderEventContextMatchesCurrent(command, current);
        OrderStateMachine.TransitionResult transition = stateMachine.onCancel(current, command.referenceId(), command.eventType());
        if (transition.noOp()) {
            LOG.warn("Order event {} is idempotent no-op for order {} currently in state {}",
                    command.eventType(), command.referenceId(), current.state());
            return;
        }

        if (transition.releaseAmount().compareTo(BigDecimal.ZERO) > 0) {
            if (command.releaseAmount() != null
                    && transition.releaseAmount().compareTo(command.releaseAmount()) != 0) {
                throw new InvalidOrderEventException(command.referenceId(), "releaseAmount does not match remaining hold amount");
            }
            postingUseCase.post(new PostLedgerEntriesCommand(
                    command.eventType().name() + "-" + command.referenceId() + "-v1",
                    command.eventType().name(),
                    command.referenceId(),
                    LocalDate.now(ZoneOffset.UTC),
                    List.of(
                            new PostingLeg(command.settledCashAccountId(), EntryDirection.DEBIT, transition.releaseAmount(), command.currency()),
                            new PostingLeg(command.reservedCashAccountId(), EntryDirection.CREDIT, transition.releaseAmount(), command.currency())
                    )
            ));
        }

        statePort.update(new OrderStateRecord(
                current.referenceId(),
                current.customerId(),
                current.settledCashAccountId(),
                current.reservedCashAccountId(),
                current.unsettledCashBuysAccountId(),
                transition.nextState(),
                current.heldAmount(),
                transition.nextFilledAmount(),
                current.currency(),
                OffsetDateTime.now(ZoneOffset.UTC)
        ));
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public BigDecimal buyingPower(UUID customerId, Currency currency) {
        OrderStatePersistencePort.BuyingPowerComponents components =
                statePort.getBuyingPowerComponentsForShare(customerId, currency);
        BigDecimal settled = components.settledCash();
        BigDecimal unsettledSales = components.unsettledCashSales();
        BigDecimal unsettledBuys = components.unsettledCashBuys();
        BigDecimal reserved = components.reservedCash();

        BigDecimal value = settled.add(unsettledSales).subtract(unsettledBuys).subtract(reserved);
        if (value.signum() < 0) {
            LOG.warn("Negative buying power computed for customer {} currency {}: {}", customerId, currency, value);
            return BigDecimal.ZERO;
        }
        return value;
    }

    private void postHold(OrderLifecycleCommand command) {
        postingUseCase.post(new PostLedgerEntriesCommand(
                "ORDER_CREATED-" + command.referenceId() + "-v1",
                "ORDER_CREATED",
                command.referenceId(),
                LocalDate.now(ZoneOffset.UTC),
                List.of(
                        new PostingLeg(command.reservedCashAccountId(), EntryDirection.DEBIT, command.heldAmount(), command.currency()),
                        new PostingLeg(command.settledCashAccountId(), EntryDirection.CREDIT, command.heldAmount(), command.currency())
                )
        ));
    }

    private void requireAmount(BigDecimal amount, String message, UUID referenceId) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidOrderEventException(referenceId, message);
        }
    }

    private void assertOrderEventContextMatchesCurrent(OrderLifecycleCommand command, OrderStateRecord current) {
        boolean sameContext = current.customerId() != null
                && current.customerId().equals(command.customerId())
                && current.settledCashAccountId() != null
                && current.settledCashAccountId().equals(command.settledCashAccountId())
                && current.reservedCashAccountId() != null
                && current.reservedCashAccountId().equals(command.reservedCashAccountId())
                && current.unsettledCashBuysAccountId() != null
                && current.unsettledCashBuysAccountId().equals(command.unsettledCashBuysAccountId())
                && current.currency().equals(command.currency());
        if (!sameContext) {
            throw new OrderOwnershipMismatchException(command.referenceId());
        }
    }

    private boolean isEquivalentCreate(OrderStateRecord existing, OrderLifecycleCommand command) {
        return existing.customerId() != null
                && existing.customerId().equals(command.customerId())
                && existing.settledCashAccountId() != null
                && existing.settledCashAccountId().equals(command.settledCashAccountId())
                && existing.reservedCashAccountId() != null
                && existing.reservedCashAccountId().equals(command.reservedCashAccountId())
                && existing.unsettledCashBuysAccountId() != null
                && existing.unsettledCashBuysAccountId().equals(command.unsettledCashBuysAccountId())
                && existing.state() == OrderState.HOLD
                && existing.currency().equals(command.currency())
                && existing.heldAmount().compareTo(command.heldAmount()) == 0
                && existing.filledAmount().compareTo(BigDecimal.ZERO.setScale(8)) == 0;
    }
}
