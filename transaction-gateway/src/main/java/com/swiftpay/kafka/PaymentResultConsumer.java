package com.swiftpay.kafka;

import com.swiftpay.dto.KafkaEvents;
import com.swiftpay.model.PaymentStatus;
import com.swiftpay.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentResultConsumer {

    private final PaymentRepository paymentRepository;

    @KafkaListener(
            topics = {"${kafka.topics.payment-completed}", "${kafka.topics.payment-failed}"},
            groupId = "transaction-gateway-result-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onPaymentResult(KafkaEvents.PaymentCompletedEvent event) {
        MDC.put("payment_id", event.getPaymentId().toString());
        try {
            PaymentStatus newStatus = "COMPLETED".equals(event.getStatus())
                    ? PaymentStatus.COMPLETED
                    : PaymentStatus.FAILED;

            int updated = paymentRepository.updateStatus(
                    event.getPaymentId(), newStatus, event.getFailureReason());

            if (updated == 0) {
                log.warn("Payment not found for status update paymentId={}", event.getPaymentId());
            } else {
                log.info("Payment status updated paymentId={} status={}", event.getPaymentId(), newStatus);
            }
        } finally {
            MDC.clear();
        }
    }
}
