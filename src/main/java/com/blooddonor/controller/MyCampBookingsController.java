package com.blooddonor.controller;

import com.blooddonor.dto.CampDtos.BookSlot;
import com.blooddonor.dto.CampDtos.MyBooking;
import com.blooddonor.security.CurrentUser;
import com.blooddonor.service.CampService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Donor's own camp bookings. The donor id always comes from the token. */
@RestController
@RequestMapping("/api/me/camp-bookings")
public class MyCampBookingsController {

    private final CampService campService;

    public MyCampBookingsController(CampService campService) {
        this.campService = campService;
    }

    @GetMapping
    public List<MyBooking> list(@AuthenticationPrincipal Jwt jwt) {
        return campService.myBookings(donorId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MyBooking book(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BookSlot req) {
        return campService.book(donorId(jwt), req);
    }

    @PostMapping("/{id}/cancel")
    public MyBooking cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return campService.cancelBooking(donorId(jwt), id);
    }

    private Long donorId(Jwt jwt) {
        return CurrentUser.from(jwt).donorId();
    }
}
