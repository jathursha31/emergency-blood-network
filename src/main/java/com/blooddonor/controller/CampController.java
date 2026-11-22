package com.blooddonor.controller;

import com.blooddonor.dto.CampDtos.*;
import com.blooddonor.entity.Role;
import com.blooddonor.exception.ForbiddenException;
import com.blooddonor.security.CurrentUser;
import com.blooddonor.service.CampService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Donation camps.
 * Public: GET /api/camps (upcoming), GET /api/camps/{id}/slots.
 * HOSPITAL / ADMIN: create, hosted list, bookings, cancel, check-in.
 */
@RestController
@RequestMapping("/api/camps")
public class CampController {

    private final CampService campService;

    public CampController(CampService campService) {
        this.campService = campService;
    }

    /** Upcoming active camps. Pass lat/lng to get distances. */
    @GetMapping
    public List<CampView> upcoming(@RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return campService.upcoming(lat, lng);
    }

    /** One camp with free places per slot. */
    @GetMapping("/{id}/slots")
    public CampView slots(@PathVariable Long id) {
        return campService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CampView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateCamp req) {
        CurrentUser user = CurrentUser.from(jwt);
        if (user.role() == Role.HOSPITAL) {
            if (req.hospitalId() != null && !req.hospitalId().equals(user.hospitalId())) {
                throw new ForbiddenException("A hospital can only create camps for itself");
            }
            req = req.withHospitalId(user.hospitalId());
        }
        return campService.create(req);
    }

    /** HOSPITAL: its own camps (past and future). ADMIN: all camps. */
    @GetMapping("/hosted")
    public List<CampView> hosted(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser user = CurrentUser.from(jwt);
        return campService.hosted(user.isAdmin() ? null : user.hospitalId());
    }

    @GetMapping("/{id}/bookings")
    public List<BookingRow> bookings(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return campService.bookings(id, CurrentUser.from(jwt));
    }

    @PostMapping("/{id}/cancel")
    public CampView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return campService.cancel(id, CurrentUser.from(jwt));
    }

    /** Check a donor in with the code on their pass (QR or typed). */
    @PostMapping("/check-in")
    public BookingRow checkIn(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CheckIn req) {
        return campService.checkIn(req.code(), CurrentUser.from(jwt));
    }
}
