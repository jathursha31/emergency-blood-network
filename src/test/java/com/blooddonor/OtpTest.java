package com.blooddonor;

import com.blooddonor.dto.AuthDtos.OtpSent;
import com.blooddonor.dto.AuthDtos.RegisterDonorAccount;
import com.blooddonor.dto.DonorDtos.RegisterDonor;
import com.blooddonor.entity.BloodGroup;
import com.blooddonor.entity.OtpCode;
import com.blooddonor.exception.BusinessRuleException;
import com.blooddonor.exception.InvalidOtpException;
import com.blooddonor.exception.TooManyRequestsException;
import com.blooddonor.repository.OtpCodeRepository;
import com.blooddonor.security.AuthService;
import com.blooddonor.security.OtpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Not @Transactional: OtpService.verify uses REQUIRES_NEW, so each test uses its own phone numbers. */
@SpringBootTest
class OtpTest {

    private static final AtomicInteger SEQ = new AtomicInteger(500);

    @Autowired OtpService otp;
    @Autowired AuthService auth;
    @Autowired OtpCodeRepository codes;

    private static String phone() {
        return "7" + String.format("%09d", SEQ.incrementAndGet());
    }

    private RegisterDonorAccount signup(String phone, String code) {
        return new RegisterDonorAccount(new RegisterDonor("Otp Person", phone, null, LocalDate.of(1996, 2, 2),
                BloodGroup.O_POS, 13.04, 80.23, "Chennai", null), "password123", code);
    }

    /** Makes the latest code look older, to get past the 30-second resend limit in a test. */
    private void ageLatestCode(String phone, OtpCode.Purpose purpose) {
        OtpCode c = codes.findTopByPhoneAndPurposeOrderByCreatedAtDesc(phone, purpose).orElseThrow();
        c.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        codes.save(c);
    }

    @Test
    void registerNeedsTheRightCodeAndCodeIsSingleUse() {
        String phone = phone();
        OtpSent sent = otp.request(phone, OtpCode.Purpose.REGISTER);
        assertThat(sent.devCode()).matches("\\d{6}");
        assertThat(sent.sentTo()).doesNotContain(phone.substring(2, 8));

        String wrong = sent.devCode().equals("000000") ? "111111" : "000000";
        assertThatThrownBy(() -> auth.registerDonor(signup(phone, wrong)))
                .isInstanceOf(InvalidOtpException.class).hasMessageContaining("4 attempt(s) left");
        assertThatThrownBy(() -> auth.registerDonor(signup(phone, null)))
                .isInstanceOf(InvalidOtpException.class);

        assertThat(auth.registerDonor(signup(phone, sent.devCode())).accessToken()).isNotBlank();

        // the number is now taken, and the code can't be reused
        assertThatThrownBy(() -> otp.request(phone, OtpCode.Purpose.REGISTER)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> otp.verify(phone, OtpCode.Purpose.REGISTER, sent.devCode()))
                .isInstanceOf(InvalidOtpException.class).hasMessageContaining("already used");
    }

    @Test
    void donorCanLogInWithCodeInsteadOfPassword() {
        String phone = phone();
        String reg = otp.request(phone, OtpCode.Purpose.REGISTER).devCode();
        auth.registerDonor(signup(phone, reg));

        String code = otp.request(phone, OtpCode.Purpose.LOGIN).devCode();
        var token = auth.loginWithOtp(phone, code);
        assertThat(token.donorId()).isNotNull();
        assertThat(token.username()).isEqualTo(phone);
    }

    @Test
    void loginCodeForUnknownNumberLooksTheSameButIsNotSent() {
        OtpSent sent = otp.request(phone(), OtpCode.Purpose.LOGIN);
        assertThat(sent.sentTo()).isNotBlank();
        assertThat(sent.devCode()).isNull();
    }

    @Test
    void fiveWrongGuessesBlockTheCode() {
        String phone = phone();
        String good = otp.request(phone, OtpCode.Purpose.REGISTER).devCode();
        String bad = good.equals("123456") ? "654321" : "123456";
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> otp.verify(phone, OtpCode.Purpose.REGISTER, bad)).isInstanceOf(InvalidOtpException.class);
        }
        assertThatThrownBy(() -> otp.verify(phone, OtpCode.Purpose.REGISTER, good))
                .isInstanceOf(InvalidOtpException.class).hasMessageContaining("Too many wrong attempts");
    }

    @Test
    void expiredCodeIsRejected() {
        String phone = phone();
        String code = otp.request(phone, OtpCode.Purpose.REGISTER).devCode();
        OtpCode c = codes.findTopByPhoneAndPurposeOrderByCreatedAtDesc(phone, OtpCode.Purpose.REGISTER).orElseThrow();
        c.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        codes.save(c);
        assertThatThrownBy(() -> otp.verify(phone, OtpCode.Purpose.REGISTER, code))
                .isInstanceOf(InvalidOtpException.class).hasMessageContaining("expired");
    }

    @Test
    void rateLimits() {
        String phone = phone();
        otp.request(phone, OtpCode.Purpose.REGISTER);
        assertThatThrownBy(() -> otp.request(phone, OtpCode.Purpose.REGISTER))
                .isInstanceOf(TooManyRequestsException.class).hasMessageContaining("wait");
        for (int i = 0; i < 4; i++) {
            ageLatestCode(phone, OtpCode.Purpose.REGISTER);
            otp.request(phone, OtpCode.Purpose.REGISTER);
        }
        ageLatestCode(phone, OtpCode.Purpose.REGISTER);
        assertThatThrownBy(() -> otp.request(phone, OtpCode.Purpose.REGISTER))
                .isInstanceOf(TooManyRequestsException.class).hasMessageContaining("hour");
    }

    @Test
    void rejectsNonIndianMobile() {
        assertThatThrownBy(() -> otp.request("12345", OtpCode.Purpose.REGISTER)).isInstanceOf(IllegalArgumentException.class);
    }
}
