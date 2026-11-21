package com.blooddonor.controller;

import com.blooddonor.dto.AuthDtos.*;
import com.blooddonor.security.AuthService;
import com.blooddonor.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Returns a JWT. Send it on later calls as the header: Authorization: Bearer &lt;token&gt; */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** Public donor sign-up. Logs the donor in straight away. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterDonorAccount request) {
        return authService.registerDonor(request);
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser user = CurrentUser.from(jwt);
        return new Me(user.username(), user.role(), user.donorId(), user.hospitalId());
    }
}
