package com.blooddonor.dto;

import com.blooddonor.dto.DonorDtos.RegisterDonor;
import com.blooddonor.entity.OtpCode;
import com.blooddonor.entity.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
            @NotBlank @Size(min = 8, max = 72) String password,
            /* 6-digit code from POST /api/auth/otp/request (purpose REGISTER); required unless app.otp.required-for-register=false */
            @Pattern(regexp = "^\\d{6}$", message = "must be the 6-digit code") String otpCode
    ) {
    }

    public record OtpRequest(@NotBlank String phone, @NotNull OtpCode.Purpose purpose) {
    }

    public record OtpLogin(@NotBlank String phone, @NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits") String code) {
    }

    /** devCode is only filled in local development (SMS provider "log"), so you can test without a phone. */
    public record OtpSent(String sentTo, long expiresInSeconds, String devCode) {
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
