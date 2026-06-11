package com.swiftpay.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class LedgerDtos {

    @Getter
    @Builder
    @AllArgsConstructor
    public static class LedgerEntryResponse {
        private UUID id;

        @JsonProperty("payment_id")
        private UUID paymentId;

        @JsonProperty("account_id")
        private UUID accountId;

        @JsonProperty("entry_type")
        private String entryType;

        private BigDecimal amount;

        @JsonProperty("balance_after")
        private BigDecimal balanceAfter;

        private String currency;

        @JsonProperty("recorded_at")
        private Instant recordedAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PaymentInitiatedEvent {
        @JsonProperty("payment_id")
        private UUID paymentId;

        @JsonProperty("sender_id")
        private UUID senderId;

        @JsonProperty("receiver_id")
        private UUID receiverId;

        private BigDecimal amount;
        private String currency;

        @JsonProperty("idempotency_key")
        private String idempotencyKey;

        @JsonProperty("initiated_at")
        private Instant initiatedAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PaymentResultEvent {
        @JsonProperty("payment_id")
        private UUID paymentId;

        private String status;

        @JsonProperty("failure_reason")
        private String failureReason;

        @JsonProperty("sender_id")
        private UUID senderId;

        @JsonProperty("receiver_id")
        private UUID receiverId;

        private BigDecimal amount;
        private String currency;

        @JsonProperty("completed_at")
        private Instant completedAt;
    }
}
