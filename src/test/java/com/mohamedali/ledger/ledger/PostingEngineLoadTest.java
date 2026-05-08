package com.mohamedali.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.mohamedali.ledger.ledger.application.port.in.LedgerBalanceQuery;
import com.mohamedali.ledger.ledger.application.port.in.LedgerPostingUseCase;
import com.mohamedali.ledger.ledger.application.port.in.order.OrderLifecycleUseCase;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.ledger.domain.model.EntryDirection;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesCommand;
import com.mohamedali.ledger.ledger.domain.model.PostingLeg;
import com.mohamedali.ledger.ledger.domain.model.order.OrderEventType;
import com.mohamedali.ledger.ledger.domain.model.order.OrderLifecycleCommand;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Load test: validates the posting engine can sustain 500 order-lifecycle operations/sec
 * with p99 posting latency under 50ms.
 * <p>
 * Not included in the standard test suite. Run via:
 * ./gradlew loadTest
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("load")
class PostingEngineLoadTest {

    private static final Logger LOG = LoggerFactory.getLogger(PostingEngineLoadTest.class);

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
    private OrderLifecycleUseCase lifecycleUseCase;

    @Autowired
    private LedgerPostingUseCase postingUseCase;

    @Autowired
    private LedgerBalanceQuery balanceQuery;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 10 customers × 50 orders = 500 order lifecycles (1,500 individual posting operations)
    private static final int CUSTOMERS = 10;
    private static final int ORDERS_PER_CUSTOMER = 50;
    private static final BigDecimal INITIAL_DEPOSIT = new BigDecimal("100000.00");
    private static final BigDecimal HOLD_PER_ORDER = new BigDecimal("200.00");
    private static final BigDecimal FILL_PER_ORDER = new BigDecimal("100.00");
    private static final long P99_THRESHOLD_MS = 50L;

    record CustomerFixture(
            UUID customerId,
            UUID settledCash,
            UUID reservedCash,
            UUID unsettledCashBuys,
            UUID omnibus
    ) {
    }

    private final List<CustomerFixture> customers = new ArrayList<>();

