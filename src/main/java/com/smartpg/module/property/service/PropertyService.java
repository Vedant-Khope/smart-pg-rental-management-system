package com.smartpg.module.property.service;

import com.smartpg.common.exception.UnauthorizedException;
import com.smartpg.module.property.dto.request.CreatePropertyRequest;
import com.smartpg.module.property.dto.request.UpdatePropertyRequest;
import com.smartpg.module.property.dto.response.PropertyDetailResponse;
import com.smartpg.module.property.dto.response.PropertyResponse;
import com.smartpg.module.property.enums.PropertyStatus;
import com.smartpg.module.property.exception.PropertyNotFoundException;
import com.smartpg.module.property.model.Property;
import com.smartpg.module.property.model.PropertyAddress;
import com.smartpg.module.property.model.PropertyAmenity;
import com.smartpg.module.property.repository.PropertyAmenityRepository;
import com.smartpg.module.property.repository.PropertyPhotoRepository;
import com.smartpg.module.property.repository.PropertyRepository;
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
 *   <li>Enforce ownership rules (only the owner can modify their property).</li>
 *   <li>Manage lifecycle state transitions (PENDING_APPROVAL → ACTIVE → INACTIVE).</li>
 *   <li>Handle aggregate persistence (Property + Address + Amenities in one transaction).</li>
 * </ul>
 *
 * <p><b>Security note:</b>
 * The {@code ownerId} parameter in every write method comes from the JWT principal
 * in the controller — NEVER from the request body. This prevents IDOR attacks
 * where a malicious user could submit another owner's ID to manipulate their listing.
 */
@Service
public class PropertyService {

    private static final Logger log = LoggerFactory.getLogger(PropertyService.class);

    private final PropertyRepository propertyRepository;
    private final PropertyPhotoRepository propertyPhotoRepository;
    private final PropertyAmenityRepository propertyAmenityRepository;
    private final UserRepository userRepository;

    public PropertyService(PropertyRepository propertyRepository,
                           PropertyPhotoRepository propertyPhotoRepository,
                           PropertyAmenityRepository propertyAmenityRepository,
                           UserRepository userRepository) {
        this.propertyRepository = propertyRepository;
        this.propertyPhotoRepository = propertyPhotoRepository;
        this.propertyAmenityRepository = propertyAmenityRepository;
        this.userRepository = userRepository;
    }

    // =========================================================================
    // CREATE
    // =========================================================================

    /**
     * Creates a new property listing submitted by an Owner.
     *
     * <p>Lifecycle: Always starts at {@code PENDING_APPROVAL}. An admin must
     * explicitly approve it before tenants can see it. This prevents fraudulent
     * listings from going live instantly.
     *
     * <p>{@code CascadeType.ALL} on the address and amenities collections means
     * saving the property root cascades the INSERT to child tables automatically —
     * no separate {@code address.save()} or {@code amenity.save()} needed.
     *
     * @param request  validated DTO from the controller
     * @param ownerId  UUID of the authenticated owner (from JWT — never from request body)
     * @return lean {@link PropertyResponse} for the API response
     */
    @Transactional
    public PropertyResponse createProperty(CreatePropertyRequest request, UUID ownerId) {
        log.info("Creating property '{}' for owner: {}", request.name(), ownerId);

        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new UnauthorizedException("Owner not found"));

        // 1. Build Property root
        Property property = new Property();
        property.setOwner(owner);
        property.setName(request.name());
        property.setType(request.type());
        property.setDescription(request.description());
        property.setGenderPreference(request.genderPreference());
        property.setHouseRules(request.houseRules());
        property.setSecurityDepositAmount(request.securityDepositAmount());
        // status auto-defaults to PENDING_APPROVAL in the entity

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

        // 4. Persist aggregate (Cascades to address + amenities in one DB transaction)
        Property saved = propertyRepository.save(property);
        log.info("Property created with ID: {}", saved.getId());

