package com.swiftpay.controller;

import com.swiftpay.dto.LedgerDtos;
import com.swiftpay.model.LedgerEntry;
import com.swiftpay.repository.LedgerEntryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/ledger")
@RequiredArgsConstructor
@Tag(name = "Ledger", description = "Transaction history and audit log endpoints")
public class LedgerController {

    private final LedgerEntryRepository ledgerEntryRepository;

    @GetMapping("/{userId}/history")
    @Operation(summary = "Get paginated ledger history for a user")
    public ResponseEntity<Page<LedgerDtos.LedgerEntryResponse>> getHistory(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<LedgerDtos.LedgerEntryResponse> result =
                ledgerEntryRepository.findByAccountIdOrderByRecordedAtDesc(userId, pageable)
                        .map(this::toResponse);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/payment/{paymentId}")
    @Operation(summary = "Get ledger entries for a specific payment")
    public ResponseEntity<List<LedgerDtos.LedgerEntryResponse>> getByPayment(
            @PathVariable UUID paymentId) {

        List<LedgerDtos.LedgerEntryResponse> entries =
                ledgerEntryRepository.findByPaymentId(paymentId)
                        .stream().map(this::toResponse)
                        .collect(Collectors.toList());

        return ResponseEntity.ok(entries);
    }

    private LedgerDtos.LedgerEntryResponse toResponse(LedgerEntry e) {
        return LedgerDtos.LedgerEntryResponse.builder()
                .id(e.getId())
                .paymentId(e.getPaymentId())
                .accountId(e.getAccountId())
                .entryType(e.getEntryType().name())
                .amount(e.getAmount())
                .balanceAfter(e.getBalanceAfter())
                .currency(e.getCurrency())
                .recordedAt(e.getRecordedAt())
                .build();
    }
}