    @BeforeAll
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_issues, reconciliation_runs, settlement_batches, withdrawal_requests, account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");
        for (int i = 0; i < CUSTOMERS; i++) {
            customers.add(createCustomerFixture());
        }
        LOG.info("Load test setup: {} customers, {} orders each = {} total order lifecycles",
                CUSTOMERS, ORDERS_PER_CUSTOMER, CUSTOMERS * ORDERS_PER_CUSTOMER);
    }

    @AfterAll
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE reconciliation_issues, reconciliation_runs, settlement_batches, withdrawal_requests, account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");
    }

    @Test
    void postingEngineHandles500OrderLifecyclesWithP99Under50ms() throws Exception {
        List<Long> latenciesNs = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger(0);

        long testStart = System.nanoTime();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();

            for (CustomerFixture cf : customers) {
                for (int i = 0; i < ORDERS_PER_CUSTOMER; i++) {
                    UUID orderId = UUID.randomUUID();
                    futures.add(executor.submit(() -> {
                        try {
                            runOrderLifecycle(cf, orderId, latenciesNs);
                        } catch (Exception ex) {
                            errors.incrementAndGet();
                            LOG.error("Order lifecycle failed orderId={} error={}", orderId, ex.getMessage(), ex);
                        }
                    }));
                }
            }

            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        }

        long totalMs = (System.nanoTime() - testStart) / 1_000_000;
        int totalOps = latenciesNs.size();
        double opsPerSec = totalMs > 0 ? (double) totalOps / (totalMs / 1000.0) : 0;

        assertThat(errors.get())
                .as("No unexpected errors during load test")
                .isZero();

        // p99 latency assertion
        List<Long> sorted = latenciesNs.stream().sorted().toList();
        long p99Ns = sorted.get((int) (sorted.size() * 0.99));
        long p99Ms = p99Ns / 1_000_000;
        LOG.info("Load test complete: {} ops in {}ms ({:.1f} ops/sec), p99={}ms",
                totalOps, totalMs, opsPerSec, p99Ms);

        assertThat(p99Ms)
                .as("p99 posting latency must be under %dms (actual: %dms)", P99_THRESHOLD_MS, p99Ms)
                .isLessThanOrEqualTo(P99_THRESHOLD_MS);

        // Balance invariants: each lifecycle holds 200, fills 100, cancels 100 remainder
        // Net per order: SETTLED_CASH -100, UNSETTLED_CASH_BUYS +100, RESERVED_CASH 0
        BigDecimal expectedSettled = INITIAL_DEPOSIT
                .subtract(FILL_PER_ORDER.multiply(new BigDecimal(ORDERS_PER_CUSTOMER)));
        BigDecimal expectedUnsettled = FILL_PER_ORDER.multiply(new BigDecimal(ORDERS_PER_CUSTOMER));

        for (CustomerFixture cf : customers) {
            BigDecimal settled = balanceQuery.getAccountBalance(cf.settledCash());
            BigDecimal reserved = balanceQuery.getAccountBalance(cf.reservedCash());
            BigDecimal unsettled = balanceQuery.getAccountBalance(cf.unsettledCashBuys());

            assertThat(settled)
                    .as("SETTLED_CASH balance after load test")
                    .isEqualByComparingTo(expectedSettled);
            assertThat(reserved)
                    .as("RESERVED_CASH must be 0 — all orders complete")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(unsettled)
                    .as("UNSETTLED_CASH_BUYS balance after load test")
                    .isEqualByComparingTo(expectedUnsettled);
        }
    }

    private void runOrderLifecycle(CustomerFixture cf, UUID orderId, List<Long> latenciesNs) {
        long t = System.nanoTime();
        lifecycleUseCase.handle(new OrderLifecycleCommand(
                orderId, OrderEventType.ORDER_CREATED, cf.customerId(),
                cf.settledCash(), cf.reservedCash(), cf.unsettledCashBuys(),
                Currency.AED, HOLD_PER_ORDER, null, null, null));
        latenciesNs.add(System.nanoTime() - t);

        UUID fillId = UUID.randomUUID();
        t = System.nanoTime();
        lifecycleUseCase.handle(new OrderLifecycleCommand(
                orderId, OrderEventType.ORDER_FILL, cf.customerId(),
                cf.settledCash(), cf.reservedCash(), cf.unsettledCashBuys(),
                Currency.AED, null, FILL_PER_ORDER, null, fillId));
        latenciesNs.add(System.nanoTime() - t);

        t = System.nanoTime();
        // null releaseAmount → service computes remainder (held - filled)
        lifecycleUseCase.handle(new OrderLifecycleCommand(
                orderId, OrderEventType.ORDER_CANCELLED, cf.customerId(),
                cf.settledCash(), cf.reservedCash(), cf.unsettledCashBuys(),
                Currency.AED, null, null, null, null));
        latenciesNs.add(System.nanoTime() - t);
    }

    private CustomerFixture createCustomerFixture() {
        UUID customerId = UUID.randomUUID();
        UUID settledCash = UUID.randomUUID();
        UUID reservedCash = UUID.randomUUID();
        UUID unsettledCashBuys = UUID.randomUUID();
        UUID omnibus = UUID.randomUUID();

        jdbcTemplate.update("INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, 'SETTLED_CASH', 'AED', 'ACTIVE')",
                settledCash, customerId);
        jdbcTemplate.update("INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, 'RESERVED_CASH', 'AED', 'ACTIVE')",
                reservedCash, customerId);
        jdbcTemplate.update("INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, 'UNSETTLED_CASH_BUYS', 'AED', 'ACTIVE')",
                unsettledCashBuys, customerId);
        jdbcTemplate.update("INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, 'BROKERAGE_OMNIBUS', 'AED', 'ACTIVE')",
                omnibus, customerId);

        postingUseCase.post(new PostLedgerEntriesCommand(
                "load-test-deposit-" + customerId,
                "VA_CREDITED",
                customerId,
                LocalDate.now(),
                List.of(
                        new PostingLeg(settledCash, EntryDirection.DEBIT, INITIAL_DEPOSIT, Currency.AED),
                        new PostingLeg(omnibus, EntryDirection.CREDIT, INITIAL_DEPOSIT, Currency.AED)
                )
        ));

        return new CustomerFixture(customerId, settledCash, reservedCash, unsettledCashBuys, omnibus);
    }
}
