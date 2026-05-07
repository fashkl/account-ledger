package com.mohamedali.ledger.ledger.application.usecase.withdrawal;

import com.mohamedali.ledger.ledger.application.port.in.LedgerPostingUseCase;
import com.mohamedali.ledger.ledger.application.port.in.withdrawal.CashMovementUseCase;
import com.mohamedali.ledger.ledger.application.port.in.withdrawal.WithdrawalStatusQuery;
import com.mohamedali.ledger.ledger.application.port.out.withdrawal.WithdrawalPersistencePort;
import com.mohamedali.ledger.ledger.domain.model.Currency;
import com.mohamedali.ledger.ledger.domain.model.EntryDirection;
import com.mohamedali.ledger.ledger.domain.model.PostLedgerEntriesCommand;
import com.mohamedali.ledger.ledger.domain.model.PostingLeg;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.CashMovementCommand;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.WithdrawalRecord;
import com.mohamedali.ledger.ledger.domain.model.withdrawal.WithdrawalStatus;
import com.mohamedali.ledger.shared.exception.InvalidCashMovementEventException;
import com.mohamedali.ledger.shared.exception.InsufficientFundsException;
import com.mohamedali.ledger.shared.exception.InvalidWithdrawalStateException;
import com.mohamedali.ledger.shared.exception.WithdrawalNotFoundException;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class CashMovementService implements CashMovementUseCase, WithdrawalStatusQuery {

    private static final Logger LOG = LoggerFactory.getLogger(CashMovementService.class);

    private final LedgerPostingUseCase postingUseCase;
    private final WithdrawalPersistencePort withdrawalPort;

    public CashMovementService(LedgerPostingUseCase postingUseCase,
                               WithdrawalPersistencePort withdrawalPort) {
        this.postingUseCase = postingUseCase;
        this.withdrawalPort = withdrawalPort;
    }

    @Override
    @Transactional
    public void handle(@Valid CashMovementCommand command) {
        switch (command.eventType()) {
            case VA_CREDITED         -> handleDeposit(command);
            case WITHDRAWAL_REQUESTED -> handleWithdrawalRequest(command);
            case WITHDRAWAL_CONFIRMED -> handleWithdrawalConfirm(command);
            case WITHDRAWAL_REJECTED  -> handleWithdrawalReject(command);
        }
    }

    /**
     * Called by the timeout job for each expired withdrawal.
     * Acquires FOR UPDATE on the withdrawal row, verifies it is still PENDING,
     * posts the reversal, and marks it TIMED_OUT — all within a single transaction.
     */
    @Transactional
    public void applyTimeout(WithdrawalRecord withdrawal) {
        WithdrawalRecord locked = withdrawalPort.findForUpdate(withdrawal.id())
                .orElseThrow(() -> new WithdrawalNotFoundException(withdrawal.id()));

        if (locked.status() != WithdrawalStatus.PENDING) {
            // Already confirmed/rejected/timed-out by a concurrent callback — skip
            LOG.warn("Timeout skipped: withdrawal {} is no longer PENDING (status={})", withdrawal.id(), locked.status());
            return;
        }

        postReversal(locked, "WITHDRAWAL_TIMEOUT-" + locked.id(), "WITHDRAWAL_TIMEOUT");
        withdrawalPort.updateStatus(locked.id(), WithdrawalStatus.TIMED_OUT);
    }

    @Override
    @Transactional(readOnly = true)
    public WithdrawalRecord getWithdrawal(UUID withdrawalId) {
        return withdrawalPort.findById(withdrawalId)
                .orElseThrow(() -> new WithdrawalNotFoundException(withdrawalId));
    }

    // -------------------------------------------------------------------------
    // Deposit
    // -------------------------------------------------------------------------

    private void handleDeposit(CashMovementCommand command) {
        requireField(command.eventId(), "eventId", command);
        requireField(command.customerId(), "customerId", command);
        requireField(command.settledCashAccountId(), "settledCashAccountId", command);
        requireField(command.brokerageOmnibusAccountId(), "brokerageOmnibusAccountId", command);
        requireField(command.currency(), "currency", command);
        requireAmount(command.amount(), command);

        postingUseCase.post(new PostLedgerEntriesCommand(
                "VA_CREDITED-" + command.eventId(),
                "VA_CREDITED",
                command.eventId(),
                LocalDate.now(ZoneOffset.UTC),
                List.of(
                        new PostingLeg(command.settledCashAccountId(), EntryDirection.DEBIT, command.amount(), command.currency()),
                        new PostingLeg(command.brokerageOmnibusAccountId(), EntryDirection.CREDIT, command.amount(), command.currency())
                )
        ));
    }

    // -------------------------------------------------------------------------
    // Withdrawal — Step 1
    // -------------------------------------------------------------------------

    private void handleWithdrawalRequest(CashMovementCommand command) {
        requireField(command.withdrawalId(), "withdrawalId", command);
        requireField(command.customerId(), "customerId", command);
        requireField(command.settledCashAccountId(), "settledCashAccountId", command);
        requireField(command.settlementPendingAccountId(), "settlementPendingAccountId", command);
        requireField(command.brokerageOmnibusAccountId(), "brokerageOmnibusAccountId", command);
        requireField(command.currency(), "currency", command);
        requireAmount(command.amount(), command);

        WithdrawalRecord existing = withdrawalPort.findForUpdate(command.withdrawalId()).orElse(null);
        if (existing != null) {
            validateIdempotentWithdrawalRequest(existing, command);
            return;
        }

        // Lock the SETTLED_CASH balance row before the NSF check so a concurrent
        // deposit or withdrawal cannot race between the check and the debit.
        BigDecimal settledBalance = withdrawalPort.lockSettledCashBalance(command.settledCashAccountId());
        if (settledBalance.compareTo(command.amount()) < 0) {
            BigDecimal shortfall = command.amount().subtract(settledBalance);
            throw new InsufficientFundsException(command.settledCashAccountId(), shortfall);
        }

        postingUseCase.post(new PostLedgerEntriesCommand(
                "WITHDRAWAL_REQUESTED-" + command.withdrawalId(),
                "WITHDRAWAL_REQUESTED",
                command.withdrawalId(),
                LocalDate.now(ZoneOffset.UTC),
                List.of(
                        new PostingLeg(command.settlementPendingAccountId(), EntryDirection.DEBIT, command.amount(), command.currency()),
                        new PostingLeg(command.settledCashAccountId(), EntryDirection.CREDIT, command.amount(), command.currency())
                )
        ));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        withdrawalPort.insertWithdrawal(new WithdrawalRecord(
                command.withdrawalId(),
                command.customerId(),
                command.settledCashAccountId(),
                command.settlementPendingAccountId(),
                command.brokerageOmnibusAccountId(),
                command.amount(),
                command.currency(),
                WithdrawalStatus.PENDING,
                now,
                now
        ));
    }

    // -------------------------------------------------------------------------
    // Withdrawal — Step 2a (bank ACK)
    // -------------------------------------------------------------------------

    private void handleWithdrawalConfirm(CashMovementCommand command) {
        requireField(command.callbackId(), "callbackId", command);
        requireField(command.withdrawalId(), "withdrawalId", command);

        WithdrawalRecord withdrawal = withdrawalPort.findForUpdate(command.withdrawalId())
                .orElseThrow(() -> new WithdrawalNotFoundException(command.withdrawalId()));

        if (withdrawal.status() == WithdrawalStatus.CONFIRMED) {
            LOG.warn("WITHDRAWAL_CONFIRMED is idempotent no-op for withdrawal {} already CONFIRMED", command.withdrawalId());
            return;
        }
        if (withdrawal.status() != WithdrawalStatus.PENDING) {
            throw new InvalidWithdrawalStateException(command.withdrawalId(),
                    "expected PENDING but was " + withdrawal.status());
        }

        postingUseCase.post(new PostLedgerEntriesCommand(
                "WITHDRAWAL_CONFIRMED-" + command.callbackId(),
                "WITHDRAWAL_CONFIRMED",
                command.withdrawalId(),
                LocalDate.now(ZoneOffset.UTC),
                List.of(
                        new PostingLeg(withdrawal.brokerageOmnibusAccountId(), EntryDirection.DEBIT, withdrawal.amount(), withdrawal.currency()),
                        new PostingLeg(withdrawal.settlementPendingAccountId(), EntryDirection.CREDIT, withdrawal.amount(), withdrawal.currency())
                )
        ));

        withdrawalPort.updateStatus(command.withdrawalId(), WithdrawalStatus.CONFIRMED);
    }

    // -------------------------------------------------------------------------
    // Withdrawal — Step 2b (bank NAK)
    // -------------------------------------------------------------------------

    private void handleWithdrawalReject(CashMovementCommand command) {
        requireField(command.callbackId(), "callbackId", command);
        requireField(command.withdrawalId(), "withdrawalId", command);

        WithdrawalRecord withdrawal = withdrawalPort.findForUpdate(command.withdrawalId())
                .orElseThrow(() -> new WithdrawalNotFoundException(command.withdrawalId()));

        if (withdrawal.status() == WithdrawalStatus.REJECTED) {
            LOG.warn("WITHDRAWAL_REJECTED is idempotent no-op for withdrawal {} already REJECTED", command.withdrawalId());
            return;
        }
        if (withdrawal.status() != WithdrawalStatus.PENDING) {
            throw new InvalidWithdrawalStateException(command.withdrawalId(),
                    "expected PENDING but was " + withdrawal.status());
        }

        postReversal(withdrawal, "WITHDRAWAL_REJECTED-" + command.callbackId(), "WITHDRAWAL_REJECTED");
        withdrawalPort.updateStatus(command.withdrawalId(), WithdrawalStatus.REJECTED);
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /**
     * Posts the reversal entry: SETTLED_CASH DEBIT → SETTLEMENT_PENDING CREDIT.
     * Used by both WITHDRAWAL_REJECTED and the timeout job.
     */
    public void postReversal(WithdrawalRecord withdrawal, String idempotencyKey, String eventType) {
        postingUseCase.post(new PostLedgerEntriesCommand(
                idempotencyKey,
                eventType,
                withdrawal.id(),
                LocalDate.now(ZoneOffset.UTC),
                List.of(
                        new PostingLeg(withdrawal.settledCashAccountId(), EntryDirection.DEBIT, withdrawal.amount(), withdrawal.currency()),
                        new PostingLeg(withdrawal.settlementPendingAccountId(), EntryDirection.CREDIT, withdrawal.amount(), withdrawal.currency())
                )
        ));
    }

    private void requireField(Object value, String fieldName, CashMovementCommand command) {
        if (value == null) {
            throw new InvalidCashMovementEventException(
                    command.eventType(),
                    referenceId(command),
                    fieldName + " is required");
        }
    }

    private void requireAmount(BigDecimal amount, CashMovementCommand command) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidCashMovementEventException(
                    command.eventType(),
                    referenceId(command),
                    "amount must be positive");
        }
    }

    private UUID referenceId(CashMovementCommand command) {
        if (command.withdrawalId() != null) {
            return command.withdrawalId();
        }
        if (command.eventId() != null) {
            return command.eventId();
        }
        return command.callbackId();
    }

    private void validateIdempotentWithdrawalRequest(WithdrawalRecord existing, CashMovementCommand command) {
        boolean samePayload = Objects.equals(existing.customerId(), command.customerId())
                && Objects.equals(existing.settledCashAccountId(), command.settledCashAccountId())
                && Objects.equals(existing.settlementPendingAccountId(), command.settlementPendingAccountId())
                && Objects.equals(existing.brokerageOmnibusAccountId(), command.brokerageOmnibusAccountId())
                && Objects.equals(existing.currency(), command.currency())
                && existing.amount().compareTo(command.amount()) == 0;
        if (!samePayload) {
            throw new InvalidCashMovementEventException(
                    command.eventType(),
                    command.withdrawalId(),
                    "payload mismatch for existing withdrawalId");
        }

        if (existing.status() != WithdrawalStatus.PENDING) {
            throw new InvalidWithdrawalStateException(
                    command.withdrawalId(),
                    "duplicate WITHDRAWAL_REQUESTED received for non-PENDING withdrawal " + existing.status());
        }

        LOG.warn("WITHDRAWAL_REQUESTED is idempotent no-op for withdrawal {} already PENDING", command.withdrawalId());
    }
}
