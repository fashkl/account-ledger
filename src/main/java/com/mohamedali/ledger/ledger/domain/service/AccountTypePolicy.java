package com.mohamedali.ledger.ledger.domain.service;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AccountTypePolicy {

    private static final Map<String, BigDecimal> MIN_BALANCE_BY_TYPE = Map.of(
            "SETTLED_CASH", BigDecimal.ZERO,
            "RESERVED_CASH", BigDecimal.ZERO,
            "UNSETTLED_CASH_BUYS", BigDecimal.ZERO,
            "UNSETTLED_CASH_SALES", BigDecimal.ZERO,
            "SETTLEMENT_PENDING", BigDecimal.ZERO
    );

    public BigDecimal minBalance(String accountType) {
        return MIN_BALANCE_BY_TYPE.getOrDefault(accountType, null);
    }
}
