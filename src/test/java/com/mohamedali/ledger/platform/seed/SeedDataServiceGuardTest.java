package com.mohamedali.ledger.platform.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mohamedali.ledger.ledger.application.port.in.LedgerPostingUseCase;
import com.mohamedali.ledger.ledger.application.port.in.order.OrderLifecycleUseCase;
import com.mohamedali.ledger.ledger.application.port.in.withdrawal.CashMovementUseCase;
import com.mohamedali.ledger.platform.jobs.withdrawal.WithdrawalTimeoutJob;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

class SeedDataServiceGuardTest {

    @Test
    void seedDisabledInNonLocalProfile_isBlocked() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LedgerPostingUseCase postingUseCase = mock(LedgerPostingUseCase.class);
        OrderLifecycleUseCase orderLifecycleUseCase = mock(OrderLifecycleUseCase.class);
        CashMovementUseCase cashMovementUseCase = mock(CashMovementUseCase.class);
        WithdrawalTimeoutJob timeoutJob = mock(WithdrawalTimeoutJob.class);
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});

        SeedDataService service = new SeedDataService(
                jdbcTemplate,
                postingUseCase,
                orderLifecycleUseCase,
                cashMovementUseCase,
                timeoutJob,
                environment,
                false,
                "medium"
        );

        SeedRunResult result = service.runDataset("medium", true);

        assertThat(result.blocked()).isTrue();
        assertThat(result.message()).contains("disabled");
        verifyNoInteractions(jdbcTemplate, postingUseCase, orderLifecycleUseCase, cashMovementUseCase, timeoutJob);
    }
}
