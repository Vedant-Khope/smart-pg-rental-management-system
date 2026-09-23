package com.smartpg.module.property.dto.request;

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
