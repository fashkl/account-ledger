package com.mohamedali.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mohamedali.ledger.ledger.application.port.in.LedgerBalanceQuery;
import com.mohamedali.ledger.ledger.application.port.in.withdrawal.CashMovementUseCase;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.CashMovementCommand;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.CashMovementEventType;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.WithdrawalStatus;
import com.mohamedali.ledger.platform.jobs.withdrawal.WithdrawalTimeoutJob;
import com.mohamedali.ledger.shared.exception.InvalidCashMovementEventException;
import com.mohamedali.ledger.shared.exception.InsufficientFundsException;
import com.mohamedali.ledger.shared.exception.InvalidWithdrawalStateException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class WithdrawalServiceIntegrationTest {

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
    private CashMovementUseCase cashMovementUseCase;

    @Autowired
    private LedgerBalanceQuery balanceQuery;

    @Autowired
    private WithdrawalTimeoutJob timeoutJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID customerId;
    private UUID settledCashAccountId;
    private UUID settlementPendingAccountId;
    private UUID brokerageOmnibusAccountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE withdrawal_requests, account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");

        customerId = UUID.randomUUID();
        settledCashAccountId = UUID.randomUUID();
        settlementPendingAccountId = UUID.randomUUID();
        brokerageOmnibusAccountId = UUID.randomUUID();

        insertAccount(settledCashAccountId, customerId, "SETTLED_CASH");
        insertAccount(settlementPendingAccountId, customerId, "SETTLEMENT_PENDING");
        insertAccount(brokerageOmnibusAccountId, customerId, "BROKERAGE_OMNIBUS");

        // Pre-fund SETTLED_CASH with 5000 AED via deposit
        cashMovementUseCase.handle(depositCommand(UUID.randomUUID(), new BigDecimal("5000.00")));
    }

    @AfterEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE withdrawal_requests, account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");
    }

    // -----------------------------------------------------------------------
    // 1. Deposit idempotency
    // -----------------------------------------------------------------------

    @Test
    void depositIdempotency_sameEventIdDoesNotDoubleCredit() {
        UUID eventId = UUID.randomUUID();

        // First deposit already happened in setUp with a different eventId.
        // Post a second deposit with the same eventId twice.
        cashMovementUseCase.handle(depositCommand(eventId, new BigDecimal("200.00")));
        cashMovementUseCase.handle(depositCommand(eventId, new BigDecimal("200.00")));

        // Only one 200 AED DEBIT on SETTLED_CASH should have landed on top of the 5000 setUp deposit
        assertThat(balanceQuery.getAccountBalance(settledCashAccountId))
                .isEqualByComparingTo("5200.00");

        Integer journalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE account_id = ?", Integer.class, settledCashAccountId);
        // setUp deposit (1 DEBIT on SETTLED_CASH) + one idempotent deposit (1 DEBIT on SETTLED_CASH) = 2
        assertThat(journalCount).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // 2. Withdrawal happy path: REQUESTED → CONFIRMED
    // -----------------------------------------------------------------------

    @Test
    void withdrawalHappyPath_requestThenConfirm() {
        UUID withdrawalId = UUID.randomUUID();
        UUID callbackId = UUID.randomUUID();

        cashMovementUseCase.handle(withdrawalRequestCommand(withdrawalId, new BigDecimal("1000.00")));

        assertThat(balanceQuery.getAccountBalance(settledCashAccountId)).isEqualByComparingTo("4000.00");
        assertThat(balanceQuery.getAccountBalance(settlementPendingAccountId)).isEqualByComparingTo("1000.00");

        cashMovementUseCase.handle(withdrawalConfirmCommand(callbackId, withdrawalId));

        assertThat(balanceQuery.getAccountBalance(settlementPendingAccountId)).isEqualByComparingTo("0.00");
        assertThat(balanceQuery.getAccountBalance(brokerageOmnibusAccountId)).isEqualByComparingTo("-4000.00");

        String status = jdbcTemplate.queryForObject("SELECT status FROM withdrawal_requests WHERE id = ?", String.class, withdrawalId);
        assertThat(status).isEqualTo("CONFIRMED");
    }

    // -----------------------------------------------------------------------
    // 3. Withdrawal rejection: REQUESTED → REJECTED
    // -----------------------------------------------------------------------

    @Test
    void withdrawalRejection_restoresSettledCash() {
        UUID withdrawalId = UUID.randomUUID();
        UUID callbackId = UUID.randomUUID();

        cashMovementUseCase.handle(withdrawalRequestCommand(withdrawalId, new BigDecimal("800.00")));
        assertThat(balanceQuery.getAccountBalance(settledCashAccountId)).isEqualByComparingTo("4200.00");

        cashMovementUseCase.handle(withdrawalRejectCommand(callbackId, withdrawalId));

        assertThat(balanceQuery.getAccountBalance(settledCashAccountId)).isEqualByComparingTo("5000.00");
        assertThat(balanceQuery.getAccountBalance(settlementPendingAccountId)).isEqualByComparingTo("0.00");

        String status = jdbcTemplate.queryForObject("SELECT status FROM withdrawal_requests WHERE id = ?", String.class, withdrawalId);
        assertThat(status).isEqualTo("REJECTED");
    }

    // -----------------------------------------------------------------------
    // 4. NSF withdrawal
    // -----------------------------------------------------------------------

    @Test
    void nsfWithdrawal_throwsAndWritesNoJournalEntries() {
        Integer journalCountBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class);

        assertThatThrownBy(() ->
                cashMovementUseCase.handle(withdrawalRequestCommand(UUID.randomUUID(), new BigDecimal("9999.00")))
        ).isInstanceOf(InsufficientFundsException.class);

        Integer journalCountAfter = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class);
        assertThat(journalCountAfter).isEqualTo(journalCountBefore);
        assertThat(balanceQuery.getAccountBalance(settledCashAccountId)).isEqualByComparingTo("5000.00");
    }

    @Test
    void duplicateWithdrawalRequestWithDifferentAmount_isRejected() {
        UUID withdrawalId = UUID.randomUUID();
        cashMovementUseCase.handle(withdrawalRequestCommand(withdrawalId, new BigDecimal("300.00")));

        assertThatThrownBy(() ->
                cashMovementUseCase.handle(withdrawalRequestCommand(withdrawalId, new BigDecimal("350.00")))
        ).isInstanceOf(InvalidCashMovementEventException.class)
                .hasMessageContaining("payload mismatch");

        Integer requestedEntries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE event_type = 'WITHDRAWAL_REQUESTED'",
                Integer.class
        );
        assertThat(requestedEntries).isEqualTo(2);
    }

    @Test
    void duplicateWithdrawalRequestWithSamePayload_isIdempotentNoOp() {
        UUID withdrawalId = UUID.randomUUID();
        cashMovementUseCase.handle(withdrawalRequestCommand(withdrawalId, new BigDecimal("300.00")));

        Integer requestedEntriesBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE event_type = 'WITHDRAWAL_REQUESTED'",
                Integer.class
        );
        BigDecimal settledBefore = balanceQuery.getAccountBalance(settledCashAccountId);
        BigDecimal pendingBefore = balanceQuery.getAccountBalance(settlementPendingAccountId);

        cashMovementUseCase.handle(withdrawalRequestCommand(withdrawalId, new BigDecimal("300.00")));

        Integer requestedEntriesAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE event_type = 'WITHDRAWAL_REQUESTED'",
                Integer.class
        );
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM withdrawal_requests WHERE id = ?",
                Integer.class,
                withdrawalId
        );
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM withdrawal_requests WHERE id = ?",
                String.class,
                withdrawalId
        );

        assertThat(requestedEntriesAfter).isEqualTo(requestedEntriesBefore);
        assertThat(balanceQuery.getAccountBalance(settledCashAccountId)).isEqualByComparingTo(settledBefore);
        assertThat(balanceQuery.getAccountBalance(settlementPendingAccountId)).isEqualByComparingTo(pendingBefore);
        assertThat(requestRows).isEqualTo(1);
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void duplicateWithdrawalRequestAfterConfirmed_isRejected() {
        UUID withdrawalId = UUID.randomUUID();
        UUID callbackId = UUID.randomUUID();

        cashMovementUseCase.handle(withdrawalRequestCommand(withdrawalId, new BigDecimal("250.00")));
        cashMovementUseCase.handle(withdrawalConfirmCommand(callbackId, withdrawalId));

        assertThatThrownBy(() ->
                cashMovementUseCase.handle(withdrawalRequestCommand(withdrawalId, new BigDecimal("250.00")))
        ).isInstanceOf(InvalidWithdrawalStateException.class)
                .hasMessageContaining("non-PENDING");
    }

    // -----------------------------------------------------------------------
    // 5. Timeout job: reversal + TIMED_OUT status
    // -----------------------------------------------------------------------

    @Test
    void timeoutJob_reversesExpiredPendingWithdrawals() {
        UUID withdrawalId = UUID.randomUUID();
        cashMovementUseCase.handle(withdrawalRequestCommand(withdrawalId, new BigDecimal("500.00")));

        // Backdate the pending_since to 49 hours ago to simulate expiry
        jdbcTemplate.update(
                "UPDATE withdrawal_requests SET pending_since = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(49 * 3600)),
                withdrawalId
        );

        int processed = timeoutJob.runOnce(10);
        assertThat(processed).isEqualTo(1);

        assertThat(balanceQuery.getAccountBalance(settledCashAccountId)).isEqualByComparingTo("5000.00");
        assertThat(balanceQuery.getAccountBalance(settlementPendingAccountId)).isEqualByComparingTo("0.00");

        String status = jdbcTemplate.queryForObject("SELECT status FROM withdrawal_requests WHERE id = ?", String.class, withdrawalId);
        assertThat(status).isEqualTo("TIMED_OUT");
    }

    // -----------------------------------------------------------------------
    // 6. Timeout job reversal is idempotent
    // -----------------------------------------------------------------------

    @Test
    void timeoutJob_reversalIsIdempotent() {
        UUID withdrawalId = UUID.randomUUID();
        cashMovementUseCase.handle(withdrawalRequestCommand(withdrawalId, new BigDecimal("300.00")));

        jdbcTemplate.update(
                "UPDATE withdrawal_requests SET pending_since = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(49 * 3600)),
                withdrawalId
        );

        timeoutJob.runOnce(10);
        // Second run: withdrawal is now TIMED_OUT so SKIP LOCKED won't pick it up — no-op
        int secondRun = timeoutJob.runOnce(10);
        assertThat(secondRun).isEqualTo(0);

        // Balance must still be fully restored (not double-reverted)
        assertThat(balanceQuery.getAccountBalance(settledCashAccountId)).isEqualByComparingTo("5000.00");
    }

    // -----------------------------------------------------------------------
    // 7. Double bank confirmation is idempotent
    // -----------------------------------------------------------------------

    @Test
    void doubleConfirmation_isIdempotent() {
        UUID withdrawalId = UUID.randomUUID();
        UUID callbackId = UUID.randomUUID();

        cashMovementUseCase.handle(withdrawalRequestCommand(withdrawalId, new BigDecimal("200.00")));
        cashMovementUseCase.handle(withdrawalConfirmCommand(callbackId, withdrawalId));
        // Second confirmation with same callbackId — must be a no-op
        cashMovementUseCase.handle(withdrawalConfirmCommand(callbackId, withdrawalId));

        // Only one set of settlement entries: SETTLEMENT_PENDING DEBIT / OMNIBUS CREDIT
        Integer settlementEntries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE event_type = 'WITHDRAWAL_CONFIRMED'",
                Integer.class
        );
        assertThat(settlementEntries).isEqualTo(2); // one debit + one credit
        assertThat(balanceQuery.getAccountBalance(settlementPendingAccountId)).isEqualByComparingTo("0.00");
    }

    // -----------------------------------------------------------------------
    // 8. Concurrent deposit + withdrawal: SETTLED_CASH never goes negative
    // -----------------------------------------------------------------------

    @Test
    void concurrentDepositAndWithdrawal_settledCashNeverNegative() throws Exception {
        // Start with 5000 AED from setUp. Run 10 deposits of 100 and 10 withdrawals of 100 concurrently.
        ExecutorService executor = Executors.newFixedThreadPool(20);
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            tasks.add(() -> {
                cashMovementUseCase.handle(depositCommand(UUID.randomUUID(), new BigDecimal("100.00")));
                return null;
            });
        }
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> {
                cashMovementUseCase.handle(withdrawalRequestCommand(UUID.randomUUID(), new BigDecimal("100.00")));
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        executor.awaitTermination(20, TimeUnit.SECONDS);
        for (Future<Void> f : futures) {
            f.get();
        }

        BigDecimal settledBalance = balanceQuery.getAccountBalance(settledCashAccountId);
        assertThat(settledBalance.signum()).isGreaterThanOrEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private CashMovementCommand depositCommand(UUID eventId, BigDecimal amount) {
        return new CashMovementCommand(
                CashMovementEventType.VA_CREDITED,
                eventId,
                null, null,
                customerId,
                settledCashAccountId,
                null,
                brokerageOmnibusAccountId,
                amount,
                Currency.AED
        );
    }

    private CashMovementCommand withdrawalRequestCommand(UUID withdrawalId, BigDecimal amount) {
        return new CashMovementCommand(
                CashMovementEventType.WITHDRAWAL_REQUESTED,
                null,
                withdrawalId,
                null,
                customerId,
                settledCashAccountId,
                settlementPendingAccountId,
                brokerageOmnibusAccountId,
                amount,
                Currency.AED
        );
    }

    private CashMovementCommand withdrawalConfirmCommand(UUID callbackId, UUID withdrawalId) {
        return new CashMovementCommand(
                CashMovementEventType.WITHDRAWAL_CONFIRMED,
                null,
                withdrawalId,
                callbackId,
                null, null, null, null, null, null
        );
    }

    private CashMovementCommand withdrawalRejectCommand(UUID callbackId, UUID withdrawalId) {
        return new CashMovementCommand(
                CashMovementEventType.WITHDRAWAL_REJECTED,
                null,
                withdrawalId,
                callbackId,
                null, null, null, null, null, null
        );
    }

    private void insertAccount(UUID accountId, UUID cId, String type) {
        jdbcTemplate.update(
                "INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, ?, ?, ?)",
                accountId, cId, type, "AED", "ACTIVE"
        );
    }
}
