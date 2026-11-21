package com.blooddonor.controller;

import com.blooddonor.dto.DonorDtos.*;
import com.blooddonor.entity.BloodGroup;
import com.blooddonor.service.DonorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin view of any donor (donors use /api/me for themselves, and sign up via /api/auth/register).
 * /nearby is public.
 */
@RestController
@RequestMapping("/api/donors")
public class DonorController {

    private final DonorService donorService;

    public DonorController(DonorService donorService) {
        this.donorService = donorService;
    }

    @GetMapping("/{id}")
    public DonorView get(@PathVariable Long id) {
        return donorService.get(id);
    }

    @PatchMapping("/{id}/availability")
    public DonorView updateAvailability(@PathVariable Long id, @Valid @RequestBody UpdateAvailability request) {
        return donorService.updateAvailability(id, request.available());
    }

    @GetMapping("/{id}/eligibility")
    public Eligibility eligibility(@PathVariable Long id) {
        return donorService.eligibility(id);
    }

    @GetMapping("/{id}/donations")
    public List<DonationRecord> donations(@PathVariable Long id) {
        return donorService.donationHistory(id);
    }

    /** e.g. /api/donors/nearby?bloodGroup=B_POS&lat=13.0418&lng=80.2341&radiusKm=10 */
    @GetMapping("/nearby")
    public List<NearbyDonor> nearby(@RequestParam BloodGroup bloodGroup,
                                    @RequestParam double lat,
                                    @RequestParam double lng,
                                    @RequestParam(defaultValue = "10") double radiusKm) {
        return donorService.searchNearby(bloodGroup, lat, lng, radiusKm);
    }
}
