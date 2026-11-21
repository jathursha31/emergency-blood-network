package com.blooddonor.dto;

import com.blooddonor.entity.BloodGroup;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class DonorDtos {

    private DonorDtos() {
    }

    public record RegisterDonor(
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Pattern(regexp = "^[6-9]\\d{9}$", message = "must be a 10 digit Indian mobile number")
            String phone,
            @Email @Size(max = 120) String email,
            @NotNull @Past LocalDate dateOfBirth,
            @NotNull BloodGroup bloodGroup,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @Size(max = 60) String city,
            @PastOrPresent LocalDate lastDonationDate
    ) {
    }

    public record UpdateAvailability(@NotNull Boolean available) {
    }

    public record DonorView(
            Long id,
            String fullName,
            String phone,
            String email,
            BloodGroup bloodGroup,
            String bloodGroupLabel,
            String city,
            double latitude,
            double longitude,
            LocalDate lastDonationDate,
            boolean available,
            LocalDateTime registeredAt
    ) {
    }

    public record Eligibility(
            boolean eligible,
            List<String> reasons,
            LocalDate nextEligibleDate,
            long daysRemaining
    ) {
    }

    /** Public search result. Phone is masked until the donor accepts a request. */
    public record NearbyDonor(
            Long donorId,
            String fullName,
            BloodGroup bloodGroup,
            String city,
            double distanceKm,
            String maskedPhone
    ) {
    }

    public record DonationRecord(
            Long requestId,
            String hospitalName,
            String patientBloodGroup,
            LocalDateTime donatedAt
    ) {
    }
}
