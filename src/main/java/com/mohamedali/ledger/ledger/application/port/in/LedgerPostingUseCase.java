package com.mohamedali.ledger.ledger.application.port.in;

import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesCommand;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesResult;
import jakarta.validation.Valid;

public interface LedgerPostingUseCase {
    PostLedgerEntriesResult post(@Valid PostLedgerEntriesCommand command);
}
