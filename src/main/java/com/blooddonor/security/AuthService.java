package com.blooddonor.security;

import com.blooddonor.dto.AuthDtos.LoginRequest;
import com.blooddonor.dto.AuthDtos.RegisterDonorAccount;
import com.blooddonor.dto.AuthDtos.TokenResponse;
import com.blooddonor.dto.DonorDtos.DonorView;
import com.blooddonor.dto.HospitalDtos.CreateHospital;
import com.blooddonor.dto.HospitalDtos.HospitalView;
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

    public AuthService(UserAccountRepository accounts,
                       DonorService donorService,
                       DonorRepository donorRepository,
                       HospitalService hospitalService,
                       HospitalRepository hospitalRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       OtpService otpService,
                       OtpProperties otpProps) {
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
    @Transactional(readOnly = true)
    public TokenResponse loginWithOtp(String phone, String code) {
        UserAccount account = accounts.findByUsername(phone.trim())
                .filter(a -> a.isEnabled() && a.getRole() == Role.DONOR)
                .orElseThrow(() -> new InvalidOtpException("Wrong code. Request a new one."));
        otpService.verify(account.getUsername(), OtpCode.Purpose.LOGIN, code);
        return tokenFor(account);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest req) {
        UserAccount account = accounts.findByUsername(req.username().trim())
                .filter(UserAccount::isEnabled)
                .filter(a -> passwordEncoder.matches(req.password(), a.getPasswordHash()))
                .orElseThrow(InvalidCredentialsException::new); // same error for unknown user and wrong password
        return tokenFor(account);
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

    /** Access token (JWT) response for an account; used by login, sign-up and refresh. */
    public TokenResponse tokenFor(UserAccount account) {
        JwtService.IssuedToken token = jwtService.issue(account);
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds(), token.expiresAt(),
                account.getUsername(), account.getRole(),
                account.getDonor() == null ? null : account.getDonor().getId(),
                account.getHospital() == null ? null : account.getHospital().getId());
    }
}
