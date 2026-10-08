package com.smartpg.module.payment.repository;

import com.smartpg.module.payment.model.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
    
    Optional<Invoice> findByPaymentId(UUID paymentId);

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);
}
