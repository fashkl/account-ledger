package com.mohamedali.ledger.platform.seed;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SeedCatalog(
        String defaultDataset,
        List<String> scenarios,
        Map<String, UUID> knownIds,
        Map<String, String> datasetShape
) {
}
