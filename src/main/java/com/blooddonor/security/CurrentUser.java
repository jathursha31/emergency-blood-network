package com.blooddonor.security;

import com.blooddonor.entity.Role;
import org.springframework.security.oauth2.jwt.Jwt;

/** The logged-in user, read from the verified JWT. */
public record CurrentUser(String username, Role role, Long donorId, Long hospitalId) {

    static final String ROLE_CLAIM = "role";
    static final String DONOR_CLAIM = "donorId";
    static final String HOSPITAL_CLAIM = "hospitalId";

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(
                jwt.getSubject(),
                Role.valueOf(jwt.getClaimAsString(ROLE_CLAIM)),
                longClaim(jwt, DONOR_CLAIM),
                longClaim(jwt, HOSPITAL_CLAIM));
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    private static Long longClaim(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        return value instanceof Number n ? n.longValue() : null;
    }
}
