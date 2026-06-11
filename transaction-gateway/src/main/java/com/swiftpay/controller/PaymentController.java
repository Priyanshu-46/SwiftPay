package com.swiftpay.controller;

import com.swiftpay.dto.PaymentDtos;
import com.swiftpay.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payments", description = "Payment initiation and status endpoints")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @Operation(
            summary = "Initiate a payment",
            description = "Accepts a P2P payment request. Requires an Idempotency-Key header to prevent duplicate processing."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Payment accepted and queued"),
            @ApiResponse(responseCode = "200", description = "Duplicate request — returns existing payment"),
            @ApiResponse(responseCode = "422", description = "Insufficient funds",
                    content = @Content(schema = @Schema(implementation = PaymentDtos.ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = PaymentDtos.ErrorResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = PaymentDtos.ErrorResponse.class)))
    })
    public ResponseEntity<PaymentDtos.PaymentResponse> initiatePayment(
            @Valid @RequestBody PaymentDtos.PaymentRequest request,
            @Parameter(description = "Unique key to ensure idempotency", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId) {

        MDC.put("request_id", requestId != null ? requestId : UUID.randomUUID().toString());
        log.info("Received payment request sender={} receiver={} amount={} currency={}",
                request.getSenderId(), request.getReceiverId(), request.getAmount(), request.getCurrency());

        try {
            PaymentDtos.PaymentResponse response = paymentService.initiatePayment(request, idempotencyKey);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } finally {
            MDC.clear();
        }
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get payment status by ID")
    public ResponseEntity<PaymentDtos.PaymentResponse> getPayment(
            @PathVariable UUID paymentId) {
        return ResponseEntity.ok(paymentService.getPayment(paymentId));
    }

    @GetMapping("/history/{userId}")
    @Operation(summary = "Get payment history for a user")
    public ResponseEntity<List<PaymentDtos.PaymentResponse>> getHistory(
            @PathVariable UUID userId) {
        return ResponseEntity.ok(paymentService.getPaymentHistory(userId));
    }
}
