package com.blooddonor.security;

import com.blooddonor.dto.AuthDtos.OtpSent;
import com.blooddonor.entity.OtpCode;
import com.blooddonor.entity.Role;
import com.blooddonor.exception.BusinessRuleException;
import com.blooddonor.exception.InvalidOtpException;
import com.blooddonor.exception.TooManyRequestsException;
import com.blooddonor.notification.SmsProperties;
import com.blooddonor.repository.OtpCodeRepository;
import com.blooddonor.repository.UserAccountRepository;
import com.blooddonor.service.DonorService;
import com.blooddonor.service.NotificationService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 6-digit codes sent by SMS, for verifying a phone at sign-up and for password-less donor login.
 * Codes are stored as BCrypt hashes, expire, allow a few wrong guesses, and are rate limited per phone.
 */
@Service
public class OtpService {

    private final OtpCodeRepository codes;
    private final UserAccountRepository accounts;
    private final NotificationService notifier;
    private final PasswordEncoder encoder;
    private final OtpProperties props;
    private final SmsProperties sms;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OtpService(OtpCodeRepository codes, UserAccountRepository accounts, NotificationService notifier,
                      PasswordEncoder encoder, OtpProperties props, SmsProperties sms, Clock clock) {
        this.codes = codes;
        this.accounts = accounts;
        this.notifier = notifier;
        this.encoder = encoder;
        this.props = props;
        this.sms = sms;
        this.clock = clock;
    }

    @Transactional
    public OtpSent request(String rawPhone, OtpCode.Purpose purpose) {
        String phone = clean(rawPhone);
        boolean hasDonorAccount = accounts.findByUsername(phone).filter(a -> a.getRole() == Role.DONOR).isPresent();

        if (purpose == OtpCode.Purpose.REGISTER && accounts.existsByUsername(phone)) {
            throw new BusinessRuleException("This mobile number is already registered. Log in instead.");
        }
        rateLimit(phone, purpose);

        // For LOGIN we answer the same way whether or not the number is registered,
        // so the endpoint can't be used to find out who is a donor. We just don't send anything.
        if (purpose == OtpCode.Purpose.LOGIN && !hasDonorAccount) {
            return new OtpSent(DonorService.maskPhone(phone), props.validMinutes() * 60L, null);
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        OtpCode otp = new OtpCode();
        otp.setPhone(phone);
        otp.setPurpose(purpose);
        otp.setCodeHash(encoder.encode(code));
        otp.setCreatedAt(now());
        otp.setExpiresAt(now().plusMinutes(props.validMinutes()));
        codes.save(otp);

        notifier.send(phone, code + " is your Blood Donor Network code. It expires in " + props.validMinutes()
                + " minutes. Never share it with anyone.");

        boolean devMode = props.showCodeInDev() && "log".equalsIgnoreCase(sms.provider());
        return new OtpSent(DonorService.maskPhone(phone), props.validMinutes() * 60L, devMode ? code : null);
    }

    /**
     * Checks a code and marks it used. Runs in its own transaction so a wrong guess is counted
     * even when the caller's transaction rolls back because of the exception.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = InvalidOtpException.class)
    public void verify(String rawPhone, OtpCode.Purpose purpose, String code) {
        String phone = clean(rawPhone);
        OtpCode otp = codes.findTopByPhoneAndPurposeOrderByCreatedAtDesc(phone, purpose)
                .orElseThrow(() -> new InvalidOtpException("Request a code first"));
        if (otp.isUsed()) throw new InvalidOtpException("This code was already used. Request a new one.");
        if (now().isAfter(otp.getExpiresAt())) throw new InvalidOtpException("The code has expired. Request a new one.");
        if (otp.getAttempts() >= props.maxAttempts()) {
            throw new InvalidOtpException("Too many wrong attempts. Request a new code.");
        }
        if (code == null || !encoder.matches(code.trim(), otp.getCodeHash())) {
            otp.setAttempts(otp.getAttempts() + 1);
            int left = props.maxAttempts() - otp.getAttempts();
            throw new InvalidOtpException(left > 0 ? "Wrong code. " + left + " attempt(s) left." : "Too many wrong attempts. Request a new code.");
        }
        otp.setUsed(true);
    }

    private void rateLimit(String phone, OtpCode.Purpose purpose) {
        codes.findTopByPhoneAndPurposeOrderByCreatedAtDesc(phone, purpose).ifPresent(last -> {
            long waited = Duration.between(last.getCreatedAt(), now()).toSeconds();
            if (waited < props.resendAfterSeconds()) {
                throw new TooManyRequestsException("Please wait " + (props.resendAfterSeconds() - waited)
                        + " seconds before asking for another code.");
            }
        });
        if (codes.countByPhoneAndCreatedAtAfter(phone, now().minusHours(1)) >= props.maxPerHour()) {
            throw new TooManyRequestsException("Too many codes requested for this number. Try again in an hour.");
        }
    }

    private static String clean(String phone) {
        String p = phone == null ? "" : phone.trim();
        if (!p.matches("^[6-9]\\d{9}$")) throw new IllegalArgumentException("Enter a 10 digit Indian mobile number");
        return p;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
