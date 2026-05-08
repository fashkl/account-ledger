package com.mohamedali.ledger.shared.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mohamedali.ledger.ledger.adapter.in.web.LedgerPostingController;
import com.mohamedali.ledger.ledger.adapter.in.web.dto.PostLedgerEntriesRequest;
import com.mohamedali.ledger.ledger.adapter.in.web.order.OrderLifecycleController;
import com.mohamedali.ledger.ledger.adapter.in.web.order.dto.OrderLifecycleRequest;
import com.mohamedali.ledger.ledger.adapter.in.web.withdrawal.WithdrawalController;
import com.mohamedali.ledger.ledger.adapter.in.web.withdrawal.dto.CashMovementRequest;
import com.mohamedali.ledger.ledger.application.port.in.LedgerBalanceQuery;
import com.mohamedali.ledger.ledger.application.port.in.LedgerPostingUseCase;
import com.mohamedali.ledger.ledger.application.port.in.order.BuyingPowerQuery;
import com.mohamedali.ledger.ledger.application.port.in.order.OrderLifecycleUseCase;
import com.mohamedali.ledger.ledger.application.port.in.withdrawal.CashMovementUseCase;
import com.mohamedali.ledger.ledger.application.port.in.withdrawal.WithdrawalStatusQuery;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.ledger.domain.model.EntryDirection;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesResult;
import com.mohamedali.ledger.ledger.domain.model.order.OrderEventType;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.CashMovementEventType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ControllerMdcEnrichmentTest {

    @AfterEach
    void clearMdc() {
        DomainMdc.clearDomainKeys();
    }

    @Test
    void ledgerPostingController_setsReferenceAndEntryGroupMdcFromPayloadAndResult() {
        LedgerPostingUseCase useCase = mock(LedgerPostingUseCase.class);
        LedgerBalanceQuery balanceQuery = mock(LedgerBalanceQuery.class);

        UUID referenceId = UUID.randomUUID();
        UUID entryGroupId = UUID.randomUUID();
        when(useCase.post(any())).thenReturn(new PostLedgerEntriesResult(entryGroupId, false));
        doAnswer(inv -> {
            assertThat(MDC.get(DomainMdc.REFERENCE_ID)).isEqualTo(referenceId.toString());
            return new PostLedgerEntriesResult(entryGroupId, false);
        }).when(useCase).post(any());

        LedgerPostingController controller = new LedgerPostingController(useCase, balanceQuery);
        controller.post(new PostLedgerEntriesRequest(
                "idemp-1",
                "ORDER_HOLD",
                referenceId,
                LocalDate.now(ZoneOffset.UTC),
                List.of(
                        new PostLedgerEntriesRequest.PostingLegRequest(
                                UUID.randomUUID(),
                                EntryDirection.DEBIT,
                                new BigDecimal("10.00"),
                                Currency.AED
                        ),
                        new PostLedgerEntriesRequest.PostingLegRequest(
                                UUID.randomUUID(),
                                EntryDirection.CREDIT,
                                new BigDecimal("10.00"),
                                Currency.AED
                        )
                )
        ));

        assertThat(MDC.get(DomainMdc.ENTRY_GROUP_ID)).isEqualTo(entryGroupId.toString());
    }

    @Test
    void orderLifecycleController_setsReferenceAndCustomerMdcFromPayload() {
        OrderLifecycleUseCase useCase = mock(OrderLifecycleUseCase.class);
        BuyingPowerQuery buyingPowerQuery = mock(BuyingPowerQuery.class);
        UUID referenceId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        doAnswer(inv -> {
            assertThat(MDC.get(DomainMdc.REFERENCE_ID)).isEqualTo(referenceId.toString());
            assertThat(MDC.get(DomainMdc.CUSTOMER_ID)).isEqualTo(customerId.toString());
            return null;
        }).when(useCase).handle(any());

        OrderLifecycleController controller = new OrderLifecycleController(useCase, buyingPowerQuery);
        controller.handle(new OrderLifecycleRequest(
                referenceId,
                OrderEventType.ORDER_CREATED,
                customerId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Currency.AED,
                new BigDecimal("100.00"),
                null,
                null,
                null
        ));
    }

    @Test
    void withdrawalController_setsEventReferenceAndCustomerMdcFromPayload() {
        CashMovementUseCase useCase = mock(CashMovementUseCase.class);
        WithdrawalStatusQuery statusQuery = mock(WithdrawalStatusQuery.class);
        LedgerBalanceQuery balanceQuery = mock(LedgerBalanceQuery.class);
        UUID eventId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        doAnswer(inv -> {
            assertThat(MDC.get(DomainMdc.EVENT_ID)).isEqualTo(eventId.toString());
            assertThat(MDC.get(DomainMdc.REFERENCE_ID)).isEqualTo(eventId.toString());
            assertThat(MDC.get(DomainMdc.CUSTOMER_ID)).isEqualTo(customerId.toString());
            return null;
        }).when(useCase).handle(any());

        WithdrawalController controller = new WithdrawalController(useCase, statusQuery, balanceQuery);
        controller.handle(new CashMovementRequest(
                CashMovementEventType.VA_CREDITED,
                eventId,
                null,
                null,
                customerId,
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                new BigDecimal("50.00"),
                Currency.AED
        ));
    }
}
