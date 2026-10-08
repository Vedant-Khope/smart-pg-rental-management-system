package com.smartpg.module.booking.dto.response;

import com.smartpg.module.booking.enums.TenancyStatus;
import com.smartpg.module.booking.model.Tenancy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TenancyResponse(
        UUID id,
        UUID tenantId,
        UUID bedId,
        UUID bookingId,
        TenancyStatus status,
        LocalDate moveInDate,
        LocalDate moveOutDate,
        BigDecimal monthlyRent,
        BigDecimal securityDeposit
) {
    public static TenancyResponse fromEntity(Tenancy tenancy) {
        return new TenancyResponse(
                tenancy.getId(),
                tenancy.getTenant().getId(),
                tenancy.getBed().getId(),
                tenancy.getBooking() != null ? tenancy.getBooking().getId() : null,
                tenancy.getStatus(),
                tenancy.getMoveInDate(),
                tenancy.getMoveOutDate(),
                tenancy.getMonthlyRent(),
                tenancy.getSecurityDeposit()
        );
    }
}
