package com.swiftpay.consumer;

import com.swiftpay.dto.LedgerDtos;
import com.swiftpay.kafka.PaymentResultProducer;
import com.swiftpay.service.LedgerTransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentInitiatedConsumer {

    private final LedgerTransferService ledgerTransferService;
    private final PaymentResultProducer resultProducer;

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 10000),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltTopicSuffix = ".DLT"
           // include = {Exception.class},
       //     exclude = {IllegalArgumentException.class}
    )
    @KafkaListener(
            topics = "${kafka.topics.payment-initiated}",
            groupId = "ledger-service-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onPaymentInitiated(LedgerDtos.PaymentInitiatedEvent event) {
        MDC.put("payment_id", event.getPaymentId().toString());
        try {
            log.info("Processing PaymentInitiated paymentId={} sender={} receiver={} amount={}",
                    event.getPaymentId(), event.getSenderId(), event.getReceiverId(), event.getAmount());

            LedgerTransferService.TransferResult result = ledgerTransferService.processTransfer(event);

            if (result.isAlreadyProcessed()) {
                log.info("Skipped already-processed payment paymentId={}", event.getPaymentId());
                return;
            }

            LedgerDtos.PaymentResultEvent resultEvent = LedgerDtos.PaymentResultEvent.builder()
                    .paymentId(result.paymentId())
                    .status(result.status())
                    .failureReason(result.failureReason())
                    .senderId(result.senderId())
                    .receiverId(result.receiverId())
                    .amount(result.amount())
                    .currency(result.currency())
                    .completedAt(Instant.now())
                    .build();

            resultProducer.emitResult(resultEvent);

        } catch (Exception e) {
            log.error("Error processing PaymentInitiated paymentId={}", event.getPaymentId(), e);
            throw e; // re-throw to trigger retry
        } finally {
            MDC.clear();
        }
    }

    @DltHandler
    public void onDlt(LedgerDtos.PaymentInitiatedEvent event,
                      @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("Payment landed in DLT after all retries. topic={} paymentId={} sender={} amount={}",
                topic, event.getPaymentId(), event.getSenderId(), event.getAmount());

        // Emit FAILED result so Service A can update payment status
        LedgerDtos.PaymentResultEvent failedEvent = LedgerDtos.PaymentResultEvent.builder()
                .paymentId(event.getPaymentId())
                .status("FAILED")
                .failureReason("Processing failed after maximum retries — check DLT: " + topic)
                .completedAt(Instant.now())
                .build();

        resultProducer.emitResult(failedEvent);
    }
}
