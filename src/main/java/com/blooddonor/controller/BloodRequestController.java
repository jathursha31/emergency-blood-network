package com.blooddonor.controller;

import com.blooddonor.dto.RequestDtos.BloodRequestView;
import com.blooddonor.dto.RequestDtos.CreateBloodRequest;
import com.blooddonor.entity.RequestStatus;
import com.blooddonor.entity.Role;
import com.blooddonor.exception.ForbiddenException;
import com.blooddonor.security.AccessGuard;
import com.blooddonor.security.CurrentUser;
import com.blooddonor.service.BloodRequestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Hospital side of a request (HOSPITAL for its own requests, ADMIN for all).
 * Donors accept/decline through /api/me/requests instead.
 */
@RestController
@RequestMapping("/api/requests")
public class BloodRequestController {

    private final BloodRequestService requestService;

    public BloodRequestController(BloodRequestService requestService) {
        this.requestService = requestService;
    }

    /** Raise an emergency request; nearby eligible donors are notified immediately. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BloodRequestView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateBloodRequest request) {
        CurrentUser user = CurrentUser.from(jwt);
        if (user.role() == Role.HOSPITAL) {
            if (request.hospitalId() != null && !request.hospitalId().equals(user.hospitalId())) {
                throw new ForbiddenException("A hospital can only raise requests for itself");
            }
            request = request.withHospitalId(user.hospitalId());
        }
        return requestService.create(request);
    }

    /** HOSPITAL: own requests. ADMIN: all, or one hospital's with ?hospitalId= */
    @GetMapping
    public List<BloodRequestView> list(@AuthenticationPrincipal Jwt jwt,
                                       @RequestParam(required = false) RequestStatus status,
                                       @RequestParam(required = false) Long hospitalId) {
        CurrentUser user = CurrentUser.from(jwt);
        return requestService.list(status, user.isAdmin() ? hospitalId : user.hospitalId());
    }

    @GetMapping("/{id}")
    public BloodRequestView get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        AccessGuard.requireHospitalOrAdmin(CurrentUser.from(jwt), requestService.hospitalIdOf(id));
        return requestService.get(id);
    }

    @PostMapping("/{id}/cancel")
    public BloodRequestView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        AccessGuard.requireHospitalOrAdmin(CurrentUser.from(jwt), requestService.hospitalIdOf(id));
        return requestService.cancel(id);
    }

    /** Hospital confirms the blood was collected from this donor. */
    @PostMapping("/{id}/donors/{donorId}/donated")
    public BloodRequestView markDonated(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable Long id, @PathVariable Long donorId) {
        AccessGuard.requireHospitalOrAdmin(CurrentUser.from(jwt), requestService.hospitalIdOf(id));
        return requestService.markDonated(id, donorId);
    }
}
