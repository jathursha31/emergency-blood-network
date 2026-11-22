package com.blooddonor.dto;

import com.blooddonor.entity.BloodGroup;
import com.blooddonor.entity.RequestStatus;
import com.blooddonor.entity.ResponseStatus;
import com.blooddonor.entity.Urgency;
import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;

public final class RequestDtos {

    private RequestDtos() {
    }

    /** hospitalId: leave empty when logged in as a hospital (your own is used); required for ADMIN. */
    public record CreateBloodRequest(
            Long hospitalId,
            @NotBlank @Size(max = 100) String patientName,
            @NotNull BloodGroup bloodGroup,
            @NotNull @Min(1) @Max(10) Integer unitsNeeded,
            @NotNull Urgency urgency,
            @NotNull @Future LocalDateTime neededBy
    ) {
        public CreateBloodRequest withHospitalId(Long id) {
            return new CreateBloodRequest(id, patientName, bloodGroup, unitsNeeded, urgency, neededBy);
        }
    }

    /**
     * What a donor sees about a request they were notified for.
     * No other donors' details; hospital phone only after the donor accepts.
     */
    public record DonorInboxItem(
            Long requestId,
            String hospitalName,
            String hospitalAddress,
            String hospitalPhone,
            String bloodGroupNeeded,
            Urgency urgency,
            RequestStatus requestStatus,
            ResponseStatus myStatus,
            double distanceKm,
            LocalDateTime neededBy,
            LocalDateTime notifiedAt
    ) {
    }

    public record BloodRequestView(
            Long id,
            Long hospitalId,
            String hospitalName,
            String patientName,
            BloodGroup bloodGroup,
            String bloodGroupLabel,
            int unitsNeeded,
            Urgency urgency,
            RequestStatus status,
            double searchRadiusKm,
            LocalDateTime neededBy,
            LocalDateTime createdAt,
            LocalDateTime closedAt,
            long notifiedCount,
            long acceptedCount,
            long donatedCount,
            List<ResponseView> donors,
            /* raised automatically by the blood bank */
            boolean restock
    ) {
    }

    /** Phone is revealed to the hospital only after the donor has accepted. */
    public record ResponseView(
            Long donorId,
            String donorName,
            BloodGroup bloodGroup,
            double distanceKm,
            ResponseStatus status,
            String phone,
            LocalDateTime notifiedAt,
            LocalDateTime respondedAt,
            LocalDateTime donatedAt,
            /* rounded to ~1 km, only once the donor has accepted (for the live map) */
            Double approxLatitude,
            Double approxLongitude
    ) {
    }
}
