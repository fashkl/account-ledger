package com.mohamedali.ledger.platform.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mohamedali.ledger.platform.jobs.reconciliation.ReconciliationService;
import com.mohamedali.ledger.platform.jobs.settlement.SettlementService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class Phase4SettlementReconciliationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("account_ledger")
            .withUsername("ledger")
            .withPassword("ledger");

    @DynamicPropertySource
    static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private ReconciliationService reconciliationService;

    private UUID customerId;
    private UUID unsettledBuysAccountId;
    private UUID brokerageOmnibusAccountId;
    private UUID settledCashAccountId;
    private UUID reservedCashAccountId;
    private UUID unsettledSalesAccountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_issues, reconciliation_runs, settlement_batches, withdrawal_requests, account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");
        customerId = UUID.randomUUID();
        unsettledBuysAccountId = UUID.randomUUID();
        brokerageOmnibusAccountId = UUID.randomUUID();
        settledCashAccountId = UUID.randomUUID();
        reservedCashAccountId = UUID.randomUUID();
        unsettledSalesAccountId = UUID.randomUUID();

        insertAccount(settledCashAccountId, "SETTLED_CASH");
        insertAccount(reservedCashAccountId, "RESERVED_CASH");
        insertAccount(unsettledBuysAccountId, "UNSETTLED_CASH_BUYS");
        insertAccount(unsettledSalesAccountId, "UNSETTLED_CASH_SALES");
        insertAccount(brokerageOmnibusAccountId, "BROKERAGE_OMNIBUS");

        jdbcTemplate.update("INSERT INTO account_balances(account_id, balance, version, updated_at) VALUES (?, ?, 0, ?)",
                unsettledBuysAccountId, new BigDecimal("100.00"), Timestamp.from(java.time.Instant.now()));
        jdbcTemplate.update("INSERT INTO account_balances(account_id, balance, version, updated_at) VALUES (?, ?, 0, ?)",
                brokerageOmnibusAccountId, BigDecimal.ZERO, Timestamp.from(java.time.Instant.now()));
    }

    @AfterEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_issues, reconciliation_runs, settlement_batches, withdrawal_requests, account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");
    }

    @Test
    void settlementIsIdempotentForSameBatchId() {
        UUID orderRef = UUID.randomUUID();
        LocalDate settlementDate = LocalDate.now(ZoneOffset.UTC);
        LocalDate tradeDate = minusBusinessDays(settlementDate, 2);
        insertOrderState(orderRef, "FILLED", new BigDecimal("100.00"), unsettledBuysAccountId, null);
        insertOrderFillJournal(orderRef, tradeDate, unsettledBuysAccountId, new BigDecimal("100.00"), "fill-1");

        var first = settlementService.run(settlementDate, "settlement-b1");
        var second = settlementService.run(settlementDate, "settlement-b1");

        Integer settlementEntries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE event_type = 'SETTLEMENT_POSTED'",
                Integer.class
        );
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM settlement_batches WHERE batch_id = 'settlement-b1'",
                String.class
        );

        assertThat(first.completed()).isTrue();
        assertThat(second.completed()).isFalse();
        assertThat(settlementEntries).isEqualTo(2);
        assertThat(status).isEqualTo("DONE");
    }

    @Test
    void settlementResumesFromFailedCheckpoint() {
        UUID orderRef = UUID.randomUUID();
        LocalDate settlementDate = LocalDate.now(ZoneOffset.UTC);
        LocalDate tradeDate = minusBusinessDays(settlementDate, 2);
        insertOrderState(orderRef, "FILLED", new BigDecimal("100.00"), unsettledBuysAccountId, null);
        insertOrderFillJournal(orderRef, tradeDate, unsettledBuysAccountId, new BigDecimal("100.00"), "fill-2");

        jdbcTemplate.update(
                "INSERT INTO settlement_batches(batch_id, settlement_date, status, entries_processed, started_at, finished_at, error_message) VALUES (?, ?, 'FAILED', 0, ?, ?, ?)",
                "settlement-resume", settlementDate, Timestamp.from(java.time.Instant.now()), Timestamp.from(java.time.Instant.now()), "simulated failure"
        );

        var resumed = settlementService.run(settlementDate, "settlement-resume");

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM settlement_batches WHERE batch_id = 'settlement-resume'",
                String.class
        );
        assertThat(resumed.completed()).isTrue();
        assertThat(status).isEqualTo("DONE");
    }

    @Test
    void settlementSkipsNonTerminalOrdersAndCreatesIssue() {
        UUID orderRef = UUID.randomUUID();
        LocalDate settlementDate = LocalDate.now(ZoneOffset.UTC);
        LocalDate tradeDate = minusBusinessDays(settlementDate, 2);
        insertOrderState(orderRef, "PARTIALLY_FILLED", new BigDecimal("10.00"), unsettledBuysAccountId, null);
        insertOrderFillJournal(orderRef, tradeDate, unsettledBuysAccountId, new BigDecimal("10.00"), "fill-3");

        settlementService.run(settlementDate, "settlement-skip");

        Integer issueCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_issues WHERE issue_type = 'SETTLEMENT_SKIPPED' AND reference_id = ?",
                Integer.class,
                orderRef
        );
        assertThat(issueCount).isEqualTo(1);
    }

    @Test
    void reconciliationDetectsSnapshotMismatch() {
        LocalDate settlementDate = LocalDate.now(ZoneOffset.UTC);
        LocalDate tradeDate = minusBusinessDays(settlementDate, 2);
        UUID orderRef = UUID.randomUUID();
        insertOrderState(orderRef, "FILLED", new BigDecimal("100.00"), unsettledBuysAccountId, null);
        insertOrderFillJournal(orderRef, tradeDate, unsettledBuysAccountId, new BigDecimal("100.00"), "fill-4");
        settlementService.run(settlementDate, "settlement-mismatch");

        jdbcTemplate.update(
                "UPDATE account_balances SET balance = ? WHERE account_id = ?",
                new BigDecimal("999.00"), unsettledBuysAccountId
        );

        var result = reconciliationService.runPeriodic();

        Integer issues = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_issues WHERE issue_type = 'SNAPSHOT_MISMATCH'",
                Integer.class
        );
        assertThat(result.mismatchCount()).isGreaterThan(0);
        assertThat(issues).isGreaterThan(0);
    }

    @Test
    void reconciliationDetectsJournalInvariantViolation() {
        UUID entryGroupId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO journal_entries(id, entry_group_id, account_id, direction, amount, currency, event_type, reference_id, effective_date, idempotency_key)
                VALUES (gen_random_uuid(), ?, ?, 'DEBIT', 100.00, 'AED', 'BROKEN', ?, current_date, ?)
                """,
                entryGroupId,
                settledCashAccountId,
                referenceId,
                "broken-1"
        );
        jdbcTemplate.update(
                """
                INSERT INTO journal_entries(id, entry_group_id, account_id, direction, amount, currency, event_type, reference_id, effective_date, idempotency_key)
                VALUES (gen_random_uuid(), ?, ?, 'CREDIT', 90.00, 'AED', 'BROKEN', ?, current_date, ?)
                """,
                entryGroupId,
                reservedCashAccountId,
                referenceId,
                "broken-2"
        );

        var result = reconciliationService.runPeriodic();
        Integer issues = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_issues WHERE issue_type = 'JOURNAL_INVARIANT'",
                Integer.class
        );

        assertThat(result.invariantViolationCount()).isGreaterThan(0);
        assertThat(issues).isGreaterThan(0);
    }

    private void insertAccount(UUID id, String type) {
        jdbcTemplate.update(
                "INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, ?, 'AED', 'ACTIVE')",
                id, customerId, type
        );
    }

    @Test
    void settlementUsesUnsettledCashSalesWhenSellFill() {
        UUID orderRef = UUID.randomUUID();
        LocalDate settlementDate = LocalDate.now(ZoneOffset.UTC);
        LocalDate tradeDate = minusBusinessDays(settlementDate, 2);
        insertOrderState(orderRef, "FILLED", new BigDecimal("70.00"), unsettledBuysAccountId, unsettledSalesAccountId);
        insertOrderFillJournal(orderRef, tradeDate, unsettledSalesAccountId, new BigDecimal("70.00"), "fill-sell-1");
        jdbcTemplate.update(
                """
                INSERT INTO account_balances(account_id, balance, version)
                VALUES (?, ?, 0)
                ON CONFLICT (account_id) DO UPDATE SET balance = EXCLUDED.balance
                """,
                unsettledSalesAccountId, new BigDecimal("70.00")
        );

        settlementService.run(settlementDate, "settlement-sell");

        BigDecimal salesBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM account_balances WHERE account_id = ?",
                BigDecimal.class,
                unsettledSalesAccountId
        );
        assertThat(salesBalance).isEqualByComparingTo("0.00");
    }

    @Test
    void reconciliationFailurePersistsFailedRunRow() {
        jdbcTemplate.execute("ALTER TABLE account_balances RENAME TO account_balances_bak");
        try {
            assertThatThrownBy(() -> reconciliationService.runPeriodic()).isInstanceOf(Exception.class);
        } finally {
            jdbcTemplate.execute("ALTER TABLE account_balances_bak RENAME TO account_balances");
        }

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM reconciliation_runs WHERE run_type = 'PERIODIC' ORDER BY started_at DESC LIMIT 1",
                String.class
        );
        assertThat(status).isEqualTo("FAILED");
    }

    @Test
    void settlementFailureMarksSettlementRunFailed() {
        UUID orderRef = UUID.randomUUID();
        LocalDate settlementDate = LocalDate.now(ZoneOffset.UTC);
        LocalDate tradeDate = minusBusinessDays(settlementDate, 2);
        insertOrderState(orderRef, "FILLED", new BigDecimal("250.00"), unsettledBuysAccountId, null);
        insertOrderFillJournal(orderRef, tradeDate, unsettledBuysAccountId, new BigDecimal("250.00"), "fill-fail-1");

        assertThatThrownBy(() -> settlementService.run(settlementDate, "settlement-fail-1")).isInstanceOf(Exception.class);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM reconciliation_runs WHERE run_type = 'SETTLEMENT' ORDER BY started_at DESC LIMIT 1",
                String.class
        );
        assertThat(status).isEqualTo("FAILED");
    }

    @Test
    void snapshotMismatchDeduplicatesWhenMismatchAmountChanges() {
        LocalDate settlementDate = LocalDate.now(ZoneOffset.UTC);
        LocalDate tradeDate = minusBusinessDays(settlementDate, 2);
        UUID orderRef = UUID.randomUUID();
        insertOrderState(orderRef, "FILLED", new BigDecimal("100.00"), unsettledBuysAccountId, null);
        insertOrderFillJournal(orderRef, tradeDate, unsettledBuysAccountId, new BigDecimal("100.00"), "fill-dedup-1");
        settlementService.run(settlementDate, "settlement-dedup-1");

        jdbcTemplate.update("UPDATE account_balances SET balance = ? WHERE account_id = ?", new BigDecimal("700.00"), unsettledBuysAccountId);
        reconciliationService.runPeriodic();

        insertOrderFillJournal(UUID.randomUUID(), tradeDate, unsettledBuysAccountId, new BigDecimal("1.00"), "fill-dedup-2");
        jdbcTemplate.update("UPDATE account_balances SET balance = ? WHERE account_id = ?", new BigDecimal("900.00"), unsettledBuysAccountId);
        reconciliationService.runPeriodic();

        Integer issueRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_issues WHERE issue_type='SNAPSHOT_MISMATCH' AND account_id = ?",
                Integer.class,
                unsettledBuysAccountId
        );
        Integer occurrences = jdbcTemplate.queryForObject(
                "SELECT occurrence_count FROM reconciliation_issues WHERE issue_type='SNAPSHOT_MISMATCH' AND account_id = ?",
                Integer.class,
                unsettledBuysAccountId
        );
        assertThat(issueRows).isEqualTo(1);
        assertThat(occurrences).isGreaterThanOrEqualTo(2);
    }

    @Test
    void journalInvariantDeduplicatesAcrossReruns() {
        UUID entryGroupId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO journal_entries(id, entry_group_id, account_id, direction, amount, currency, event_type, reference_id, effective_date, idempotency_key)
                VALUES (gen_random_uuid(), ?, ?, 'DEBIT', 40.00, 'AED', 'BROKEN', ?, current_date, ?)
                """,
                entryGroupId, settledCashAccountId, referenceId, "broken-dedup-1"
        );
        jdbcTemplate.update(
                """
                INSERT INTO journal_entries(id, entry_group_id, account_id, direction, amount, currency, event_type, reference_id, effective_date, idempotency_key)
                VALUES (gen_random_uuid(), ?, ?, 'CREDIT', 35.00, 'AED', 'BROKEN', ?, current_date, ?)
                """,
                entryGroupId, reservedCashAccountId, referenceId, "broken-dedup-2"
        );

        reconciliationService.runPeriodic();
        reconciliationService.runPeriodic();

        Integer issueRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_issues WHERE issue_type='JOURNAL_INVARIANT' AND reference_id = ?",
                Integer.class,
                entryGroupId
        );
        Integer occurrences = jdbcTemplate.queryForObject(
                "SELECT occurrence_count FROM reconciliation_issues WHERE issue_type='JOURNAL_INVARIANT' AND reference_id = ?",
                Integer.class,
                entryGroupId
        );
        assertThat(issueRows).isEqualTo(1);
        assertThat(occurrences).isGreaterThanOrEqualTo(2);
    }

    @Test
    void snapshotMismatchSkipsAccountsWithoutRecentActivity() {
        UUID quietAccountId = UUID.randomUUID();
        UUID otherCustomer = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, ?, 'AED', 'ACTIVE')",
                quietAccountId, otherCustomer, "SETTLED_CASH"
        );
        jdbcTemplate.update("INSERT INTO account_balances(account_id, balance, version, updated_at) VALUES (?, ?, 0, ?)",
                quietAccountId, new BigDecimal("999.00"), Timestamp.from(java.time.Instant.now()));

        reconciliationService.runPeriodic();

        Integer issueRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_issues WHERE issue_type='SNAPSHOT_MISMATCH' AND account_id = ?",
                Integer.class,
                quietAccountId
        );
        assertThat(issueRows).isEqualTo(0);
    }

    @Test
    void journalInvariantUsesEffectiveDateWindow() {
        UUID entryGroupId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        LocalDate oldDate = LocalDate.now(ZoneOffset.UTC).minusDays(30);
        Timestamp nowTs = Timestamp.from(java.time.Instant.now());

        jdbcTemplate.update(
                """
                INSERT INTO journal_entries(id, entry_group_id, account_id, direction, amount, currency, event_type, reference_id, effective_date, created_at, idempotency_key)
                VALUES (gen_random_uuid(), ?, ?, 'DEBIT', 80.00, 'AED', 'BROKEN_OLD', ?, ?, ?, ?)
                """,
                entryGroupId, settledCashAccountId, referenceId, oldDate, nowTs, "old-broken-1"
        );
        jdbcTemplate.update(
                """
                INSERT INTO journal_entries(id, entry_group_id, account_id, direction, amount, currency, event_type, reference_id, effective_date, created_at, idempotency_key)
                VALUES (gen_random_uuid(), ?, ?, 'CREDIT', 60.00, 'AED', 'BROKEN_OLD', ?, ?, ?, ?)
                """,
                entryGroupId, reservedCashAccountId, referenceId, oldDate, nowTs, "old-broken-2"
        );

        reconciliationService.runPeriodic();

        Integer issueRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_issues WHERE issue_type='JOURNAL_INVARIANT' AND reference_id = ?",
                Integer.class,
                entryGroupId
        );
        assertThat(issueRows).isEqualTo(0);
    }

    private void insertOrderState(UUID referenceId, String state, BigDecimal filledAmount,
                                  UUID unsettledBuysAccountIdParam, UUID unsettledSalesAccountIdParam) {
        jdbcTemplate.update(
                """
                INSERT INTO order_states(
                    reference_id, customer_id, settled_cash_account_id, reserved_cash_account_id,
                    unsettled_cash_buys_account_id, unsettled_cash_sales_account_id,
                    state, held_amount, filled_amount, currency, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'AED', ?)
                """,
                referenceId,
                customerId,
                settledCashAccountId,
                reservedCashAccountId,
                unsettledBuysAccountIdParam,
                unsettledSalesAccountIdParam,
                state,
                filledAmount,
                filledAmount,
                Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).toInstant())
        );
    }

    private void insertOrderFillJournal(UUID referenceId,
                                        LocalDate tradeDate,
                                        UUID unsettledAccountId,
                                        BigDecimal amount,
                                        String keySuffix) {
        UUID entryGroupId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO journal_entries(id, entry_group_id, account_id, direction, amount, currency, event_type, reference_id, effective_date, idempotency_key)
                VALUES (gen_random_uuid(), ?, ?, 'DEBIT', ?, 'AED', 'ORDER_FILL', ?, ?, ?)
                """,
                entryGroupId, unsettledAccountId, amount, referenceId, tradeDate, "fill-debit-" + keySuffix
        );
        jdbcTemplate.update(
                """
                INSERT INTO journal_entries(id, entry_group_id, account_id, direction, amount, currency, event_type, reference_id, effective_date, idempotency_key)
                VALUES (gen_random_uuid(), ?, ?, 'CREDIT', ?, 'AED', 'ORDER_FILL', ?, ?, ?)
                """,
                entryGroupId, reservedCashAccountId, amount, referenceId, tradeDate, "fill-credit-" + keySuffix
        );
    }

    private LocalDate minusBusinessDays(LocalDate value, int businessDays) {
        LocalDate cursor = value;
        int remaining = businessDays;
        while (remaining > 0) {
            cursor = cursor.minusDays(1);
            switch (cursor.getDayOfWeek()) {
                case SATURDAY, SUNDAY -> {
                }
                default -> remaining--;
            }
        }
        return cursor;
    }
}
