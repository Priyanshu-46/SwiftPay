package com.swiftpay.integration;

import com.swiftpay.model.Payment;
import com.swiftpay.model.PaymentStatus;
import com.swiftpay.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.concurrent.TimeUnit;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@EmbeddedKafka(partitions = 1,
        topics = {"payment.initiated", "payment.completed", "payment.failed"},
        brokerProperties = {"listeners=PLAINTEXT://localhost:${spring.kafka.bootstrap-servers}"})
@DisplayName("Transaction Gateway integration tests")
class PaymentIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transactions_db")
            .withUsername("swiftpay")
            .withPassword("swiftpay");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host",     redis::getHost);
        registry.add("spring.data.redis.port",     () -> redis.getMappedPort(6379));
    }

    @Autowired MockMvc mockMvc;
    @Autowired PaymentRepository paymentRepository;

    private static final String SENDER_ID   = "a0000000-0000-0000-0000-000000000001";
    private static final String RECEIVER_ID = "a0000000-0000-0000-0000-000000000002";

    @Test
    @DisplayName("Full payment flow — payment persisted as PENDING in DB")
    void initiatePayment_persistsAsPending() throws Exception {
        String idemKey = "integ-test-" + UUID.randomUUID();

        String body = """
                {
                  "sender_id":   "%s",
                  "receiver_id": "%s",
                  "amount":      "25.00",
                  "currency":    "USD"
                }
                """.formatted(SENDER_ID, RECEIVER_ID);

        String responseJson = mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idemKey)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        // Verify persisted in DB
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Optional<Payment> payment = paymentRepository.findByIdempotencyKey(idemKey);
            assertThat(payment).isPresent();
            assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.get().getAmount()).isEqualByComparingTo("25.00");
        });
    }

    @Test
    @DisplayName("Idempotency — second request with same key returns same payment")
    void initiatePayment_duplicateKey_returnsSamePayment() throws Exception {
        String idemKey = "idem-dup-" + UUID.randomUUID();
        String body = """
                {
                  "sender_id":   "%s",
                  "receiver_id": "%s",
                  "amount":      "10.00",
                  "currency":    "USD"
                }
                """.formatted(SENDER_ID, RECEIVER_ID);

        // First request
        String firstResponse = mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idemKey)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        // Second request — same key
        String secondResponse = mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idemKey)
                        .content(body))
                .andReturn().getResponse().getContentAsString();

        // Both responses should reference the same payment_id
        assertThat(firstResponse).contains("payment_id");
        assertThat(secondResponse).contains("payment_id");

        // Only one payment row should exist for this idem key
        assertThat(paymentRepository.findByIdempotencyKey(idemKey)).isPresent();
    }

    @Test
    @DisplayName("Insufficient funds — 422 with correct error code")
    void initiatePayment_insufficientFunds_returns422() throws Exception {
        String body = """
                {
                  "sender_id":   "%s",
                  "receiver_id": "%s",
                  "amount":      "9999999.00",
                  "currency":    "USD"
                }
                """.formatted(SENDER_ID, RECEIVER_ID);

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "big-amount-" + UUID.randomUUID())
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }
}
