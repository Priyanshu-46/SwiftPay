package com.swiftpay.service;

import com.swiftpay.dto.PaymentDtos;
import com.swiftpay.exception.AccountNotFoundException;
import com.swiftpay.exception.InsufficientFundsException;
import com.swiftpay.kafka.PaymentProducer;
import com.swiftpay.model.Account;
import com.swiftpay.model.Payment;
import com.swiftpay.model.PaymentStatus;
import com.swiftpay.repository.AccountRepository;
import com.swiftpay.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService unit tests")
class PaymentServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock AccountRepository accountRepository;
    @Mock IdempotencyService idempotencyService;
    @Mock BalanceCacheService balanceCacheService;
    @Mock PaymentProducer paymentProducer;

    @InjectMocks PaymentService paymentService;

    private UUID senderId;
    private UUID receiverId;
    private Account senderAccount;
    private PaymentDtos.PaymentRequest request;
    private static final String IDEM_KEY = "test-idem-key-001";

    @BeforeEach
    void setUp() {
        senderId   = UUID.randomUUID();
        receiverId = UUID.randomUUID();

        senderAccount = Account.builder()
                .id(senderId)
                .ownerName("Alice")
                .balance(new BigDecimal("1000.00"))
                .currency("USD")
                .version(0L)
                .build();

        request = PaymentDtos.PaymentRequest.builder()
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .build();
    }

    @Test
    @DisplayName("initiatePayment — happy path returns PENDING response")
    void initiatePayment_happyPath_returnsPending() {
        // Arrange
        when(idempotencyService.getExistingPaymentId(IDEM_KEY)).thenReturn(Optional.empty());
        when(accountRepository.findById(senderId)).thenReturn(Optional.of(senderAccount));
        when(accountRepository.existsById(receiverId)).thenReturn(true);
        when(balanceCacheService.getCachedBalance(senderId))
                .thenReturn(Optional.of(new BigDecimal("1000.00")));

        Payment savedPayment = Payment.builder()
                .id(UUID.randomUUID())
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(request.getAmount())
                .currency("USD")
                .status(PaymentStatus.PENDING)
                .idempotencyKey(IDEM_KEY)
                .createdAt(Instant.now())
                .build();

        when(paymentRepository.save(any(Payment.class))).thenReturn(savedPayment);
        when(idempotencyService.tryReserve(anyString(), anyString())).thenReturn(true);
        when(paymentProducer.emitPaymentInitiated(any())).thenReturn(CompletableFuture.completedFuture(null));

        // Act
        PaymentDtos.PaymentResponse response = paymentService.initiatePayment(request, IDEM_KEY);

        // Assert
        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(response.getSenderId()).isEqualTo(senderId);
        assertThat(response.getReceiverId()).isEqualTo(receiverId);
        assertThat(response.getAmount()).isEqualByComparingTo("100.00");

        verify(paymentRepository).save(any(Payment.class));
        verify(paymentProducer).emitPaymentInitiated(any());
    }

    @Test
    @DisplayName("initiatePayment — insufficient funds throws InsufficientFundsException")
    void initiatePayment_insufficientFunds_throwsException() {
        // Arrange
        when(idempotencyService.getExistingPaymentId(IDEM_KEY)).thenReturn(Optional.empty());
        when(accountRepository.findById(senderId)).thenReturn(Optional.of(senderAccount));
        when(accountRepository.existsById(receiverId)).thenReturn(true);
        when(balanceCacheService.getCachedBalance(senderId))
                .thenReturn(Optional.of(new BigDecimal("50.00"))); // less than 100

        // Act & Assert
        assertThatThrownBy(() -> paymentService.initiatePayment(request, IDEM_KEY))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("insufficient funds");

        verify(paymentRepository, never()).save(any());
        verify(paymentProducer, never()).emitPaymentInitiated(any());
    }

    @Test
    @DisplayName("initiatePayment — sender not found throws AccountNotFoundException")
    void initiatePayment_senderNotFound_throwsException() {
        when(idempotencyService.getExistingPaymentId(IDEM_KEY)).thenReturn(Optional.empty());
        when(accountRepository.findById(senderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.initiatePayment(request, IDEM_KEY))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("initiatePayment — duplicate idempotency key returns existing payment")
    void initiatePayment_duplicateKey_returnsExisting() {
        UUID existingPaymentId = UUID.randomUUID();
        Payment existing = Payment.builder()
                .id(existingPaymentId)
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(request.getAmount())
                .currency("USD")
                .status(PaymentStatus.PENDING)
                .idempotencyKey(IDEM_KEY)
                .createdAt(Instant.now())
                .build();

        when(idempotencyService.getExistingPaymentId(IDEM_KEY))
                .thenReturn(Optional.of(existingPaymentId.toString()));
        when(paymentRepository.findById(existingPaymentId)).thenReturn(Optional.of(existing));

        PaymentDtos.PaymentResponse response = paymentService.initiatePayment(request, IDEM_KEY);

        assertThat(response.getPaymentId()).isEqualTo(existingPaymentId);
        verify(paymentRepository, never()).save(any());
        verify(paymentProducer, never()).emitPaymentInitiated(any());
    }

    @Test
    @DisplayName("initiatePayment — balance falls back to DB when cache miss")
    void initiatePayment_cacheMiss_fallsBackToDb() {
        when(idempotencyService.getExistingPaymentId(IDEM_KEY)).thenReturn(Optional.empty());
        when(accountRepository.findById(senderId)).thenReturn(Optional.of(senderAccount));
        when(accountRepository.existsById(receiverId)).thenReturn(true);
        when(balanceCacheService.getCachedBalance(senderId)).thenReturn(Optional.empty()); // cache miss

        Payment savedPayment = Payment.builder()
                .id(UUID.randomUUID()).senderId(senderId).receiverId(receiverId)
                .amount(request.getAmount()).currency("USD").status(PaymentStatus.PENDING)
                .idempotencyKey(IDEM_KEY).createdAt(Instant.now()).build();

        when(paymentRepository.save(any())).thenReturn(savedPayment);
        when(idempotencyService.tryReserve(anyString(), anyString())).thenReturn(true);
        when(paymentProducer.emitPaymentInitiated(any())).thenReturn(CompletableFuture.completedFuture(null));

        PaymentDtos.PaymentResponse response = paymentService.initiatePayment(request, IDEM_KEY);

        // senderAccount.getBalance() = 1000, request amount = 100 → should succeed
        assertThat(response.getStatus()).isEqualTo("PENDING");
        // balance was cached after DB read
        verify(balanceCacheService).cacheBalance(eq(senderId), eq(new BigDecimal("1000.00")));
    }
}