        return PropertyResponse.from(saved);
    }

    // =========================================================================
    // READ
    // =========================================================================

    /**
     * Fetches a single property by ID — used for the tenant detail page and owner detail view.
     *
     * <p><b>Why {@code readOnly = true}?</b>
     * Marks the transaction as read-only, which:
     * (1) Lets Spring skip the dirty-checking phase at transaction end (no UPDATE scan).
     * (2) On replicated DBs, Spring can route the query to a read replica automatically.
     * Result: faster and more efficient than a regular {@code @Transactional}.
     *
     * <p><b>Lazy loading:</b> Address, photos, and amenities are LAZY. Accessing them
     * inside this {@code @Transactional} method is safe — the session is open. The
     * DTO factory ({@code PropertyDetailResponse.from()}) accesses these collections
     * while still inside the transaction, so Hibernate fires the SELECT at that point.
     *
     * @param propertyId UUID of the property to fetch
     * @return rich {@link PropertyDetailResponse} with all photos, amenities, and address
     * @throws PropertyNotFoundException if no property exists with the given ID
     */
    @Transactional(readOnly = true)
    public PropertyDetailResponse getPropertyById(UUID propertyId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new PropertyNotFoundException(propertyId));

        return PropertyDetailResponse.from(property);
    }

    /**
     * Returns all properties owned by a given owner, paginated.
     * Used by the Owner dashboard.
     *
     * @param ownerId  UUID of the owner
     * @param pageable page/size/sort params from the HTTP request
     * @return paginated list of lean {@link PropertyResponse} objects
     */
    @Transactional(readOnly = true)
    public Page<PropertyResponse> getOwnerProperties(UUID ownerId, Pageable pageable) {
        return propertyRepository.findByOwnerId(ownerId, pageable)
                .map(PropertyResponse::from);
    }

    // =========================================================================
    // UPDATE
    // =========================================================================

    /**
     * Updates an existing property's editable fields.
     *
     * <p><b>Who can call this?</b> Only the property owner. We verify ownership
     * inside the service — not just via role annotation — to prevent one OWNER
     * from editing another OWNER's property (IDOR protection).
     *
     * <p><b>Amenity strategy:</b> wipe-and-repopulate.
     * We delete all existing amenities for this property, then insert the new set.
     * This is simpler and more reliable than computing a diff. Since amenities carry
     * no financial or tenancy history, there's no audit risk in doing so.
     *
     * <p><b>Note on status:</b> A minor update (description, amenities) does NOT
     * require re-approval. A significant change like address is allowed here but
     * could be configured to trigger re-approval via a flag — left as a TODO for v1.1.
     *
     * @param propertyId UUID of the property to update
     * @param request    validated update DTO
     * @param ownerId    UUID from JWT — verified against property.owner.id
     * @return updated lean {@link PropertyResponse}
     */
    @Transactional
    public PropertyResponse updateProperty(UUID propertyId, UpdatePropertyRequest request, UUID ownerId) {
        log.info("Owner {} updating property {}", ownerId, propertyId);

        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new PropertyNotFoundException(propertyId));

        // Ownership check: IDOR guard
        if (!property.getOwner().getId().equals(ownerId)) {
            throw new UnauthorizedException("You do not own this property");
        }

        // Cannot edit a DELETED or PENDING_APPROVAL property
        if (property.getStatus() == PropertyStatus.DELETED) {
            throw new IllegalStateException("Cannot update a deleted property");
        }

        // Update scalar fields
        property.setName(request.name());
        property.setDescription(request.description());
        property.setGenderPreference(request.genderPreference());
        property.setHouseRules(request.houseRules());
        property.setSecurityDepositAmount(request.securityDepositAmount());

        // Update address fields in-place (address row already exists, just mutate and save)
        PropertyAddress address = property.getAddress();
        if (address == null) {
            address = new PropertyAddress();
            address.setProperty(property);
            property.setAddress(address);
        }
        address.setStreetAddress(request.streetAddress());
        address.setLandmark(request.landmark());
        address.setLocality(request.locality());
        address.setCity(request.city());
        address.setState(request.state());
        address.setPincode(request.pincode());

        // Wipe existing amenities + repopulate
        propertyAmenityRepository.deleteByPropertyId(propertyId);
        if (request.amenities() != null) {
            request.amenities().forEach(amenityType -> {
                PropertyAmenity amenity = new PropertyAmenity();
                amenity.setProperty(property);
                amenity.setAmenityType(amenityType);
                property.getAmenities().add(amenity);
            });
        }

        Property saved = propertyRepository.save(property);
        log.info("Property {} updated successfully", propertyId);

        return PropertyResponse.from(saved);
    }

    // =========================================================================
    // DEACTIVATE / DELETE (Soft)
    // =========================================================================

    /**
     * Owner-initiated soft deactivation of a property listing.
     *
     * <p><b>Effect:</b> Sets status to {@code INACTIVE}. The property disappears
     * from tenant search results immediately. All existing tenancies are unaffected —
     * tenants already living there are NOT evicted (per US-205 acceptance criteria).
     *
     * <p><b>Why a targeted JPQL update?</b>
     * Instead of loading the full entity (with photos, amenities, address in collections),
     * we issue a single targeted UPDATE. Faster, and avoids race conditions with concurrent
     * edits (we only touch the {@code status} column, not the whole row).
     *
     * @param propertyId UUID of the property to deactivate
     * @param ownerId    UUID from JWT — verified against property.owner.id
     */
    @Transactional
    public void deactivateProperty(UUID propertyId, UUID ownerId) {
        log.info("Owner {} deactivating property {}", ownerId, propertyId);

        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new PropertyNotFoundException(propertyId));

        if (!property.getOwner().getId().equals(ownerId)) {
            throw new UnauthorizedException("You do not own this property");
        }

        if (property.getStatus() == PropertyStatus.DELETED) {
            throw new IllegalStateException("Cannot deactivate an already-deleted property");
        }
        if (property.getStatus() == PropertyStatus.INACTIVE) {
            throw new IllegalStateException("Property is already inactive");
        }

        propertyRepository.updatePropertyStatus(
                propertyId, PropertyStatus.INACTIVE, null, null, null, Instant.now()
        );
        log.info("Property {} set to INACTIVE", propertyId);
    }

    // =========================================================================
    // ADMIN OPERATIONS
    // =========================================================================

    /**
     * Admin approval / rejection of a pending property listing.
     *
     * <p>Uses a targeted JPQL update via {@link PropertyRepository#updatePropertyStatus}
     * instead of loading the full entity. This is intentional: the admin is only
     * changing the status, reason, and approvedAt. Hydrating photos, amenities,
     * and address just to change two columns is wasteful and introduces risk of
     * accidental field overwrites.
     *
     * @param propertyId UUID of the property under review
     * @param isApproved {@code true} to approve, {@code false} to reject
     * @param reason     required when rejecting; null when approving
     * @param adminId    UUID of the admin performing the action
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

        // TODO: Publish Spring ApplicationEvent → NotificationService emails the Owner
        log.info("Property {} status updated to {}", propertyId, newStatus);
    }

    // =========================================================================
    // PHOTO OPERATIONS
    // =========================================================================

    /**
     * Sets the cover photo for a property (shown in search result cards).
     *
     * <p><b>Two-step process (required for correctness):</b>
     * <ol>
     *   <li>Clear ALL cover flags for the property (ensures no two covers exist).</li>
     *   <li>Set the chosen photo to {@code isCoverPhoto = true}.</li>
     * </ol>
     * Both steps run in the same {@code @Transactional} — if step 2 fails
     * (e.g., wrong photo ID), step 1 is rolled back. The property always ends
     * up with exactly one cover photo.
     *
     * @param propertyId UUID of the property
     * @param photoId    UUID of the photo to set as cover
     * @param ownerId    UUID from JWT — verified against property.owner.id
     */
    @Transactional
    public void setCoverPhoto(UUID propertyId, UUID photoId, UUID ownerId) {
        log.info("Setting photo {} as cover for property {}", photoId, propertyId);

        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new PropertyNotFoundException(propertyId));

        if (!property.getOwner().getId().equals(ownerId)) {
            throw new UnauthorizedException("You do not own this property");
        }

        propertyPhotoRepository.setAllCoverPhotosFalse(propertyId);
        int rows = propertyPhotoRepository.setCoverPhotoTrue(propertyId, photoId);

        if (rows == 0) {
            throw new IllegalArgumentException("Photo not found or does not belong to this property");
        }
    }
}
