package com.mohamedali.ledger.ledger.application.port.out;

import com.mohamedali.ledger.ledger.domain.model.AccountInfo;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesCommand;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesResult;
import com.mohamedali.ledger.ledger.domain.model.PostingLeg;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface LedgerPostingPersistencePort {

    Map<UUID, AccountInfo> getAccountInfoById(List<UUID> accountIds);

    PostLedgerEntriesResult reserveIdempotencyOrGetExisting(String idempotencyKey,
                                                            UUID entryGroupId,
                                                            String eventType,
                                                            UUID referenceId);

    void insertJournalEntries(UUID entryGroupId, PostLedgerEntriesCommand command);

    void applySnapshotDeltas(UUID entryGroupId, List<PostingLeg> legs, Map<UUID, AccountInfo> accountInfoById);

    Optional<BigDecimal> getBalance(UUID accountId);
}
