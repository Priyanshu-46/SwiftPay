package com.swiftpay.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedPayment {

    @Id
    @Column(name = "payment_id")
    private UUID paymentId;

    @CreationTimestamp
    @Column(name = "processed_at", updatable = false)
    private Instant processedAt;
}
