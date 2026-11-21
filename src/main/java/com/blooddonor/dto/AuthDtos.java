package com.blooddonor.dto;

import com.blooddonor.dto.DonorDtos.RegisterDonor;
import com.blooddonor.entity.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /** Public donor sign-up. The donor logs in with their phone number. */
    public record RegisterDonorAccount(
            @NotNull @Valid RegisterDonor donor,
            @NotBlank @Size(min = 8, max = 72) String password
    ) {
    }

    public record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            Instant expiresAt,
            String username,
            Role role,
            Long donorId,
            Long hospitalId
    ) {
    }

    public record Me(String username, Role role, Long donorId, Long hospitalId) {
    }
}
