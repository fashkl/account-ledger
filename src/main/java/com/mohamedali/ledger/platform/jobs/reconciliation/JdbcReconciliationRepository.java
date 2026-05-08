package com.mohamedali.ledger.platform.jobs.reconciliation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcReconciliationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTx;

    public JdbcReconciliationRepository(JdbcTemplate jdbcTemplate,
                                        ObjectMapper objectMapper,
                                        PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
    }

    public UUID createRun(ReconciliationRunType type) {
        return requiresNewTx.execute(status -> {
            UUID runId = UUID.randomUUID();
            jdbcTemplate.update(
                    """
                    INSERT INTO reconciliation_runs(run_id, run_type, status, started_at)
                    VALUES (?, ?, 'RUNNING', ?)
                    """,
                    runId,
                    type.name(),
                    Timestamp.from(Instant.now())
            );
            return runId;
        });
    }

    public void completeRun(UUID runId, int mismatchCount, int invariantViolationCount) {
        jdbcTemplate.update(
                """
                UPDATE reconciliation_runs
                SET status = 'DONE',
                    finished_at = ?,
                    mismatch_count = ?,
                    invariant_violation_count = ?,
                    error_message = NULL
                WHERE run_id = ?
                """,
                Timestamp.from(Instant.now()),
                mismatchCount,
                invariantViolationCount,
                runId
        );
    }

    public void failRun(UUID runId, String errorMessage) {
        requiresNewTx.executeWithoutResult(status -> jdbcTemplate.update(
            """
            UPDATE reconciliation_runs
            SET status = 'FAILED',
                finished_at = ?,
                error_message = ?
            WHERE run_id = ?
            """,
            Timestamp.from(Instant.now()),
            truncate(errorMessage, 2000),
            runId
        ));
    }

    public void insertIssue(UUID runId,
                            ReconciliationIssueType issueType,
                            String severity,
                            UUID accountId,
                            UUID referenceId,
                            Map<String, Object> details) {
        String detailsJson = toJson(details);
        String fingerprint = fingerprint(issueType, accountId, referenceId, detailsJson);
        jdbcTemplate.update(
                """
                INSERT INTO reconciliation_issues(run_id, issue_type, severity, account_id, reference_id, details, issue_fingerprint, first_seen_at, last_seen_at, occurrence_count)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, now(), now(), 1)
                ON CONFLICT (issue_type, issue_fingerprint)
                DO UPDATE SET
                    run_id = EXCLUDED.run_id,
                    severity = EXCLUDED.severity,
                    account_id = EXCLUDED.account_id,
                    reference_id = EXCLUDED.reference_id,
                    details = EXCLUDED.details,
                    last_seen_at = now(),
                    occurrence_count = reconciliation_issues.occurrence_count + 1
                """,
                runId,
                issueType.name(),
                severity,
                accountId,
                referenceId,
                detailsJson,
                fingerprint
        );
    }

    private String toJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize reconciliation issue details", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private String fingerprint(ReconciliationIssueType issueType, UUID accountId, UUID referenceId, String detailsJson) {
        String raw;
        switch (issueType) {
            case SNAPSHOT_MISMATCH -> raw = issueType.name() + "|" + Objects.toString(accountId, "");
            case JOURNAL_INVARIANT -> raw = issueType.name() + "|" + Objects.toString(referenceId, "");
            default -> raw = issueType.name() + "|" + Objects.toString(accountId, "") + "|" + Objects.toString(referenceId, "") + "|" + detailsJson;
        }
        Integer hash = jdbcTemplate.queryForObject("SELECT hashtext(?)", Integer.class, raw);
        return hash == null ? String.valueOf(raw.hashCode()) : String.valueOf(hash);
    }
}
