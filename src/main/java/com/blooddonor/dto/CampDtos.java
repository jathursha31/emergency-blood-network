package com.blooddonor.dto;

import com.blooddonor.entity.CampBooking;
import com.blooddonor.entity.DonationCamp;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public final class CampDtos {

    private CampDtos() {
    }

    /** hospitalId: only for admins; a hospital always hosts its own camps. */
    public record CreateCamp(
            Long hospitalId,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 250) String address,
            @Size(max = 60) String city,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @FutureOrPresent LocalDate campDate,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotNull @Min(10) @Max(120) Integer slotMinutes,
            @NotNull @Min(1) @Max(50) Integer capacityPerSlot
    ) {
        public CreateCamp withHospitalId(Long id) {
            return new CreateCamp(id, name, address, city, latitude, longitude, campDate, startTime, endTime,
                    slotMinutes, capacityPerSlot);
        }
    }

    public record SlotView(LocalTime time, int booked, int capacity, int available) {
    }

    public record CampView(
            Long id,
            Long hospitalId,
            String hospitalName,
            String name,
            String address,
            String city,
            double latitude,
            double longitude,
            LocalDate campDate,
            LocalTime startTime,
            LocalTime endTime,
            int slotMinutes,
            int capacityPerSlot,
            DonationCamp.Status status,
            int totalCapacity,
            int booked,
            int attended,
            /* only when the caller passed lat/lng */
            Double distanceKm,
            List<SlotView> slots
    ) {
    }

    public record BookSlot(@NotNull Long campId, @NotNull LocalTime slotTime) {
    }

    public record CheckIn(@NotBlank @Size(max = 12) String code) {
    }

    /** What a donor sees: their pass with the check-in code. */
    public record MyBooking(
            Long id,
            String code,
            CampBooking.Status status,
            LocalTime slotTime,
            LocalDateTime bookedAt,
            LocalDateTime checkedInAt,
            Long campId,
            String campName,
            String hospitalName,
            String address,
            LocalDate campDate,
            DonationCamp.Status campStatus,
            double latitude,
            double longitude
    ) {
    }

    /** What the hospital sees for each booking. */
    public record BookingRow(
            Long id,
            String code,
            CampBooking.Status status,
            LocalTime slotTime,
            Long donorId,
            String donorName,
            String bloodGroup,
            String phone,
            LocalDateTime checkedInAt
    ) {
    }
}
