package com.mohamedali.ledger.ledger.application.port.in.withdrawal;

import com.mohamedali.ledger.ledger.domain.model.withdrawal.CashMovementCommand;
import jakarta.validation.Valid;

public interface CashMovementUseCase {

    void handle(@Valid CashMovementCommand command);
}
