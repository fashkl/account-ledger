package com.mohamedali.ledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mohamedali.ledger.ledger.application.port.in.LedgerBalanceQuery;
import com.mohamedali.ledger.ledger.application.port.in.LedgerPostingUseCase;
import com.mohamedali.ledger.ledger.domain.exception.InvalidPostingStructureException;
import com.mohamedali.ledger.ledger.domain.exception.UnbalancedPostingException;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.ledger.domain.model.EntryDirection;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesCommand;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesResult;
import com.mohamedali.ledger.ledger.domain.model.PostingLeg;
import com.mohamedali.ledger.platform.jobs.SnapshotRebuildService;
import com.mohamedali.ledger.shared.exception.AccountClosedException;
import com.mohamedali.ledger.shared.exception.IdempotencyKeyCollisionException;
import com.mohamedali.ledger.shared.exception.InsufficientFundsException;
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
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
class LedgerPostingServiceIntegrationTest {

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
    private LedgerBalanceQuery balanceQuery;

    @Autowired
    private SnapshotRebuildService snapshotRebuildService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private UUID customerId;
    private UUID reservedCashAccountId;
    private UUID settledCashAccountId;
    private UUID omnibusAccountId;

    @BeforeEach
    void setUp() {
        circuitBreakerRegistry.circuitBreaker("ledgerProcessing").reset();
        jdbcTemplate.execute("TRUNCATE TABLE account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");

        customerId = UUID.randomUUID();
        reservedCashAccountId = UUID.randomUUID();
        settledCashAccountId = UUID.randomUUID();
        omnibusAccountId = UUID.randomUUID();

        insertAccount(reservedCashAccountId, customerId, "RESERVED_CASH", "ACTIVE");
        insertAccount(settledCashAccountId, customerId, "SETTLED_CASH", "ACTIVE");
        insertAccount(omnibusAccountId, customerId, "BROKERAGE_OMNIBUS", "ACTIVE");

        post(
                "deposit-1",
                "VA_CREDITED",
                UUID.randomUUID(),
                List.of(
                        new PostingLeg(settledCashAccountId, EntryDirection.DEBIT, new BigDecimal("5000.00"), Currency.AED),
                        new PostingLeg(omnibusAccountId, EntryDirection.CREDIT, new BigDecimal("5000.00"), Currency.AED)
                )
        );
    }

    @AfterEach
    void clean() {
        circuitBreakerRegistry.circuitBreaker("ledgerProcessing").reset();
        jdbcTemplate.execute("TRUNCATE TABLE account_balances, journal_entries, ledger_postings, order_states, accounts CASCADE");
    }

    @Test
    void postCreatesJournalAndSnapshotBalances() {
        PostLedgerEntriesResult result = post(
                "hold-order-1",
                "ORDER_HOLD",
                UUID.randomUUID(),
                List.of(
                        new PostingLeg(reservedCashAccountId, EntryDirection.DEBIT, new BigDecimal("5000.00"), Currency.AED),
                        new PostingLeg(settledCashAccountId, EntryDirection.CREDIT, new BigDecimal("5000.00"), Currency.AED)
                )
        );

        assertThat(result.duplicate()).isFalse();
        Integer journalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class);
        assertThat(journalCount).isEqualTo(4);

