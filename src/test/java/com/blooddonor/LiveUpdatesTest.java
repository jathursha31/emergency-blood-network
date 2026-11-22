package com.blooddonor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Server-Sent Events end to end: the right people get the right events, after commit. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveUpdatesTest {

    @Autowired Environment env;
    private final HttpClient http = HttpClient.newHttpClient();

    /** Close the open event streams, otherwise their threads keep the test JVM alive. */
    @AfterEach
    void closeStreams() {
        http.shutdownNow();
    }

    @Test
    void hospitalAndNotifiedDonorGetEventsOtherHospitalDoesNot() throws Exception {
        String admin = token(post("/api/auth/login", "{\"username\":\"admin\",\"password\":\"admin123\"}", null));
        post("/api/hospitals", hospital("Live One", "04400100001", "live_one"), admin);
        post("/api/hospitals", hospital("Live Two", "04400100002", "live_two"), admin);
        String one = token(post("/api/auth/login", "{\"username\":\"live_one\",\"password\":\"staff-pass-1\"}", null));
        String two = token(post("/api/auth/login", "{\"username\":\"live_two\",\"password\":\"staff-pass-1\"}", null));
        String donor = token(post("/api/auth/register", """
                {"donor":{"fullName":"Live Donor","phone":"9333333333","dateOfBirth":"1994-04-04",
                 "bloodGroup":"O_NEG","latitude":13.041,"longitude":80.234,"city":"Chennai"},
                 "password":"donor-pass-1"}""", null));

        BlockingQueue<String> oneEvents = listen(one);
        BlockingQueue<String> twoEvents = listen(two);
        BlockingQueue<String> donorEvents = listen(donor);
        assertThat(next(oneEvents, "connected")).isNotNull();
        assertThat(next(twoEvents, "connected")).isNotNull();
        assertThat(next(donorEvents, "connected")).isNotNull();

        HttpResponse<String> created = post("/api/requests", """
                {"patientName":"Live Patient","bloodGroup":"A_POS","unitsNeeded":1,"urgency":"HIGH",
                 "neededBy":"2030-01-01T10:00:00"}""", one);
        assertThat(created.statusCode()).isEqualTo(201);

        assertThat(next(oneEvents, "request.created")).contains("\"message\"");
        String forDonor = next(donorEvents, "request.created");
        assertThat(forDonor).contains("requestId").doesNotContain("message");
        assertThat(next(twoEvents, "request.created", 1)).isNull();
    }

    // ------------------------------------------------------------------ helpers

    /** Opens /api/live and puts "event:...|data:..." strings into a queue as they arrive. */
    private BlockingQueue<String> listen(String bearer) {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        HttpRequest req = HttpRequest.newBuilder(uri("/api/live"))
                .header("Authorization", "Bearer " + bearer).header("Accept", "text/event-stream").GET().build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofLines()).thenAccept(res -> {
            String[] event = {null};
            res.body().forEach(line -> {
                if (line.startsWith("event:")) event[0] = line.substring(6).trim();
                else if (line.startsWith("data:") && event[0] != null) queue.add(event[0] + "|" + line.substring(5));
            });
        });
        return queue;
    }

    private String next(BlockingQueue<String> q, String type) throws InterruptedException {
        return next(q, type, 10);
    }

    private String next(BlockingQueue<String> q, String type, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            String e = q.poll(200, TimeUnit.MILLISECONDS);
            if (e != null && e.startsWith(type + "|")) return e;
        }
        return null;
    }

    private String hospital(String name, String phone, String user) {
        return """
                {"name":"%s","phone":"%s","latitude":13.0418,"longitude":80.2341,
                 "loginUsername":"%s","loginPassword":"staff-pass-1"}""".formatted(name, phone, user);
    }

    private HttpResponse<String> post(String path, String json, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isBetween(200, 201);
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(r.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
    }

}
