package com.mohamedali.ledger.platform.jobs;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SnapshotRebuildService {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotRebuildService.class);
    private static final long SNAPSHOT_REBUILD_LOCK_KEY = 987654321L;

    private final JdbcTemplate jdbcTemplate;

    public SnapshotRebuildService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SnapshotRebuildResult rebuildAll() {
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)",
                Boolean.class,
                SNAPSHOT_REBUILD_LOCK_KEY
        );
        if (!Boolean.TRUE.equals(acquired)) {
            return new SnapshotRebuildResult(0, 0, false, true);
        }

        int rebuiltRows = buildStagingTable();

        Integer mismatchCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM (
                    SELECT
                        COALESCE(a.account_id, r.account_id) AS account_id,
                        COALESCE(a.balance, 0) AS current_balance,
                        COALESCE(r.balance, 0) AS rebuilt_balance
                    FROM account_balances a
                    FULL OUTER JOIN account_balances_rebuild r ON a.account_id = r.account_id
                ) diff
                WHERE current_balance <> rebuilt_balance
                """,
                Integer.class
        );

        int mismatches = mismatchCount == null ? 0 : mismatchCount;
        if (mismatches > 0) {
            List<Map<String, Object>> sample = jdbcTemplate.queryForList(
                    """
                    SELECT
                        COALESCE(a.account_id, r.account_id) AS account_id,
                        COALESCE(a.balance, 0) AS current_balance,
                        COALESCE(r.balance, 0) AS rebuilt_balance
                    FROM account_balances a
                    FULL OUTER JOIN account_balances_rebuild r ON a.account_id = r.account_id
                    WHERE COALESCE(a.balance, 0) <> COALESCE(r.balance, 0)
                    LIMIT 100
                    """
            );
            for (Map<String, Object> row : sample) {
                LOG.warn(
                        "Snapshot mismatch accountId={} currentBalance={} rebuiltBalance={}",
                        row.get("account_id"),
                        row.get("current_balance"),
                        row.get("rebuilt_balance")
                );
            }
            return new SnapshotRebuildResult(rebuiltRows, mismatches, false, false);
        }

        forceSwapFromStaging();
        return new SnapshotRebuildResult(rebuiltRows, 0, true, false);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SnapshotRebuildResult forceRebuild() {
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)",
                Boolean.class,
                SNAPSHOT_REBUILD_LOCK_KEY
        );
        if (!Boolean.TRUE.equals(acquired)) {
            return new SnapshotRebuildResult(0, 0, false, true);
        }

        int rebuiltRows = buildStagingTable();

        forceSwapFromStaging();
        return new SnapshotRebuildResult(rebuiltRows, 0, true, false);
    }

    private void forceSwapFromStaging() {
        jdbcTemplate.update("TRUNCATE TABLE account_balances");
        jdbcTemplate.update(
                """
                INSERT INTO account_balances(account_id, balance, version, last_entry_id, updated_at)
                SELECT account_id, balance, version, last_entry_id, updated_at
                FROM account_balances_rebuild
                """
        );
        jdbcTemplate.execute("DROP TABLE account_balances_rebuild");
    }

    private int buildStagingTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS account_balances_rebuild");
        jdbcTemplate.execute(
                """
                CREATE TABLE account_balances_rebuild (
                    account_id UUID PRIMARY KEY,
                    balance NUMERIC(20, 8) NOT NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    last_entry_id UUID,
                    updated_at TIMESTAMPTZ NOT NULL
                )
                """
        );

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT account_id,
                       COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0) AS balance
                FROM journal_entries
                GROUP BY account_id
                """
        );

        int rebuiltRows = 0;
        for (Map<String, Object> row : rows) {
            jdbcTemplate.update(
                    "INSERT INTO account_balances_rebuild(account_id, balance, version, last_entry_id, updated_at) VALUES (?, ?, 0, NULL, ?)",
                    row.get("account_id"),
                    row.get("balance"),
                    Timestamp.from(java.time.Instant.now())
            );
            rebuiltRows++;
        }
        return rebuiltRows;
    }

    public record SnapshotRebuildResult(int rebuiltRows, int mismatchCount, boolean swapped, boolean skippedDueToLock) {
    }
}
