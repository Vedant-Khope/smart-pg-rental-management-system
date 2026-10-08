package com.smartpg.module.booking.repository;

import com.smartpg.module.booking.enums.TenancyStatus;
import com.smartpg.module.booking.model.Tenancy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenancyRepository extends JpaRepository<Tenancy, UUID> {

    /**
     * Find the active tenancy for a specific bed.
     * There should only ever be one ACTIVE tenancy per bed at a time.
     */
    Optional<Tenancy> findByBedIdAndStatus(UUID bedId, TenancyStatus status);

    /**
     * Get all tenancies (past and present) for a tenant.
     * Used for tenant history.
     */
    List<Tenancy> findByTenantIdOrderByMoveInDateDesc(UUID tenantId);

    /**
     * Find all active tenancies in a specific property.
     * This requires joining through Bed -> Room -> Property.
     * Used for the Owner's dashboard to see who is currently staying.
     */
    @Query("SELECT t FROM Tenancy t JOIN t.bed b JOIN b.room r WHERE r.property.id = :propertyId AND t.status = 'ACTIVE'")
    List<Tenancy> findActiveTenanciesByPropertyId(@Param("propertyId") UUID propertyId);
}
