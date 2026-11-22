package com.blooddonor.controller;

import com.blooddonor.dto.AuthDtos.*;
import com.blooddonor.security.AuthService;
import com.blooddonor.security.CurrentUser;
import com.blooddonor.security.OtpService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final OtpService otpService;

    public AuthController(AuthService authService, OtpService otpService) {
        this.authService = authService;
        this.otpService = otpService;
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

    /** Sends a 6-digit code by SMS. purpose REGISTER (verify a new number) or LOGIN (donor login without password). */
    @PostMapping("/otp/request")
    public OtpSent requestOtp(@Valid @RequestBody OtpRequest request) {
        return otpService.request(request.phone(), request.purpose());
    }

    /** Donor login with the code instead of a password. */
    @PostMapping("/otp/login")
    public TokenResponse loginWithOtp(@Valid @RequestBody OtpLogin request) {
        return authService.loginWithOtp(request.phone(), request.code());
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser user = CurrentUser.from(jwt);
        return new Me(user.username(), user.role(), user.donorId(), user.hospitalId());
    }
}
