package com.mohamedali.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mohamedali.ledger.ledger.application.port.in.LedgerBalanceQuery;
import com.mohamedali.ledger.ledger.application.port.in.LedgerPostingUseCase;
import com.mohamedali.ledger.ledger.application.port.in.order.OrderLifecycleUseCase;
import com.mohamedali.ledger.ledger.domain.exception.InvalidPostingStructureException;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.ledger.domain.model.EntryDirection;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesCommand;
import com.mohamedali.ledger.ledger.domain.model.PostingLeg;
import com.mohamedali.ledger.ledger.domain.model.order.OrderEventType;
import com.mohamedali.ledger.ledger.domain.model.order.OrderLifecycleCommand;
import com.mohamedali.ledger.shared.exception.InsufficientFundsException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Chaos tests: validates system invariants under adversarial conditions.
 * <p>
 * Scenarios:
 * 1. 100 concurrent threads posting with the same idempotency key — exactly 1 posting committed
 * 2. Future effective-date rejected before any DB write
 * 3. NSF guard holds under concurrent overdraft attempts — balance never goes negative
 * <p>
 * Run via: ./gradlew chaosTest
 */
@SpringBootTest
@Testcontainers
@Tag("chaos")
class ChaosIntegrationTest {

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
    private LedgerPostingUseCase postingUseCase;

    @Autowired
    private OrderLifecycleUseCase lifecycleUseCase;

    @Autowired
    private LedgerBalanceQuery balanceQuery;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID customerId;
    private UUID settledCash;
    private UUID reservedCash;
    private UUID unsettledCashBuys;
    private UUID omnibus;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_issues, reconciliation_runs, settlement_batches, withdrawal_requests, account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");
        customerId = UUID.randomUUID();
        settledCash = UUID.randomUUID();
        reservedCash = UUID.randomUUID();
        unsettledCashBuys = UUID.randomUUID();
        omnibus = UUID.randomUUID();

