package com.mohamedali.ledger.ledger.adapter.out.persistence.withdrawal;

import com.mohamedali.ledger.ledger.application.port.out.withdrawal.WithdrawalPersistencePort;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.WithdrawalRecord;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.WithdrawalStatus;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcWithdrawalAdapter implements WithdrawalPersistencePort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcWithdrawalAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public BigDecimal lockSettledCashBalance(UUID settledCashAccountId) {
        // Acquires a row-level lock on account_balances for the SETTLED_CASH account.
        // If no balance row exists yet (no prior credits), the account effectively has 0 balance.
        // We lock the accounts row as a fallback to prevent concurrent writes when no balance row exists.
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT COALESCE(balance, 0) FROM account_balances WHERE account_id = ? FOR UPDATE",
                    BigDecimal.class,
                    settledCashAccountId
            );
        } catch (EmptyResultDataAccessException e) {
            // No snapshot row means zero balance — lock accounts row instead to serialise concurrent requests
            jdbcTemplate.queryForObject("SELECT id FROM accounts WHERE id = ? FOR UPDATE", UUID.class, settledCashAccountId);
            return BigDecimal.ZERO;
        }
    }

    @Override
    public void insertWithdrawal(WithdrawalRecord record) {
        jdbcTemplate.update(
                """
                INSERT INTO withdrawal_requests(
                    id, customer_id, settled_cash_account_id, settlement_pending_account_id,
                    brokerage_omnibus_account_id, amount, currency, status, pending_since, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                record.id(),
                record.customerId(),
                record.settledCashAccountId(),
                record.settlementPendingAccountId(),
                record.brokerageOmnibusAccountId(),
                record.amount(),
                record.currency().name(),
                record.status().name(),
                Timestamp.from(record.pendingSince().toInstant()),
                Timestamp.from(record.updatedAt().toInstant())
        );
    }

    @Override
    public Optional<WithdrawalRecord> findForUpdate(UUID withdrawalId) {
        try {
            WithdrawalRecord record = jdbcTemplate.queryForObject(
                    """
                    SELECT id, customer_id, settled_cash_account_id, settlement_pending_account_id,
                           brokerage_omnibus_account_id, amount, currency, status, pending_since, updated_at
                    FROM withdrawal_requests
                    WHERE id = ?
                    FOR UPDATE
                    """,
                    (rs, rowNum) -> mapRow(rs),
                    withdrawalId
            );
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<WithdrawalRecord> findById(UUID withdrawalId) {
        try {
            WithdrawalRecord record = jdbcTemplate.queryForObject(
                    """
                    SELECT id, customer_id, settled_cash_account_id, settlement_pending_account_id,
                           brokerage_omnibus_account_id, amount, currency, status, pending_since, updated_at
                    FROM withdrawal_requests
                    WHERE id = ?
                    """,
                    (rs, rowNum) -> mapRow(rs),
                    withdrawalId
            );
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void updateStatus(UUID withdrawalId, WithdrawalStatus status) {
        jdbcTemplate.update(
                "UPDATE withdrawal_requests SET status = ?, updated_at = ? WHERE id = ?",
                status.name(),
                Timestamp.from(java.time.Instant.now()),
                withdrawalId
        );
    }

    @Override
    public List<WithdrawalRecord> findExpiredPending(OffsetDateTime threshold, int limit) {
        return jdbcTemplate.query(
                """
                SELECT id, customer_id, settled_cash_account_id, settlement_pending_account_id,
                       brokerage_omnibus_account_id, amount, currency, status, pending_since, updated_at
                FROM withdrawal_requests
                WHERE status = 'PENDING'
                  AND pending_since < ?
                ORDER BY pending_since ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> mapRow(rs),
                Timestamp.from(threshold.toInstant()),
                limit
        );
    }

    private WithdrawalRecord mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WithdrawalRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("customer_id"),
                (UUID) rs.getObject("settled_cash_account_id"),
                (UUID) rs.getObject("settlement_pending_account_id"),
                (UUID) rs.getObject("brokerage_omnibus_account_id"),
                rs.getBigDecimal("amount"),
                Currency.valueOf(rs.getString("currency")),
                WithdrawalStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("pending_since").toInstant().atOffset(ZoneOffset.UTC),
                rs.getTimestamp("updated_at").toInstant().atOffset(ZoneOffset.UTC)
        );
    }
}
