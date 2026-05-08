package com.mohamedali.ledger.platform.jobs.reconciliation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationSchedulerTest {

    @Test
    void runPeriodic_invokesService() {
        ReconciliationService service = mock(ReconciliationService.class);
        when(service.runPeriodic()).thenReturn(new ReconciliationService.ReconciliationRunResult(UUID.randomUUID(), 0, 0, false));
        ReconciliationScheduler scheduler = new ReconciliationScheduler(service);
        scheduler.runPeriodic();
        verify(service, times(1)).runPeriodic();
    }

    @Test
    void runDailyBankCheck_invokesService() {
        ReconciliationService service = mock(ReconciliationService.class);
        when(service.runDailyBankCheck()).thenReturn(new ReconciliationService.ReconciliationRunResult(UUID.randomUUID(), 0, 0, false));
        ReconciliationScheduler scheduler = new ReconciliationScheduler(service);
        scheduler.runDailyBankCheck();
        verify(service, times(1)).runDailyBankCheck();
    }
}
