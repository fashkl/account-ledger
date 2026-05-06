package com.mohamedali.ledger.ledger.domain.model;

import java.util.UUID;

public record AccountInfo(UUID accountId, String type, String status) {
    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
