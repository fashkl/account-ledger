package com.mohamedali.ledger.platform.jobs.reconciliation;

import com.mohamedali.ledger.ledger.domain.model.Currency;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {

    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationService.class);
    private static final long RECONCILIATION_LOCK_KEY = 777002L;
    private static final long RECONCILIATION_BANK_LOCK_KEY = 777003L;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcReconciliationRepository reconciliationRepository;
    private final BankStatementProvider bankStatementProvider;
    private final MeterRegistry meterRegistry;
    private final int lookbackHours;

    public ReconciliationService(JdbcTemplate jdbcTemplate,
                                 JdbcReconciliationRepository reconciliationRepository,
                                 BankStatementProvider bankStatementProvider,
                                 MeterRegistry meterRegistry,
                                 @Value("${ledger.jobs.reconciliation.lookback-hours:4}") int lookbackHours) {
        this.jdbcTemplate = jdbcTemplate;
        this.reconciliationRepository = reconciliationRepository;
        this.bankStatementProvider = bankStatementProvider;
        this.meterRegistry = meterRegistry;
        this.lookbackHours = lookbackHours;
    }

    @Transactional
    public ReconciliationRunResult runPeriodic() {
        Boolean acquired = jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, RECONCILIATION_LOCK_KEY);
        if (!Boolean.TRUE.equals(acquired)) {
            return new ReconciliationRunResult(null, 0, 0, true);
        }

        UUID runId = reconciliationRepository.createRun(ReconciliationRunType.PERIODIC);
        int mismatches = 0;
        int invariantViolations = 0;
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusHours(lookbackHours);
        try {
            mismatches = detectSnapshotMismatches(runId, since);
            invariantViolations = detectJournalInvariantViolations(runId, since);
            reconciliationRepository.completeRun(runId, mismatches, invariantViolations);
            meterRegistry.counter("reconciliation_runs_total", "status", "done").increment();
            meterRegistry.counter("reconciliation_mismatches_total").increment(mismatches);
            meterRegistry.counter("reconciliation_journal_invariant_violation_total").increment(invariantViolations);
            LOG.info("Reconciliation periodic run completed runId={} mismatches={} invariantViolations={}",
                    runId, mismatches, invariantViolations);
            return new ReconciliationRunResult(runId, mismatches, invariantViolations, false);
        } catch (Exception e) {
            reconciliationRepository.failRun(runId, e.getMessage());
            meterRegistry.counter("reconciliation_runs_total", "status", "failed").increment();
            throw e;
        }
    }

    @Transactional
    public ReconciliationRunResult runDailyBankCheck() {
        Boolean acquired = jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, RECONCILIATION_BANK_LOCK_KEY);
        if (!Boolean.TRUE.equals(acquired)) {
            return new ReconciliationRunResult(null, 0, 0, true);
        }
        UUID runId = reconciliationRepository.createRun(ReconciliationRunType.BANK_DAILY);
        int mismatches = 0;
        try {
            for (Currency currency : Currency.values()) {
                BigDecimal internal = jdbcTemplate.queryForObject(
                        """
                        SELECT COALESCE(SUM(ab.balance), 0)
                        FROM account_balances ab
                        JOIN accounts a ON ab.account_id = a.id
                        WHERE a.type = 'SETTLED_CASH'
                          AND a.currency = ?
                        """,
                        BigDecimal.class,
                        currency.name()
                );
                var bankTotal = bankStatementProvider.totalSettledCash(currency, LocalDate.now(ZoneOffset.UTC));
                if (bankTotal.isEmpty()) {
                    continue;
                }
                if (internal.compareTo(bankTotal.get()) != 0) {
                    mismatches++;
                    reconciliationRepository.insertIssue(
                            runId,
                            ReconciliationIssueType.BANK_STATEMENT_MISMATCH,
                            "HIGH",
                            null,
                            null,
                            Map.of("currency", currency.name(), "internalTotal", internal, "bankTotal", bankTotal.get())
                    );
                }
            }
            reconciliationRepository.completeRun(runId, mismatches, 0);
            meterRegistry.counter("reconciliation_runs_total", "status", "done").increment();
            meterRegistry.counter("reconciliation_mismatches_total").increment(mismatches);
            return new ReconciliationRunResult(runId, mismatches, 0, false);
        } catch (Exception e) {
            reconciliationRepository.failRun(runId, e.getMessage());
            meterRegistry.counter("reconciliation_runs_total", "status", "failed").increment();
            throw e;
        } finally {
            jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, RECONCILIATION_BANK_LOCK_KEY);
        }
    }

    private int detectSnapshotMismatches(UUID runId, OffsetDateTime since) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                WITH impacted_accounts AS (
                    SELECT DISTINCT account_id
                    FROM journal_entries
                    WHERE created_at >= ?
                      AND effective_date >= CURRENT_DATE - INTERVAL '7 days'
                ),
                ledger_totals AS (
                    SELECT
                        je.account_id,
                        COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0) AS ledger_balance
                    FROM journal_entries je
                    JOIN impacted_accounts ia ON ia.account_id = je.account_id
                    GROUP BY je.account_id
                )
                SELECT
                    COALESCE(ab.account_id, lt.account_id) AS account_id,
                    COALESCE(ab.balance, 0) AS snapshot_balance,
                    COALESCE(lt.ledger_balance, 0) AS ledger_balance
                FROM account_balances ab
                FULL OUTER JOIN ledger_totals lt ON ab.account_id = lt.account_id
                JOIN impacted_accounts ia ON ia.account_id = COALESCE(ab.account_id, lt.account_id)
                WHERE COALESCE(ab.balance, 0) <> COALESCE(lt.ledger_balance, 0)
                """,
                Timestamp.from(since.toInstant())
        );
        for (Map<String, Object> row : rows) {
            UUID accountId = (UUID) row.get("account_id");
            reconciliationRepository.insertIssue(
                    runId,
                    ReconciliationIssueType.SNAPSHOT_MISMATCH,
                    "HIGH",
                    accountId,
                    null,
                    Map.of(
                            "snapshotBalance", row.get("snapshot_balance"),
                            "ledgerBalance", row.get("ledger_balance")
                    )
            );
        }
        return rows.size();
    }

    private int detectJournalInvariantViolations(UUID runId, OffsetDateTime since) {
        LocalDate sinceDate = since.toLocalDate();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                WITH impacted_entry_groups AS (
                    SELECT DISTINCT entry_group_id
                    FROM journal_entries
                    WHERE effective_date >= ?
                )
                SELECT je.entry_group_id,
                       je.currency,
                       SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE 0 END) AS total_debit,
                       SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE 0 END) AS total_credit
                FROM journal_entries je
                JOIN impacted_entry_groups ieg ON ieg.entry_group_id = je.entry_group_id
                GROUP BY je.entry_group_id, je.currency
                HAVING SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE 0 END)
                    <> SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE 0 END)
                """,
                sinceDate
        );

        for (Map<String, Object> row : rows) {
            UUID entryGroupId = (UUID) row.get("entry_group_id");
            reconciliationRepository.insertIssue(
                    runId,
                    ReconciliationIssueType.JOURNAL_INVARIANT,
                    "CRITICAL",
                    null,
                    entryGroupId,
                    Map.of(
                            "currency", row.get("currency"),
                            "totalDebit", row.get("total_debit"),
                            "totalCredit", row.get("total_credit")
                    )
            );
        }
        return rows.size();
    }

    public record ReconciliationRunResult(UUID runId,
                                          int mismatchCount,
                                          int invariantViolationCount,
                                          boolean skippedDueToLock) {
    }
}
