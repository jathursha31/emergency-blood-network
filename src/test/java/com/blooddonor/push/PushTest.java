package com.blooddonor.push;

import com.blooddonor.repository.PushSubscriptionRepository;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Web Push end to end against a fake push service on localhost: subscribe, a hospital creates a request,
 * the donor's "browser" receives a VAPID-signed, encrypted message it can decrypt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.push.allow-http-endpoints=true")
class PushTest {

    private static final AtomicLong PHONE = new AtomicLong(9444400000L + (System.nanoTime() % 10000));

    @Autowired Environment env;
    @Autowired VapidKeys vapid;
    @Autowired PushSubscriptionRepository subscriptions;

    private final HttpClient http = HttpClient.newHttpClient();
    private HttpServer pushService;
    private final BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
    private final AtomicInteger replyStatus = new AtomicInteger(201);

    @BeforeEach
    void startFakePushService() throws Exception {
        pushService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pushService.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            received.add(Map.of("path", ex.getRequestURI().getPath(), "body", body,
                    "auth", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")),
                    "encoding", String.valueOf(ex.getRequestHeaders().getFirst("Content-Encoding")),
                    "urgency", String.valueOf(ex.getRequestHeaders().getFirst("Urgency")),
                    "ttl", String.valueOf(ex.getRequestHeaders().getFirst("TTL"))));
            ex.sendResponseHeaders(replyStatus.get(), -1);
            ex.close();
        });
        pushService.start();
    }

    @AfterEach
    void stop() {
        pushService.stop(0);
        http.shutdownNow();
    }

    @Test
    void donorGetsAnEncryptedSignedNotificationForANewRequest() throws Exception {
        String hospital = hospital("push_hosp_1");
        Browser browser = new Browser("/sub/donor1");
        String donor = donor("O_POS");
        assertThat(post("/api/me/push-subscriptions", browser.subscriptionJson(), donor).statusCode()).isEqualTo(204);

        assertThat(post("/api/requests", """
                {"patientName":"Push Patient","bloodGroup":"O_POS","unitsNeeded":2,"urgency":"CRITICAL",
                 "neededBy":"2030-01-01T10:00:00"}""", hospital).statusCode()).isEqualTo(201);

        Map<String, Object> msg = nextFor(browser.path);
        assertThat(msg).as("push message delivered").isNotNull();
        assertThat(msg.get("encoding")).isEqualTo("aes128gcm");
        assertThat(msg.get("urgency")).isEqualTo("high");
        assertThat(msg.get("ttl")).isEqualTo("43200");

        // VAPID: "vapid t=<jwt>, k=<our public key>", JWT signed with that key, audience = push service origin
        Matcher m = Pattern.compile("vapid t=([^,]+), k=(\\S+)").matcher((String) msg.get("auth"));
        assertThat(m.matches()).isTrue();
        assertThat(m.group(2)).isEqualTo(vapid.publicKey());
        SignedJWT jwt = SignedJWT.parse(m.group(1));
        assertThat(jwt.verify(new ECDSAVerifier(WebPushCrypto.decodePublic(WebPushCrypto.b64(m.group(2)))))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("http://127.0.0.1:" + pushService.getAddress().getPort());
        assertThat(jwt.getJWTClaimsSet().getSubject()).startsWith("mailto:");

        String json = browser.decrypt((byte[]) msg.get("body"));
        assertThat(json).contains("\"title\":\"🚨 O+ blood needed nearby\"")
                .contains("needs 2 unit(s) (CRITICAL)")
                .contains("\"url\":\"/donor\"")
                .contains("\"urgent\":true");
    }

    @Test
    void goneSubscriptionsAreDeletedAndTestEndpointReportsDelivery() throws Exception {
        Browser browser = new Browser("/sub/gone");
        String donor = donor("B_NEG");
        post("/api/me/push-subscriptions", browser.subscriptionJson(), donor);
        assertThat(get("/api/me/push-subscriptions", donor).body()).isEqualTo("{\"devices\":1}");

        HttpResponse<String> test = post("/api/me/push-subscriptions/test", "{}", donor);
        assertThat(test.body()).isEqualTo("{\"delivered\":1}");
        assertThat(browser.decrypt((byte[]) nextFor(browser.path).get("body"))).contains("Alerts are on");

        replyStatus.set(410);   // browser unsubscribed at the push service
        assertThat(post("/api/me/push-subscriptions/test", "{}", donor).body()).isEqualTo("{\"delivered\":0}");
        assertThat(subscriptions.findByEndpoint(browser.endpoint())).isEmpty();
        assertThat(post("/api/me/push-subscriptions/test", "{}", donor).statusCode()).isEqualTo(409);
    }

    @Test
    void sameBrowserMovesToTheNewUserAndOnlyOwnerCanUnsubscribe() throws Exception {
        Browser browser = new Browser("/sub/shared");
        String first = donor("A_POS");
        String second = donor("A_POS");
        post("/api/me/push-subscriptions", browser.subscriptionJson(), first);
        post("/api/me/push-subscriptions", browser.subscriptionJson(), second);
        assertThat(get("/api/me/push-subscriptions", first).body()).isEqualTo("{\"devices\":0}");
        assertThat(get("/api/me/push-subscriptions", second).body()).isEqualTo("{\"devices\":1}");

        String unsub = "{\"endpoint\":\"" + browser.endpoint() + "\"}";
        assertThat(send("DELETE", "/api/me/push-subscriptions", unsub, first).statusCode()).isEqualTo(204);
        assertThat(get("/api/me/push-subscriptions", second).body()).isEqualTo("{\"devices\":1}");
        send("DELETE", "/api/me/push-subscriptions", unsub, second);
        assertThat(get("/api/me/push-subscriptions", second).body()).isEqualTo("{\"devices\":0}");
    }

    @Test
    void rejectsBadSubscriptionsAndServesThePublicKey() throws Exception {
        String donor = donor("AB_POS");
        Browser browser = new Browser("/sub/x");
        String good = browser.subscriptionJson();
        assertThat(post("/api/me/push-subscriptions", good.replace(browser.p256dh, "AAAA"), donor).statusCode()).isEqualTo(409);
        assertThat(post("/api/me/push-subscriptions", "{\"endpoint\":\"" + browser.endpoint() + "\"}", donor).statusCode()).isEqualTo(409);
        assertThat(post("/api/me/push-subscriptions", good.replace("http://", "ftp://"), donor).statusCode()).isEqualTo(409);
        assertThat(post("/api/me/push-subscriptions", good, null).statusCode()).isEqualTo(401);

        HttpResponse<String> key = get("/api/push/vapid-public-key", null);
        assertThat(key.statusCode()).isEqualTo(200);
        assertThat(key.body()).isEqualTo("{\"publicKey\":\"" + vapid.publicKey() + "\"}");
        assertThat(WebPushCrypto.b64(vapid.publicKey())).hasSize(65);
    }

    // ------------------------------------------------------------------ fake browser

    /** Holds a browser's push keys and decrypts what the push service delivers to it (RFC 8291 receiving side). */
    private class Browser {
        final String path;
        final KeyPair keys = WebPushCrypto.generateKeyPair();
        final byte[] auth = new byte[16];
        final String p256dh;

        Browser(String path) {
            this.path = path + "-" + System.nanoTime();
            new SecureRandom().nextBytes(auth);
            p256dh = WebPushCrypto.b64(WebPushCrypto.encodePublic((ECPublicKey) keys.getPublic()));
        }

        String endpoint() {
            return "http://127.0.0.1:" + pushService.getAddress().getPort() + path;
        }

        String subscriptionJson() {
            return """
                    {"endpoint":"%s","keys":{"p256dh":"%s","auth":"%s"},"userAgent":"JUnit"}"""
                    .formatted(endpoint(), p256dh, WebPushCrypto.b64(auth));
        }

        String decrypt(byte[] body) throws Exception {
            ByteBuffer in = ByteBuffer.wrap(body);
            byte[] salt = new byte[16];
            in.get(salt);
            in.getInt();
            byte[] asPublic = new byte[in.get()];
            in.get(asPublic);
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(keys.getPrivate());
            ka.doPhase(WebPushCrypto.decodePublic(asPublic), true);
            byte[] uaPublic = WebPushCrypto.b64(p256dh);
            byte[] ikm = hmac(hmac(auth, ka.generateSecret()),
                    concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic, new byte[]{1}));
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, "Content-Encoding: aes128gcm\0\1".getBytes(StandardCharsets.US_ASCII)), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, "Content-Encoding: nonce\0\1".getBytes(StandardCharsets.US_ASCII)), 12);
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] plain = aes.doFinal(Arrays.copyOfRange(body, in.position(), body.length));
            return new String(plain, 0, plain.length - 1, StandardCharsets.UTF_8);
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ HTTP helpers

    private Map<String, Object> nextFor(String path) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            Map<String, Object> m = received.poll(200, TimeUnit.MILLISECONDS);
            if (m != null && path.equals(m.get("path"))) return m;
        }
        return null;
    }

    private String hospital(String user) throws Exception {
        String admin = token(post("/api/auth/login", "{\"username\":\"admin\",\"password\":\"admin123\"}", null));
        post("/api/hospitals", """
                {"name":"Push Hospital","phone":"04400200%03d","latitude":13.0418,"longitude":80.2341,
                 "loginUsername":"%s","loginPassword":"staff-pass-1"}""".formatted(PHONE.get() % 1000, user), admin);
        return token(post("/api/auth/login", "{\"username\":\"" + user + "\",\"password\":\"staff-pass-1\"}", null));
    }

    private String donor(String group) throws Exception {
        String phone = String.valueOf(PHONE.incrementAndGet());
        Matcher otp = Pattern.compile("\"devCode\":\"(\\d{6})\"").matcher(
                post("/api/auth/otp/request", "{\"phone\":\"" + phone + "\",\"purpose\":\"REGISTER\"}", null).body());
        assertThat(otp.find()).isTrue();
        return token(post("/api/auth/register", """
                {"donor":{"fullName":"Push Donor","phone":"%s","dateOfBirth":"1994-04-04",
                 "bloodGroup":"%s","latitude":13.041,"longitude":80.234,"city":"Chennai"},
                 "password":"donor-pass-1","otpCode":"%s"}""".formatted(phone, group, otp.group(1)), null));
    }

    private HttpResponse<String> post(String path, String json, String bearer) throws Exception {
        return send("POST", path, json, bearer);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20)).GET();
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String json, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
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
