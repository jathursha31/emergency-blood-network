package com.blooddonor.dto;

import com.blooddonor.entity.AuditEvent;
import com.blooddonor.entity.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class AdminDtos {

    private AdminDtos() {
    }

    public record AccountRow(
            Long id,
            String username,
            Role role,
            /* donor's name or hospital's name */
            String displayName,
            Long donorId,
            Long hospitalId,
            boolean enabled,
            LocalDateTime createdAt,
            LocalDateTime lastLoginAt,
            long activeSessions,
            /* set while the account is locked after too many failed logins */
            LocalDateTime lockedUntil
    ) {
    }

    public record PageResult<T>(List<T> items, int page, int size, long total) {
    }

    /** Why an account is being disabled (kept in the audit log). */
    public record DisableRequest(@Size(max = 200) String reason) {
    }

    /** The temporary password is shown to the admin once and never stored in plain text. */
    public record PasswordReset(String username, String temporaryPassword, long sessionsClosed) {
    }

    public record ChangePassword(@NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    public record AuditRow(Long id, LocalDateTime at, String actor, AuditEvent.Action action, String target,
                           String details, String ip) {
    }
}
