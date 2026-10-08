package com.smartpg.module.payment.service;

import com.smartpg.module.booking.model.Tenancy;
import com.smartpg.module.payment.enums.RentStatus;
import com.smartpg.module.payment.model.RentLedger;
import com.smartpg.module.payment.repository.RentLedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RentLedgerService {

    private final RentLedgerRepository rentLedgerRepository;

    /**
     * Finds a ledger by ID or throws an exception.
     */
    @Transactional(readOnly = true)
    public RentLedger getLedgerById(UUID id) {
        return rentLedgerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Rent Ledger not found for ID: " + id));
    }

    /**
     * CRON Job: Runs at 1 AM on the 1st of every month.
     * Finds all active tenancies and creates a rent ledger for them.
     */
    @Scheduled(cron = "0 0 1 * * ?")
    @Transactional
    public void generateMonthlyLedgers() {
        log.info("Starting monthly rent ledger generation...");
        // In a real flow, we would fetch all ACTIVE tenancies from TenancyRepository
        // List<Tenancy> activeTenancies = tenancyRepository.findAllByStatus(TenancyStatus.ACTIVE);
        
        // For each tenancy, calculate the rent and create a ledger.
        // Example logic:
        // for (Tenancy tenancy : activeTenancies) {
        //     createLedgerForTenancy(tenancy, LocalDate.now());
        // }
        log.info("Monthly rent ledger generation completed.");
    }

    /**
     * CRON Job: Runs daily at 2 AM to flag overdue rent entries.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void flagOverdueLedgers() {
        log.info("Flagging overdue rent ledgers...");
        List<RentLedger> pendingLedgers = rentLedgerRepository
                .findByStatusAndDueDateBefore(RentStatus.PENDING, LocalDate.now());
        
        for (RentLedger ledger : pendingLedgers) {
            ledger.setStatus(RentStatus.OVERDUE);
            // We could also apply late fees here based on Owner config
            // ledger.setLateFee(calculateLateFee(ledger.getTenancy().getProperty()));
        }
        
        rentLedgerRepository.saveAll(pendingLedgers);
        log.info("Flagged {} ledgers as OVERDUE.", pendingLedgers.size());
    }

    @Transactional
    public RentLedger createLedgerForTenancy(Tenancy tenancy, LocalDate billingMonth) {
        // Check if a ledger already exists to prevent duplicate billing
        if (rentLedgerRepository.findByTenancyIdAndBillingMonth(tenancy.getId(), billingMonth).isPresent()) {
            throw new RuntimeException("Ledger already exists for this month.");
        }

        RentLedger ledger = new RentLedger();
        ledger.setTenancy(tenancy);
        ledger.setBillingMonth(billingMonth);
        ledger.setDueDate(billingMonth.plusDays(5)); // Due on 5th
        ledger.setAmountDue(tenancy.getMonthlyRent()); 
        
        return rentLedgerRepository.save(ledger);
    }
}
