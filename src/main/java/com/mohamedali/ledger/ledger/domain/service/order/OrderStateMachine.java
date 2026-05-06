package com.mohamedali.ledger.ledger.domain.service.order;

import com.mohamedali.ledger.ledger.domain.model.order.OrderEventType;
import com.mohamedali.ledger.ledger.domain.model.order.OrderState;
import com.mohamedali.ledger.ledger.domain.model.order.OrderStateRecord;
import com.mohamedali.ledger.shared.exception.InvalidOrderEventException;
import com.mohamedali.ledger.shared.exception.InvalidStateTransitionException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OrderStateMachine {

    private static final MathContext MC = MathContext.DECIMAL128;

    public TransitionResult onFill(OrderStateRecord current, UUID referenceId, BigDecimal fillAmount) {
        ensureState(current, referenceId, OrderEventType.ORDER_FILL, OrderState.HOLD, OrderState.PARTIALLY_FILLED);

        BigDecimal normalizedFill = normalize(fillAmount);
        BigDecimal nextFilled = normalize(current.filledAmount().add(normalizedFill, MC));

        if (nextFilled.compareTo(current.heldAmount()) > 0) {
            throw new InvalidOrderEventException(referenceId, "fill exceeds remaining hold");
        }

        OrderState nextState = nextFilled.compareTo(current.heldAmount()) == 0
                ? OrderState.FILLED
                : OrderState.PARTIALLY_FILLED;

        return new TransitionResult(nextState, nextFilled, normalizedFill, false);
    }

    public TransitionResult onCancel(OrderStateRecord current, UUID referenceId, OrderEventType eventType) {
        if (current.state() == OrderState.CANCELLED || current.state() == OrderState.REJECTED || current.state() == OrderState.FILLED) {
            return new TransitionResult(current.state(), current.filledAmount(), BigDecimal.ZERO, true);
        }

        if (current.state() != OrderState.HOLD && current.state() != OrderState.PARTIALLY_FILLED) {
            throw new InvalidStateTransitionException(referenceId, "invalid cancel transition from state " + current.state());
        }

        BigDecimal remaining = normalize(current.heldAmount().subtract(current.filledAmount(), MC));
        OrderState target = eventType == OrderEventType.ORDER_REJECTED && current.filledAmount().compareTo(BigDecimal.ZERO) == 0
                ? OrderState.REJECTED
                : OrderState.CANCELLED;

        return new TransitionResult(target, current.filledAmount(), remaining, false);
    }

    public void validateCreateAbsent(UUID referenceId, OrderStateRecord existing) {
        if (existing != null) {
            throw new InvalidStateTransitionException(referenceId, "ORDER_CREATED received for existing order state " + existing.state());
        }
    }

    public void validateExistingForEvent(UUID referenceId, OrderEventType eventType, OrderStateRecord existing) {
        if (existing == null && eventType != OrderEventType.ORDER_CREATED) {
            throw new InvalidStateTransitionException(referenceId, "event " + eventType + " received without prior hold");
        }
    }

    private void ensureState(OrderStateRecord current,
                             UUID referenceId,
                             OrderEventType eventType,
                             OrderState... allowed) {
        for (OrderState state : allowed) {
            if (current.state() == state) {
                return;
            }
        }
        throw new InvalidStateTransitionException(referenceId, eventType + " invalid from state " + current.state());
    }

    private BigDecimal normalize(BigDecimal value) {
        return value.round(MC).setScale(8, RoundingMode.HALF_UP);
    }

    public record TransitionResult(
            OrderState nextState,
            BigDecimal nextFilledAmount,
            BigDecimal releaseAmount,
            boolean noOp
    ) {
    }
}
