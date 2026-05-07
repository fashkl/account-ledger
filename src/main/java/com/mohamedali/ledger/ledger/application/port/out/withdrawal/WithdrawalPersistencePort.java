package com.mohamedali.ledger.ledger.application.port.out.withdrawal;

import com.mohamedali.ledger.ledger.domain.model.withdrawal.WithdrawalRecord;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.WithdrawalStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WithdrawalPersistencePort {

    /**
     * Locks the SETTLED_CASH account_balances row FOR UPDATE and returns the current balance.
     * Must be called inside an active transaction before the NSF check.
     */
    java.math.BigDecimal lockSettledCashBalance(UUID settledCashAccountId);

    void insertWithdrawal(WithdrawalRecord record);

    /**
     * Loads the withdrawal row by ID and acquires FOR UPDATE lock on it.
     * Returns empty if not found.
     */
    Optional<WithdrawalRecord> findForUpdate(UUID withdrawalId);

    Optional<WithdrawalRecord> findById(UUID withdrawalId);

    void updateStatus(UUID withdrawalId, WithdrawalStatus status);

    /**
     * Returns PENDING withdrawals whose pending_since < threshold, up to limit rows.
     * Uses SKIP LOCKED so concurrent job invocations don't collide.
     */
    List<WithdrawalRecord> findExpiredPending(OffsetDateTime threshold, int limit);
}
