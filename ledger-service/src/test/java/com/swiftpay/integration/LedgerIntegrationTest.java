package com.swiftpay.integration;

import com.swiftpay.dto.LedgerDtos;
import com.swiftpay.model.Account;
import com.swiftpay.repository.AccountLedgerRepository;
import com.swiftpay.repository.LedgerEntryRepository;
import com.swiftpay.repository.ProcessedPaymentRepository;
import com.swiftpay.service.LedgerTransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@DisplayName("LedgerTransferService integration tests")
class LedgerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledger_db")
            .withUsername("swiftpay")
            .withPassword("swiftpay");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Disable Kafka for this test slice
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9999");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
    }

    @Autowired LedgerTransferService     ledgerTransferService;
    @Autowired AccountLedgerRepository   accountRepository;
    @Autowired LedgerEntryRepository     ledgerEntryRepository;
    @Autowired ProcessedPaymentRepository processedPaymentRepository;

    private UUID senderId;
    private UUID receiverId;

    @BeforeEach
    void setUp() {
        // Use fixed IDs that match Flyway seed data
        senderId   = UUID.fromString("a0000000-0000-0000-0000-000000000001"); // Alice: 10000
        receiverId = UUID.fromString("a0000000-0000-0000-0000-000000000002"); // Bob:   5000
    }

    @Test
    @DisplayName("processTransfer — balances correctly updated in DB")
    void processTransfer_updatesBalancesInDb() {
        BigDecimal transferAmount = new BigDecimal("250.00");
        UUID paymentId = UUID.randomUUID();

        Account senderBefore   = accountRepository.findById(senderId).orElseThrow();
        Account receiverBefore = accountRepository.findById(receiverId).orElseThrow();

        LedgerDtos.PaymentInitiatedEvent event = LedgerDtos.PaymentInitiatedEvent.builder()
                .paymentId(paymentId)
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(transferAmount)
                .currency("USD")
                .idempotencyKey("integ-" + paymentId)
                .initiatedAt(Instant.now())
                .build();

        LedgerTransferService.TransferResult result = ledgerTransferService.processTransfer(event);

        assertThat(result.status()).isEqualTo("COMPLETED");

        Account senderAfter   = accountRepository.findById(senderId).orElseThrow();
        Account receiverAfter = accountRepository.findById(receiverId).orElseThrow();

        assertThat(senderAfter.getBalance())
                .isEqualByComparingTo(senderBefore.getBalance().subtract(transferAmount));
        assertThat(receiverAfter.getBalance())
                .isEqualByComparingTo(receiverBefore.getBalance().add(transferAmount));
    }

    @Test
    @DisplayName("processTransfer — two ledger entries written per payment")
    void processTransfer_writesLedgerEntries() {
        UUID paymentId = UUID.randomUUID();

        LedgerDtos.PaymentInitiatedEvent event = LedgerDtos.PaymentInitiatedEvent.builder()
                .paymentId(paymentId)
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(new BigDecimal("10.00"))
                .currency("USD")
                .idempotencyKey("entries-" + paymentId)
                .initiatedAt(Instant.now())
                .build();

        ledgerTransferService.processTransfer(event);

        var entries = ledgerEntryRepository.findByPaymentId(paymentId);
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(e -> e.getEntryType().name())
                .containsExactlyInAnyOrder("DEBIT", "CREDIT");
    }

    @Test
    @DisplayName("processTransfer — concurrent identical events are idempotent")
    void processTransfer_concurrentDuplicates_processedOnlyOnce() throws InterruptedException {
        UUID paymentId = UUID.randomUUID();

        LedgerDtos.PaymentInitiatedEvent event = LedgerDtos.PaymentInitiatedEvent.builder()
                .paymentId(paymentId)
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(new BigDecimal("5.00"))
                .currency("USD")
                .idempotencyKey("concurrent-" + paymentId)
                .initiatedAt(Instant.now())
                .build();

        // Process same event twice sequentially
        ledgerTransferService.processTransfer(event);
        ledgerTransferService.processTransfer(event); // should be a no-op

        // Only 2 ledger entries (one DEBIT, one CREDIT) — not 4
        assertThat(ledgerEntryRepository.findByPaymentId(paymentId)).hasSize(2);
    }

    @Test
    @DisplayName("processTransfer — insufficient funds leaves balances unchanged")
    void processTransfer_insufficientFunds_balancesUnchanged() {
        UUID paymentId = UUID.randomUUID();
        Account senderBefore = accountRepository.findById(senderId).orElseThrow();

        LedgerDtos.PaymentInitiatedEvent event = LedgerDtos.PaymentInitiatedEvent.builder()
                .paymentId(paymentId)
                .senderId(senderId)
                .receiverId(receiverId)
                .amount(new BigDecimal("9999999.00")) // way more than balance
                .currency("USD")
                .idempotencyKey("insuf-" + paymentId)
                .initiatedAt(Instant.now())
                .build();

        LedgerTransferService.TransferResult result = ledgerTransferService.processTransfer(event);

        assertThat(result.status()).isEqualTo("FAILED");

        Account senderAfter = accountRepository.findById(senderId).orElseThrow();
        assertThat(senderAfter.getBalance()).isEqualByComparingTo(senderBefore.getBalance());

        // No ledger entries written for a failed transfer
        assertThat(ledgerEntryRepository.findByPaymentId(paymentId)).isEmpty();
    }
}
