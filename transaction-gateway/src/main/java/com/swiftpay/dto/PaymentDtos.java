package com.swiftpay.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class PaymentDtos {

    @Schema(description = "Request body for initiating a payment")
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PaymentRequest {

        @NotNull(message = "sender_id is required")
        @Schema(description = "UUID of the sending account", example = "a0000000-0000-0000-0000-000000000001")
        @JsonProperty("sender_id")
        private UUID senderId;

        @NotNull(message = "receiver_id is required")
        @Schema(description = "UUID of the receiving account", example = "a0000000-0000-0000-0000-000000000002")
        @JsonProperty("receiver_id")
        private UUID receiverId;

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.0001", message = "amount must be greater than 0")
        @Digits(integer = 15, fraction = 4, message = "amount must have at most 15 integer digits and 4 decimal places")
        @Schema(description = "Transfer amount", example = "150.00")
        private BigDecimal amount;

        @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO 4217 code")
        @Schema(description = "ISO 4217 currency code", example = "USD")
        private String currency;
    }

    @Schema(description = "Response after payment initiation")
    @Getter
    @Builder
    @AllArgsConstructor
    public static class PaymentResponse {

        @Schema(description = "Unique payment ID")
        @JsonProperty("payment_id")
        private UUID paymentId;

        @JsonProperty("sender_id")
        private UUID senderId;

        @JsonProperty("receiver_id")
        private UUID receiverId;

        private BigDecimal amount;
        private String currency;
        private String status;

        @JsonProperty("idempotency_key")
        private String idempotencyKey;

        @JsonProperty("created_at")
        private Instant createdAt;
    }

    @Schema(description = "Standard error response")
    @Getter
    @Builder
    @AllArgsConstructor
    public static class ErrorResponse {

        @Schema(description = "Machine-readable error code", example = "INSUFFICIENT_FUNDS")
        private String code;

        @Schema(description = "Human-readable error message")
        private String message;

        @JsonProperty("request_id")
        private String requestId;

        private Instant timestamp;
    }
}
