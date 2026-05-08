package com.mohamedali.ledger.platform.seed;

import com.mohamedali.ledger.ledger.application.port.in.LedgerPostingUseCase;
import com.mohamedali.ledger.ledger.application.port.in.order.OrderLifecycleUseCase;
import com.mohamedali.ledger.ledger.application.port.in.withdrawal.CashMovementUseCase;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.ledger.domain.model.EntryDirection;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesCommand;
import com.mohamedali.ledger.ledger.domain.model.PostingLeg;
import com.mohamedali.ledger.ledger.domain.model.order.OrderEventType;
import com.mohamedali.ledger.ledger.domain.model.order.OrderLifecycleCommand;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.CashMovementCommand;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.CashMovementEventType;
import com.mohamedali.ledger.platform.jobs.withdrawal.WithdrawalTimeoutJob;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SeedDataService {

    private static final Logger LOG = LoggerFactory.getLogger(SeedDataService.class);
    private static final long SEED_LOCK_KEY = 777004L;

    private static final String DATASET_MEDIUM = "medium";

    private final JdbcTemplate jdbcTemplate;
    private final LedgerPostingUseCase postingUseCase;
    private final OrderLifecycleUseCase orderLifecycleUseCase;
    private final CashMovementUseCase cashMovementUseCase;
    private final WithdrawalTimeoutJob withdrawalTimeoutJob;
    private final Environment environment;
    private final boolean seedEnabled;
    private final String defaultDataset;

    public SeedDataService(JdbcTemplate jdbcTemplate,
                           LedgerPostingUseCase postingUseCase,
                           OrderLifecycleUseCase orderLifecycleUseCase,
                           CashMovementUseCase cashMovementUseCase,
                           WithdrawalTimeoutJob withdrawalTimeoutJob,
                           Environment environment,
                           @Value("${ledger.seed.enabled:false}") boolean seedEnabled,
                           @Value("${ledger.seed.default-dataset:medium}") String defaultDataset) {
        this.jdbcTemplate = jdbcTemplate;
        this.postingUseCase = postingUseCase;
        this.orderLifecycleUseCase = orderLifecycleUseCase;
        this.cashMovementUseCase = cashMovementUseCase;
        this.withdrawalTimeoutJob = withdrawalTimeoutJob;
        this.environment = environment;
        this.seedEnabled = seedEnabled;
        this.defaultDataset = defaultDataset;
    }

    @Transactional
    public SeedRunResult resetOnly() {
        long started = System.nanoTime();
        UUID runId = UUID.randomUUID();
        if (!isSeedingAllowed()) {
            return blocked(runId, "reset", null, false, started, "seeding is disabled outside local profile");
        }

        if (!tryLock()) {
            return lockSkipped(runId, "reset", null, false, started);
        }

        try {
            resetBusinessData();
            return success(runId, "reset", null, true, started, List.of(), tableCounts());
        } finally {
            unlock();
        }
    }

    @Transactional
    public SeedRunResult runDataset(String dataset, boolean reset) {
        String requestedDataset = (dataset == null || dataset.isBlank()) ? defaultDataset : dataset.trim().toLowerCase();
        long started = System.nanoTime();
        UUID runId = UUID.randomUUID();

        if (!isSeedingAllowed()) {
            return blocked(runId, requestedDataset, null, reset, started, "seeding is disabled outside local profile");
        }

        if (!DATASET_MEDIUM.equals(requestedDataset)) {
            return blocked(runId, requestedDataset, null, reset, started, "unsupported dataset: " + requestedDataset);
        }

        if (!tryLock()) {
            return lockSkipped(runId, requestedDataset, null, reset, started);
        }

        try {
            if (reset) {
                resetBusinessData();
            }

            seedMediumDataset();
            return success(runId, requestedDataset, null, reset, started, List.of(), tableCounts());
        } finally {
            unlock();
        }
    }

    @Transactional
    public SeedRunResult runScenario(String scenario, boolean reset) {
        String scenarioName = scenario == null ? "" : scenario.trim().toLowerCase();
        long started = System.nanoTime();
        UUID runId = UUID.randomUUID();

        if (!isSeedingAllowed()) {
            return blocked(runId, null, scenarioName, reset, started, "seeding is disabled outside local profile");
        }

        if (!supportedScenarios().contains(scenarioName)) {
            return blocked(runId, null, scenarioName, reset, started, "unsupported scenario: " + scenarioName);
        }

        if (!tryLock()) {
            return lockSkipped(runId, null, scenarioName, reset, started);
        }

        try {
            if (reset) {
                resetBusinessData();
            }

            seedMediumDataset();
            List<String> warnings = new ArrayList<>();
            if ("reconciliation-mismatch".equals(scenarioName)) {
                UUID accountId = knownIds().get("firstSettledCashAccountId");
                jdbcTemplate.update("UPDATE account_balances SET balance = balance + 1 WHERE account_id = ?", accountId);
                warnings.add("Introduced snapshot mismatch on account " + accountId);
            } else if ("settlement-pending".equals(scenarioName)) {
                warnings.add("Dataset includes HOLD/PARTIALLY_FILLED orders suitable for settlement skipped checks");
            }

            return success(runId, null, scenarioName, reset, started, warnings, tableCounts());
        } finally {
            unlock();
        }
    }

    public SeedCatalog catalog() {
        return new SeedCatalog(
                DATASET_MEDIUM,
                supportedScenarios(),
                knownIds(),
                Map.of(
                        "customers", "24",
                        "ordersApprox", "144+sell-fill-extensions",
                        "withdrawalsApprox", "72"
                )
        );
    }

    private void seedMediumDataset() {
        List<CustomerAccounts> customers = createCustomersWithAccounts(24);

        for (CustomerAccounts customer : customers) {
            seedDeposit(customer, new BigDecimal("50000.00"));
            seedOrders(customer);
            seedSellSettlementReference(customer);
            seedWithdrawals(customer);
        }

        markSomeWithdrawalsForTimeout();
        withdrawalTimeoutJob.runOnce(1000);
    }

    private List<CustomerAccounts> createCustomersWithAccounts(int count) {
        List<CustomerAccounts> customers = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            UUID customerId = deterministicUuid("seed-customer-" + i);
            CustomerAccounts accounts = new CustomerAccounts(
                    customerId,
                    deterministicUuid("seed-account-" + i + "-SETTLED_CASH"),
                    deterministicUuid("seed-account-" + i + "-RESERVED_CASH"),
                    deterministicUuid("seed-account-" + i + "-UNSETTLED_CASH_BUYS"),
                    deterministicUuid("seed-account-" + i + "-UNSETTLED_CASH_SALES"),
                    deterministicUuid("seed-account-" + i + "-SETTLEMENT_PENDING"),
                    deterministicUuid("seed-account-" + i + "-BROKERAGE_OMNIBUS")
            );
            insertAccount(accounts.settledCashAccountId(), customerId, "SETTLED_CASH");
            insertAccount(accounts.reservedCashAccountId(), customerId, "RESERVED_CASH");
            insertAccount(accounts.unsettledCashBuysAccountId(), customerId, "UNSETTLED_CASH_BUYS");
            insertAccount(accounts.unsettledCashSalesAccountId(), customerId, "UNSETTLED_CASH_SALES");
            insertAccount(accounts.settlementPendingAccountId(), customerId, "SETTLEMENT_PENDING");
            insertAccount(accounts.brokerageOmnibusAccountId(), customerId, "BROKERAGE_OMNIBUS");
            customers.add(accounts);
        }
        return customers;
    }

    private void insertAccount(UUID accountId, UUID customerId, String type) {
        jdbcTemplate.update(
                """
                INSERT INTO accounts(id, customer_id, type, currency, status)
                VALUES (?, ?, ?, 'AED', 'ACTIVE')
                ON CONFLICT (id) DO UPDATE
                SET customer_id = EXCLUDED.customer_id,
                    type = EXCLUDED.type,
                    currency = EXCLUDED.currency,
                    status = EXCLUDED.status
                """,
                accountId, customerId, type
        );
    }

    private void seedDeposit(CustomerAccounts customer, BigDecimal amount) {
        UUID eventId = deterministicUuid("seed-deposit-" + customer.customerId());
        cashMovementUseCase.handle(new CashMovementCommand(
                CashMovementEventType.VA_CREDITED,
                eventId,
                null,
                null,
                customer.customerId(),
                customer.settledCashAccountId(),
                null,
                customer.brokerageOmnibusAccountId(),
                amount,
                Currency.AED
        ));
    }

    private void seedOrders(CustomerAccounts customer) {
        for (int i = 0; i < 6; i++) {
            UUID referenceId = deterministicUuid("seed-order-" + customer.customerId() + "-" + i);
            BigDecimal held = new BigDecimal(100 + (i * 10)).setScale(2);

            orderLifecycleUseCase.handle(new OrderLifecycleCommand(
                    referenceId,
                    OrderEventType.ORDER_CREATED,
                    customer.customerId(),
                    customer.settledCashAccountId(),
                    customer.reservedCashAccountId(),
                    customer.unsettledCashBuysAccountId(),
                    Currency.AED,
                    held,
                    null,
                    null,
                    null
            ));

            switch (i) {
                case 0 -> fillOrder(customer, referenceId, held, 0);
                case 1 -> cancelOrder(customer, referenceId, held, OrderEventType.ORDER_CANCELLED);
                case 2 -> cancelOrder(customer, referenceId, held, OrderEventType.ORDER_REJECTED);
                case 3 -> {
                    BigDecimal partial = held.multiply(new BigDecimal("0.40")).setScale(2);
                    fillOrder(customer, referenceId, partial, 1);
                    cancelOrder(customer, referenceId, held.subtract(partial), OrderEventType.ORDER_CANCELLED);
                }
                case 4 -> {
                    // Leave HOLD state intentionally for settlement skip scenarios.
                }
                case 5 -> {
                    BigDecimal partial = held.multiply(new BigDecimal("0.50")).setScale(2);
                    fillOrder(customer, referenceId, partial, 2);
                }
                default -> {
                }
            }
        }
    }

    private void fillOrder(CustomerAccounts customer, UUID referenceId, BigDecimal amount, int seq) {
        orderLifecycleUseCase.handle(new OrderLifecycleCommand(
                referenceId,
                OrderEventType.ORDER_FILL,
                customer.customerId(),
                customer.settledCashAccountId(),
                customer.reservedCashAccountId(),
                customer.unsettledCashBuysAccountId(),
                Currency.AED,
                null,
                amount,
                null,
                deterministicUuid("seed-fill-" + referenceId + "-" + seq)
        ));
    }

    private void cancelOrder(CustomerAccounts customer, UUID referenceId, BigDecimal release, OrderEventType eventType) {
        orderLifecycleUseCase.handle(new OrderLifecycleCommand(
                referenceId,
                eventType,
                customer.customerId(),
                customer.settledCashAccountId(),
                customer.reservedCashAccountId(),
                customer.unsettledCashBuysAccountId(),
                Currency.AED,
                null,
                null,
                release,
                null
        ));
    }

    private void seedSellSettlementReference(CustomerAccounts customer) {
        UUID referenceId = deterministicUuid("seed-sell-order-" + customer.customerId());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO order_states(
                    reference_id, customer_id, settled_cash_account_id, reserved_cash_account_id,
                    unsettled_cash_buys_account_id, unsettled_cash_sales_account_id,
                    state, held_amount, filled_amount, currency, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'FILLED', ?, ?, 'AED', ?)
                ON CONFLICT (reference_id) DO UPDATE
                SET unsettled_cash_sales_account_id = EXCLUDED.unsettled_cash_sales_account_id,
                    state = EXCLUDED.state,
                    held_amount = EXCLUDED.held_amount,
                    filled_amount = EXCLUDED.filled_amount,
                    updated_at = EXCLUDED.updated_at
                """,
                referenceId,
                customer.customerId(),
                customer.settledCashAccountId(),
                customer.reservedCashAccountId(),
                customer.unsettledCashBuysAccountId(),
                customer.unsettledCashSalesAccountId(),
                new BigDecimal("500.00"),
                new BigDecimal("500.00"),
                Timestamp.from(now.toInstant())
        );

        postingUseCase.post(new PostLedgerEntriesCommand(
                "SEED-SELL-FILL-" + referenceId,
                "ORDER_FILL",
                referenceId,
                LocalDate.now(ZoneOffset.UTC),
                List.of(
                        new PostingLeg(customer.unsettledCashSalesAccountId(), EntryDirection.DEBIT, new BigDecimal("500.00"), Currency.AED),
                        new PostingLeg(customer.brokerageOmnibusAccountId(), EntryDirection.CREDIT, new BigDecimal("500.00"), Currency.AED)
                )
        ));
    }

    private void seedWithdrawals(CustomerAccounts customer) {
        UUID requestedConfirmedId = deterministicUuid("seed-withdrawal-confirm-" + customer.customerId());
        requestWithdrawal(customer, requestedConfirmedId, new BigDecimal("200.00"));
        confirmWithdrawal(requestedConfirmedId);

        UUID requestedRejectedId = deterministicUuid("seed-withdrawal-reject-" + customer.customerId());
        requestWithdrawal(customer, requestedRejectedId, new BigDecimal("150.00"));
        rejectWithdrawal(requestedRejectedId);

        UUID requestedPendingId = deterministicUuid("seed-withdrawal-pending-" + customer.customerId());
        requestWithdrawal(customer, requestedPendingId, new BigDecimal("120.00"));
    }

    private void requestWithdrawal(CustomerAccounts customer, UUID withdrawalId, BigDecimal amount) {
        cashMovementUseCase.handle(new CashMovementCommand(
                CashMovementEventType.WITHDRAWAL_REQUESTED,
                null,
                withdrawalId,
                null,
                customer.customerId(),
                customer.settledCashAccountId(),
                customer.settlementPendingAccountId(),
                customer.brokerageOmnibusAccountId(),
                amount,
                Currency.AED
        ));
    }

    private void confirmWithdrawal(UUID withdrawalId) {
        cashMovementUseCase.handle(new CashMovementCommand(
                CashMovementEventType.WITHDRAWAL_CONFIRMED,
                null,
                withdrawalId,
                deterministicUuid("seed-callback-confirm-" + withdrawalId),
                null,
                null,
                null,
                null,
                null,
                null
        ));
    }

    private void rejectWithdrawal(UUID withdrawalId) {
        cashMovementUseCase.handle(new CashMovementCommand(
                CashMovementEventType.WITHDRAWAL_REJECTED,
                null,
                withdrawalId,
                deterministicUuid("seed-callback-reject-" + withdrawalId),
                null,
                null,
                null,
                null,
                null,
                null
        ));
    }

    private void markSomeWithdrawalsForTimeout() {
        List<UUID> ids = IntStream.rangeClosed(1, 8)
                .mapToObj(i -> deterministicUuid("seed-withdrawal-pending-" + deterministicUuid("seed-customer-" + i)))
                .toList();

        Timestamp past = Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).minusHours(49).toInstant());
        for (UUID id : ids) {
            jdbcTemplate.update(
                    "UPDATE withdrawal_requests SET pending_since = ? WHERE id = ? AND status = 'PENDING'",
                    past,
                    id
            );
        }
    }

    private void resetBusinessData() {
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
        LOG.info("mock-data seed reset completed");
    }

    private Map<String, Integer> tableCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("accounts", count("accounts"));
        counts.put("ledger_postings", count("ledger_postings"));
        counts.put("journal_entries", count("journal_entries"));
        counts.put("account_balances", count("account_balances"));
        counts.put("order_states", count("order_states"));
        counts.put("withdrawal_requests", count("withdrawal_requests"));
        counts.put("settlement_batches", count("settlement_batches"));
        counts.put("reconciliation_runs", count("reconciliation_runs"));
        counts.put("reconciliation_issues", count("reconciliation_issues"));
        return counts;
    }

    private int count(String tableName) {
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
        return value == null ? 0 : value;
    }

    private boolean isSeedingAllowed() {
        if (seedEnabled) {
            return true;
        }
        return Set.of(environment.getActiveProfiles()).contains("local");
    }

    private boolean tryLock() {
        Boolean acquired = jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, SEED_LOCK_KEY);
        return Boolean.TRUE.equals(acquired);
    }

    private void unlock() {
        jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, SEED_LOCK_KEY);
    }

    private List<String> supportedScenarios() {
        return List.of("happy-path", "reconciliation-mismatch", "settlement-pending");
    }

    private Map<String, UUID> knownIds() {
        UUID customer = deterministicUuid("seed-customer-1");
        Map<String, UUID> ids = new LinkedHashMap<>();
        ids.put("firstCustomerId", customer);
        ids.put("firstSettledCashAccountId", deterministicUuid("seed-account-1-SETTLED_CASH"));
        ids.put("firstReservedCashAccountId", deterministicUuid("seed-account-1-RESERVED_CASH"));
        ids.put("firstUnsettledCashBuysAccountId", deterministicUuid("seed-account-1-UNSETTLED_CASH_BUYS"));
        ids.put("firstUnsettledCashSalesAccountId", deterministicUuid("seed-account-1-UNSETTLED_CASH_SALES"));
        ids.put("firstSettlementPendingAccountId", deterministicUuid("seed-account-1-SETTLEMENT_PENDING"));
        ids.put("firstBrokerageOmnibusAccountId", deterministicUuid("seed-account-1-BROKERAGE_OMNIBUS"));
        ids.put("firstOrderReferenceId", deterministicUuid("seed-order-" + customer + "-0"));
        ids.put("firstWithdrawalId", deterministicUuid("seed-withdrawal-confirm-" + customer));
        return ids;
    }

    private static SeedRunResult blocked(UUID runId,
                                         String dataset,
                                         String scenario,
                                         boolean resetApplied,
                                         long startedNanos,
                                         String message) {
        return new SeedRunResult(
                runId,
                dataset,
                scenario,
                resetApplied,
                true,
                false,
                List.of(message),
                Map.of(),
                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
                message
        );
    }

    private static SeedRunResult lockSkipped(UUID runId,
                                             String dataset,
                                             String scenario,
                                             boolean resetApplied,
                                             long startedNanos) {
        return new SeedRunResult(
                runId,
                dataset,
                scenario,
                resetApplied,
                false,
                true,
                List.of("another seed run is in progress"),
                Map.of(),
                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
                "seed run skipped due to advisory lock contention"
        );
    }

    private static SeedRunResult success(UUID runId,
                                         String dataset,
                                         String scenario,
                                         boolean resetApplied,
                                         long startedNanos,
                                         List<String> warnings,
                                         Map<String, Integer> counts) {
        return new SeedRunResult(
                runId,
                dataset,
                scenario,
                resetApplied,
                false,
                false,
                warnings,
                counts,
                Duration.ofNanos(System.nanoTime() - startedNanos).toMillis(),
                "ok"
        );
    }

    private static UUID deterministicUuid(String source) {
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private record CustomerAccounts(
            UUID customerId,
            UUID settledCashAccountId,
            UUID reservedCashAccountId,
            UUID unsettledCashBuysAccountId,
            UUID unsettledCashSalesAccountId,
            UUID settlementPendingAccountId,
            UUID brokerageOmnibusAccountId
    ) {
    }
}
