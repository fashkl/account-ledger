package com.mohamedali.ledger.platform.jobs.settlement;

import com.mohamedali.ledger.ledger.application.port.in.LedgerPostingUseCase;
import com.mohamedali.ledger.ledger.domain.model.EntryDirection;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesCommand;
import com.mohamedali.ledger.ledger.domain.model.PostingLeg;
import com.mohamedali.ledger.platform.jobs.reconciliation.JdbcReconciliationRepository;
import com.mohamedali.ledger.platform.jobs.reconciliation.ReconciliationRunType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SettlementService {

    private static final Logger LOG = LoggerFactory.getLogger(SettlementService.class);
    private static final long SETTLEMENT_LOCK_KEY = 777001L;

    private final JdbcTemplate jdbcTemplate;
    private final JdbcSettlementRepository settlementRepository;
    private final JdbcReconciliationRepository reconciliationRepository;
    private final LedgerPostingUseCase postingUseCase;
    private final MeterRegistry meterRegistry;
    private final int chunkSize;

    public SettlementService(@Qualifier("settlementJdbcTemplate") JdbcTemplate jdbcTemplate,
                             JdbcSettlementRepository settlementRepository,
                             JdbcReconciliationRepository reconciliationRepository,
                             LedgerPostingUseCase postingUseCase,
                             MeterRegistry meterRegistry,
                             @Value("${ledger.jobs.settlement.chunk-size:200}") int chunkSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.settlementRepository = settlementRepository;
        this.reconciliationRepository = reconciliationRepository;
        this.postingUseCase = postingUseCase;
        this.meterRegistry = meterRegistry;
        this.chunkSize = chunkSize;
    }

    public SettlementRunResult run(LocalDate settlementDate, String batchId) {
        Boolean acquired = jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, SETTLEMENT_LOCK_KEY);
        if (!Boolean.TRUE.equals(acquired)) {
            return new SettlementRunResult(batchId, settlementDate, 0, true, false);
        }

        long startNanos = System.nanoTime();
        try {
            return runWithCheckpoint(settlementDate, batchId, startNanos);
        } finally {
            jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, SETTLEMENT_LOCK_KEY);
        }
    }

    private SettlementRunResult runWithCheckpoint(LocalDate settlementDate, String batchId, long startNanos) {
        int processed = 0;
        UUID runId = null;
        try {
            settlementRepository.findBatch(batchId).ifPresentOrElse(existing -> {
                if (existing.status() == SettlementBatchStatus.DONE) {
                    throw new AlreadyDone(existing.entriesProcessed());
                }
            }, () -> settlementRepository.insertPendingBatch(batchId, settlementDate));
            settlementRepository.markBatchRunning(batchId);

            int offset = settlementRepository.findBatch(batchId)
                    .map(JdbcSettlementRepository.SettlementBatchRow::entriesProcessed)
                    .orElse(0);
            processed = offset;
            LocalDate tradeDate = minusBusinessDays(settlementDate, 2);

            runId = reconciliationRepository.createRun(ReconciliationRunType.SETTLEMENT);
            while (true) {
                List<SettlementCandidate> chunk = settlementRepository.findSettlementCandidates(tradeDate, offset, chunkSize);
                if (chunk.isEmpty()) {
                    break;
                }

                for (SettlementCandidate candidate : chunk) {
                    UUID unsettledTarget = candidate.unsettledCashBuysAccountId() != null
                            ? candidate.unsettledCashBuysAccountId()
                            : candidate.unsettledCashSalesAccountId();
                    if (candidate.brokerageOmnibusAccountId() == null || unsettledTarget == null) {
                        settlementRepository.insertSettlementSkippedIssue(
                                runId,
                                candidate.referenceId(),
                                candidate.customerId(),
                                "missing brokerage omnibus or unsettled cash account target"
                        );
                        offset++;
                        processed = offset;
                        settlementRepository.updateBatchProgress(batchId, processed);
                        continue;
                    }

                    postingUseCase.post(new PostLedgerEntriesCommand(
                            "SETTLEMENT-" + batchId + "-" + candidate.journalEntryId(),
                            "SETTLEMENT_POSTED",
                            candidate.referenceId(),
                            settlementDate,
                            List.of(
                                    new PostingLeg(candidate.brokerageOmnibusAccountId(), EntryDirection.DEBIT, candidate.amount(), candidate.currency()),
                                    new PostingLeg(unsettledTarget, EntryDirection.CREDIT, candidate.amount(), candidate.currency())
                            )
                    ));

                    offset++;
                    processed = offset;
                    settlementRepository.updateBatchProgress(batchId, processed);
                }
            }

            List<Map<String, Object>> skipped = settlementRepository.findSkippedNonTerminalOrders(tradeDate, 0, chunkSize);
            for (Map<String, Object> row : skipped) {
                settlementRepository.insertSettlementSkippedIssue(
                        runId,
                        (UUID) row.get("reference_id"),
                        (UUID) row.get("customer_id"),
                        "order is non-terminal and was skipped for settlement; state=" + row.get("state")
                );
            }

            settlementRepository.markBatchDone(batchId, processed);
            reconciliationRepository.completeRun(runId, 0, 0);
            meterRegistry.counter("settlement_runs_total", "status", "done").increment();
            meterRegistry.counter("settlement_rows_processed_total").increment(processed);
            Timer.builder("settlement_run_duration_ms")
                    .register(meterRegistry)
                    .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            LOG.info("Settlement batch completed batchId={} settlementDate={} tradeDate={} processed={}",
                    batchId, settlementDate, tradeDate, processed);
            return new SettlementRunResult(batchId, settlementDate, processed, false, true);
        } catch (AlreadyDone done) {
            return new SettlementRunResult(batchId, settlementDate, done.entriesProcessed, false, false);
        } catch (Exception e) {
            settlementRepository.markBatchFailed(batchId, processed, e.getMessage());
            if (runId != null) {
                reconciliationRepository.failRun(runId, e.getMessage());
            }
            meterRegistry.counter("settlement_runs_total", "status", "failed").increment();
            LOG.error("Settlement batch failed batchId={} settlementDate={} processed={} error={}",
                    batchId, settlementDate, processed, e.getMessage(), e);
            throw e;
        }
    }

    public SettlementRunResult runForToday() {
        LocalDate settlementDate = LocalDate.now(ZoneOffset.UTC);
        String batchId = "settlement-" + settlementDate;
        return run(settlementDate, batchId);
    }

    private LocalDate minusBusinessDays(LocalDate value, int businessDays) {
        LocalDate cursor = value;
        int remaining = businessDays;
        while (remaining > 0) {
            cursor = cursor.minusDays(1);
            DayOfWeek day = cursor.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                remaining--;
            }
        }
        return cursor;
    }

    public record SettlementRunResult(String batchId,
                                      LocalDate settlementDate,
                                      int processed,
                                      boolean skippedDueToLock,
                                      boolean completed) {
    }

    private static final class AlreadyDone extends RuntimeException {
        private final int entriesProcessed;

        private AlreadyDone(int entriesProcessed) {
            this.entriesProcessed = entriesProcessed;
        }
    }
}
