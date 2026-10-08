package com.smartpg.module.payment.repository;

import com.smartpg.module.payment.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    
    List<Payment> findByRentLedgerId(UUID rentLedgerId);

    Optional<Payment> findByTransactionReference(String transactionReference);
}
