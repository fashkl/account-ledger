package com.mohamedali.ledger.platform.jobs.withdrawal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class WithdrawalTimeoutSchedulerTest {

    @Test
    void runHourlyTimeoutSweep_invokesTimeoutJob() {
        WithdrawalTimeoutJob job = mock(WithdrawalTimeoutJob.class);
        when(job.runOnce()).thenReturn(3);

        WithdrawalTimeoutScheduler scheduler = new WithdrawalTimeoutScheduler(job);
        scheduler.runHourlyTimeoutSweep();

        verify(job, times(1)).runOnce();
    }
}

