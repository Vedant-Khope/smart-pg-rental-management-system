package com.smartpg.module.payment.service;

import com.smartpg.module.payment.enums.PaymentMethod;
import com.smartpg.module.payment.enums.PaymentStatus;
import com.smartpg.module.payment.enums.RentStatus;
import com.smartpg.module.payment.model.Invoice;
import com.smartpg.module.payment.model.Payment;
import com.smartpg.module.payment.model.RentLedger;
import com.smartpg.module.payment.repository.InvoiceRepository;
import com.smartpg.module.payment.repository.PaymentRepository;
import com.smartpg.module.user.model.User;
import com.smartpg.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final RentLedgerService rentLedgerService;
    private final UserRepository userRepository;

    /**
     * Used when an Owner records a manual cash payment from a Tenant.
     */
    @Transactional
    public Payment recordCashPayment(UUID rentLedgerId, UUID paidByUserId, BigDecimal amount) {
        RentLedger ledger = rentLedgerService.getLedgerById(rentLedgerId);
        User paidBy = userRepository.findById(paidByUserId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (ledger.getStatus() == RentStatus.PAID) {
            throw new RuntimeException("Rent is already fully paid.");
        }

        Payment payment = new Payment();
        payment.setRentLedger(ledger);
        payment.setPaidBy(paidBy);
        payment.setAmount(amount);
        payment.setPaymentMethod(PaymentMethod.CASH);
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setPaymentDate(Instant.now());
        payment.setTransactionReference("CASH-" + Instant.now().toEpochMilli());

        paymentRepository.save(payment);

        processSuccessfulPayment(ledger, payment);

        return payment;
    }

    /**
     * Core logic to update the ledger and generate an invoice once a payment succeeds.
     * This handles partial payments as well.
     */
    private void processSuccessfulPayment(RentLedger ledger, Payment payment) {
        // Update amount paid
        BigDecimal newAmountPaid = ledger.getAmountPaid().add(payment.getAmount());
        ledger.setAmountPaid(newAmountPaid);

        // Check if total amount due (including late fees) is cleared
        BigDecimal totalDue = ledger.getAmountDue().add(ledger.getLateFee());
        if (newAmountPaid.compareTo(totalDue) >= 0) {
            ledger.setStatus(RentStatus.PAID);
        }

        // Generate Invoice
        Invoice invoice = new Invoice();
        invoice.setPayment(payment);
        invoice.setInvoiceNumber("INV-" + payment.getId().toString().substring(0, 8).toUpperCase());
        // In reality, call an S3 service to generate and upload PDF, then set URL here
        invoice.setPdfUrl("https://s3.smartpg.com/invoices/" + invoice.getInvoiceNumber() + ".pdf");
        
        invoiceRepository.save(invoice);
        
        log.info("Processed successful payment {} for ledger {}. Invoice {} generated.", 
                payment.getId(), ledger.getId(), invoice.getInvoiceNumber());
    }
}
