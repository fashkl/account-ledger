package com.mohamedali.ledger.ledger.adapter.in.web.dto;

import java.util.UUID;

public record PostLedgerEntriesResponse(
        UUID entryGroupId,
        boolean duplicate
) {
}
