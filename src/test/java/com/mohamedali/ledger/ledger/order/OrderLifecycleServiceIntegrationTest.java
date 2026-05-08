package com.mohamedali.ledger.ledger.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mohamedali.ledger.ledger.application.port.in.LedgerBalanceQuery;
import com.mohamedali.ledger.ledger.application.port.in.LedgerPostingUseCase;
import com.mohamedali.ledger.ledger.application.port.in.order.BuyingPowerQuery;
import com.mohamedali.ledger.ledger.application.port.in.order.OrderLifecycleUseCase;
import com.mohamedali.ledger.ledger.domain.model.EntryDirection;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesCommand;
import com.mohamedali.ledger.ledger.domain.model.PostingLeg;
import com.mohamedali.ledger.ledger.domain.model.order.OrderEventType;
import com.mohamedali.ledger.ledger.domain.model.order.OrderLifecycleCommand;
import com.mohamedali.ledger.shared.exception.InvalidStateTransitionException;
import com.mohamedali.ledger.shared.exception.InvalidOrderEventException;
import com.mohamedali.ledger.shared.exception.OrderOwnershipMismatchException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
class OrderLifecycleServiceIntegrationTest {

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
    private BuyingPowerQuery buyingPowerQuery;

    @Autowired
    private LedgerBalanceQuery ledgerBalanceQuery;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID customerId;
    private UUID settledCashAccountId;
    private UUID reservedCashAccountId;
    private UUID unsettledCashBuysAccountId;
    private UUID omnibusAccountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");

        customerId = UUID.randomUUID();
        settledCashAccountId = UUID.randomUUID();
        reservedCashAccountId = UUID.randomUUID();
        unsettledCashBuysAccountId = UUID.randomUUID();
        omnibusAccountId = UUID.randomUUID();

        insertAccount(settledCashAccountId, "SETTLED_CASH");
        insertAccount(reservedCashAccountId, "RESERVED_CASH");
        insertAccount(unsettledCashBuysAccountId, "UNSETTLED_CASH_BUYS");
        insertAccount(omnibusAccountId, "BROKERAGE_OMNIBUS");

