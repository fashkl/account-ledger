package com.mohamedali.ledger.platform.seed;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SeedRunResult(
        UUID runId,
        String dataset,
        String scenario,
        boolean resetApplied,
        boolean blocked,
        boolean skippedDueToLock,
        List<String> warnings,
        Map<String, Integer> recordsCreatedByTable,
        long durationMs,
        String message
) {
}
