package com.mohamedali.ledger.ledger.adapter.out.persistence.order;

import com.mohamedali.ledger.ledger.application.port.out.order.OrderStatePersistencePort;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.ledger.domain.model.order.OrderState;
import com.mohamedali.ledger.ledger.domain.model.order.OrderStateRecord;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcOrderStateAdapter implements OrderStatePersistencePort {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcTemplate readReplicaJdbcTemplate;

    public JdbcOrderStateAdapter(JdbcTemplate jdbcTemplate,
                                 @Qualifier("readReplicaJdbcTemplate") JdbcTemplate readReplicaJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.readReplicaJdbcTemplate = readReplicaJdbcTemplate;
    }

    @Override
    public OrderStateRecord findForUpdate(UUID referenceId) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT reference_id, customer_id, settled_cash_account_id, reserved_cash_account_id,
                           unsettled_cash_buys_account_id, unsettled_cash_sales_account_id,
                           state, held_amount, filled_amount, currency, updated_at
                    FROM order_states
                    WHERE reference_id = ?
                    FOR UPDATE
                    """,
                    (rs, rowNum) -> new OrderStateRecord(
                            (UUID) rs.getObject("reference_id"),
                            (UUID) rs.getObject("customer_id"),
                            (UUID) rs.getObject("settled_cash_account_id"),
                            (UUID) rs.getObject("reserved_cash_account_id"),
                            (UUID) rs.getObject("unsettled_cash_buys_account_id"),
                            (UUID) rs.getObject("unsettled_cash_sales_account_id"),
                            OrderState.valueOf(rs.getString("state")),
                            rs.getBigDecimal("held_amount"),
                            rs.getBigDecimal("filled_amount"),
                            Currency.valueOf(rs.getString("currency")),
                            rs.getTimestamp("updated_at").toInstant().atOffset(ZoneOffset.UTC)
                    ),
                    referenceId
            );
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    @Override
    public void insert(OrderStateRecord state) {
        jdbcTemplate.update(
                """
                INSERT INTO order_states(
                    reference_id, customer_id, settled_cash_account_id, reserved_cash_account_id,
                    unsettled_cash_buys_account_id, unsettled_cash_sales_account_id,
                    state, held_amount, filled_amount, currency, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                state.referenceId(),
                state.customerId(),
                state.settledCashAccountId(),
                state.reservedCashAccountId(),
                state.unsettledCashBuysAccountId(),
                state.unsettledCashSalesAccountId(),
                state.state().name(),
                state.heldAmount(),
                state.filledAmount(),
                state.currency().name(),
                Timestamp.from(state.updatedAt().toInstant())
        );
    }

    @Override
    public void update(OrderStateRecord state) {
        jdbcTemplate.update(
                """
                UPDATE order_states
                SET state = ?, held_amount = ?, filled_amount = ?, currency = ?, updated_at = ?
                WHERE reference_id = ?
                """,
                state.state().name(),
                state.heldAmount(),
                state.filledAmount(),
                state.currency().name(),
                Timestamp.from(state.updatedAt().toInstant()),
                state.referenceId()
        );
    }

    @Override
    public BuyingPowerComponents getBuyingPowerComponentsForShare(UUID customerId, Currency currency) {
        List<BuyingPowerComponents> values = readReplicaJdbcTemplate.query(
                """
                WITH locked_accounts AS (
                    SELECT a.type, COALESCE(ab.balance, 0) AS balance
                    FROM accounts a
                    LEFT JOIN account_balances ab ON a.id = ab.account_id
                    WHERE a.customer_id = ?
                      AND a.currency = ?
                )
                SELECT
                    COALESCE(SUM(CASE WHEN type = 'SETTLED_CASH' THEN balance ELSE 0 END), 0) AS settled_cash,
                    COALESCE(SUM(CASE WHEN type = 'UNSETTLED_CASH_SALES' THEN balance ELSE 0 END), 0) AS unsettled_cash_sales,
                    COALESCE(SUM(CASE WHEN type = 'UNSETTLED_CASH_BUYS' THEN balance ELSE 0 END), 0) AS unsettled_cash_buys,
                    COALESCE(SUM(CASE WHEN type = 'RESERVED_CASH' THEN balance ELSE 0 END), 0) AS reserved_cash
                FROM locked_accounts
                """,
                (rs, rowNum) -> new BuyingPowerComponents(
                        rs.getBigDecimal("settled_cash"),
                        rs.getBigDecimal("unsettled_cash_sales"),
                        rs.getBigDecimal("unsettled_cash_buys"),
                        rs.getBigDecimal("reserved_cash")
                ),
                customerId,
                currency.name()
        );
        if (values.isEmpty()) {
            return new BuyingPowerComponents(
                    BigDecimal.ZERO.setScale(8),
                    BigDecimal.ZERO.setScale(8),
                    BigDecimal.ZERO.setScale(8),
                    BigDecimal.ZERO.setScale(8)
            );
        }
        return values.get(0);
    }

    @Override
    public List<OrderStateRecord> findExpiredOpenOrders(OffsetDateTime threshold, int limit) {
        return jdbcTemplate.query(
                """
                SELECT reference_id, customer_id, settled_cash_account_id, reserved_cash_account_id,
                       unsettled_cash_buys_account_id, unsettled_cash_sales_account_id,
                       state, held_amount, filled_amount, currency, updated_at
                FROM order_states
                WHERE state IN ('HOLD', 'PARTIALLY_FILLED')
                  AND updated_at < ?
                  AND customer_id IS NOT NULL
                  AND settled_cash_account_id IS NOT NULL
                  AND reserved_cash_account_id IS NOT NULL
                  AND unsettled_cash_buys_account_id IS NOT NULL
                  AND currency IS NOT NULL
                ORDER BY updated_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> new OrderStateRecord(
                        (UUID) rs.getObject("reference_id"),
                        (UUID) rs.getObject("customer_id"),
                        (UUID) rs.getObject("settled_cash_account_id"),
                        (UUID) rs.getObject("reserved_cash_account_id"),
                        (UUID) rs.getObject("unsettled_cash_buys_account_id"),
                        (UUID) rs.getObject("unsettled_cash_sales_account_id"),
                        OrderState.valueOf(rs.getString("state")),
                        rs.getBigDecimal("held_amount"),
                        rs.getBigDecimal("filled_amount"),
                        Currency.valueOf(rs.getString("currency")),
                        rs.getTimestamp("updated_at").toInstant().atOffset(ZoneOffset.UTC)
                ),
                Timestamp.from(threshold.toInstant()),
                limit
        );
    }
}
