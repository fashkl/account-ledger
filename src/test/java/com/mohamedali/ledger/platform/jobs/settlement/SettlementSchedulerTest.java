package com.mohamedali.ledger.platform.jobs.settlement;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SettlementSchedulerTest {

    @Test
    void runDailySettlement_invokesService() {
        SettlementService service = mock(SettlementService.class);
        when(service.runForToday()).thenReturn(
                new SettlementService.SettlementRunResult("settlement-2026-01-01", LocalDate.of(2026, 1, 1), 10, false, true)
        );
        SettlementScheduler scheduler = new SettlementScheduler(service);
        scheduler.runDailySettlement();
        verify(service, times(1)).runForToday();
    }
}

