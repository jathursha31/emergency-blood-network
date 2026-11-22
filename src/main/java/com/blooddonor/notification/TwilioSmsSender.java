package com.blooddonor.notification;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Sends SMS through Twilio's REST API (no SDK needed):
 * POST https://api.twilio.com/2010-04-01/Accounts/{AccountSid}/Messages.json
 * with form fields To, From, Body and HTTP Basic auth (AccountSid : AuthToken).
 */
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "twilio")
public class TwilioSmsSender implements SmsSender {

    private final RestClient http;
    private final String accountSid;
    private final String fromNumber;

    @Autowired
    public TwilioSmsSender(SmsProperties props) {
        this(props, RestClient.builder());
    }

    /** Tests pass a builder bound to a mock server. */
    TwilioSmsSender(SmsProperties props, RestClient.Builder builder) {
        SmsProperties.Twilio t = props.twilio();
        if (!StringUtils.hasText(t.accountSid()) || !StringUtils.hasText(t.authToken())
                || !StringUtils.hasText(t.fromNumber())) {
            throw new IllegalStateException("app.sms.provider=twilio needs app.sms.twilio.account-sid, "
                    + "auth-token and from-number (env vars TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_FROM_NUMBER)");
        }
        this.accountSid = t.accountSid();
        this.fromNumber = t.fromNumber();
        this.http = builder
                .baseUrl(t.apiBaseUrl())
                .defaultHeaders(h -> h.setBasicAuth(t.accountSid(), t.authToken()))
                .build();
    }

    @Override
    public void send(String e164Phone, String text) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", e164Phone);
        form.add("From", fromNumber);
        form.add("Body", text);

        http.post()
                .uri("/2010-04-01/Accounts/{sid}/Messages.json", accountSid)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity(); // throws on 4xx/5xx, e.g. unverified number on a trial account
    }

    @Override
    public String name() {
        return "twilio";
    }
}
