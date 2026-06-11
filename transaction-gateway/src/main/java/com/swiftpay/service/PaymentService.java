package com.swiftpay.service;

import com.swiftpay.dto.KafkaEvents;
import com.swiftpay.dto.PaymentDtos;
import com.swiftpay.exception.AccountNotFoundException;
import com.swiftpay.exception.InsufficientFundsException;
import com.swiftpay.kafka.PaymentProducer;
import com.swiftpay.model.Account;
import com.swiftpay.model.Payment;
import com.swiftpay.model.PaymentStatus;
import com.swiftpay.repository.AccountRepository;
import com.swiftpay.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final AccountRepository accountRepository;
    private final IdempotencyService idempotencyService;
    private final BalanceCacheService balanceCacheService;
    private final PaymentProducer paymentProducer;

    /**
     * Initiates a payment:
     *   1. Check idempotency (Redis)
     *   2. Validate sender balance (Redis cache → DB fallback)
     *   3. Persist PENDING payment (Postgres)
     *   4. Emit PaymentInitiated event (Kafka)
     */
    @Transactional
    public PaymentDtos.PaymentResponse initiatePayment(
            PaymentDtos.PaymentRequest request, String idempotencyKey) {

        // 1. Idempotency check — return existing if already processed
        Optional<String> existingId = idempotencyService.getExistingPaymentId(idempotencyKey);
        if (existingId.isPresent()) {
            log.info("Idempotent request detected for key={}", idempotencyKey);
            return paymentRepository.findById(UUID.fromString(existingId.get()))
                    .map(this::toResponse)
                    .orElseThrow(() -> new IllegalStateException("Inconsistency: idempotency key exists but payment missing"));
        }

        // 2. Validate accounts exist
        Account sender = accountRepository.findById(request.getSenderId())
                .orElseThrow(() -> new AccountNotFoundException(request.getSenderId()));
        if (!accountRepository.existsById(request.getReceiverId())) {
            throw new AccountNotFoundException(request.getReceiverId());
        }

        // 3. Balance check — try cache first, fall back to DB
        BigDecimal balance = balanceCacheService.getCachedBalance(request.getSenderId())
                .orElseGet(() -> {
                    BigDecimal dbBalance = sender.getBalance();
                    balanceCacheService.cacheBalance(request.getSenderId(), dbBalance);
                    return dbBalance;
                });

        if (balance.compareTo(request.getAmount()) < 0) {
            throw new InsufficientFundsException(
                    String.format("Sender %s has insufficient funds (available: %s, requested: %s)",
                            request.getSenderId(), balance, request.getAmount()));
        }

        // 4. Persist PENDING payment
        Payment payment = Payment.builder()
                .senderId(request.getSenderId())
                .receiverId(request.getReceiverId())
                .amount(request.getAmount())
                .currency(request.getCurrency().toUpperCase())
                .status(PaymentStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .build();

        payment = paymentRepository.save(payment);
        MDC.put("payment_id", payment.getId().toString());
        log.info("Payment persisted as PENDING paymentId={}", payment.getId());

        // 5. Reserve idempotency key in Redis (after DB save so we have the ID)
        idempotencyService.tryReserve(idempotencyKey, payment.getId().toString());

        // 6. Emit Kafka event (async — do NOT block the HTTP response)
        KafkaEvents.PaymentInitiatedEvent event = KafkaEvents.PaymentInitiatedEvent.builder()
                .paymentId(payment.getId())
                .senderId(payment.getSenderId())
                .receiverId(payment.getReceiverId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .idempotencyKey(idempotencyKey)
                .initiatedAt(Instant.now())
                .build();

        paymentProducer.emitPaymentInitiated(event);

        return toResponse(payment);
    }

    public List<PaymentDtos.PaymentResponse> getPaymentHistory(UUID userId) {
        return paymentRepository
                .findBySenderIdOrReceiverIdOrderByCreatedAtDesc(userId, userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public PaymentDtos.PaymentResponse getPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .map(this::toResponse)
                .orElseThrow(() -> new RuntimeException("Payment not found: " + paymentId));
    }

    private PaymentDtos.PaymentResponse toResponse(Payment p) {
        return PaymentDtos.PaymentResponse.builder()
                .paymentId(p.getId())
                .senderId(p.getSenderId())
                .receiverId(p.getReceiverId())
                .amount(p.getAmount())
                .currency(p.getCurrency())
                .status(p.getStatus().name())
                .idempotencyKey(p.getIdempotencyKey())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
