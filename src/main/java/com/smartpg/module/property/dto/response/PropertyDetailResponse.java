package com.smartpg.module.property.dto.response;

import com.smartpg.module.property.model.Property;
import com.smartpg.module.property.model.PropertyAmenity;
import com.smartpg.module.property.model.PropertyPhoto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Rich response DTO for the full property detail page (US-207).
 *
 * <p><b>Why two response DTOs (PropertyResponse and PropertyDetailResponse)?</b>
 *
 * <p>{@link PropertyResponse} is the <em>card</em>: a lean object returned in
 * paginated lists (Owner dashboard, search results). It carries only what the
 * frontend needs to render a card — name, locality, status, cover photo URL.
 * Loading 50 cards with amenities, all photos, and address details would be
 * wasteful (Over-fetching).
 *
 * <p>{@link PropertyDetailResponse} is the <em>full listing page</em>: a richer
 * object with every field, every photo, and every amenity. It's returned only
 * when the user explicitly opens a specific property. This pattern is called
 * <b>CQRS-lite</b> (different read models for different read use cases).
 *
 * <p><b>Security note on street address:</b>
 * The full street address is included here because, per US-207, it is shown
 * on the detail page. In a more privacy-strict design, the street address would
 * be omitted until a booking is confirmed (visible only to confirmed tenants).
 * That gate would be enforced in the service layer, not by using yet another DTO.
 */
public record PropertyDetailResponse(

        UUID id,
        String name,
        String type,
        String status,
        String description,
        String genderPreference,
        String houseRules,
        Integer securityDepositAmount,
        boolean featured,
        Instant createdAt,
        Instant approvedAt,

        // Owner summary (public-safe subset — no email/phone)
        UUID ownerId,

        // Address (full for detail page)
        String streetAddress,
        String landmark,
        String locality,
        String city,
        String state,
        String pincode,

        // Collections
        List<String> amenities,            // ["WIFI", "AC", "MEALS_PROVIDED"]
        List<PhotoSummary> photos          // ordered by displayOrder ASC

) {

    /**
     * Nested record for photo data — keeps the response self-contained,
     * no need to import a separate PhotoResponse class.
     */
    public record PhotoSummary(UUID id, String photoUrl, String caption, boolean isCoverPhoto) {}

    /**
     * Static factory: maps the Property entity + collections to this DTO.
     *
     * <p>Called only AFTER the service has explicitly fetched address, photos,
     * and amenities (or triggered lazy loading within a transaction). This keeps
     * the mapping logic in one place and the constructor clean.
     */
    public static PropertyDetailResponse from(Property property) {
        List<String> amenityNames = property.getAmenities().stream()
                .map(a -> a.getAmenityType().name())
                .toList();

        List<PhotoSummary> photoSummaries = property.getPhotos().stream()
                .map(p -> new PhotoSummary(p.getId(), p.getPhotoUrl(), p.getCaption(), p.isCoverPhoto()))
                .toList();

        var addr = property.getAddress();

        return new PropertyDetailResponse(
                property.getId(),
                property.getName(),
                property.getType().name(),
                property.getStatus().name(),
                property.getDescription(),
                property.getGenderPreference().name(),
                property.getHouseRules(),
                property.getSecurityDepositAmount(),
                property.isFeatured(),
                property.getCreatedAt(),
                property.getApprovedAt(),
                property.getOwner().getId(),
                addr != null ? addr.getStreetAddress() : null,
                addr != null ? addr.getLandmark() : null,
                addr != null ? addr.getLocality() : null,
                addr != null ? addr.getCity() : null,
                addr != null ? addr.getState() : null,
                addr != null ? addr.getPincode() : null,
                amenityNames,
                photoSummaries
        );
    }
}
