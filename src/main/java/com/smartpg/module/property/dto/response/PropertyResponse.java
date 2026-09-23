package com.smartpg.module.property.dto.response;

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
