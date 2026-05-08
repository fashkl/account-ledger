package com.mohamedali.ledger.platform.health;

import com.mohamedali.ledger.platform.jobs.reconciliation.ReconciliationService;
import com.mohamedali.ledger.platform.jobs.settlement.SettlementService;
import com.mohamedali.ledger.platform.kafka.KafkaConsumerControlService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
    private final KafkaConsumerControlService kafkaConsumerControlService;

    public JobsAdminController(SettlementService settlementService,
                               ReconciliationService reconciliationService,
                               KafkaConsumerControlService kafkaConsumerControlService) {
        this.settlementService = settlementService;
        this.reconciliationService = reconciliationService;
        this.kafkaConsumerControlService = kafkaConsumerControlService;
    }

    @PostMapping("/settlement/run")
    @CircuitBreaker(name = "ledgerProcessing", fallbackMethod = "fallbackServiceUnavailable")
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
    @CircuitBreaker(name = "ledgerProcessing", fallbackMethod = "fallbackServiceUnavailable")
    public ResponseEntity<Map<String, Object>> runReconciliation() {
        var result = reconciliationService.runPeriodic();
        return ResponseEntity.ok(Map.of(
                "runId", result.runId(),
                "mismatchCount", result.mismatchCount(),
                "invariantViolationCount", result.invariantViolationCount(),
                "skippedDueToLock", result.skippedDueToLock()
        ));
    }

    @PostMapping("/kafka/pause")
    public ResponseEntity<Map<String, Object>> pauseKafkaConsumer(@RequestParam(defaultValue = "manual") String reason) {
        kafkaConsumerControlService.pause("ledger-events-consumer", reason);
        return ResponseEntity.ok(Map.of("paused", true, "reason", reason));
    }

    @PostMapping("/kafka/resume")
    public ResponseEntity<Map<String, Object>> resumeKafkaConsumer(@RequestParam(defaultValue = "manual") String reason) {
        kafkaConsumerControlService.resume("ledger-events-consumer", reason);
        return ResponseEntity.ok(Map.of("paused", false, "reason", reason));
    }

    private ResponseEntity<Map<String, Object>> fallbackServiceUnavailable(LocalDate settlementDate,
                                                                           String batchId,
                                                                           Throwable throwable) {
        return serviceUnavailableResponse(throwable);
    }

    private ResponseEntity<Map<String, Object>> fallbackServiceUnavailable(Throwable throwable) {
        return serviceUnavailableResponse(throwable);
    }

    private ResponseEntity<Map<String, Object>> serviceUnavailableResponse(Throwable throwable) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "30");
        return new ResponseEntity<>(Map.of(
                "status", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "error", "Service temporarily unavailable",
                "message", throwable.getMessage()
        ), headers, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
