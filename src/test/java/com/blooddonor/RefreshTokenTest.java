package com.blooddonor;

import com.blooddonor.repository.RefreshTokenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Refresh-token cookie over real HTTP: rotation, reuse detection, logout, logout everywhere. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefreshTokenTest {

    @Autowired Environment env;
    @Autowired RefreshTokenRepository tokens;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void close() {
        http.shutdownNow();
    }

    @Test
    void loginSetsASafeHttpOnlyCookie() throws Exception {
        HttpResponse<String> login = login();
        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(setCookie).startsWith("bdn_refresh=").contains("HttpOnly", "SameSite=Strict", "Path=/api/auth");
        assertThat(login.body()).doesNotContain(cookieValue(login)); // never in the JSON body
    }

    @Test
    void refreshRotatesTheCookieAndReuseKillsTheWholeLogin() throws Exception {
        String first = cookieValue(login());

        HttpResponse<String> r1 = refresh(first);
        assertThat(r1.statusCode()).isEqualTo(200);
        assertThat(r1.body()).contains("\"accessToken\"", "\"role\":\"ADMIN\"");
        String second = cookieValue(r1);
        assertThat(second).isNotEqualTo(first);

        // an attacker replays the old cookie -> rejected, and the legitimate new one dies too
        HttpResponse<String> replay = refresh(first);
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(replay.body()).contains("used somewhere else");
        assertThat(refresh(second).statusCode()).isEqualTo(401);
    }

    @Test
    void logoutRevokesTheCookie() throws Exception {
        String cookie = cookieValue(login());
        HttpResponse<String> out = post("/api/auth/logout", null, null, cookie);
        assertThat(out.statusCode()).isEqualTo(204);
        assertThat(out.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=0");
        assertThat(refresh(cookie).statusCode()).isEqualTo(401);
    }

    @Test
    void logoutEverywhereEndsEverySession() throws Exception {
        HttpResponse<String> laptop = login();
        HttpResponse<String> phone = login();

        HttpResponse<String> all = post("/api/auth/logout-all", null, accessToken(laptop), cookieValue(laptop));
        assertThat(all.statusCode()).isEqualTo(200);
        assertThat(all.body()).matches(".*\"sessionsClosed\":([2-9]|\\d{2,}).*");

        assertThat(refresh(cookieValue(laptop)).statusCode()).isEqualTo(401);
        assertThat(refresh(cookieValue(phone)).statusCode()).isEqualTo(401);
        assertThat(post("/api/auth/logout-all", null, null, null).statusCode()).isEqualTo(401); // needs a JWT
    }

    @Test
    void missingOrExpiredCookieIsRejected() throws Exception {
        assertThat(post("/api/auth/refresh", null, null, null).statusCode()).isEqualTo(401);
        assertThat(refresh("not-a-real-token").statusCode()).isEqualTo(401);

        String cookie = cookieValue(login());
        tokens.findAll().stream()
                .filter(t -> t.getRevokedAt() == null && t.getExpiresAt().isAfter(LocalDateTime.now()))
                .forEach(t -> { t.setExpiresAt(LocalDateTime.now().minusMinutes(1)); tokens.save(t); });
        HttpResponse<String> expired = refresh(cookie);
        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(expired.body()).contains("expired");
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> login() throws Exception {
        HttpResponse<String> r = post("/api/auth/login", "{\"username\":\"admin\",\"password\":\"admin123\"}", null, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return r;
    }

    private HttpResponse<String> refresh(String cookie) throws Exception {
        return post("/api/auth/refresh", null, null, cookie);
    }

    private static String cookieValue(HttpResponse<String> r) {
        String header = r.headers().firstValue("Set-Cookie").orElseThrow();
        Matcher m = Pattern.compile("bdn_refresh=([^;]*)").matcher(header);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static String accessToken(HttpResponse<String> r) {
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(r.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> post(String path, String json, String bearer, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://localhost:" + env.getProperty("local.server.port") + path));
        if (json != null) b.header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (cookie != null) b.header("Cookie", "bdn_refresh=" + cookie);
        b.POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
