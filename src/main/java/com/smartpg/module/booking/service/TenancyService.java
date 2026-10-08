package com.smartpg.module.booking.service;

import com.smartpg.common.exception.BadRequestException;
import com.smartpg.common.exception.UnauthorizedException;
import com.smartpg.module.booking.dto.response.TenancyResponse;
import com.smartpg.module.booking.enums.BookingStatus;
import com.smartpg.module.booking.enums.TenancyStatus;
import com.smartpg.module.booking.exception.BookingNotFoundException;
import com.smartpg.module.booking.exception.TenancyNotFoundException;
import com.smartpg.module.booking.model.Booking;
import com.smartpg.module.booking.model.Tenancy;
import com.smartpg.module.booking.repository.BookingRepository;
import com.smartpg.module.booking.repository.TenancyRepository;
import com.smartpg.module.property.exception.PropertyNotFoundException;
import com.smartpg.module.property.model.Property;
import com.smartpg.module.property.repository.PropertyRepository;
import com.smartpg.module.room.enums.BedStatus;
import com.smartpg.module.room.model.Bed;
import com.smartpg.module.room.repository.BedRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class TenancyService {

    private final TenancyRepository tenancyRepository;
    private final BookingRepository bookingRepository;
    private final BedRepository bedRepository;
    private final PropertyRepository propertyRepository;

    public TenancyService(TenancyRepository tenancyRepository,
                          BookingRepository bookingRepository,
                          BedRepository bedRepository,
                          PropertyRepository propertyRepository) {
        this.tenancyRepository = tenancyRepository;
        this.bookingRepository = bookingRepository;
        this.bedRepository = bedRepository;
        this.propertyRepository = propertyRepository;
    }

    /**
     * Converts an APPROVED Booking into an ACTIVE Tenancy when the tenant moves in.
     */
    @Transactional
    public Tenancy moveInTenant(UUID bookingId, BigDecimal monthlyRent, BigDecimal securityDeposit) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (booking.getStatus() != BookingStatus.APPROVED) {
            throw new BadRequestException("Only APPROVED bookings can be moved in.");
        }

        Bed bed = booking.getBed();

        // Safety check: is the bed still available? 
        if (bed.getStatus() != BedStatus.AVAILABLE) {
            throw new BadRequestException("Cannot move in: Bed is currently occupied or under maintenance.");
        }

        // 1. Create Tenancy
        Tenancy tenancy = new Tenancy();
        tenancy.setTenant(booking.getTenant());
        tenancy.setBed(bed);
        tenancy.setBooking(booking);
        tenancy.setMoveInDate(LocalDate.now());
        tenancy.setMonthlyRent(monthlyRent);
        tenancy.setSecurityDeposit(securityDeposit);
        tenancy.setStatus(TenancyStatus.ACTIVE);
        
        Tenancy savedTenancy = tenancyRepository.save(tenancy);

        // 2. Update Bed Status to OCCUPIED
        bed.setStatus(BedStatus.OCCUPIED);
        bedRepository.save(bed);

        return savedTenancy;
    }

    /**
     * Records a tenant moving out, freeing up the bed.
     */
    @Transactional
    public Tenancy moveOutTenant(UUID tenancyId) {
        Tenancy tenancy = tenancyRepository.findById(tenancyId)
                .orElseThrow(() -> new TenancyNotFoundException(tenancyId));

        if (tenancy.getStatus() != TenancyStatus.ACTIVE && tenancy.getStatus() != TenancyStatus.NOTICE_PERIOD) {
            throw new BadRequestException("Can only move out tenants with ACTIVE or NOTICE_PERIOD status.");
        }

        // 1. Mark Tenancy as VACATED
        tenancy.setStatus(TenancyStatus.VACATED);
        tenancy.setMoveOutDate(LocalDate.now());
        Tenancy updatedTenancy = tenancyRepository.save(tenancy);

        // 2. Mark Bed as AVAILABLE
        Bed bed = tenancy.getBed();
        bed.setStatus(BedStatus.AVAILABLE);
        bedRepository.save(bed);

        return updatedTenancy;
    }

    /**
     * Get all tenancies for a specific tenant (History/Current stay).
     */
    @Transactional(readOnly = true)
    public List<TenancyResponse> getTenantTenancies(UUID tenantId) {
        return tenancyRepository.findByTenantIdOrderByMoveInDateDesc(tenantId).stream()
                .map(TenancyResponse::fromEntity)
                .toList();
    }

    /**
     * Get active tenancies in a property for the Owner dashboard.
     */
    @Transactional(readOnly = true)
    public List<TenancyResponse> getActiveTenanciesByProperty(UUID propertyId, UUID ownerId) {
        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new PropertyNotFoundException(propertyId));

        if (!property.getOwner().getId().equals(ownerId)) {
            throw new UnauthorizedException("You do not own this property");
        }

        return tenancyRepository.findActiveTenanciesByPropertyId(propertyId).stream()
                .map(TenancyResponse::fromEntity)
                .toList();
    }
}
