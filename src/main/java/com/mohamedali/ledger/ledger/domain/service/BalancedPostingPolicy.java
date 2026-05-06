package com.mohamedali.ledger.ledger.domain.service;

import com.mohamedali.ledger.ledger.domain.model.EntryDirection;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesCommand;
import com.mohamedali.ledger.ledger.domain.model.PostingLeg;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.ledger.domain.exception.InvalidPostingStructureException;
import com.mohamedali.ledger.ledger.domain.exception.UnbalancedPostingException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class BalancedPostingPolicy {

    public void validate(PostLedgerEntriesCommand command) {
        List<PostingLeg> legs = command.legs();
        if (legs.size() < 2) {
            throw new InvalidPostingStructureException("A posting requires at least 2 legs");
        }

        Map<Currency, BigDecimal> debitByCurrency = new HashMap<>();
        Map<Currency, BigDecimal> creditByCurrency = new HashMap<>();

        for (PostingLeg leg : legs) {
            if (leg.amount().signum() <= 0) {
                throw new InvalidPostingStructureException("Posting leg amount must be positive");
            }

            if (leg.direction() == EntryDirection.DEBIT) {
                debitByCurrency.merge(leg.currency(), leg.amount(), BigDecimal::add);
            } else {
                creditByCurrency.merge(leg.currency(), leg.amount(), BigDecimal::add);
            }
        }

        if (!debitByCurrency.keySet().equals(creditByCurrency.keySet())) {
            throw new UnbalancedPostingException("Posting is unbalanced by currency");
        }

        // Double-entry invariant is enforced per currency, not just on global totals.
        for (Currency currency : debitByCurrency.keySet()) {
            if (debitByCurrency.get(currency).compareTo(creditByCurrency.get(currency)) != 0) {
                throw new UnbalancedPostingException("Posting is unbalanced for currency: " + currency);
            }
        }
    }
}
