package com.mohamedali.ledger.platform.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;
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

@SpringBootTest(properties = "ledger.seed.enabled=true")
@Testcontainers
class SeedDataServiceIntegrationTest {

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
        registry.add("ledger.datasource.settlement.url", postgres::getJdbcUrl);
        registry.add("ledger.datasource.settlement.username", postgres::getUsername);
        registry.add("ledger.datasource.settlement.password", postgres::getPassword);
        registry.add("ledger.datasource.read-replica.enabled", () -> false);
    }

    @Autowired
    private SeedDataService seedDataService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    reconciliation_issues,
                    reconciliation_runs,
                    settlement_batches,
                    withdrawal_requests,
                    account_balances,
                    journal_entries,
                    ledger_postings,
                    order_states,
                    accounts
                CASCADE
                """);
    }

    @Test
    void seedMediumDataset_createsExpectedCountsAndInvariants() {
        SeedRunResult result = seedDataService.runDataset("medium", true);

        assertThat(result.blocked()).isFalse();
        assertThat(result.skippedDueToLock()).isFalse();
        assertThat(result.recordsCreatedByTable().get("accounts")).isEqualTo(144);
        assertThat(result.recordsCreatedByTable().get("order_states")).isGreaterThanOrEqualTo(144);
        assertThat(result.recordsCreatedByTable().get("withdrawal_requests")).isEqualTo(72);

        Integer holdOrPartial = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_states WHERE state IN ('HOLD', 'PARTIALLY_FILLED')",
                Integer.class
        );
        assertThat(holdOrPartial).isNotNull();
        assertThat(holdOrPartial).isGreaterThan(0);

        Integer timedOut = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM withdrawal_requests WHERE status = 'TIMED_OUT'",
                Integer.class
        );
        assertThat(timedOut).isNotNull();
        assertThat(timedOut).isGreaterThan(0);
    }

    @Test
    void resetThenSeed_isDeterministicAcrossReruns() {
        SeedRunResult first = seedDataService.runDataset("medium", true);
        UUID firstKnownAccount = seedDataService.catalog().knownIds().get("firstSettledCashAccountId");

        BigDecimal firstBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM account_balances WHERE account_id = ?",
                BigDecimal.class,
                firstKnownAccount
        );

        SeedRunResult second = seedDataService.runDataset("medium", true);
        BigDecimal secondBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM account_balances WHERE account_id = ?",
                BigDecimal.class,
                firstKnownAccount
        );

        assertThat(first.recordsCreatedByTable()).isEqualTo(second.recordsCreatedByTable());
        assertThat(firstBalance).isEqualByComparingTo(secondBalance);
    }

    @Test
    void concurrentSeedRequests_secondIsRejectedOrSkipped() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<SeedRunResult> task = () -> seedDataService.runDataset("medium", false);
            Future<SeedRunResult> f1 = pool.submit(task);
            Future<SeedRunResult> f2 = pool.submit(task);

            SeedRunResult r1 = f1.get();
            SeedRunResult r2 = f2.get();

            assertThat(r1.skippedDueToLock() || r2.skippedDueToLock()).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void seedScenario_reconciliationMismatch_createsDrift() {
        SeedRunResult result = seedDataService.runScenario("reconciliation-mismatch", true);
        assertThat(result.blocked()).isFalse();
        assertThat(result.warnings()).isNotEmpty();

        UUID accountId = seedDataService.catalog().knownIds().get("firstSettledCashAccountId");
        BigDecimal snapshot = jdbcTemplate.queryForObject(
                "SELECT balance FROM account_balances WHERE account_id = ?",
                BigDecimal.class,
                accountId
        );
        BigDecimal ledger = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0)
                FROM journal_entries
                WHERE account_id = ?
                """,
                BigDecimal.class,
                accountId
        );
        assertThat(snapshot).isNotEqualByComparingTo(ledger);
    }
}
