package com.swiftpay.repository;

import com.swiftpay.model.ProcessedPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ProcessedPaymentRepository extends JpaRepository<ProcessedPayment, UUID> {
    boolean existsByPaymentId(UUID paymentId);
}
