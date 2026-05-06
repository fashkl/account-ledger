package com.mohamedali.ledger.platform.jobs.order;

import com.mohamedali.ledger.ledger.application.port.in.order.OrderLifecycleUseCase;
import com.mohamedali.ledger.ledger.application.port.out.order.OrderStatePersistencePort;
import com.mohamedali.ledger.ledger.domain.model.order.OrderEventType;
import com.mohamedali.ledger.ledger.domain.model.order.OrderLifecycleCommand;
import com.mohamedali.ledger.ledger.domain.model.order.OrderStateRecord;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class HoldExpiryJob {

    private static final Logger LOG = LoggerFactory.getLogger(HoldExpiryJob.class);
    private static final long LOCK_KEY = 42424242L;

    private final JdbcTemplate jdbcTemplate;
    private final OrderStatePersistencePort statePort;
    private final OrderLifecycleUseCase lifecycleUseCase;

    public HoldExpiryJob(JdbcTemplate jdbcTemplate,
                         OrderStatePersistencePort statePort,
                         OrderLifecycleUseCase lifecycleUseCase) {
        this.jdbcTemplate = jdbcTemplate;
        this.statePort = statePort;
        this.lifecycleUseCase = lifecycleUseCase;
    }

    public int runOnce(int limit) {
        Boolean lock = jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
        if (!Boolean.TRUE.equals(lock)) {
            return 0;
        }

        try {
            OffsetDateTime threshold = OffsetDateTime.now(ZoneOffset.UTC).minusHours(24);
            List<OrderStateRecord> expired = statePort.findExpiredOpenOrders(threshold, limit);
            for (OrderStateRecord order : expired) {
                if (!isComplete(order)) {
                    LOG.error("Skipping expired order {} due to incomplete ownership context in order_states",
                            order.referenceId());
                    continue;
                }
                lifecycleUseCase.handle(new OrderLifecycleCommand(
                        order.referenceId(),
                        OrderEventType.ORDER_CANCELLED,
                        order.customerId(),
                        order.settledCashAccountId(),
                        order.reservedCashAccountId(),
                        order.unsettledCashBuysAccountId(),
                        order.currency(),
                        null,
                        null,
                        null,
                        null
                ));
            }
            return expired.size();
        } finally {
            jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
        }
    }

    private boolean isComplete(OrderStateRecord order) {
        return order.customerId() != null
                && order.settledCashAccountId() != null
                && order.reservedCashAccountId() != null
                && order.unsettledCashBuysAccountId() != null
                && order.currency() != null;
    }
}
