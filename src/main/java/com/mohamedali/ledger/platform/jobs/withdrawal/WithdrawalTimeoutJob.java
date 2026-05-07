package com.mohamedali.ledger.platform.jobs.withdrawal;

import com.mohamedali.ledger.ledger.application.port.out.withdrawal.WithdrawalPersistencePort;
import com.mohamedali.ledger.ledger.application.usecase.withdrawal.CashMovementService;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.WithdrawalRecord;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Hourly job that finds PENDING withdrawals older than 48 hours and posts a
 * reversal entry (SETTLED_CASH DEBIT / SETTLEMENT_PENDING CREDIT), then marks them TIMED_OUT.
 *
 * <p>Uses {@code pg_try_advisory_lock(12345)} (session-level) for single-pod execution.
 * Each withdrawal is processed in its own {@code @Transactional} call on
 * {@link CashMovementService#applyTimeout} so a failure on one row does not
 * abort the entire batch.
 *
 * <p>The reversal posting key {@code WITHDRAWAL_TIMEOUT-{withdrawalId}} is idempotent —
 * a job crash and retry will not double-post.
 */
@Component
public class WithdrawalTimeoutJob {

    private static final Logger LOG = LoggerFactory.getLogger(WithdrawalTimeoutJob.class);
    private static final long LOCK_KEY = 12345L;
    private static final int DEFAULT_BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final WithdrawalPersistencePort withdrawalPort;
    private final CashMovementService cashMovementService;

    public WithdrawalTimeoutJob(JdbcTemplate jdbcTemplate,
                                WithdrawalPersistencePort withdrawalPort,
                                CashMovementService cashMovementService) {
        this.jdbcTemplate = jdbcTemplate;
        this.withdrawalPort = withdrawalPort;
        this.cashMovementService = cashMovementService;
    }

    public int runOnce(int limit) {
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
        if (!Boolean.TRUE.equals(acquired)) {
            LOG.info("WithdrawalTimeoutJob skipped — lock held by another instance");
            return 0;
        }

        try {
            OffsetDateTime threshold = OffsetDateTime.now(ZoneOffset.UTC).minusHours(48);
            List<WithdrawalRecord> expired = withdrawalPort.findExpiredPending(threshold, limit);

            int processed = 0;
            for (WithdrawalRecord withdrawal : expired) {
                try {
                    cashMovementService.applyTimeout(withdrawal);
                    LOG.info("WithdrawalTimeoutJob: timed out withdrawal {}", withdrawal.id());
                    processed++;
                } catch (Exception e) {
                    // Log and continue — failure on one row must not abort the batch.
                    // The withdrawal stays PENDING and will be retried on the next run.
                    LOG.error("WithdrawalTimeoutJob: failed to process withdrawal {}: {}",
                            withdrawal.id(), e.getMessage(), e);
                }
            }
            return processed;
        } finally {
            jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
        }
    }

    public int runOnce() {
        return runOnce(DEFAULT_BATCH_SIZE);
    }
}
