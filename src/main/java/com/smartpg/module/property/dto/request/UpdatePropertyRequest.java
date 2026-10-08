package com.smartpg.module.property.dto.request;

import com.smartpg.module.property.enums.AmenityType;
import com.smartpg.module.property.enums.GenderPreference;
import com.smartpg.module.property.enums.PropertyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request DTO for an Owner to update an existing property.
 *
 * <p><b>Why a separate DTO from CreatePropertyRequest?</b>
 * Create and Update have different field semantics:
 * - On create: owner_id comes from the JWT principal (never the request body).
 * - On update: we may allow partial updates, and certain fields (like owner, type)
 *   may be immutable after creation. A shared DTO would require nullable fields
 *   everywhere, which breaks validation clarity.
 *
 * <p><b>Immutable fields not included here:</b>
 * - {@code ownerId} — never changeable, comes from JWT
 * - {@code type}    — changing property type (PG → FLAT) has tenancy implications;
 *                      requires a dedicated re-approval workflow, not a simple PATCH
 */
public record UpdatePropertyRequest(

        @NotBlank(message = "Property name is required")
        String name,

        String description,

        @NotNull(message = "Gender preference is required")
        GenderPreference genderPreference,

        String houseRules,
        Integer securityDepositAmount,

        // Address fields — all updatable (minor landmark/locality tweaks don't need re-approval)
        @NotBlank(message = "Street address is required")
        String streetAddress,

        String landmark,

        @NotBlank(message = "Locality is required")
        String locality,

        @NotBlank(message = "City is required")
        String city,

        @NotBlank(message = "State is required")
        String state,

        @NotBlank(message = "Pincode is required")
        String pincode,

        // Replaces ALL existing amenities. Null/empty list = remove all amenities.
        List<AmenityType> amenities
) {}
