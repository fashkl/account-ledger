package com.mohamedali.ledger.ledger.application.port.in;

import java.math.BigDecimal;
import java.util.UUID;

public interface LedgerBalanceQuery {
    BigDecimal getAccountBalance(UUID accountId);
}
