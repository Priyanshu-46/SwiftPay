package com.swiftpay.repository;

import com.swiftpay.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    @Query("SELECT a.balance FROM Account a WHERE a.id = :id")
    Optional<BigDecimal> findBalanceById(@Param("id") UUID id);
}
