package com.blooddonor.dto;

import jakarta.validation.constraints.*;

public final class HospitalDtos {

    private HospitalDtos() {
    }

    public record CreateHospital(
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Pattern(regexp = "^\\d{10,12}$", message = "must be 10-12 digits") String phone,
            @Size(max = 250) String address,
            @Size(max = 60) String city,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            /* login for the hospital's staff account */
            @NotBlank @Pattern(regexp = "^[a-zA-Z0-9._@-]{3,60}$",
                    message = "3-60 characters: letters, digits, . _ @ -") String loginUsername,
            @NotBlank @Size(min = 8, max = 72) String loginPassword
    ) {
    }

    public record HospitalView(
            Long id,
            String name,
            String phone,
            String address,
            String city,
            double latitude,
            double longitude
    ) {
    }
}
