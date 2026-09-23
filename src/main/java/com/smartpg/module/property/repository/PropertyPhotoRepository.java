package com.smartpg.module.property.repository;

import com.smartpg.module.property.model.PropertyPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository for managing property photos.
 * Extracting this from PropertyRepository enables direct photo manipulations
 * (like setting cover photos) without loading the entire heavy Property aggregate.
 */
@Repository
public interface PropertyPhotoRepository extends JpaRepository<PropertyPhoto, UUID> {

    /**
     * Step 1 of setting a cover photo: clear existing cover flags for the property.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PropertyPhoto p SET p.coverPhoto = false WHERE p.property.id = :propertyId")
    void setAllCoverPhotosFalse(@Param("propertyId") UUID propertyId);

    /**
     * Step 2 of setting a cover photo: set the chosen one to true.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PropertyPhoto p SET p.coverPhoto = true WHERE p.id = :photoId AND p.property.id = :propertyId")
    int setCoverPhotoTrue(@Param("propertyId") UUID propertyId, @Param("photoId") UUID photoId);
}
