package com.mohamedali.ledger.ledger.application.port.in.order;

import com.mohamedali.ledger.ledger.domain.model.order.OrderLifecycleCommand;
import jakarta.validation.Valid;

public interface OrderLifecycleUseCase {
    void handle(@Valid OrderLifecycleCommand command);
}
