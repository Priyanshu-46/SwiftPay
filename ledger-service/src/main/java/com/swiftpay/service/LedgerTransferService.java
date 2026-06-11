package com.swiftpay.service;

import com.swiftpay.dto.LedgerDtos;
import com.swiftpay.model.Account;
import com.swiftpay.model.EntryType;
import com.swiftpay.model.LedgerEntry;
import com.swiftpay.model.ProcessedPayment;
import com.swiftpay.repository.AccountLedgerRepository;
import com.swiftpay.repository.LedgerEntryRepository;
import com.swiftpay.repository.ProcessedPaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerTransferService {

    private final AccountLedgerRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ProcessedPaymentRepository processedPaymentRepository;

    /**
     * Atomically debit sender and credit receiver.
     * Uses SERIALIZABLE isolation + pessimistic row locking.
     * Locks rows in consistent UUID order to prevent deadlocks.
     * Returns "COMPLETED" or "FAILED" with a reason.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TransferResult processTransfer(LedgerDtos.PaymentInitiatedEvent event) {
        UUID paymentId = event.getPaymentId();

        // Consumer-side idempotency: skip if already processed
        if (processedPaymentRepository.existsByPaymentId(paymentId)) {
            log.info("Payment already processed, skipping paymentId={}", paymentId);
            return TransferResult.alreadyProcessed(paymentId);
        }

        // Lock accounts in consistent order (lower UUID first) to prevent deadlocks
        UUID firstId  = event.getSenderId().compareTo(event.getReceiverId()) < 0
                ? event.getSenderId() : event.getReceiverId();
        UUID secondId = firstId.equals(event.getSenderId())
                ? event.getReceiverId() : event.getSenderId();

        Account first  = accountRepository.findByIdWithLock(firstId)
                .orElseThrow(() -> new IllegalStateException("Account not found: " + firstId));
        Account second = accountRepository.findByIdWithLock(secondId)
                .orElseThrow(() -> new IllegalStateException("Account not found: " + secondId));

        Account sender   = first.getId().equals(event.getSenderId()) ? first : second;
        Account receiver = first.getId().equals(event.getReceiverId()) ? first : second;

        // Validate balance (final check, enforced here regardless of Service A pre-check)
        if (sender.getBalance().compareTo(event.getAmount()) < 0) {
            log.warn("Insufficient funds at ledger level paymentId={} senderId={} balance={} amount={}",
                    paymentId, sender.getId(), sender.getBalance(), event.getAmount());
            markProcessed(paymentId);
            return TransferResult.failed(paymentId, "Insufficient funds at settlement time");
        }

        // Apply debit
        BigDecimal newSenderBalance = sender.getBalance().subtract(event.getAmount());
        sender.setBalance(newSenderBalance);
        accountRepository.save(sender);

        LedgerEntry debitEntry = LedgerEntry.builder()
                .paymentId(paymentId)
                .accountId(sender.getId())
                .entryType(EntryType.DEBIT)
                .amount(event.getAmount())
                .balanceAfter(newSenderBalance)
                .currency(event.getCurrency())
                .build();

        // Apply credit
        BigDecimal newReceiverBalance = receiver.getBalance().add(event.getAmount());
        receiver.setBalance(newReceiverBalance);
        accountRepository.save(receiver);

        LedgerEntry creditEntry = LedgerEntry.builder()
                .paymentId(paymentId)
                .accountId(receiver.getId())
                .entryType(EntryType.CREDIT)
                .amount(event.getAmount())
                .balanceAfter(newReceiverBalance)
                .currency(event.getCurrency())
                .build();

        ledgerEntryRepository.saveAll(List.of(debitEntry, creditEntry));

        markProcessed(paymentId);

        log.info("Transfer completed paymentId={} sender={} -{} receiver={} +{}",
                paymentId, sender.getId(), event.getAmount(),
                receiver.getId(), event.getAmount());

        return TransferResult.completed(paymentId, event.getSenderId(), event.getReceiverId(),
                event.getAmount(), event.getCurrency());
    }

    private void markProcessed(UUID paymentId) {
        processedPaymentRepository.save(ProcessedPayment.builder().paymentId(paymentId).build());
    }

    // ── Result value object ──────────────────────────────────────────────

    public record TransferResult(
            UUID paymentId,
            String status,
            String failureReason,
            UUID senderId,
            UUID receiverId,
            BigDecimal amount,
            String currency
    ) {
        static TransferResult completed(UUID paymentId, UUID senderId, UUID receiverId,
                                        BigDecimal amount, String currency) {
            return new TransferResult(paymentId, "COMPLETED", null, senderId, receiverId, amount, currency);
        }

        static TransferResult failed(UUID paymentId, String reason) {
            return new TransferResult(paymentId, "FAILED", reason, null, null, null, null);
        }

        static TransferResult alreadyProcessed(UUID paymentId) {
            return new TransferResult(paymentId, "COMPLETED", null, null, null, null, null);
        }

        public boolean isAlreadyProcessed() {
            return senderId == null && "COMPLETED".equals(status);
        }
    }
}
