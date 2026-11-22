package com.blooddonor.security;

import com.blooddonor.entity.Role;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The logged-in user, read from the verified JWT.
 * sessionId is the login session (refresh-token family) the token belongs to; null for tokens without one.
 */
public record CurrentUser(String username, Role role, Long donorId, Long hospitalId, String sessionId) {

    static final String ROLE_CLAIM = "role";
    static final String DONOR_CLAIM = "donorId";
    static final String HOSPITAL_CLAIM = "hospitalId";
    static final String SESSION_CLAIM = "sid";
    static final String TOKEN_VERSION_CLAIM = "tv";

    public CurrentUser(String username, Role role, Long donorId, Long hospitalId) {
        this(username, role, donorId, hospitalId, null);
    }

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(
                jwt.getSubject(),
                Role.valueOf(jwt.getClaimAsString(ROLE_CLAIM)),
                longClaim(jwt, DONOR_CLAIM),
                longClaim(jwt, HOSPITAL_CLAIM),
                jwt.getClaimAsString(SESSION_CLAIM));
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    static Long longClaim(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        return value instanceof Number n ? n.longValue() : null;
    }
}
