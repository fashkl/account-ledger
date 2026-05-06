package com.mohamedali.ledger.ledger.application.port.out.order;

import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.ledger.domain.model.order.OrderStateRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface OrderStatePersistencePort {

    OrderStateRecord findForUpdate(UUID referenceId);

    void insert(OrderStateRecord state);

    void update(OrderStateRecord state);

    BuyingPowerComponents getBuyingPowerComponentsForShare(UUID customerId, Currency currency);

    List<OrderStateRecord> findExpiredOpenOrders(OffsetDateTime threshold, int limit);

    record BuyingPowerComponents(
            BigDecimal settledCash,
            BigDecimal unsettledCashSales,
            BigDecimal unsettledCashBuys,
            BigDecimal reservedCash
    ) {
    }
}
