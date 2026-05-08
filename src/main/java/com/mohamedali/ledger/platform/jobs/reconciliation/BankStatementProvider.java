package com.mohamedali.ledger.platform.jobs.reconciliation;

import com.mohamedali.ledger.ledger.domain.model.Currency;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

public interface BankStatementProvider {
    Optional<BigDecimal> totalSettledCash(Currency currency, LocalDate statementDate);
}

