package com.smartpg.module.property.repository;

import com.smartpg.module.property.enums.AmenityType;
import com.smartpg.module.property.model.PropertyAmenity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Data access layer for PropertyAmenity.
 *
 * <p>Kept as a separate repository from {@link PropertyRepository} so
 * amenity-specific bulk operations (e.g., replace all amenities on an update)
 * can be done directly without loading the full Property aggregate.
 */
@Repository
public interface PropertyAmenityRepository extends JpaRepository<PropertyAmenity, UUID> {

    /**
     * Loads all amenities for a given property.
     * Used when rendering the property detail page.
     */
    List<PropertyAmenity> findByPropertyId(UUID propertyId);

    /**
     * Deletes all amenities for a property — used during a full amenity update.
     * After calling this, the service re-inserts the new amenity set.
     *
     * <p>Why delete-then-insert instead of diffing?
     * Diffing (find added, find removed) requires two queries and complex logic.
     * Since amenities are lightweight (no financial records attached), a clean
     * wipe-and-repopulate is simpler, auditable, and fast.
     */
    void deleteByPropertyId(UUID propertyId);

    /**
     * Checks if an amenity type already exists on a property.
     * Service-layer guard before insert to give a cleaner error than a DB constraint violation.
     */
    boolean existsByPropertyIdAndAmenityType(UUID propertyId, AmenityType amenityType);
}
