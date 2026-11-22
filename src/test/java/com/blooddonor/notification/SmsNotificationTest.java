package com.blooddonor.notification;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SmsNotificationTest {

    private static SmsProperties props(String provider, List<String> allowed) {
        return props(provider, "sms", allowed);
    }

    private static SmsProperties props(String provider, String channel, List<String> allowed) {
        return new SmsProperties(provider, channel, "+91", allowed,
                new SmsProperties.Twilio("AC123", "secret", "+15005550006", "https://api.twilio.com"));
    }

    /** Records what would have been sent. */
    static class FakeSender implements SmsSender {
        final List<String> sent = new ArrayList<>();
        final String name;
        FakeSender(String name) { this.name = name; }
        @Override public void send(String to, String text) { sent.add(to + " | " + text); }
        @Override public String name() { return name; }
    }

    @Test
    void normalisesIndianNumbers() {
        assertThat(SmsNotificationService.normalize("9840000001", "+91")).isEqualTo("+919840000001");
        assertThat(SmsNotificationService.normalize("04420001111", "+91")).isEqualTo("+914420001111");
        assertThat(SmsNotificationService.normalize("919840000001", "+91")).isEqualTo("+919840000001");
        assertThat(SmsNotificationService.normalize("+1 (415) 555-0100", "+91")).isEqualTo("+14155550100");
    }

    @Test
    void sendsImmediatelyOutsideATransaction() {
        FakeSender sender = new FakeSender("twilio");
        new SmsNotificationService(sender, props("twilio", List.of()), new SyncTaskExecutor())
                .send("9840000001", "Blood needed");
        assertThat(sender.sent).containsExactly("+919840000001 | Blood needed");
    }

    @Test
    void waitsForCommitInsideATransaction() {
        FakeSender sender = new FakeSender("twilio");
        var service = new SmsNotificationService(sender, props("twilio", List.of()), new SyncTaskExecutor());

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.send("9840000001", "Blood needed");
            assertThat(sender.sent).isEmpty(); // nothing yet: the transaction hasn't committed

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(sender.sent).hasSize(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void allowListBlocksOtherNumbers() {
        FakeSender sender = new FakeSender("twilio");
        var service = new SmsNotificationService(sender, props("twilio", List.of("9876543210")), new SyncTaskExecutor());

        service.send("9840000001", "to a stranger");
        service.send("9876543210", "to me");

        assertThat(sender.sent).containsExactly("+919876543210 | to me");
    }

    @Test
    void providerFailureDoesNotThrow() {
        SmsSender broken = new SmsSender() {
            @Override public void send(String to, String text) { throw new IllegalStateException("Twilio down"); }
            @Override public String name() { return "twilio"; }
        };
        new SmsNotificationService(broken, props("twilio", List.of()), new SyncTaskExecutor())
                .send("9840000001", "hello"); // logged, not thrown
    }

    @Test
    void twilioSenderPostsTheRightRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer twilio = MockRestServiceServer.bindTo(builder).build();
        twilio.expect(requestTo("https://api.twilio.com/2010-04-01/Accounts/AC123/Messages.json"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic QUMxMjM6c2VjcmV0")) // base64("AC123:secret")
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "To", "+919840000001", "From", "+15005550006", "Body", "Blood needed")))
                .andRespond(withSuccess("{\"sid\":\"SM1\"}", MediaType.APPLICATION_JSON));

        new TwilioSmsSender(props("twilio", List.of()), builder).send("+919840000001", "Blood needed");
        twilio.verify();
    }

    @Test
    void whatsappChannelPrefixesBothNumbers() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer twilio = MockRestServiceServer.bindTo(builder).build();
        twilio.expect(requestTo("https://api.twilio.com/2010-04-01/Accounts/AC123/Messages.json"))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "To", "whatsapp:+919840000001", "From", "whatsapp:+15005550006")))
                .andRespond(withSuccess("{\"sid\":\"SM1\"}", MediaType.APPLICATION_JSON));

        TwilioSmsSender sender = new TwilioSmsSender(props("twilio", "whatsapp", List.of()), builder);
        sender.send("+919840000001", "Blood needed");
        twilio.verify();
        assertThat(sender.name()).isEqualTo("twilio-whatsapp");
    }

    @Test
    void twilioErrorIsRaisedToTheCaller() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer twilio = MockRestServiceServer.bindTo(builder).build();
        twilio.expect(requestTo("https://api.twilio.com/2010-04-01/Accounts/AC123/Messages.json"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"code\":21608,\"message\":\"unverified number\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        TwilioSmsSender sender = new TwilioSmsSender(props("twilio", List.of()), builder);
        assertThatThrownBy(() -> sender.send("+919840000001", "x")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void twilioNeedsCredentials() {
        SmsProperties missing = new SmsProperties("twilio", "sms", "+91", List.of(),
                new SmsProperties.Twilio("", "", "", "https://api.twilio.com"));
        assertThatThrownBy(() -> new TwilioSmsSender(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TWILIO_ACCOUNT_SID");
    }
}
