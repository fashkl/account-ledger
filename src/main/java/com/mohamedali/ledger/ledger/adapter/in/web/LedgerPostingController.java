package com.mohamedali.ledger.ledger.adapter.in.web;

import com.mohamedali.ledger.ledger.adapter.in.web.dto.PostLedgerEntriesRequest;
import com.mohamedali.ledger.ledger.adapter.in.web.dto.PostLedgerEntriesResponse;
import com.mohamedali.ledger.ledger.application.port.in.LedgerBalanceQuery;
import com.mohamedali.ledger.ledger.application.port.in.LedgerPostingUseCase;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesResult;
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
@RequestMapping("/api/v1/ledger")
public class LedgerPostingController {

    private final LedgerPostingUseCase postingUseCase;
    private final LedgerBalanceQuery balanceQuery;

    public LedgerPostingController(LedgerPostingUseCase postingUseCase,
                                   LedgerBalanceQuery balanceQuery) {
        this.postingUseCase = postingUseCase;
        this.balanceQuery = balanceQuery;
    }

    @PostMapping("/postings")
    public ResponseEntity<PostLedgerEntriesResponse> post(@Valid @RequestBody PostLedgerEntriesRequest request) {
        DomainMdc.putIfPresent(DomainMdc.REFERENCE_ID, request.referenceId());
        PostLedgerEntriesResult result = postingUseCase.post(request.toCommand());
        DomainMdc.putIfPresent(DomainMdc.ENTRY_GROUP_ID, result.entryGroupId());
        return ResponseEntity.ok(new PostLedgerEntriesResponse(result.entryGroupId(), result.duplicate()));
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable UUID accountId) {
        BigDecimal balance = balanceQuery.getAccountBalance(accountId);
        return ResponseEntity.ok(Map.of(
                "accountId", accountId,
                "balance", balance
        ));
    }
}
