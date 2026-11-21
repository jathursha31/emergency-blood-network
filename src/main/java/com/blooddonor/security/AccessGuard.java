package com.blooddonor.security;

import com.blooddonor.entity.Role;
import com.blooddonor.exception.ForbiddenException;

/** Ownership checks that URL role rules can't express. */
public final class AccessGuard {

    private AccessGuard() {
    }

    /** ADMIN, or the HOSPITAL that owns the resource. */
    public static void requireHospitalOrAdmin(CurrentUser user, Long hospitalId) {
        if (user.isAdmin()) return;
        if (user.role() == Role.HOSPITAL && hospitalId != null && hospitalId.equals(user.hospitalId())) return;
        throw new ForbiddenException("This request belongs to another hospital");
    }
}
