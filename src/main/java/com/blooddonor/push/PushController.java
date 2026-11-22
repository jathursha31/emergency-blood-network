package com.blooddonor.push;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Browser notifications: the server's public key (public) and the donor's own device subscriptions. */
@RestController
public class PushController {

    /** Same shape as the browser's PushSubscription.toJSON(), plus the user agent. */
    public record SubscribeBody(String endpoint, Keys keys, String userAgent) {
        public record Keys(String p256dh, String auth) {
        }
    }

    public record EndpointBody(String endpoint) {
    }

    private final PushService push;
    private final VapidKeys vapid;

    public PushController(PushService push, VapidKeys vapid) {
        this.push = push;
        this.vapid = vapid;
    }

    @GetMapping("/api/push/vapid-public-key")
    public Map<String, String> publicKey() {
        return Map.of("publicKey", vapid.publicKey());
    }

    @GetMapping("/api/me/push-subscriptions")
    public Map<String, Integer> devices(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("devices", push.deviceCount(jwt.getSubject()));
    }

    @PostMapping("/api/me/push-subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@AuthenticationPrincipal Jwt jwt, @RequestBody SubscribeBody body) {
        SubscribeBody.Keys keys = body.keys() == null ? new SubscribeBody.Keys(null, null) : body.keys();
        push.subscribe(jwt.getSubject(),
                new PushService.SubscriptionRequest(body.endpoint(), keys.p256dh(), keys.auth(), body.userAgent()));
    }

    @DeleteMapping("/api/me/push-subscriptions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@AuthenticationPrincipal Jwt jwt, @RequestBody EndpointBody body) {
        if (body.endpoint() != null) push.unsubscribe(jwt.getSubject(), body.endpoint());
    }

    @PostMapping("/api/me/push-subscriptions/test")
    public Map<String, Integer> test(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("delivered", push.sendTest(jwt.getSubject()));
    }
}
