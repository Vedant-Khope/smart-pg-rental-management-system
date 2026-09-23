package com.smartpg.module.property.repository;

import com.smartpg.module.property.enums.PropertyStatus;
import com.smartpg.module.property.model.Property;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Data access layer for the Property entity.
 *
 * <p><b>Design Decisions:</b>
 * <ul>
 *   <li><b>Pagination built-in</b>: All queries returning multiple properties use Pageable.
 *       We never want a query to accidentally return 10,000 rows into memory.</li>
 *   <li><b>Targeted Updates</b>: State transitions (approve/reject) use @Modifying
 *       queries instead of full entity saves to avoid overriding concurrent changes
 *       and to make the intent explicitly clear at the DB level.</li>
 * </ul>
 */
@Repository
public interface PropertyRepository extends JpaRepository<Property, UUID> {

    /**
     * Used by the Owner dashboard to list their properties.
     */
    Page<Property> findByOwnerId(UUID ownerId, Pageable pageable);

    /**
     * Used by Admin dashboard to review properties (e.g., status = PENDING_APPROVAL).
     */
    Page<Property> findByStatus(PropertyStatus status, Pageable pageable);

    /**
     * Targetted bulk/single update for admin approval workflows.
     * Prevents dirty-checking overhead and race conditions on other fields.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Property p SET p.status = :status, p.rejectionReason = :reason, p.approvedAt = :approvedAt, p.approvedBy = :adminId, p.updatedAt = :now WHERE p.id = :id")
    int updatePropertyStatus(
            @Param("id") UUID id,
            @Param("status") PropertyStatus status,
            @Param("reason") String reason,
            @Param("approvedAt") Instant approvedAt,
            @Param("adminId") UUID adminId,
            @Param("now") Instant now
    );
    
    /**
     * Used by OwnerService/AdminService to get a quick count without hydrating entities.
     */
    long countByOwnerId(UUID ownerId);
}
