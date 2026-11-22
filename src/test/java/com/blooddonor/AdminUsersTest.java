package com.blooddonor;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Admin user management and the audit log, over real HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminUsersTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired Environment env;
    private final HttpClient http = HttpClient.newHttpClient();

    private String admin;
    private String staffUser;
    private long staffId;

    @BeforeEach
    void setUp() throws Exception {
        admin = token(call("POST", "/api/auth/login", "{\"username\":\"admin\",\"password\":\"admin123\"}", null));
        int n = SEQ.incrementAndGet();
        staffUser = "users_test_staff_" + n;
        HttpResponse<String> h = call("POST", "/api/hospitals", """
                {"name":"Users Test Hospital %d","phone":"0440077%04d","latitude":13.04,"longitude":80.23,
                 "loginUsername":"%s","loginPassword":"staff-pass-1"}""".formatted(n, n, staffUser), admin);
        assertThat(h.statusCode()).as(h.body()).isEqualTo(201);
        HttpResponse<String> found = call("GET", "/api/admin/users?q=" + staffUser, null, admin);
        assertThat(found.body()).contains("\"username\":\"" + staffUser + "\"", "\"role\":\"HOSPITAL\"");
        staffId = number(found.body(), "id");
    }

    @AfterEach
    void close() {
        http.shutdownNow();
    }

    @Test
    void onlyAdminsCanManageUsers() throws Exception {
        String staff = staffLogin("staff-pass-1");
        assertThat(call("GET", "/api/admin/users", null, staff).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/admin/audit", null, staff).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/admin/users", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void searchShowsLoginAndSessions() throws Exception {
        staffLogin("staff-pass-1");
        String body = call("GET", "/api/admin/users?q=" + staffUser + "&role=HOSPITAL", null, admin).body();
        assertThat(body).contains("\"displayName\":\"Users Test Hospital", "\"enabled\":true", "\"activeSessions\":1",
                "\"total\":1").doesNotContain("\"lastLoginAt\":null");
    }

    @Test
    void disablingLogsTheUserOutAtOnceAndBlocksLogin() throws Exception {
        String staff = staffLogin("staff-pass-1");
        assertThat(call("GET", "/api/auth/me", null, staff).statusCode()).isEqualTo(200);

        HttpResponse<String> off = call("POST", "/api/admin/users/" + staffId + "/disable", "{\"reason\":\"left the job\"}", admin);
        assertThat(off.statusCode()).isEqualTo(200);
        assertThat(off.body()).contains("\"enabled\":false", "\"activeSessions\":0");

        assertThat(call("GET", "/api/auth/me", null, staff).statusCode()).isEqualTo(401); // instantly
        assertThat(login(staffUser, "staff-pass-1").statusCode()).isEqualTo(401);

        String audit = call("GET", "/api/admin/audit?user=" + staffUser, null, admin).body();
        assertThat(audit).contains("\"action\":\"ACCOUNT_DISABLED\"", "left the job", "\"actor\":\"admin\"",
                "\"action\":\"LOGIN_FAILED\"", "account disabled");

        assertThat(call("POST", "/api/admin/users/" + staffId + "/enable", null, admin).statusCode()).isEqualTo(200);
        assertThat(login(staffUser, "staff-pass-1").statusCode()).isEqualTo(200);
    }

    @Test
    void adminCannotDisableThemselves() throws Exception {
        long adminId = number(call("GET", "/api/admin/users?q=admin&role=ADMIN", null, admin).body(), "id");
        HttpResponse<String> r = call("POST", "/api/admin/users/" + adminId + "/disable", null, admin);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(r.body()).contains("your own account");
    }

    @Test
    void passwordResetGivesATemporaryPasswordAndEndsSessions() throws Exception {
        String staff = staffLogin("staff-pass-1");
        HttpResponse<String> reset = call("POST", "/api/admin/users/" + staffId + "/reset-password", null, admin);
        assertThat(reset.statusCode()).isEqualTo(200);
        Matcher m = Pattern.compile("\"temporaryPassword\":\"([^\"]+)\"").matcher(reset.body());
        assertThat(m.find()).isTrue();
        String temp = m.group(1);
        assertThat(temp).hasSize(12);

        assertThat(call("GET", "/api/auth/me", null, staff).statusCode()).isEqualTo(401);
        assertThat(login(staffUser, "staff-pass-1").statusCode()).isEqualTo(401);
        assertThat(login(staffUser, temp).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/admin/audit?user=" + staffUser + "&action=PASSWORD_RESET", null, admin).body())
                .contains("\"total\":1");
    }

    @Test
    void userCanChangeTheirOwnPassword() throws Exception {
        String staff = staffLogin("staff-pass-1");
        HttpResponse<String> wrong = call("POST", "/api/auth/change-password",
                "{\"currentPassword\":\"nope-nope\",\"newPassword\":\"brand-new-1\"}", staff);
        assertThat(wrong.statusCode()).isEqualTo(409);

        HttpResponse<String> ok = call("POST", "/api/auth/change-password",
                "{\"currentPassword\":\"staff-pass-1\",\"newPassword\":\"brand-new-1\"}", staff);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("Set-Cookie").orElse("")).startsWith("bdn_refresh=");

        assertThat(call("GET", "/api/auth/me", null, staff).statusCode()).isEqualTo(401);    // old token dead
        assertThat(call("GET", "/api/auth/me", null, token(ok)).statusCode()).isEqualTo(200); // still logged in here
        assertThat(login(staffUser, "staff-pass-1").statusCode()).isEqualTo(401);
        assertThat(login(staffUser, "brand-new-1").statusCode()).isEqualTo(200);
    }

    @Test
    void failedLoginsAreAuditedWithTheirIp() throws Exception {
        login(staffUser, "wrong-password");
        String audit = call("GET", "/api/admin/audit?user=" + staffUser + "&action=LOGIN_FAILED", null, admin).body();
        assertThat(audit).contains("wrong password", "\"ip\":\"127.0.0.1\"");
    }

    // ------------------------------------------------------------------ helpers

    private String staffLogin(String password) throws Exception {
        return token(login(staffUser, password));
    }

    private HttpResponse<String> login(String user, String password) throws Exception {
        return call("POST", "/api/auth/login", "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, password), null);
    }

    private String token(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isBetween(200, 201);
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(r.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static long number(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":(\\d+)").matcher(json);
        assertThat(m.find()).as(field + " in " + json).isTrue();
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
