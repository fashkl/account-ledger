package com.mohamedali.ledger.platform.seed;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/seed")
public class SeedAdminController {

    private final SeedDataService seedDataService;

    public SeedAdminController(SeedDataService seedDataService) {
        this.seedDataService = seedDataService;
    }

    @PostMapping("/reset")
    public ResponseEntity<SeedRunResult> reset() {
        return toResponse(seedDataService.resetOnly());
    }

    @PostMapping("/run")
    public ResponseEntity<SeedRunResult> runDataset(@RequestParam(defaultValue = "medium") String dataset,
                                                    @RequestParam(defaultValue = "true") boolean reset) {
        return toResponse(seedDataService.runDataset(dataset, reset));
    }

    @PostMapping("/scenario")
    public ResponseEntity<SeedRunResult> runScenario(@RequestParam String name,
                                                     @RequestParam(defaultValue = "false") boolean reset) {
        return toResponse(seedDataService.runScenario(name, reset));
    }

    @GetMapping("/catalog")
    public ResponseEntity<Map<String, Object>> catalog() {
        SeedCatalog catalog = seedDataService.catalog();
        return ResponseEntity.ok(Map.of(
                "defaultDataset", catalog.defaultDataset(),
                "scenarios", catalog.scenarios(),
                "knownIds", catalog.knownIds(),
                "datasetShape", catalog.datasetShape()
        ));
    }

    private ResponseEntity<SeedRunResult> toResponse(SeedRunResult result) {
        if (result.blocked()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
        }
        if (result.skippedDueToLock()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        }
        return ResponseEntity.ok(result);
    }
}
