package com.mohamedali.ledger.ledger.application.port.in.withdrawal;

import com.mohamedali.ledger.ledger.domain.model.withdrawal.WithdrawalRecord;
import java.util.UUID;

public interface WithdrawalStatusQuery {

    WithdrawalRecord getWithdrawal(UUID withdrawalId);
}
