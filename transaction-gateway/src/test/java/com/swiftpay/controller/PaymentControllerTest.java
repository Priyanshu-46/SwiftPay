package com.swiftpay.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftpay.dto.PaymentDtos;
import com.swiftpay.exception.GlobalExceptionHandler;
import com.swiftpay.exception.InsufficientFundsException;
import com.swiftpay.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("PaymentController web layer tests")
class PaymentControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean  PaymentService paymentService;

    private static final UUID SENDER_ID   = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID RECEIVER_ID = UUID.fromString("a0000000-0000-0000-0000-000000000002");

    @Test
    @DisplayName("POST /v1/payments — 202 Accepted on valid request")
    void postPayment_valid_returns202() throws Exception {
        PaymentDtos.PaymentRequest req = PaymentDtos.PaymentRequest.builder()
                .senderId(SENDER_ID)
                .receiverId(RECEIVER_ID)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .build();

        PaymentDtos.PaymentResponse resp = PaymentDtos.PaymentResponse.builder()
                .paymentId(UUID.randomUUID())
                .senderId(SENDER_ID)
                .receiverId(RECEIVER_ID)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .status("PENDING")
                .idempotencyKey("idem-key-001")
                .createdAt(Instant.now())
                .build();

        when(paymentService.initiatePayment(any(), eq("idem-key-001"))).thenReturn(resp);

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-key-001")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(100.00));
    }

    @Test
    @DisplayName("POST /v1/payments — 400 when Idempotency-Key header missing")
    void postPayment_missingIdempotencyKey_returns400() throws Exception {
        PaymentDtos.PaymentRequest req = PaymentDtos.PaymentRequest.builder()
                .senderId(SENDER_ID)
                .receiverId(RECEIVER_ID)
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .build();

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /v1/payments — 422 on insufficient funds")
    void postPayment_insufficientFunds_returns422() throws Exception {
        PaymentDtos.PaymentRequest req = PaymentDtos.PaymentRequest.builder()
                .senderId(SENDER_ID)
                .receiverId(RECEIVER_ID)
                .amount(new BigDecimal("999999.00"))
                .currency("USD")
                .build();

        when(paymentService.initiatePayment(any(), any()))
                .thenThrow(new InsufficientFundsException("Sender has insufficient funds"));

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-key-002")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    @DisplayName("POST /v1/payments — 400 on negative amount")
    void postPayment_negativeAmount_returns400() throws Exception {
        PaymentDtos.PaymentRequest req = PaymentDtos.PaymentRequest.builder()
                .senderId(SENDER_ID)
                .receiverId(RECEIVER_ID)
                .amount(new BigDecimal("-50.00"))
                .currency("USD")
                .build();

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-key-003")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /v1/payments — 400 on missing sender_id")
    void postPayment_missingSenderId_returns400() throws Exception {
        String badBody = """
                {
                  "receiver_id": "%s",
                  "amount": "100.00",
                  "currency": "USD"
                }
                """.formatted(RECEIVER_ID);

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-key-004")
                        .content(badBody))
                .andExpect(status().isBadRequest());
    }
}
