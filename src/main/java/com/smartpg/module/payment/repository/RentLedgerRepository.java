package com.smartpg.module.payment.repository;

import com.smartpg.module.payment.enums.RentStatus;
import com.smartpg.module.payment.model.RentLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RentLedgerRepository extends JpaRepository<RentLedger, UUID> {
    
    List<RentLedger> findByTenancyId(UUID tenancyId);

    Optional<RentLedger> findByTenancyIdAndBillingMonth(UUID tenancyId, LocalDate billingMonth);

    List<RentLedger> findByStatusAndDueDateBefore(RentStatus status, LocalDate date);
}
