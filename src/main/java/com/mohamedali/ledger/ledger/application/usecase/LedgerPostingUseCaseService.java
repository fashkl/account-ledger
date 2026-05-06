package com.mohamedali.ledger.ledger.application.usecase;

import com.mohamedali.ledger.ledger.application.port.in.LedgerBalanceQuery;
import com.mohamedali.ledger.ledger.application.port.in.LedgerPostingUseCase;
import com.mohamedali.ledger.ledger.application.port.out.LedgerPostingPersistencePort;
import com.mohamedali.ledger.ledger.domain.exception.InvalidPostingStructureException;
import com.mohamedali.ledger.ledger.domain.model.AccountInfo;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesCommand;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesResult;
import com.mohamedali.ledger.ledger.domain.service.BalancedPostingPolicy;
import com.mohamedali.ledger.shared.exception.AccountClosedException;
import com.mohamedali.ledger.shared.exception.AccountNotFoundException;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class LedgerPostingUseCaseService implements LedgerPostingUseCase, LedgerBalanceQuery {

    private final BalancedPostingPolicy postingPolicy;
    private final LedgerPostingPersistencePort persistencePort;

    public LedgerPostingUseCaseService(BalancedPostingPolicy postingPolicy,
                                       LedgerPostingPersistencePort persistencePort) {
        this.postingPolicy = postingPolicy;
        this.persistencePort = persistencePort;
    }

    @Override
    @Transactional
    public PostLedgerEntriesResult post(@Valid PostLedgerEntriesCommand command) {
        postingPolicy.validate(command);
        validateEffectiveDate(command.effectiveDate());

        List<UUID> accountIds = command.legs().stream().map(leg -> leg.accountId()).distinct().toList();
        Map<UUID, AccountInfo> accountInfoById = persistencePort.getAccountInfoById(accountIds);
        validateAccounts(accountIds, accountInfoById);

        UUID entryGroupId = UUID.randomUUID();
        PostLedgerEntriesResult reservation = persistencePort.reserveIdempotencyOrGetExisting(
                command.idempotencyKey(),
                entryGroupId,
                command.eventType(),
                command.referenceId()
        );

        if (reservation.duplicate()) {
            return reservation;
        }

        persistencePort.insertJournalEntries(entryGroupId, command);
        persistencePort.applySnapshotDeltas(entryGroupId, command.legs(), accountInfoById);

        return reservation;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getAccountBalance(UUID accountId) {
        return persistencePort.getBalance(accountId).orElse(BigDecimal.ZERO);
    }

    private void validateEffectiveDate(LocalDate effectiveDate) {
        LocalDate utcToday = LocalDate.now(ZoneOffset.UTC);
        if (effectiveDate.isAfter(utcToday)) {
            throw new InvalidPostingStructureException("effectiveDate cannot be in the future");
        }
    }

    private void validateAccounts(List<UUID> accountIds, Map<UUID, AccountInfo> accountInfoById) {
        for (UUID accountId : accountIds) {
            AccountInfo accountInfo = accountInfoById.get(accountId);
            if (accountInfo == null) {
                throw new AccountNotFoundException(accountId);
            }
            if (!accountInfo.active()) {
                throw new AccountClosedException(accountId);
            }
        }
    }
}
