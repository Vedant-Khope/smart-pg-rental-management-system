import os

base_dir = r'C:\Users\vedan\.gemini\antigravity\scratch\smart-pg-backend\src\main\java\com\smartpg\module\property'
dirs = ['repository', 'service', 'dto/request', 'dto/response', 'exception']
for d in dirs:
    os.makedirs(os.path.join(base_dir, d.replace('/', '\\')), exist_ok=True)

files = {}

files[r'exception\PropertyNotFoundException.java'] = '''package com.smartpg.module.property.exception;

import com.smartpg.common.exception.ResourceNotFoundException;
import java.util.UUID;

public class PropertyNotFoundException extends ResourceNotFoundException {
    public PropertyNotFoundException(UUID id) {
        super("Property not found with ID: " + id);
    }
}
'''

files[r'dto\request\CreatePropertyRequest.java'] = '''package com.smartpg.module.property.dto.request;

import com.smartpg.module.property.enums.AmenityType;
import com.smartpg.module.property.enums.GenderPreference;
import com.smartpg.module.property.enums.PropertyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CreatePropertyRequest(
    @NotBlank(message = "Property name is required") String name,
    @NotNull(message = "Property type is required") PropertyType type,
    String description,
    @NotNull(message = "Gender preference is required") GenderPreference genderPreference,
    String houseRules,
    Integer securityDepositAmount,
    
    // Address fields
    @NotBlank(message = "Street address is required") String streetAddress,
    String landmark,
    @NotBlank(message = "Locality is required") String locality,
    @NotBlank(message = "City is required") String city,
    @NotBlank(message = "State is required") String state,
    @NotBlank(message = "Pincode is required") String pincode,
    
    // Amenities
    List<AmenityType> amenities
) {}
'''

files[r'dto\response\PropertyResponse.java'] = '''package com.smartpg.module.property.dto.response;

import com.smartpg.module.property.model.Property;
import java.util.UUID;

public record PropertyResponse(
    UUID id,
    String name,
    String type,
    String status,
    String locality,
    String city,
    boolean isFeatured
) {
    public static PropertyResponse from(Property property) {
        return new PropertyResponse(
            property.getId(),
            property.getName(),
            property.getType().name(),
            property.getStatus().name(),
            property.getAddress() != null ? property.getAddress().getLocality() : null,
            property.getAddress() != null ? property.getAddress().getCity() : null,
            property.isFeatured()
        );
    }
}
'''

files[r'repository\PropertyRepository.java'] = '''package com.smartpg.module.property.repository;

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
'''

files[r'repository\PropertyPhotoRepository.java'] = '''package com.smartpg.module.property.repository;

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
'''

files[r'service\PropertyService.java'] = '''package com.smartpg.module.property.service;

import com.smartpg.module.property.dto.request.CreatePropertyRequest;
import com.smartpg.module.property.dto.response.PropertyResponse;
import com.smartpg.module.property.enums.PropertyStatus;
import com.smartpg.module.property.exception.PropertyNotFoundException;
import com.smartpg.module.property.model.Property;
import com.smartpg.module.property.model.PropertyAddress;
import com.smartpg.module.property.model.PropertyAmenity;
import com.smartpg.module.property.repository.PropertyPhotoRepository;
import com.smartpg.module.property.repository.PropertyRepository;
import com.smartpg.module.user.exception.UnauthorizedException;
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
'''

for path, content in files.items():
    full_path = os.path.join(base_dir, path.replace('\\', '/'))
    with open(full_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f'Written: {path}')
print('Done!')
