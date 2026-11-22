package com.blooddonor.controller;

import com.blooddonor.dto.ChatDtos.*;
import com.blooddonor.entity.ChatMessage.Sender;
import com.blooddonor.security.AccessGuard;
import com.blooddonor.security.CurrentUser;
import com.blooddonor.service.BloodRequestService;
import com.blooddonor.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Hospital-donor chat about a blood request.
 * Donor side lives under /api/me (donor id from the token); hospital side under /api/requests (ownership checked).
 */
@RestController
public class ChatController {

    private final ChatService chat;
    private final BloodRequestService requests;

    public ChatController(ChatService chat, BloodRequestService requests) {
        this.chat = chat;
        this.requests = requests;
    }

    // ---------------------------------------------------------------- donor

    @GetMapping("/api/me/requests/{id}/messages")
    public Conversation donorConversation(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return chat.forDonor(id, CurrentUser.from(jwt).donorId());
    }

    @PostMapping("/api/me/requests/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView donorSend(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody SendMessage body) {
        Long donorId = CurrentUser.from(jwt).donorId();
        chat.forDonor(id, donorId); // same access rule as reading
        return chat.send(id, donorId, Sender.DONOR, false, body.body());
    }

    @GetMapping("/api/me/messages/unread")
    public List<Unread> donorUnread(@AuthenticationPrincipal Jwt jwt) {
        return chat.unreadForDonor(CurrentUser.from(jwt).donorId());
    }

    // ---------------------------------------------------------------- hospital / admin

    @GetMapping("/api/requests/{id}/donors/{donorId}/messages")
    public Conversation hospitalConversation(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @PathVariable Long donorId) {
        CurrentUser user = CurrentUser.from(jwt);
        AccessGuard.requireHospitalOrAdmin(user, requests.hospitalIdOf(id));
        return chat.forHospital(id, donorId, !user.isAdmin());
    }

    @PostMapping("/api/requests/{id}/donors/{donorId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView hospitalSend(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @PathVariable Long donorId,
                                    @Valid @RequestBody SendMessage body) {
        CurrentUser user = CurrentUser.from(jwt);
        AccessGuard.requireHospitalOrAdmin(user, requests.hospitalIdOf(id));
        return chat.send(id, donorId, Sender.HOSPITAL, user.isAdmin(), body.body());
    }

    /** HOSPITAL: unread donor messages on its requests. ADMIN: on all requests. */
    @GetMapping("/api/requests/messages/unread")
    public List<Unread> hospitalUnread(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser user = CurrentUser.from(jwt);
        return chat.unreadForHospital(user.isAdmin() ? null : user.hospitalId());
    }
}
