package com.mohamedali.ledger.ledger.adapter.out.persistence;

import com.mohamedali.ledger.ledger.application.port.out.LedgerPostingPersistencePort;
import com.mohamedali.ledger.ledger.domain.model.AccountInfo;
import com.mohamedali.ledger.ledger.domain.model.EntryDirection;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesCommand;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesResult;
import com.mohamedali.ledger.ledger.domain.model.PostingLeg;
import com.mohamedali.ledger.ledger.domain.service.AccountTypePolicy;
import com.mohamedali.ledger.shared.exception.IdempotencyKeyCollisionException;
import com.mohamedali.ledger.shared.exception.InsufficientFundsException;
import java.math.BigDecimal;
import java.sql.Statement;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcLedgerPostingAdapter implements LedgerPostingPersistencePort {

    private final JdbcTemplate jdbcTemplate;
    private final AccountTypePolicy accountTypePolicy;

    public JdbcLedgerPostingAdapter(JdbcTemplate jdbcTemplate, AccountTypePolicy accountTypePolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountTypePolicy = accountTypePolicy;
    }

    @Override
    public Map<UUID, AccountInfo> getAccountInfoById(List<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(accountIds.size(), "?"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, type, status FROM accounts WHERE id IN (" + placeholders + ")",
                accountIds.toArray()
        );

        Map<UUID, AccountInfo> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            String status = (String) row.get("status");
            String type = (String) row.get("type");
            result.put(id, new AccountInfo(id, type, status));
        }
        return result;
    }

    @Override
    public PostLedgerEntriesResult reserveIdempotencyOrGetExisting(String idempotencyKey,
                                                                   UUID entryGroupId,
                                                                   String eventType,
                                                                   UUID referenceId) {
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO ledger_postings(idempotency_key, entry_group_id, event_type, reference_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """,
                idempotencyKey,
                entryGroupId,
                eventType,
                referenceId
        );

        if (inserted == 1) {
            return new PostLedgerEntriesResult(entryGroupId, false);
        }

        Map<String, Object> existing = jdbcTemplate.queryForMap(
                "SELECT entry_group_id, event_type, reference_id FROM ledger_postings WHERE idempotency_key = ?",
                idempotencyKey
        );

        String existingEventType = (String) existing.get("event_type");
        UUID existingReferenceId = (UUID) existing.get("reference_id");

        if (!Objects.equals(existingEventType, eventType) || !Objects.equals(existingReferenceId, referenceId)) {
            throw new IdempotencyKeyCollisionException(idempotencyKey);
        }

        UUID existingEntryGroup = (UUID) existing.get("entry_group_id");
        return new PostLedgerEntriesResult(existingEntryGroup, true);
    }

    @Override
    public void insertJournalEntries(UUID entryGroupId, PostLedgerEntriesCommand command) {
        String sql = """
                INSERT INTO journal_entries(entry_group_id, account_id, direction, amount, currency, event_type, reference_id, effective_date, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        List<Object[]> batchArgs = command.legs().stream().map(leg -> new Object[]{
                entryGroupId,
                leg.accountId(),
                leg.direction().name(),
                leg.amount(),
                leg.currency().name(),
                command.eventType(),
                command.referenceId(),
                Date.valueOf(command.effectiveDate()),
                command.idempotencyKey()
        }).toList();

        int[] updates = jdbcTemplate.batchUpdate(sql, batchArgs);
        for (int update : updates) {
            if (update != 1 && update != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("Unexpected batch insert result for journal entry: " + update);
            }
        }
    }

    @Override
    public void applySnapshotDeltas(UUID entryGroupId, List<PostingLeg> legs, Map<UUID, AccountInfo> accountInfoById) {
        for (PostingLeg leg : legs) {
            BigDecimal delta = leg.direction() == EntryDirection.DEBIT ? leg.amount() : leg.amount().negate();
            AccountInfo accountInfo = accountInfoById.get(leg.accountId());
            applySingleBalanceDeltaWithPessimisticLock(leg.accountId(), accountInfo.type(), delta, entryGroupId);
        }
    }

    private void applySingleBalanceDeltaWithPessimisticLock(UUID accountId,
                                                            String accountType,
                                                            BigDecimal delta,
                                                            UUID entryGroupId) {
        BigDecimal minBalance = accountTypePolicy.minBalance(accountType);
        for (int attempt = 0; attempt < 2; attempt++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT balance, version FROM account_balances WHERE account_id = ? FOR UPDATE",
                    accountId
            );

            if (rows.isEmpty()) {
                BigDecimal newBalance = delta;
                if (minBalance != null && newBalance.compareTo(minBalance) < 0) {
                    throw new InsufficientFundsException(accountId, minBalance.subtract(newBalance));
                }

                int inserted = jdbcTemplate.update(
                        """
                        INSERT INTO account_balances(account_id, balance, version, last_entry_id, updated_at)
                        VALUES (?, ?, 0, ?, ?)
                        ON CONFLICT (account_id) DO NOTHING
                        """,
                        accountId,
                        newBalance,
                        entryGroupId,
                        Timestamp.from(java.time.Instant.now())
                );
                if (inserted == 1) {
                    return;
                }
                continue;
            }

            BigDecimal current = (BigDecimal) rows.get(0).get("balance");
            BigDecimal newBalance = current.add(delta);

            if (minBalance != null && newBalance.compareTo(minBalance) < 0) {
                throw new InsufficientFundsException(accountId, minBalance.subtract(newBalance));
            }

            jdbcTemplate.update(
                    """
                    UPDATE account_balances
                    SET balance = ?,
                        version = version + 1,
                        last_entry_id = ?,
                        updated_at = ?
                    WHERE account_id = ?
                    """,
                    newBalance,
                    entryGroupId,
                    Timestamp.from(java.time.Instant.now()),
                    accountId
            );
            return;
        }

        throw new IllegalStateException("Failed to apply snapshot delta for account " + accountId);
    }

    @Override
    public Optional<BigDecimal> getBalance(UUID accountId) {
        try {
            BigDecimal balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM account_balances WHERE account_id = ?",
                    BigDecimal.class,
                    accountId
            );
            return Optional.ofNullable(balance);
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }
}
