package com.swiftpay.service;

import com.swiftpay.dto.LedgerDtos;
import com.swiftpay.model.Account;
import com.swiftpay.model.EntryType;
import com.swiftpay.model.LedgerEntry;
import com.swiftpay.model.ProcessedPayment;
import com.swiftpay.repository.AccountLedgerRepository;
import com.swiftpay.repository.LedgerEntryRepository;
import com.swiftpay.repository.ProcessedPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LedgerTransferService unit tests")
class LedgerTransferServiceTest {

    @Mock AccountLedgerRepository accountRepository;
    @Mock LedgerEntryRepository   ledgerEntryRepository;
    @Mock ProcessedPaymentRepository processedPaymentRepository;

    @InjectMocks LedgerTransferService ledgerTransferService;

    private UUID paymentId;
    private UUID senderId;
    private UUID receiverId;
    private Account sender;
    private Account receiver;
    private LedgerDtos.PaymentInitiatedEvent event;

    @BeforeEach
    void setUp() {
        paymentId  = UUID.randomUUID();
        // Force a consistent lock order: sender < receiver
        senderId   = UUID.fromString("10000000-0000-0000-0000-000000000001");
        receiverId = UUID.fromString("90000000-0000-0000-0000-000000000002");

        sender = Account.builder()
                .id(senderId)
                .ownerName("Alice")
                .balance(new BigDecimal("500.00"))
                .currency("USD")
                .version(0L)
                .build();

        receiver = Account.builder()
                .id(receiverId)
                .ownerName("Bob")
                .balance(new BigDecimal("200.00"))
                .currency("USD")
                .version(0L)
                .build();

        event = LedgerDtos.PaymentInitiatedEvent.builder()
                .paymentId(paymentId)
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .idempotencyKey("idem-key-001")
                .initiatedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("processTransfer — happy path debits sender and credits receiver")
    void processTransfer_happyPath_appliesDebitAndCredit() {
        when(processedPaymentRepository.existsByPaymentId(paymentId)).thenReturn(false);
        // Lock order: senderId (10...) < receiverId (90...) → sender locked first
        when(accountRepository.findByIdWithLock(senderId)).thenReturn(Optional.of(sender));
        when(accountRepository.findByIdWithLock(receiverId)).thenReturn(Optional.of(receiver));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerEntryRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(processedPaymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LedgerTransferService.TransferResult result = ledgerTransferService.processTransfer(event);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.paymentId()).isEqualTo(paymentId);

        // Sender balance reduced
        assertThat(sender.getBalance()).isEqualByComparingTo("400.00");
        // Receiver balance increased
        assertThat(receiver.getBalance()).isEqualByComparingTo("300.00");

        // Two ledger entries saved
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntry>> entriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository).saveAll(entriesCaptor.capture());
        List<LedgerEntry> entries = entriesCaptor.getValue();

        assertThat(entries).hasSize(2);
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.getEntryType()).isEqualTo(EntryType.DEBIT);
            assertThat(e.getAccountId()).isEqualTo(senderId);
            assertThat(e.getAmount()).isEqualByComparingTo("100.00");
            assertThat(e.getBalanceAfter()).isEqualByComparingTo("400.00");
        });
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.getEntryType()).isEqualTo(EntryType.CREDIT);
            assertThat(e.getAccountId()).isEqualTo(receiverId);
            assertThat(e.getAmount()).isEqualByComparingTo("100.00");
            assertThat(e.getBalanceAfter()).isEqualByComparingTo("300.00");
        });
    }

    @Test
    @DisplayName("processTransfer — insufficient funds returns FAILED result")
    void processTransfer_insufficientFunds_returnsFailed() {
        sender.setBalance(new BigDecimal("50.00")); // less than 100
        when(processedPaymentRepository.existsByPaymentId(paymentId)).thenReturn(false);
        when(accountRepository.findByIdWithLock(senderId)).thenReturn(Optional.of(sender));
        when(accountRepository.findByIdWithLock(receiverId)).thenReturn(Optional.of(receiver));
        when(processedPaymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LedgerTransferService.TransferResult result = ledgerTransferService.processTransfer(event);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureReason()).containsIgnoringCase("insufficient");

        // No ledger entries written
        verify(ledgerEntryRepository, never()).saveAll(any());
        // Balances unchanged
        assertThat(sender.getBalance()).isEqualByComparingTo("50.00");
        assertThat(receiver.getBalance()).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("processTransfer — already processed payment is skipped (consumer idempotency)")
    void processTransfer_alreadyProcessed_skipsProcessing() {
        when(processedPaymentRepository.existsByPaymentId(paymentId)).thenReturn(true);

        LedgerTransferService.TransferResult result = ledgerTransferService.processTransfer(event);

        assertThat(result.isAlreadyProcessed()).isTrue();
        assertThat(result.status()).isEqualTo("COMPLETED");

        verify(accountRepository, never()).findByIdWithLock(any());
        verify(ledgerEntryRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("processTransfer — marks payment as processed after successful transfer")
    void processTransfer_marksProcessed() {
        when(processedPaymentRepository.existsByPaymentId(paymentId)).thenReturn(false);
        when(accountRepository.findByIdWithLock(senderId)).thenReturn(Optional.of(sender));
        when(accountRepository.findByIdWithLock(receiverId)).thenReturn(Optional.of(receiver));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerEntryRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(processedPaymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ledgerTransferService.processTransfer(event);

        ArgumentCaptor<ProcessedPayment> captor = ArgumentCaptor.forClass(ProcessedPayment.class);
        verify(processedPaymentRepository).save(captor.capture());
        assertThat(captor.getValue().getPaymentId()).isEqualTo(paymentId);
    }

    @Test
    @DisplayName("processTransfer — locks accounts in UUID order regardless of sender/receiver")
    void processTransfer_locksInUuidOrder() {

        LedgerDtos.PaymentInitiatedEvent reversedEvent =
                LedgerDtos.PaymentInitiatedEvent.builder()
                        .paymentId(paymentId)
                        .senderId(receiverId)   // higher UUID
                        .receiverId(senderId)   // lower UUID
                        .amount(new BigDecimal("100.00"))
                        .currency("USD")
                        .idempotencyKey("idem-key-001")
                        .initiatedAt(Instant.now())
                        .build();

        when(processedPaymentRepository.existsByPaymentId(paymentId)).thenReturn(false);

        when(accountRepository.findByIdWithLock(senderId))
                .thenReturn(Optional.of(sender));

        when(accountRepository.findByIdWithLock(receiverId))
                .thenReturn(Optional.of(receiver));

        when(accountRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        when(ledgerEntryRepository.saveAll(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        when(processedPaymentRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        ledgerTransferService.processTransfer(reversedEvent);

        InOrder inOrder = inOrder(accountRepository);

        inOrder.verify(accountRepository).findByIdWithLock(senderId);   // lower UUID first
        inOrder.verify(accountRepository).findByIdWithLock(receiverId); // higher UUID second
    }
}
