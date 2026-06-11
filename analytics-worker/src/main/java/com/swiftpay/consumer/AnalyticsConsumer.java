package com.swiftpay.consumer;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.swiftpay.model.PaymentAnalytics;
import jakarta.persistence.EntityManager;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Component
@Slf4j
public class AnalyticsConsumer {

    @PersistenceContext
    private EntityManager entityManager;

    @KafkaListener(
            topics = "${kafka.topics.payment-completed}",
            groupId = "analytics-worker-group"
    )
    @Transactional
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        try {
            log.debug("Analytics: recording payment paymentId={}", event.getPaymentId());

            PaymentAnalytics record = PaymentAnalytics.builder()
                    .paymentId(event.getPaymentId())
                    .senderId(event.getSenderId() != null ? event.getSenderId() : UUID.randomUUID())
                    .receiverId(event.getReceiverId() != null ? event.getReceiverId() : UUID.randomUUID())
                    .amount(event.getAmount() != null ? event.getAmount() : BigDecimal.ZERO)
                    .currency(event.getCurrency() != null ? event.getCurrency() : "USD")
                    .status(event.getStatus())
                    .completedAt(event.getCompletedAt() != null ? event.getCompletedAt() : Instant.now())
                    .build();

            entityManager.persist(record);
            log.info("Analytics recorded paymentId={} amount={} currency={}",
                    event.getPaymentId(), event.getAmount(), event.getCurrency());

        } catch (DataIntegrityViolationException e) {
            log.info("Analytics: duplicate payment skipped paymentId={}", event.getPaymentId());
        } catch (Exception e) {
            log.error("Analytics: failed to record payment paymentId={}", event.getPaymentId(), e);
            throw e;
        }
    }

    // Local DTO matching the event structure emitted by Ledger Service
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PaymentCompletedEvent {
        @JsonProperty("payment_id")  private UUID paymentId;
        @JsonProperty("sender_id")   private UUID senderId;
        @JsonProperty("receiver_id") private UUID receiverId;
        private BigDecimal amount;
        private String currency;
        private String status;
        @JsonProperty("failure_reason")  private String failureReason;
        @JsonProperty("completed_at")    private Instant completedAt;
    }
}
