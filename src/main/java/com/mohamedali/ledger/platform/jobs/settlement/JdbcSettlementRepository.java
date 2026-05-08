package com.mohamedali.ledger.platform.jobs.settlement;

import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.platform.jobs.reconciliation.ReconciliationIssueType;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcSettlementRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcSettlementRepository(@Qualifier("settlementJdbcTemplate") JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<SettlementBatchRow> findBatch(String batchId) {
        List<SettlementBatchRow> rows = jdbcTemplate.query(
                """
                SELECT batch_id, settlement_date, status, entries_processed, started_at, finished_at, error_message
                FROM settlement_batches
                WHERE batch_id = ?
                """,
                (rs, rowNum) -> new SettlementBatchRow(
                        rs.getString("batch_id"),
                        rs.getDate("settlement_date").toLocalDate(),
                        SettlementBatchStatus.valueOf(rs.getString("status")),
                        rs.getInt("entries_processed"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                        rs.getString("error_message")
                ),
                batchId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void insertPendingBatch(String batchId, LocalDate settlementDate) {
        jdbcTemplate.update(
                """
                INSERT INTO settlement_batches(batch_id, settlement_date, status, entries_processed, started_at)
                VALUES (?, ?, 'PENDING', 0, ?)
                """,
                batchId, Date.valueOf(settlementDate), Timestamp.from(Instant.now())
        );
    }

    public void markBatchRunning(String batchId) {
        jdbcTemplate.update(
                "UPDATE settlement_batches SET status = 'RUNNING' WHERE batch_id = ?",
                batchId
        );
    }

    public void updateBatchProgress(String batchId, int entriesProcessed) {
        jdbcTemplate.update(
                "UPDATE settlement_batches SET entries_processed = ? WHERE batch_id = ?",
                entriesProcessed, batchId
        );
    }

    public void markBatchDone(String batchId, int entriesProcessed) {
        jdbcTemplate.update(
                """
                UPDATE settlement_batches
                SET status = 'DONE', entries_processed = ?, finished_at = ?, error_message = NULL
                WHERE batch_id = ?
                """,
                entriesProcessed, Timestamp.from(Instant.now()), batchId
        );
    }

    public void markBatchFailed(String batchId, int entriesProcessed, String errorMessage) {
        jdbcTemplate.update(
                """
                UPDATE settlement_batches
                SET status = 'FAILED', entries_processed = ?, finished_at = ?, error_message = ?
                WHERE batch_id = ?
                """,
                entriesProcessed, Timestamp.from(Instant.now()), truncate(errorMessage, 2000), batchId
        );
    }

    public List<SettlementCandidate> findSettlementCandidates(LocalDate tradeDate, int offset, int limit) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT
                    je.id AS journal_entry_id,
                    je.reference_id,
                    os.customer_id,
                    os.unsettled_cash_buys_account_id,
                    os.unsettled_cash_sales_account_id,
                    je.amount,
                    je.currency,
                    a.id AS brokerage_omnibus_account_id,
                    src.type AS source_account_type
                FROM journal_entries je
                JOIN accounts src ON src.id = je.account_id
                JOIN order_states os ON os.reference_id = je.reference_id
                LEFT JOIN accounts a
                    ON a.customer_id = os.customer_id
                   AND a.currency = os.currency
                   AND a.type = 'BROKERAGE_OMNIBUS'
                   AND a.status = 'ACTIVE'
                WHERE je.event_type = 'ORDER_FILL'
                  AND je.effective_date = ?
                  AND je.direction = 'DEBIT'
                  AND src.type IN ('UNSETTLED_CASH_BUYS', 'UNSETTLED_CASH_SALES')
                ORDER BY je.id
                OFFSET ? LIMIT ?
                FOR UPDATE OF je SKIP LOCKED
                """,
                Date.valueOf(tradeDate),
                offset,
                limit
        );

        List<SettlementCandidate> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String sourceType = (String) row.get("source_account_type");
            out.add(new SettlementCandidate(
                    (UUID) row.get("journal_entry_id"),
                    (UUID) row.get("reference_id"),
                    (UUID) row.get("customer_id"),
                    sourceType,
                    "UNSETTLED_CASH_BUYS".equals(sourceType) ? (UUID) row.get("unsettled_cash_buys_account_id") : null,
                    "UNSETTLED_CASH_SALES".equals(sourceType) ? (UUID) row.get("unsettled_cash_sales_account_id") : null,
                    (UUID) row.get("brokerage_omnibus_account_id"),
                    (BigDecimal) row.get("amount"),
                    Currency.valueOf((String) row.get("currency"))
            ));
        }
        return out;
    }

    public List<Map<String, Object>> findSkippedNonTerminalOrders(LocalDate tradeDate, int offset, int limit) {
        return jdbcTemplate.queryForList(
                """
                SELECT DISTINCT os.reference_id, os.customer_id, os.state, os.filled_amount, os.currency
                FROM order_states os
                JOIN journal_entries je ON je.reference_id = os.reference_id
                WHERE state IN ('HOLD', 'PARTIALLY_FILLED')
                  AND je.event_type = 'ORDER_FILL'
                  AND je.effective_date = ?
                ORDER BY os.reference_id
                OFFSET ? LIMIT ?
                """,
                Date.valueOf(tradeDate),
                offset,
                limit
        );
    }

    public void insertSettlementSkippedIssue(UUID runId, UUID referenceId, UUID customerId, String reason) {
        Map<String, Object> detailsMap = new HashMap<>();
        detailsMap.put("reason", reason);
        detailsMap.put("referenceId", referenceId == null ? null : referenceId.toString());
        detailsMap.put("customerId", customerId == null ? null : customerId.toString());
        String details = toJson(detailsMap);
        String fingerprint = fingerprint("SETTLEMENT_SKIPPED", referenceId, details);
        jdbcTemplate.update(
                """
                INSERT INTO reconciliation_issues(run_id, issue_type, severity, reference_id, details, issue_fingerprint, first_seen_at, last_seen_at, occurrence_count)
                VALUES (?, ?, 'HIGH', ?, ?::jsonb, ?, now(), now(), 1)
                ON CONFLICT (issue_type, issue_fingerprint)
                DO UPDATE SET
                    run_id = EXCLUDED.run_id,
                    severity = EXCLUDED.severity,
                    reference_id = EXCLUDED.reference_id,
                    details = EXCLUDED.details,
                    last_seen_at = now(),
                    occurrence_count = reconciliation_issues.occurrence_count + 1
                """,
                runId,
                ReconciliationIssueType.SETTLEMENT_SKIPPED.name(),
                referenceId,
                details,
                fingerprint
        );
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
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

    private String fingerprint(String issueType, UUID referenceId, String detailsJson) {
        String raw = issueType + "|" + (referenceId == null ? "" : referenceId) + "|" + detailsJson;
        Integer hash = jdbcTemplate.queryForObject("SELECT hashtext(?)", Integer.class, raw);
        return hash == null ? String.valueOf(raw.hashCode()) : String.valueOf(hash);
    }

    public record SettlementBatchRow(
            String batchId,
            LocalDate settlementDate,
            SettlementBatchStatus status,
            int entriesProcessed,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage
    ) {
    }
}
