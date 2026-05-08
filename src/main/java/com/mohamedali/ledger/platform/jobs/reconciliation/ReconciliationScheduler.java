package com.mohamedali.ledger.platform.jobs.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;

    public ReconciliationScheduler(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
        LOG.info("Reconciliation scheduler enabled with 4-hour UTC cadence, cron='${ledger.jobs.reconciliation.cron:0 0 */4 * * *}'");
    }

    @Scheduled(cron = "${ledger.jobs.reconciliation.cron:0 0 */4 * * *}", zone = "UTC")
    public void runPeriodic() {
        ReconciliationService.ReconciliationRunResult result = reconciliationService.runPeriodic();
        LOG.info("Reconciliation periodic run completed runId={} mismatchCount={} invariantViolationCount={} skippedDueToLock={}",
                result.runId(), result.mismatchCount(), result.invariantViolationCount(), result.skippedDueToLock());
    }

    @Scheduled(cron = "${ledger.jobs.reconciliation.bank-check-cron:0 30 1 * * *}", zone = "UTC")
    public void runDailyBankCheck() {
        ReconciliationService.ReconciliationRunResult result = reconciliationService.runDailyBankCheck();
        LOG.info("Reconciliation daily bank check completed runId={} mismatchCount={}",
                result.runId(), result.mismatchCount());
    }
}
