package com.blooddonor.controller;

import com.blooddonor.dto.DonorDtos.*;
import com.blooddonor.dto.RequestDtos.DonorInboxItem;
import com.blooddonor.dto.RewardDtos.MyRewards;
import com.blooddonor.security.CurrentUser;
import com.blooddonor.service.BloodRequestService;
import com.blooddonor.service.DonorService;
import com.blooddonor.service.RewardService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Donor self-service. The donor id always comes from the JWT, never from the URL,
 * so one donor cannot act on behalf of another.
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final DonorService donorService;
    private final BloodRequestService requestService;
    private final RewardService rewardService;

    public MeController(DonorService donorService, BloodRequestService requestService, RewardService rewardService) {
        this.donorService = donorService;
        this.requestService = requestService;
        this.rewardService = rewardService;
    }

    /** My badge, donation count, lives helped and leaderboard rank. */
    @GetMapping("/rewards")
    public MyRewards rewards(@AuthenticationPrincipal Jwt jwt) {
        return rewardService.forDonor(donorId(jwt));
    }

    @GetMapping
    public DonorView profile(@AuthenticationPrincipal Jwt jwt) {
        return donorService.get(donorId(jwt));
    }

    @PatchMapping("/availability")
    public DonorView updateAvailability(@AuthenticationPrincipal Jwt jwt,
                                        @Valid @RequestBody UpdateAvailability request) {
        return donorService.updateAvailability(donorId(jwt), request.available());
    }

    @GetMapping("/eligibility")
    public Eligibility eligibility(@AuthenticationPrincipal Jwt jwt) {
        return donorService.eligibility(donorId(jwt));
    }

    @GetMapping("/donations")
    public List<DonationRecord> donations(@AuthenticationPrincipal Jwt jwt) {
        return donorService.donationHistory(donorId(jwt));
    }

    /** Requests I was notified about. */
    @GetMapping("/requests")
    public List<DonorInboxItem> inbox(@AuthenticationPrincipal Jwt jwt) {
        return requestService.inbox(donorId(jwt));
    }

    @PostMapping("/requests/{id}/accept")
    public DonorInboxItem accept(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Long donorId = donorId(jwt);
        requestService.accept(id, donorId);
        return requestService.inboxItem(id, donorId);
    }

    @PostMapping("/requests/{id}/decline")
    public DonorInboxItem decline(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        Long donorId = donorId(jwt);
        requestService.decline(id, donorId);
        return requestService.inboxItem(id, donorId);
    }

    private Long donorId(Jwt jwt) {
        return CurrentUser.from(jwt).donorId();
    }
}
