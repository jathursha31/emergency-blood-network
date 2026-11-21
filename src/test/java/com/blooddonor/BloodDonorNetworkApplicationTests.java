package com.blooddonor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the real web server and walks through the secured flow over HTTP:
 * admin adds hospitals, donor signs up, hospital raises a request, donor accepts, hospital confirms.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BloodDonorNetworkApplicationTests {

    @Autowired
    Environment env;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void publicEndpointsNeedNoToken() throws Exception {
        HttpResponse<String> groups = call("GET", "/api/blood-groups/A_NEG", null, null);
        assertThat(groups.statusCode()).isEqualTo(200);
        assertThat(groups.body()).contains("\"canReceiveFrom\":[\"O-\",\"A-\"]");
        assertThat(call("GET", "/v3/api-docs", null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void securedEndpointsRejectMissingOrBadTokens() throws Exception {
        assertThat(call("GET", "/api/requests", null, null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/requests", null, "not.a.real.token").statusCode()).isEqualTo(401);
        assertThat(login("admin", "wrong-password").statusCode()).isEqualTo(401);
        assertThat(login("nobody", "whatever1").statusCode()).isEqualTo(401);
    }

    @Test
    void fullSecuredFlow() throws Exception {
        String admin = token(login("admin", "admin123"));

        // admin adds two hospitals, each with a staff login
        assertThat(call("POST", "/api/hospitals", hospitalJson("Alpha Hospital", "04400000001", "alpha_staff"), admin)
                .statusCode()).isEqualTo(201);
        assertThat(call("POST", "/api/hospitals", hospitalJson("Beta Hospital", "04400000002", "beta_staff"), admin)
                .statusCode()).isEqualTo(201);

        // donor signs up publicly and is logged in at once
        HttpResponse<String> signup = call("POST", "/api/auth/register", """
                {"donor":{"fullName":"Web Donor","phone":"9111111111","dateOfBirth":"1995-05-05",
                 "bloodGroup":"B_POS","latitude":13.0405,"longitude":80.2335,"city":"Chennai"},
                 "password":"donor-pass-1"}""", null);
        assertThat(signup.statusCode()).isEqualTo(201);
        String donor = token(signup);
        long donorId = number(signup.body(), "donorId");

        // a donor cannot add hospitals or raise requests
        assertThat(call("POST", "/api/hospitals", hospitalJson("X", "04400000009", "x_staff"), donor)
                .statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/requests", requestJson(), donor).statusCode()).isEqualTo(403);

        // hospital raises a request (its own hospitalId comes from the token)
        String alpha = token(login("alpha_staff", "staff-pass-1"));
        HttpResponse<String> created = call("POST", "/api/requests", requestJson(), alpha);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"hospitalName\":\"Alpha Hospital\"", "\"donorId\":" + donorId);
        long requestId = number(created.body(), "id");

        // another hospital can't see or touch it
        String beta = token(login("beta_staff", "staff-pass-1"));
        assertThat(call("GET", "/api/requests/" + requestId, null, beta).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/requests/" + requestId + "/cancel", null, beta).statusCode()).isEqualTo(403);

        // donor sees it in their inbox, without the hospital phone yet
        HttpResponse<String> inbox = call("GET", "/api/me/requests", null, donor);
        assertThat(inbox.body()).contains("\"requestId\":" + requestId, "\"hospitalPhone\":null");

        // donor accepts -> hospital phone revealed to the donor
        HttpResponse<String> accepted = call("POST", "/api/me/requests/" + requestId + "/accept", null, donor);
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.body()).contains("\"myStatus\":\"ACCEPTED\"", "\"hospitalPhone\":\"04400000001\"");

        // hospital tokens can't use donor self-service
        assertThat(call("GET", "/api/me/requests", null, alpha).statusCode()).isEqualTo(403);

        // owning hospital confirms the donation
        HttpResponse<String> donated = call("POST",
                "/api/requests/" + requestId + "/donors/" + donorId + "/donated", null, alpha);
        assertThat(donated.statusCode()).isEqualTo(200);
        assertThat(donated.body()).contains("\"status\":\"FULFILLED\"");

        assertThat(call("GET", "/api/me/eligibility", null, donor).body()).contains("\"eligible\":false");

        // hospital list shows only its own requests; admin sees all
        assertThat(call("GET", "/api/requests", null, beta).body()).isEqualTo("[]");
        assertThat(call("GET", "/api/requests", null, admin).body()).contains("Alpha Hospital");
    }

    // ------------------------------------------------------------------ helpers

    private String hospitalJson(String name, String phone, String username) {
        return """
                {"name":"%s","phone":"%s","latitude":13.04,"longitude":80.23,
                 "loginUsername":"%s","loginPassword":"staff-pass-1"}""".formatted(name, phone, username);
    }

    private String requestJson() {
        return """
                {"patientName":"Test Patient","bloodGroup":"B_POS","unitsNeeded":1,
                 "urgency":"HIGH","neededBy":"2030-01-01T10:00:00"}""";
    }

    private HttpResponse<String> login(String username, String password) throws Exception {
        return call("POST", "/api/auth/login",
                "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password), null);
    }

    private String token(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 201);
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(response.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private long number(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":(\\d+)").matcher(json);
        assertThat(m.find()).as(field + " in " + json).isTrue();
        return Long.parseLong(m.group(1));
    }

    private HttpResponse<String> call(String method, String path, String json, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                URI.create("http://localhost:" + env.getProperty("local.server.port") + path));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (json != null) b.header("Content-Type", "application/json");
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
