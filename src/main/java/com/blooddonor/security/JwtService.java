package com.blooddonor.security;

import com.blooddonor.entity.UserAccount;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class JwtService {

    public static final String ISSUER = "blood-donor-network";

    private final JwtEncoder encoder;
    private final SecurityProperties props;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, SecurityProperties props, Clock clock) {
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
    }

    public IssuedToken issue(UserAccount account) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(props.jwtExpiryMinutes(), ChronoUnit.MINUTES);

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(account.getUsername())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(CurrentUser.ROLE_CLAIM, account.getRole().name());
        if (account.getDonor() != null) claims.claim(CurrentUser.DONOR_CLAIM, account.getDonor().getId());
        if (account.getHospital() != null) claims.claim(CurrentUser.HOSPITAL_CLAIM, account.getHospital().getId());

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new IssuedToken(token, expiresAt, props.jwtExpiryMinutes() * 60);
    }

    public record IssuedToken(String value, Instant expiresAt, long expiresInSeconds) {
    }
}
