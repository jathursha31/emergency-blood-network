package com.blooddonor.live;

import com.blooddonor.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live event stream for the logged-in user (any role):
 * GET /api/live with "Authorization: Bearer ..." and "Accept: text/event-stream".
 */
@RestController
public class LiveController {

    private final LiveUpdateService live;

    public LiveController(LiveUpdateService live) {
        this.live = live;
    }

    @GetMapping(path = "/api/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
        return live.subscribe(CurrentUser.from(jwt));
    }
}
