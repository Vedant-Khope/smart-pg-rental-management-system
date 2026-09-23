package com.smartpg.module.property.service;

import com.smartpg.module.property.dto.request.CreatePropertyRequest;
import com.smartpg.module.property.dto.response.PropertyResponse;
import com.smartpg.module.property.enums.PropertyStatus;
import com.smartpg.module.property.exception.PropertyNotFoundException;
import com.smartpg.module.property.model.Property;
import com.smartpg.module.property.model.PropertyAddress;
import com.smartpg.module.property.model.PropertyAmenity;
import com.smartpg.module.property.repository.PropertyPhotoRepository;
import com.smartpg.module.property.repository.PropertyRepository;
import com.smartpg.common.exception.UnauthorizedException;
import com.smartpg.module.user.model.User;
import com.smartpg.module.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Core business logic for managing Properties.
 *
 * <p><b>Responsibilities:</b>
 * <ul>
 *   <li>Enforce business rules (e.g., only owners can create listings).</li>
 *   <li>Manage state transitions (PENDING_APPROVAL -> ACTIVE).</li>
 *   <li>Handle aggregate persistence (saving Property + Address + Amenities safely).</li>
 * </ul>
 */
@Service
public class PropertyService {

    private static final Logger log = LoggerFactory.getLogger(PropertyService.class);

    private final PropertyRepository propertyRepository;
    private final PropertyPhotoRepository propertyPhotoRepository;
    private final UserRepository userRepository;

    public PropertyService(PropertyRepository propertyRepository,
                           PropertyPhotoRepository propertyPhotoRepository,
                           UserRepository userRepository) {
        this.propertyRepository = propertyRepository;
        this.propertyPhotoRepository = propertyPhotoRepository;
        this.userRepository = userRepository;
    }

    /**
     * Creates a new property listing.
     * Starts in PENDING_APPROVAL status automatically.
     */
    @Transactional
    public PropertyResponse createProperty(CreatePropertyRequest request, UUID ownerId) {
        log.info("Creating new property for owner: {}", ownerId);

        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new UnauthorizedException("Owner not found"));

        // 1. Build Property Aggregate Root
        Property property = new Property();
        property.setOwner(owner);
        property.setName(request.name());
        property.setType(request.type());
        property.setDescription(request.description());
        property.setGenderPreference(request.genderPreference());
        property.setHouseRules(request.houseRules());
        property.setSecurityDepositAmount(request.securityDepositAmount());
        // Status is automatically PENDING_APPROVAL from the entity default

        // 2. Build and link Address
        PropertyAddress address = new PropertyAddress();
        address.setProperty(property);
        address.setStreetAddress(request.streetAddress());
        address.setLandmark(request.landmark());
        address.setLocality(request.locality());
        address.setCity(request.city());
        address.setState(request.state());
        address.setPincode(request.pincode());
        property.setAddress(address);

        // 3. Build and link Amenities
        if (request.amenities() != null) {
            request.amenities().forEach(amenityType -> {
                PropertyAmenity amenity = new PropertyAmenity();
                amenity.setProperty(property);
                amenity.setAmenityType(amenityType);
                property.getAmenities().add(amenity);
            });
        }

        // 4. Persist aggregate (Cascades to Address and Amenities)
        Property savedProperty = propertyRepository.save(property);
        log.info("Property created successfully with ID: {}", savedProperty.getId());

        // Note: OwnerProfile.totalProperties cache update would ideally happen here
        // usually via Spring Events to keep domains decoupled.

        return PropertyResponse.from(savedProperty);
    }

    /**
     * Admin workflow to approve or reject a pending property.
     */
    @Transactional
    public void reviewProperty(UUID propertyId, boolean isApproved, String reason, UUID adminId) {
        log.info("Admin {} reviewing property {}. Approved: {}", adminId, propertyId, isApproved);
        
        PropertyStatus newStatus = isApproved ? PropertyStatus.ACTIVE : PropertyStatus.REJECTED;
        Instant approvedAt = isApproved ? Instant.now() : null;
        UUID approver = isApproved ? adminId : null;
        
        int updated = propertyRepository.updatePropertyStatus(
            propertyId, newStatus, reason, approvedAt, approver, Instant.now()
        );
        
        if (updated == 0) {
            throw new PropertyNotFoundException(propertyId);
        }
        
        // Future: Publish Spring Event so NotificationService can email the Owner
    }

    /**
     * Allows an owner to set a specific photo as the "Cover Photo".
     */
    @Transactional
    public void setCoverPhoto(UUID propertyId, UUID photoId, UUID ownerId) {
        log.info("Setting photo {} as cover for property {}", photoId, propertyId);
        
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new PropertyNotFoundException(propertyId));
                
        if (!property.getOwner().getId().equals(ownerId)) {
            throw new UnauthorizedException("You do not own this property");
        }

        // Two-step process enforced at the DB level for atomicity
        propertyPhotoRepository.setAllCoverPhotosFalse(propertyId);
        int rows = propertyPhotoRepository.setCoverPhotoTrue(propertyId, photoId);
        
        if (rows == 0) {
            throw new IllegalArgumentException("Photo does not exist or belong to this property");
        }
    }
    
    @Transactional(readOnly = true)
    public Page<PropertyResponse> getOwnerProperties(UUID ownerId, Pageable pageable) {
        return propertyRepository.findByOwnerId(ownerId, pageable)
                .map(PropertyResponse::from);
    }
}