        seedDeposit(new BigDecimal("10000.00"));
    }

    @AfterEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");
    }

    @Test
    void fillAfterCancelIsRejectedAndBalancesStayStable() {
        UUID orderId = UUID.randomUUID();
        lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_CREATED, new BigDecimal("5000.00"), null, null));
        lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_CANCELLED, null, null, null));

        BigDecimal settledAfterCancel = ledgerBalanceQuery.getAccountBalance(settledCashAccountId);
        BigDecimal reservedAfterCancel = ledgerBalanceQuery.getAccountBalance(reservedCashAccountId);

        assertThatThrownBy(() -> lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_FILL, null, new BigDecimal("1000.00"), UUID.randomUUID())))
                .isInstanceOf(InvalidStateTransitionException.class);

        assertThat(ledgerBalanceQuery.getAccountBalance(settledCashAccountId)).isEqualByComparingTo(settledAfterCancel);
        assertThat(ledgerBalanceQuery.getAccountBalance(reservedCashAccountId)).isEqualByComparingTo(reservedAfterCancel);
    }

    @Test
    void partialFillThenCancelReleasesRemainder() {
        UUID orderId = UUID.randomUUID();
        lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_CREATED, new BigDecimal("5000.00"), null, null));
        lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_FILL, null, new BigDecimal("3000.00"), UUID.randomUUID()));
        lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_CANCELLED, null, null, null));

        assertThat(ledgerBalanceQuery.getAccountBalance(reservedCashAccountId)).isEqualByComparingTo("0.00000000");
        assertThat(ledgerBalanceQuery.getAccountBalance(settledCashAccountId)).isEqualByComparingTo("7000.00000000");
        assertThat(ledgerBalanceQuery.getAccountBalance(unsettledCashBuysAccountId)).isEqualByComparingTo("3000.00000000");
    }

    @Test
    void overFillIsRejected() {
        UUID orderId = UUID.randomUUID();
        lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_CREATED, new BigDecimal("5000.00"), null, null));
        lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_FILL, null, new BigDecimal("3000.00"), UUID.randomUUID()));

        assertThatThrownBy(() -> lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_FILL, null, new BigDecimal("3000.00"), UUID.randomUUID())))
                .isInstanceOf(InvalidOrderEventException.class)
                .hasMessageContaining("fill exceeds");
    }

    @Test
    void concurrentFillAndCancelMaintainsInvariants() throws Exception {
        UUID orderId = UUID.randomUUID();
        lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_CREATED, new BigDecimal("5000.00"), null, null));

        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Callable<Void>> tasks = new ArrayList<>();
        AtomicInteger unexpectedErrors = new AtomicInteger(0);
        for (int i = 0; i < 9; i++) {
            tasks.add(() -> {
                try {
                    lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_FILL, null, new BigDecimal("500.00"), UUID.randomUUID()));
                } catch (InvalidStateTransitionException ignored) {
                } catch (RuntimeException ex) {
                    unexpectedErrors.incrementAndGet();
                }
                return null;
            });
        }
        tasks.add(() -> {
            try {
                lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_CANCELLED, null, null, null));
            } catch (InvalidStateTransitionException ignored) {
            } catch (RuntimeException ex) {
                unexpectedErrors.incrementAndGet();
            }
            return null;
        });

        List<Future<Void>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        executor.awaitTermination(20, TimeUnit.SECONDS);
        for (Future<Void> future : futures) {
            future.get();
        }
        assertThat(unexpectedErrors.get()).isEqualTo(0);

        BigDecimal held = jdbcTemplate.queryForObject("SELECT held_amount FROM order_states WHERE reference_id = ?", BigDecimal.class, orderId);
        BigDecimal filled = jdbcTemplate.queryForObject("SELECT filled_amount FROM order_states WHERE reference_id = ?", BigDecimal.class, orderId);
        BigDecimal reserved = ledgerBalanceQuery.getAccountBalance(reservedCashAccountId);
        BigDecimal release = held.subtract(filled).subtract(reserved);

        assertThat(reserved.signum()).isGreaterThanOrEqualTo(0);
        assertThat(filled.add(release).setScale(8)).isEqualByComparingTo(held.setScale(8));
    }

    @Test
    void buyingPowerIsDerivedAndNonNegative() {
        UUID orderId = UUID.randomUUID();
        lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_CREATED, new BigDecimal("4000.00"), null, null));

        BigDecimal buyingPower = buyingPowerQuery.buyingPower(customerId, Currency.AED);
        assertThat(buyingPower).isEqualByComparingTo("2000.00000000");
    }

    @Test
    void cancellingTwiceIsNoOpSecondTime() {
        UUID orderId = UUID.randomUUID();
        lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_CREATED, new BigDecimal("1000.00"), null, null));
        lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_CANCELLED, null, null, null));

        Integer firstCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class);
        lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_CANCELLED, null, null, null));
        Integer secondCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class);

        assertThat(secondCount).isEqualTo(firstCount);
    }

    @Test
    void createEventIsIdempotentForIdenticalPayload() {
        UUID orderId = UUID.randomUUID();
        OrderLifecycleCommand cmd = createCmd(orderId, OrderEventType.ORDER_CREATED, new BigDecimal("1200.00"), null, null);

        lifecycleUseCase.handle(cmd);
        Integer firstJournalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class);
        lifecycleUseCase.handle(cmd);
        Integer secondJournalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class);

        assertThat(secondJournalCount).isEqualTo(firstJournalCount);
    }

    @Test
    void fillWithMismatchedAccountsIsRejected() {
        UUID orderId = UUID.randomUUID();
        lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_CREATED, new BigDecimal("1200.00"), null, null));

        OrderLifecycleCommand tampered = new OrderLifecycleCommand(
                orderId,
                OrderEventType.ORDER_FILL,
                customerId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Currency.AED,
                null,
                new BigDecimal("200.00"),
                null,
                UUID.randomUUID()
        );

        assertThatThrownBy(() -> lifecycleUseCase.handle(tampered))
                .isInstanceOf(OrderOwnershipMismatchException.class);
    }

    @Test
    void cancelWithMismatchedReleaseAmountIsRejected() {
        UUID orderId = UUID.randomUUID();
        lifecycleUseCase.handle(createCmd(orderId, OrderEventType.ORDER_CREATED, new BigDecimal("5000.00"), null, null));
        assertThatThrownBy(() -> lifecycleUseCase.handle(new OrderLifecycleCommand(
                orderId,
                OrderEventType.ORDER_CANCELLED,
                customerId,
                settledCashAccountId,
                reservedCashAccountId,
                unsettledCashBuysAccountId,
                Currency.AED,
                null,
                null,
                new BigDecimal("1234.00"),
                null
        ))).isInstanceOf(InvalidOrderEventException.class)
                .hasMessageContaining("releaseAmount does not match");
    }

    private void seedDeposit(BigDecimal amount) {
        PostLedgerEntriesCommand cmd = new PostLedgerEntriesCommand(
                "seed-deposit-" + UUID.randomUUID(),
                "VA_CREDITED",
                UUID.randomUUID(),
                LocalDate.now(ZoneOffset.UTC),
                List.of(
                        new PostingLeg(settledCashAccountId, EntryDirection.DEBIT, amount, Currency.AED),
                        new PostingLeg(omnibusAccountId, EntryDirection.CREDIT, amount, Currency.AED)
                )
        );
        postingUseCase.post(cmd);
    }

    private void insertAccount(UUID accountId, String type) {
        jdbcTemplate.update(
                "INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, ?, 'AED', 'ACTIVE')",
                accountId,
                customerId,
                type
        );
    }

    private OrderLifecycleCommand createCmd(UUID ref,
                                            OrderEventType type,
                                            BigDecimal held,
                                            BigDecimal fill,
                                            UUID fillId) {
        return new OrderLifecycleCommand(
                ref,
                type,
                customerId,
                settledCashAccountId,
                reservedCashAccountId,
                unsettledCashBuysAccountId,
                Currency.AED,
                held,
                fill,
                null,
                fillId
        );
    }
}
