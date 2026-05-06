package com.mohamedali.ledger.ledger.domain.model;

import java.util.UUID;

public record PostLedgerEntriesResult(
        UUID entryGroupId,
        boolean duplicate
) {
}