        assertThat(balanceQuery.getAccountBalance(reservedCashAccountId)).isEqualByComparingTo("5000.00");
        assertThat(balanceQuery.getAccountBalance(settledCashAccountId)).isEqualByComparingTo("0.00");
    }

    @Test
    void postIsIdempotentByKey() {
        UUID referenceId = UUID.randomUUID();
        PostLedgerEntriesResult first = post(
                "hold-order-2",
                "ORDER_HOLD",
                referenceId,
                List.of(
                        new PostingLeg(reservedCashAccountId, EntryDirection.DEBIT, new BigDecimal("100.00"), Currency.AED),
                        new PostingLeg(settledCashAccountId, EntryDirection.CREDIT, new BigDecimal("100.00"), Currency.AED)
                )
        );
        PostLedgerEntriesResult second = post(
                "hold-order-2",
                "ORDER_HOLD",
                referenceId,
                List.of(
                        new PostingLeg(reservedCashAccountId, EntryDirection.DEBIT, new BigDecimal("100.00"), Currency.AED),
                        new PostingLeg(settledCashAccountId, EntryDirection.CREDIT, new BigDecimal("100.00"), Currency.AED)
                )
        );

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.entryGroupId()).isEqualTo(first.entryGroupId());

        Integer journalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class);
        assertThat(journalCount).isEqualTo(4);
    }

    @Test
    void postRejectsUnbalancedEntriesWithoutSideEffects() {
        PostLedgerEntriesCommand command = command(
                "hold-order-3",
                "ORDER_HOLD",
                UUID.randomUUID(),
                List.of(
                        new PostingLeg(reservedCashAccountId, EntryDirection.DEBIT, new BigDecimal("120.00"), Currency.AED),
                        new PostingLeg(settledCashAccountId, EntryDirection.CREDIT, new BigDecimal("100.00"), Currency.AED)
                )
        );

        assertThatThrownBy(() -> postingUseCase.post(command))
                .isInstanceOf(UnbalancedPostingException.class)
                .hasMessageContaining("unbalanced");

        Integer journalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class);
        Integer postingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_postings", Integer.class);
        assertThat(journalCount).isEqualTo(2);
        assertThat(postingCount).isEqualTo(1);
    }

    @Test
    void nsfRejectsWithoutJournalWrites() {
        PostLedgerEntriesCommand command = command(
                "hold-order-nsf",
                "ORDER_HOLD",
                UUID.randomUUID(),
                List.of(
                        new PostingLeg(reservedCashAccountId, EntryDirection.DEBIT, new BigDecimal("6000.00"), Currency.AED),
                        new PostingLeg(settledCashAccountId, EntryDirection.CREDIT, new BigDecimal("6000.00"), Currency.AED)
                )
        );

        assertThatThrownBy(() -> postingUseCase.post(command))
                .isInstanceOf(InsufficientFundsException.class);

        Integer journalCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM journal_entries", Integer.class);
        Integer postingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_postings", Integer.class);
        assertThat(journalCount).isEqualTo(2);
        assertThat(postingCount).isEqualTo(1);
    }

    @Test
    void rejectsClosedAccount() {
        jdbcTemplate.update("UPDATE accounts SET status='CLOSED' WHERE id = ?", settledCashAccountId);

        PostLedgerEntriesCommand command = command(
                "hold-order-closed",
                "ORDER_HOLD",
                UUID.randomUUID(),
                List.of(
                        new PostingLeg(reservedCashAccountId, EntryDirection.DEBIT, new BigDecimal("10.00"), Currency.AED),
                        new PostingLeg(settledCashAccountId, EntryDirection.CREDIT, new BigDecimal("10.00"), Currency.AED)
                )
        );

        assertThatThrownBy(() -> postingUseCase.post(command)).isInstanceOf(AccountClosedException.class);
    }

    @Test
    void detectsIdempotencyCollision() {
        UUID ref1 = UUID.randomUUID();
        UUID ref2 = UUID.randomUUID();

        post(
                "same-key",
                "ORDER_HOLD",
                ref1,
                List.of(
                        new PostingLeg(reservedCashAccountId, EntryDirection.DEBIT, new BigDecimal("10.00"), Currency.AED),
                        new PostingLeg(settledCashAccountId, EntryDirection.CREDIT, new BigDecimal("10.00"), Currency.AED)
                )
        );

        PostLedgerEntriesCommand command = command(
                "same-key",
                "ORDER_HOLD",
                ref2,
                List.of(
                        new PostingLeg(reservedCashAccountId, EntryDirection.DEBIT, new BigDecimal("10.00"), Currency.AED),
                        new PostingLeg(settledCashAccountId, EntryDirection.CREDIT, new BigDecimal("10.00"), Currency.AED)
                )
        );

        assertThatThrownBy(() -> postingUseCase.post(command)).isInstanceOf(IdempotencyKeyCollisionException.class);
    }

    @Test
    void rejectsFutureEffectiveDate() {
        PostLedgerEntriesCommand command = new PostLedgerEntriesCommand(
                "future-effective",
                "ORDER_HOLD",
                UUID.randomUUID(),
                LocalDate.now(ZoneOffset.UTC).plusDays(1),
                List.of(
                        new PostingLeg(reservedCashAccountId, EntryDirection.DEBIT, new BigDecimal("10.00"), Currency.AED),
                        new PostingLeg(settledCashAccountId, EntryDirection.CREDIT, new BigDecimal("10.00"), Currency.AED)
                )
        );

        assertThatThrownBy(() -> postingUseCase.post(command)).isInstanceOf(InvalidPostingStructureException.class);
    }

    @Test
    void journalEntriesAreImmutableAtDbLevel() {
        UUID existingId = jdbcTemplate.queryForObject("SELECT id FROM journal_entries LIMIT 1", UUID.class);

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE journal_entries SET amount = amount + 1 WHERE id = ?", existingId))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void dbTriggerRejectsNegativeBalanceForConstrainedAccountType() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE account_balances SET balance = ? WHERE account_id = ?",
                new BigDecimal("-1.00"),
                settledCashAccountId
        ))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("negative balance is not allowed");
    }

    @Test
    void dbTriggerAllowsNegativeBalanceForBrokerageOmnibus() {
        int updated = jdbcTemplate.update(
                "UPDATE account_balances SET balance = ? WHERE account_id = ?",
                new BigDecimal("-6000.00"),
                omnibusAccountId
        );

        assertThat(updated).isEqualTo(1);
        assertThat(balanceQuery.getAccountBalance(omnibusAccountId)).isEqualByComparingTo("-6000.00");
    }

    @Test
    void concurrentPostingOnSameAccountDoesNotFail() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int n = i;
            tasks.add(() -> {
                post(
                        "concurrent-" + n,
                        "ORDER_HOLD",
                        UUID.randomUUID(),
                        List.of(
                                new PostingLeg(reservedCashAccountId, EntryDirection.DEBIT, new BigDecimal("10.00"), Currency.AED),
                                new PostingLeg(settledCashAccountId, EntryDirection.CREDIT, new BigDecimal("10.00"), Currency.AED)
                        )
                );
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        executor.awaitTermination(20, TimeUnit.SECONDS);
        for (Future<Void> future : futures) {
            future.get();
        }

        assertThat(balanceQuery.getAccountBalance(reservedCashAccountId)).isEqualByComparingTo("500.00");
        assertThat(balanceQuery.getAccountBalance(settledCashAccountId)).isEqualByComparingTo("4500.00");
    }

    @Test
    void snapshotRebuildRestoresBalancesFromJournal() {
        post(
                "hold-order-rebuild",
                "ORDER_HOLD",
                UUID.randomUUID(),
                List.of(
                        new PostingLeg(reservedCashAccountId, EntryDirection.DEBIT, new BigDecimal("250.00"), Currency.AED),
                        new PostingLeg(settledCashAccountId, EntryDirection.CREDIT, new BigDecimal("250.00"), Currency.AED)
                )
        );

        SnapshotRebuildService.SnapshotRebuildResult rebuilt = snapshotRebuildService.rebuildAll();

        assertThat(rebuilt.rebuiltRows()).isGreaterThan(0);
        assertThat(rebuilt.mismatchCount()).isZero();
        assertThat(rebuilt.swapped()).isTrue();
        assertThat(balanceQuery.getAccountBalance(reservedCashAccountId)).isEqualByComparingTo("250.00");
        assertThat(balanceQuery.getAccountBalance(settledCashAccountId)).isEqualByComparingTo("4750.00");
    }

    @Test
    void snapshotRebuildDetectsMismatchAndForceRebuildRepairsIt() {
        post(
                "hold-order-corrupt",
                "ORDER_HOLD",
                UUID.randomUUID(),
                List.of(
                        new PostingLeg(reservedCashAccountId, EntryDirection.DEBIT, new BigDecimal("50.00"), Currency.AED),
                        new PostingLeg(settledCashAccountId, EntryDirection.CREDIT, new BigDecimal("50.00"), Currency.AED)
                )
        );

        jdbcTemplate.update("UPDATE account_balances SET balance = 999999 WHERE account_id = ?", settledCashAccountId);

        SnapshotRebuildService.SnapshotRebuildResult checkOnly = snapshotRebuildService.rebuildAll();
        assertThat(checkOnly.mismatchCount()).isGreaterThan(0);
        assertThat(checkOnly.swapped()).isFalse();

        SnapshotRebuildService.SnapshotRebuildResult forced = snapshotRebuildService.forceRebuild();
        assertThat(forced.swapped()).isTrue();
        assertThat(balanceQuery.getAccountBalance(settledCashAccountId)).isEqualByComparingTo("4950.00");
    }

    private void insertAccount(UUID accountId, UUID cId, String type, String status) {
        jdbcTemplate.update(
                "INSERT INTO accounts(id, customer_id, type, currency, status) VALUES (?, ?, ?, ?, ?)",
                accountId,
                cId,
                type,
                "AED",
                status
        );
    }

    private PostLedgerEntriesResult post(String key, String eventType, UUID referenceId, List<PostingLeg> legs) {
        return postingUseCase.post(command(key, eventType, referenceId, legs));
    }

    private PostLedgerEntriesCommand command(String key, String eventType, UUID referenceId, List<PostingLeg> legs) {
        return new PostLedgerEntriesCommand(key, eventType, referenceId, LocalDate.now(ZoneOffset.UTC), legs);
    }
}
