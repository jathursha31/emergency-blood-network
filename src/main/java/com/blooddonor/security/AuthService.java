package com.blooddonor.security;

import com.blooddonor.dto.AuthDtos.LoginRequest;
import com.blooddonor.dto.AuthDtos.RegisterDonorAccount;
import com.blooddonor.dto.AuthDtos.TokenResponse;
import com.blooddonor.dto.DonorDtos.DonorView;
import com.blooddonor.dto.HospitalDtos.CreateHospital;
import com.blooddonor.dto.HospitalDtos.HospitalView;
import com.blooddonor.entity.AuditEvent;
import com.blooddonor.entity.Donor;
import com.blooddonor.entity.Hospital;
import com.blooddonor.entity.OtpCode;
import com.blooddonor.entity.Role;
import com.blooddonor.entity.UserAccount;
import com.blooddonor.exception.BusinessRuleException;
import com.blooddonor.exception.InvalidCredentialsException;
import com.blooddonor.exception.InvalidOtpException;
import com.blooddonor.repository.DonorRepository;
import com.blooddonor.repository.HospitalRepository;
import com.blooddonor.repository.UserAccountRepository;
import com.blooddonor.service.DonorService;
import com.blooddonor.service.HospitalService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@Transactional
public class AuthService {

    private final UserAccountRepository accounts;
    private final DonorService donorService;
    private final DonorRepository donorRepository;
    private final HospitalService hospitalService;
    private final HospitalRepository hospitalRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final OtpService otpService;
    private final OtpProperties otpProps;
    private final AuditService audit;
    private final Clock clock;
    private final RefreshTokenService refreshTokens;

    public AuthService(UserAccountRepository accounts,
                       DonorService donorService,
                       DonorRepository donorRepository,
                       HospitalService hospitalService,
                       HospitalRepository hospitalRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       OtpService otpService,
                       OtpProperties otpProps,
                       AuditService audit,
                       Clock clock,
                       RefreshTokenService refreshTokens) {
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.clock = clock;
        this.otpService = otpService;
        this.otpProps = otpProps;
        this.accounts = accounts;
        this.donorService = donorService;
        this.donorRepository = donorRepository;
        this.hospitalService = hospitalService;
        this.hospitalRepository = hospitalRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /** Password-less login for donors with a code sent to their registered mobile. */
    public TokenResponse loginWithOtp(String phone, String code) {
        UserAccount account = accounts.findByUsername(phone.trim())
                .filter(a -> a.isEnabled() && a.getRole() == Role.DONOR)
                .orElse(null);
        if (account == null) {
            audit.record(null, AuditEvent.Action.LOGIN_FAILED, phone.trim(), "OTP login: unknown or disabled account");
            throw new InvalidOtpException("Wrong code. Request a new one.");
        }
        try {
            otpService.verify(account.getUsername(), OtpCode.Purpose.LOGIN, code);
        } catch (InvalidOtpException e) {
            audit.record(null, AuditEvent.Action.LOGIN_FAILED, account.getUsername(), "OTP login: " + e.getMessage());
            throw e;
        }
        loggedIn(account, AuditEvent.Action.OTP_LOGIN);
        return tokenFor(account);
    }

    public TokenResponse login(LoginRequest req) {
        String username = req.username().trim();
        UserAccount account = accounts.findByUsername(username).orElse(null);
        if (account == null || !passwordEncoder.matches(req.password(), account.getPasswordHash())) {
            audit.record(null, AuditEvent.Action.LOGIN_FAILED, username, account == null ? "unknown username" : "wrong password");
            throw new InvalidCredentialsException(); // same error for unknown user and wrong password
        }
        if (!account.isEnabled()) {
            audit.record(null, AuditEvent.Action.LOGIN_FAILED, username, "account disabled");
            throw new InvalidCredentialsException();
        }
        loggedIn(account, AuditEvent.Action.LOGIN);
        return tokenFor(account);
    }

    private void loggedIn(UserAccount account, AuditEvent.Action how) {
        account.setLastLoginAt(LocalDateTime.now(clock));
        audit.record(account.getUsername(), how, account.getUsername(), null);
    }

    /** Change my own password. Ends every session (the caller then gets a fresh one). */
    public void changePassword(String username, String currentPassword, String newPassword) {
        UserAccount account = accounts.findByUsername(username).orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            audit.record(username, AuditEvent.Action.LOGIN_FAILED, username, "change password: wrong current password");
            throw new BusinessRuleException("Your current password is not correct");
        }
        if (passwordEncoder.matches(newPassword, account.getPasswordHash())) {
            throw new BusinessRuleException("The new password must be different from the current one");
        }
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        int closed = refreshTokens.revokeAllFor(account); // someone who knew the old password is logged out too
        audit.record(username, AuditEvent.Action.PASSWORD_CHANGED, username, closed + " session(s) closed");
    }

    /** Public sign-up: creates the donor profile and its login (username = phone) in one transaction. */
    public TokenResponse registerDonor(RegisterDonorAccount req) {
        String username = req.donor().phone();
        if (accounts.existsByUsername(username)) {
            throw new BusinessRuleException("An account with phone " + username + " already exists");
        }
        if (otpProps.requiredForRegister()) {
            if (req.otpCode() == null) throw new InvalidOtpException("Verify your mobile number: enter the code we sent by SMS");
            otpService.verify(username, OtpCode.Purpose.REGISTER, req.otpCode());
        }
        DonorView donor = donorService.register(req.donor());
        UserAccount account = createAccount(username, req.password(), Role.DONOR,
                donorRepository.getReferenceById(donor.id()), null);
        account.setLastLoginAt(LocalDateTime.now(clock));
        audit.record(username, AuditEvent.Action.DONOR_REGISTERED, username, donor.bloodGroupLabel() + ", " + donor.city());
        return tokenFor(account);
    }

    /** Admin adds a hospital together with its staff login. */
    public HospitalView createHospital(CreateHospital req) {
        if (accounts.existsByUsername(req.loginUsername())) {
            throw new BusinessRuleException("Username " + req.loginUsername() + " is already taken");
        }
        HospitalView hospital = hospitalService.create(req);
        createAccount(req.loginUsername(), req.loginPassword(), Role.HOSPITAL, null,
                hospitalRepository.getReferenceById(hospital.id()));
        audit.record(AuditEvent.Action.HOSPITAL_CREATED, req.loginUsername(), hospital.name());
        return hospital;
    }

    public UserAccount createAccount(String username, String rawPassword, Role role, Donor donor, Hospital hospital) {
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        account.setRole(role);
        account.setDonor(donor);
        account.setHospital(hospital);
        return accounts.save(account);
    }

    /** Access token (JWT) response for an account, without a session id. */
    public TokenResponse tokenFor(UserAccount account) {
        return tokenFor(account, null);
    }

    /** Access token tied to a login session (refresh-token family), so logging that session out kills it at once. */
    @Transactional(readOnly = true)
    public TokenResponse sessionToken(String username, String sessionId) {
        UserAccount account = accounts.findByUsername(username).orElseThrow(InvalidCredentialsException::new);
        return tokenFor(account, sessionId);
    }

    public TokenResponse tokenFor(UserAccount account, String sessionId) {
        JwtService.IssuedToken token = jwtService.issue(account, sessionId);
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds(), token.expiresAt(),
                account.getUsername(), account.getRole(),
                account.getDonor() == null ? null : account.getDonor().getId(),
                account.getHospital() == null ? null : account.getHospital().getId());
    }
}
