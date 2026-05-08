package com.mohamedali.ledger.platform.jobs.settlement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SettlementScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(SettlementScheduler.class);

    private final SettlementService settlementService;

    public SettlementScheduler(SettlementService settlementService) {
        this.settlementService = settlementService;
        LOG.info("Settlement scheduler enabled with daily UTC cadence, cron='${ledger.jobs.settlement.cron:0 15 0 * * *}'");
    }

    @Scheduled(cron = "${ledger.jobs.settlement.cron:0 15 0 * * *}", zone = "UTC")
    public void runDailySettlement() {
        SettlementService.SettlementRunResult result = settlementService.runForToday();
        LOG.info("Settlement scheduler run completed batchId={} settlementDate={} processed={} skippedDueToLock={} completed={}",
                result.batchId(), result.settlementDate(), result.processed(), result.skippedDueToLock(), result.completed());
    }
}

