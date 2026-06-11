package com.swiftpay.kafka;

import com.swiftpay.dto.LedgerDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentResultProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${kafka.topics.payment-completed}")
    private String completedTopic;

    @Value("${kafka.topics.payment-failed}")
    private String failedTopic;

    public void emitResult(LedgerDtos.PaymentResultEvent event) {
        String topic = "COMPLETED".equals(event.getStatus()) ? completedTopic : failedTopic;
        String key = event.getPaymentId().toString();

        log.info("Emitting payment result paymentId={} status={} topic={}",
                event.getPaymentId(), event.getStatus(), topic);

        kafkaTemplate.send(topic, key, event).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to emit payment result paymentId={}", event.getPaymentId(), ex);
            }
        });
    }
}