        jdbcTemplate.update("INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, 'SETTLED_CASH', 'AED', 'ACTIVE')", settledCash, customerId);
        jdbcTemplate.update("INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, 'RESERVED_CASH', 'AED', 'ACTIVE')", reservedCash, customerId);
        jdbcTemplate.update("INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, 'UNSETTLED_CASH_BUYS', 'AED', 'ACTIVE')", unsettledCashBuys, customerId);
        jdbcTemplate.update("INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, 'BROKERAGE_OMNIBUS', 'AED', 'ACTIVE')", omnibus, customerId);
    }

    @AfterEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_issues, reconciliation_runs, settlement_batches, withdrawal_requests, account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");
    }

    /**
     * Scenario 1: 100 concurrent threads posting the same idempotency key.
     * <p>
     * Invariant: exactly 1 ledger_posting row and 2 journal_entry rows are written,
     * regardless of concurrency. No phantom duplicates or partial states.
     */
    @Test
    void concurrentDuplicatePostings_exactlyOneCommitted() throws Exception {
        String idempotencyKey = "chaos-dedup-" + UUID.randomUUID();
        seed(new BigDecimal("5000.00"));

        PostLedgerEntriesCommand cmd = new PostLedgerEntriesCommand(
                idempotencyKey,
                "VA_CREDITED",
                customerId,
                LocalDate.now(),
                List.of(
                        new PostingLeg(settledCash, EntryDirection.DEBIT, new BigDecimal("100.00"), Currency.AED),
                        new PostingLeg(omnibus, EntryDirection.CREDIT, new BigDecimal("100.00"), Currency.AED)
                )
        );

        int threads = 100;
        AtomicInteger unexpectedErrors = new AtomicInteger(0);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    try {
                        postingUseCase.post(cmd);
                    } catch (Exception ex) {
                        // IdempotencyKeyCollisionException or similar is expected — count unexpected ones only
                        String name = ex.getClass().getSimpleName();
                        if (!name.contains("Idempotency") && !name.contains("Duplicate")) {
                            unexpectedErrors.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(unexpectedErrors.get())
                .as("Only idempotency-related exceptions are acceptable under concurrent duplicate posting")
                .isZero();

        Integer postings = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_postings WHERE idempotency_key = ?", Integer.class, idempotencyKey);
        Integer journalRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE idempotency_key = ?", Integer.class, idempotencyKey);

        assertThat(postings)
                .as("Exactly 1 ledger_posting row must exist for the idempotency key")
                .isEqualTo(1);
        assertThat(journalRows)
                .as("Exactly 2 journal_entry rows must exist (1 DEBIT + 1 CREDIT leg)")
                .isEqualTo(2);

        BigDecimal balance = balanceQuery.getAccountBalance(settledCash);
        assertThat(balance)
                .as("Balance reflects exactly one posting, not 100 duplicates")
                .isEqualByComparingTo("5100.00");
    }

    /**
     * Scenario 2: posting with effectiveDate in the future is rejected before any DB write.
     * <p>
     * Invariant: zero rows written to ledger_postings or journal_entries.
     */
    @Test
    void futureEffectiveDate_rejectedBeforeAnyDbWrite() {
        PostLedgerEntriesCommand cmd = new PostLedgerEntriesCommand(
                "future-date-" + UUID.randomUUID(),
                "VA_CREDITED",
                customerId,
                LocalDate.now().plusDays(1),
                List.of(
                        new PostingLeg(settledCash, EntryDirection.DEBIT, new BigDecimal("50.00"), Currency.AED),
                        new PostingLeg(omnibus, EntryDirection.CREDIT, new BigDecimal("50.00"), Currency.AED)
                )
        );

        assertThatThrownBy(() -> postingUseCase.post(cmd))
                .isInstanceOf(InvalidPostingStructureException.class)
                .hasMessageContaining("future");

        Integer postings = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_postings", Integer.class);
        Integer journalRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class);

        assertThat(postings).as("No ledger_posting rows written for rejected future-dated command").isZero();
        assertThat(journalRows).as("No journal_entry rows written for rejected future-dated command").isZero();
    }

    /**
     * Scenario 3: 10 concurrent threads each attempt an ORDER_CREATED hold of 600 AED
     * against a SETTLED_CASH balance of 1000 AED.
     * <p>
     * Invariant: at most 1 succeeds (600 ≤ 1000 but 600+600 > 1000), at least 9 fail with
     * InsufficientFundsException. Final balance must never go below 0.
     */
    @Test
    void concurrentOverdraftAttempts_nsfGuardHolds() throws Exception {
        seed(new BigDecimal("1000.00"));

        int threads = 10;
        BigDecimal holdAmount = new BigDecimal("600.00");

        AtomicInteger nsfCount = new AtomicInteger(0);
        AtomicInteger unexpectedErrors = new AtomicInteger(0);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                UUID orderId = UUID.randomUUID();
                tasks.add(() -> {
                    try {
                        lifecycleUseCase.handle(new OrderLifecycleCommand(
                                orderId, OrderEventType.ORDER_CREATED, customerId,
                                settledCash, reservedCash, unsettledCashBuys,
                                Currency.AED, holdAmount, null, null, null));
                    } catch (InsufficientFundsException ex) {
                        nsfCount.incrementAndGet();
                    } catch (Exception ex) {
                        unexpectedErrors.incrementAndGet();
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = executor.invokeAll(tasks);
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);
            for (Future<Void> f : futures) {
                f.get();
            }
        }

        assertThat(unexpectedErrors.get())
                .as("No unexpected exceptions — only InsufficientFundsException is acceptable")
                .isZero();

        // With 1000 balance and 600 hold per order: at most 1 order succeeds
        assertThat(nsfCount.get())
                .as("At least 9 of 10 concurrent overdraft attempts must fail with InsufficientFundsException")
                .isGreaterThanOrEqualTo(9);

        BigDecimal settled = balanceQuery.getAccountBalance(settledCash);
        BigDecimal reserved = balanceQuery.getAccountBalance(reservedCash);

        assertThat(settled.signum())
                .as("SETTLED_CASH balance must never go negative")
                .isGreaterThanOrEqualTo(0);
        assertThat(reserved.signum())
                .as("RESERVED_CASH balance must never go negative")
                .isGreaterThanOrEqualTo(0);

        // Accounting identity: settled + reserved == initial deposit (1000)
        assertThat(settled.add(reserved))
                .as("settled + reserved must equal initial deposit — no money created or destroyed")
                .isEqualByComparingTo("1000.00");
    }

    private void seed(BigDecimal amount) {
        postingUseCase.post(new PostLedgerEntriesCommand(
                "chaos-seed-" + UUID.randomUUID(),
                "VA_CREDITED",
                customerId,
                LocalDate.now(),
                List.of(
                        new PostingLeg(settledCash, EntryDirection.DEBIT, amount, Currency.AED),
                        new PostingLeg(omnibus, EntryDirection.CREDIT, amount, Currency.AED)
                )
        ));
    }
}
