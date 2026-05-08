package com.mohamedali.ledger.platform.health;

import com.mohamedali.ledger.platform.jobs.reconciliation.ReconciliationService;
import com.mohamedali.ledger.platform.jobs.settlement.SettlementService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/jobs")
public class JobsAdminController {

    private final SettlementService settlementService;
    private final ReconciliationService reconciliationService;

    public JobsAdminController(SettlementService settlementService,
                               ReconciliationService reconciliationService) {
        this.settlementService = settlementService;
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/settlement/run")
    public ResponseEntity<Map<String, Object>> runSettlement(@RequestParam LocalDate settlementDate,
                                                             @RequestParam String batchId) {
        var result = settlementService.run(settlementDate, batchId);
        return ResponseEntity.ok(Map.of(
                "batchId", result.batchId(),
                "settlementDate", result.settlementDate(),
                "processed", result.processed(),
                "skippedDueToLock", result.skippedDueToLock(),
                "completed", result.completed()
        ));
    }

    @PostMapping("/reconciliation/run")
    public ResponseEntity<Map<String, Object>> runReconciliation() {
        var result = reconciliationService.runPeriodic();
        return ResponseEntity.ok(Map.of(
                "runId", result.runId(),
                "mismatchCount", result.mismatchCount(),
                "invariantViolationCount", result.invariantViolationCount(),
                "skippedDueToLock", result.skippedDueToLock()
        ));
    }
}

