package com.blooddonor.controller;

import com.blooddonor.dto.HospitalDtos.CreateHospital;
import com.blooddonor.dto.HospitalDtos.HospitalView;
import com.blooddonor.security.AuthService;
import com.blooddonor.service.HospitalService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/hospitals")
public class HospitalController {

    private final HospitalService hospitalService;
    private final AuthService authService;

    public HospitalController(HospitalService hospitalService, AuthService authService) {
        this.hospitalService = hospitalService;
        this.authService = authService;
    }

    /** ADMIN only: adds the hospital and creates its staff login. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HospitalView create(@Valid @RequestBody CreateHospital request) {
        return authService.createHospital(request);
    }

    @GetMapping
    public List<HospitalView> list() {
        return hospitalService.list();
    }

    @GetMapping("/{id}")
    public HospitalView get(@PathVariable Long id) {
        return hospitalService.get(id);
    }
}
