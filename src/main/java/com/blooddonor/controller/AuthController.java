package com.blooddonor.controller;

import com.blooddonor.dto.AuthDtos.*;
import com.blooddonor.security.AuthService;
import com.blooddonor.security.CurrentUser;
import com.blooddonor.security.OtpService;
import com.blooddonor.security.RefreshTokenService;
import com.blooddonor.security.SecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Login returns a short-lived access token (JWT, in the response body) and sets a long-lived
 * refresh token as an httpOnly cookie. JavaScript can't read the cookie, so an XSS bug can't steal it.
 * The cookie is only sent to /api/auth, and SameSite=Strict keeps other sites from using it.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String REFRESH_COOKIE = "bdn_refresh";
    private static final String COOKIE_PATH = "/api/auth";

    private final AuthService authService;
    private final OtpService otpService;
    private final RefreshTokenService refreshTokens;
    private final SecurityProperties securityProps;

    public AuthController(AuthService authService, OtpService otpService,
                          RefreshTokenService refreshTokens, SecurityProperties securityProps) {
        this.authService = authService;
        this.otpService = otpService;
        this.refreshTokens = refreshTokens;
        this.securityProps = securityProps;
    }

    /** Returns a JWT. Send it on later calls as the header: Authorization: Bearer &lt;token&gt; */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http, HttpServletResponse res) {
        return startSession(authService.login(request), http, res);
    }

    /** Public donor sign-up. Logs the donor in straight away. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterDonorAccount request,
                                  HttpServletRequest http, HttpServletResponse res) {
        return startSession(authService.registerDonor(request), http, res);
    }

    /** Sends a 6-digit code by SMS. purpose REGISTER (verify a new number) or LOGIN (donor login without password). */
    @PostMapping("/otp/request")
    public OtpSent requestOtp(@Valid @RequestBody OtpRequest request) {
        return otpService.request(request.phone(), request.purpose());
    }

    /** Donor login with the code instead of a password. */
    @PostMapping("/otp/login")
    public TokenResponse loginWithOtp(@Valid @RequestBody OtpLogin request, HttpServletRequest http, HttpServletResponse res) {
        return startSession(authService.loginWithOtp(request.phone(), request.code()), http, res);
    }

    /** New access token from the refresh cookie. The cookie itself is rotated (replaced) every time. */
    @PostMapping("/refresh")
    public TokenResponse refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                                 HttpServletRequest http, HttpServletResponse res) {
        try {
            RefreshTokenService.Rotated rotated = refreshTokens.rotate(refreshToken, http.getHeader(HttpHeaders.USER_AGENT));
            setCookie(res, rotated.refreshToken(), refreshTokens.maxAgeSeconds());
            return authService.tokenFor(rotated.account(), rotated.sessionId());
        } catch (RuntimeException e) {
            setCookie(res, "", 0); // drop a dead cookie so the browser stops sending it
            throw e;
        }
    }

    /** Log out on this device: revokes the refresh token and clears the cookie. Works without an access token. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletResponse res) {
        refreshTokens.revoke(refreshToken);
        setCookie(res, "", 0);
    }

    /** Log out everywhere: revokes every refresh token of this account (other devices stop at their next refresh). */
    @PostMapping("/logout-all")
    public Map<String, Integer> logoutAll(@AuthenticationPrincipal Jwt jwt, HttpServletResponse res) {
        int closed = refreshTokens.revokeAll(CurrentUser.from(jwt).username());
        setCookie(res, "", 0);
        return Map.of("sessionsClosed", closed);
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser user = CurrentUser.from(jwt);
        return new Me(user.username(), user.role(), user.donorId(), user.hospitalId());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The credentials were already checked (login/sign-up/OTP). Open a session: refresh cookie plus an
     * access token that carries the session id, so logging this session out also rejects that token at once.
     */
    private TokenResponse startSession(TokenResponse checked, HttpServletRequest http, HttpServletResponse res) {
        RefreshTokenService.Issued session = refreshTokens.issue(checked.username(), http.getHeader(HttpHeaders.USER_AGENT));
        setCookie(res, session.refreshToken(), refreshTokens.maxAgeSeconds());
        return authService.sessionToken(checked.username(), session.sessionId());
    }

    private void setCookie(HttpServletResponse res, String value, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(securityProps.cookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
