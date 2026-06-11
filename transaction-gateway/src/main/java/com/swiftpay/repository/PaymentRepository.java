package com.swiftpay.repository;

import com.swiftpay.model.Payment;
import com.swiftpay.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findBySenderIdOrReceiverIdOrderByCreatedAtDesc(UUID senderId, UUID receiverId);

    @Modifying
    @Query("UPDATE Payment p SET p.status = :status, p.failureReason = :reason WHERE p.id = :id")
    int updateStatus(@Param("id") UUID id,
                     @Param("status") PaymentStatus status,
                     @Param("reason") String reason);
}
