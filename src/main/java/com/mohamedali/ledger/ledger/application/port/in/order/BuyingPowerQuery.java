package com.mohamedali.ledger.ledger.application.port.in.order;

import com.mohamedali.ledger.ledger.domain.model.Currency;
import java.math.BigDecimal;
import java.util.UUID;

public interface BuyingPowerQuery {
    BigDecimal buyingPower(UUID customerId, Currency currency);
}
