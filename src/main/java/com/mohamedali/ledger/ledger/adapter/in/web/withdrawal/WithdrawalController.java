package com.mohamedali.ledger.ledger.adapter.in.web.withdrawal;

import com.mohamedali.ledger.ledger.adapter.in.web.withdrawal.dto.CashMovementRequest;
import com.mohamedali.ledger.ledger.application.port.in.LedgerBalanceQuery;
import com.mohamedali.ledger.ledger.application.port.in.withdrawal.CashMovementUseCase;
import com.mohamedali.ledger.ledger.application.port.in.withdrawal.WithdrawalStatusQuery;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.WithdrawalRecord;
import com.mohamedali.ledger.shared.tracing.DomainMdc;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cash")
public class WithdrawalController {

    private final CashMovementUseCase cashMovementUseCase;
    private final WithdrawalStatusQuery withdrawalStatusQuery;
    private final LedgerBalanceQuery balanceQuery;

    public WithdrawalController(CashMovementUseCase cashMovementUseCase,
                                WithdrawalStatusQuery withdrawalStatusQuery,
                                LedgerBalanceQuery balanceQuery) {
        this.cashMovementUseCase = cashMovementUseCase;
        this.withdrawalStatusQuery = withdrawalStatusQuery;
        this.balanceQuery = balanceQuery;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> handle(@Valid @RequestBody CashMovementRequest request) {
        DomainMdc.putIfPresent(DomainMdc.EVENT_ID, request.eventId());
        DomainMdc.putIfPresent(DomainMdc.CUSTOMER_ID, request.customerId());
        DomainMdc.putIfPresent(DomainMdc.REFERENCE_ID,
                request.withdrawalId() != null ? request.withdrawalId() : request.eventId());
        cashMovementUseCase.handle(request.toCommand());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/withdrawals/{withdrawalId}")
    public ResponseEntity<Map<String, Object>> getWithdrawal(@PathVariable UUID withdrawalId) {
        WithdrawalRecord record = withdrawalStatusQuery.getWithdrawal(withdrawalId);
        return ResponseEntity.ok(Map.of(
                "withdrawalId", record.id(),
                "status", record.status(),
                "amount", record.amount(),
                "currency", record.currency(),
                "pendingSince", record.pendingSince()
        ));
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<Map<String, Object>> balance(@PathVariable UUID accountId) {
        BigDecimal balance = balanceQuery.getAccountBalance(accountId);
        return ResponseEntity.ok(Map.of("accountId", accountId, "balance", balance));
    }
}
