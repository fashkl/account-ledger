package com.mohamedali.ledger.platform.jobs.reconciliation;

import com.mohamedali.ledger.ledger.domain.model.Currency;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class NoopBankStatementProvider implements BankStatementProvider {
    @Override
    public Optional<BigDecimal> totalSettledCash(Currency currency, LocalDate statementDate) {
        return Optional.empty();
    }
}

