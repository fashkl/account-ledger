package com.mohamedali.ledger.platform.jobs.withdrawal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WithdrawalTimeoutScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(WithdrawalTimeoutScheduler.class);

    private final WithdrawalTimeoutJob timeoutJob;

    public WithdrawalTimeoutScheduler(WithdrawalTimeoutJob timeoutJob) {
        this.timeoutJob = timeoutJob;
        LOG.info("Withdrawal timeout scheduler enabled with hourly cadence (UTC), cron='0 0 * * * *'");
    }

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void runHourlyTimeoutSweep() {
        int processed = timeoutJob.runOnce();
        LOG.info("Withdrawal timeout scheduler run completed, processed={}", processed);
    }
}

