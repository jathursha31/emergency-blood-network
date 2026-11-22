package com.blooddonor.push;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;

/**
 * Sends one encrypted message to one browser's push service (FCM for Chrome, autopush for Firefox, Apple for Safari).
 * Authenticates with a VAPID JWT (RFC 8292) and encrypts the payload per RFC 8291.
 */
@Component
public class WebPushSender {

    /** Result of one delivery attempt. gone = the subscription no longer exists and should be deleted. */
    public record Result(int status, boolean ok, boolean gone) {
    }

    private static final Duration TTL = Duration.ofHours(12);

    private final VapidKeys vapid;
    private final String subject;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public WebPushSender(VapidKeys vapid, @Value("${app.push.subject:mailto:admin@blooddonor.local}") String subject,
                         Clock clock) {
        this.vapid = vapid;
        this.subject = subject;
        this.clock = clock;
    }

    public Result send(String endpoint, String p256dh, String auth, String json, boolean urgent)
            throws IOException, InterruptedException {
        byte[] body = WebPushCrypto.encrypt(json.getBytes(StandardCharsets.UTF_8),
                WebPushCrypto.b64(p256dh), WebPushCrypto.b64(auth));
        URI uri = URI.create(endpoint);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "vapid t=" + vapidJwt(uri) + ", k=" + vapid.publicKey())
                .header("TTL", String.valueOf(TTL.toSeconds()))
                .header("Urgency", urgent ? "high" : "normal")
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        return new Result(status, status >= 200 && status < 300, status == 404 || status == 410);
    }

    /** ES256 JWT whose audience is the push service's origin; valid for 12 hours (the limit is 24). */
    String vapidJwt(URI endpoint) {
        String audience = endpoint.getScheme() + "://" + endpoint.getHost()
                + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .audience(audience)
                .expirationTime(Date.from(clock.instant().plus(Duration.ofHours(12))))
                .subject(subject)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new ECDSASigner(vapid.privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign VAPID token", e);
        }
        return jwt.serialize();
    }
}
