package com.mohamedali.ledger.platform.jobs.reconciliation;

public enum ReconciliationIssueType {
    SNAPSHOT_MISMATCH,
    JOURNAL_INVARIANT,
    SETTLEMENT_SKIPPED,
    BANK_STATEMENT_MISMATCH
}

