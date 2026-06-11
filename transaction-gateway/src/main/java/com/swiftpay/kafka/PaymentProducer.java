package com.swiftpay.kafka;

import com.swiftpay.dto.KafkaEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${kafka.topics.payment-initiated}")
    private String paymentInitiatedTopic;

    /**
     * Emits a PaymentInitiated event. Keyed by senderId for ordering guarantees.
     */
    public CompletableFuture<SendResult<String, Object>> emitPaymentInitiated(
            KafkaEvents.PaymentInitiatedEvent event) {

        String key = event.getSenderId().toString();
        log.info("Emitting PaymentInitiated event paymentId={} topic={}", event.getPaymentId(), paymentInitiatedTopic);

        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(paymentInitiatedTopic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to emit PaymentInitiated paymentId={}", event.getPaymentId(), ex);
            } else {
                log.debug("PaymentInitiated emitted paymentId={} offset={}",
                        event.getPaymentId(),
                        result.getRecordMetadata().offset());
            }
        });

        return future;
    }
}
