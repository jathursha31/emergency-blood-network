package com.blooddonor;

import com.blooddonor.exception.TooManyRequestsException;
import com.blooddonor.repository.UserAccountRepository;
import com.blooddonor.security.AuditService;
import com.blooddonor.security.LoginGuard;
import com.blooddonor.security.SecurityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Brute-force protection: account lock over HTTP, IP block on the service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginGuardTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired Environment env;
    @Autowired UserAccountRepository accounts;
    @Autowired AuditService audit;
    @Autowired Clock clock;

    private final HttpClient http = HttpClient.newHttpClient();
    private String admin;
    private String user;

    @BeforeEach
    void setUp() throws Exception {
        admin = token(login("admin", "admin123"));
        int n = SEQ.incrementAndGet();
        user = "guard_test_staff_" + n;
        HttpResponse<String> h = call("POST", "/api/hospitals", """
                {"name":"Guard Test Hospital %d","phone":"0440088%04d","latitude":13.04,"longitude":80.23,
                 "loginUsername":"%s","loginPassword":"right-pass-1"}""".formatted(n, n, user), admin);
        assertThat(h.statusCode()).as(h.body()).isEqualTo(201);
    }

    @AfterEach
    void close() {
        http.shutdownNow();
    }

    @Test
    void fiveWrongPasswordsLockTheAccountEvenForTheRightPassword() throws Exception {
        for (int i = 0; i < 5; i++) assertThat(login(user, "wrong-" + i).statusCode()).isEqualTo(401);

        HttpResponse<String> locked = login(user, "right-pass-1");
        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.body()).contains("Too many failed attempts", "Try again in 15 minutes");
        assertThat(Long.parseLong(locked.headers().firstValue("Retry-After").orElseThrow())).isBetween(800L, 900L);

        assertThat(call("GET", "/api/admin/audit?user=" + user + "&action=ACCOUNT_LOCKED", null, admin).body())
                .contains("\"total\":1", "5 failed logins");
        assertThat(call("GET", "/api/admin/users?q=" + user, null, admin).body()).doesNotContain("\"lockedUntil\":null");
    }

    @Test
    void aSuccessfulLoginResetsTheCounter() throws Exception {
        for (int i = 0; i < 4; i++) login(user, "wrong-" + i);
        assertThat(login(user, "right-pass-1").statusCode()).isEqualTo(200);
        for (int i = 0; i < 4; i++) login(user, "wrong-again-" + i);
        assertThat(login(user, "right-pass-1").statusCode()).isEqualTo(200);
    }

    @Test
    void unknownUsernamesAreLockedTheSameWay() throws Exception {
        String ghost = "nobody_" + System.nanoTime();
        for (int i = 0; i < 5; i++) assertThat(login(ghost, "x-" + i).statusCode()).isEqualTo(401);
        HttpResponse<String> r = login(ghost, "anything");
        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.body()).contains("Too many failed attempts for this account"); // same as a real account
    }

    @Test
    void lockExpiresByItself() throws Exception {
        for (int i = 0; i < 5; i++) login(user, "wrong-" + i);
        assertThat(login(user, "right-pass-1").statusCode()).isEqualTo(429);

        var account = accounts.findByUsername(user).orElseThrow();
        account.setLockedUntil(LocalDateTime.now().minusSeconds(1));
        accounts.save(account);
        assertThat(login(user, "right-pass-1").statusCode()).isEqualTo(200);
    }

    @Test
    void adminCanUnlockEarly() throws Exception {
        for (int i = 0; i < 5; i++) login(user, "wrong-" + i);
        long id = number(call("GET", "/api/admin/users?q=" + user, null, admin).body(), "id");

        HttpResponse<String> unlock = call("POST", "/api/admin/users/" + id + "/unlock", null, admin);
        assertThat(unlock.statusCode()).isEqualTo(200);
        assertThat(unlock.body()).contains("\"lockedUntil\":null");
        assertThat(login(user, "right-pass-1").statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/admin/users/" + id + "/unlock", null, admin).statusCode()).isEqualTo(409);
    }

    @Test
    void oneIpTryingManyUsernamesGetsBlocked() {
        // a guard with a small IP limit; tests share 127.0.0.1, so use made-up addresses
        SecurityProperties small = new SecurityProperties("x".repeat(40), 15, 14, false, 5, 15, 3, "admin", "admin123");
        LoginGuard guard = new LoginGuard(accounts, small, audit, clock);
        String ip = "10.9.8." + SEQ.incrementAndGet();

        guard.recordFailure("alpha_" + ip, ip);
        guard.recordFailure("beta_" + ip, ip);
        guard.checkAllowed("gamma_" + ip, ip); // still fine after 2
        guard.recordFailure("gamma_" + ip, ip);

        assertThatThrownBy(() -> guard.checkAllowed("delta_" + ip, ip))
                .isInstanceOf(TooManyRequestsException.class).hasMessageContaining("from your network");
        guard.checkAllowed("delta_" + ip, "10.255.255.254"); // other addresses unaffected
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> login(String u, String p) throws Exception {
        return call("POST", "/api/auth/login", "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(u, p), null);
    }

    private String token(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(r.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static long number(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":(\\d+)").matcher(json);
        assertThat(m.find()).isTrue();
        return Long.parseLong(m.group(1));
    }

    private HttpResponse<String> call(String method, String path, String json, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + env.getProperty("local.server.port") + path));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (json != null) b.header("Content-Type", "application/json");
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
