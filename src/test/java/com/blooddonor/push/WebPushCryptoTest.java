package com.blooddonor.push;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

import static com.blooddonor.push.WebPushCrypto.b64;
import static org.assertj.core.api.Assertions.assertThat;

class WebPushCryptoTest {

    // RFC 8291 appendix A: "Push Message Encryption Example"
    static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    static final String EXPECTED_BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    @Test
    void matchesTheRfc8291Example() throws Exception {
        KeyPair as = WebPushCrypto.keyPair(b64(AS_PUBLIC), b64(AS_PRIVATE));
        byte[] body = WebPushCrypto.encrypt(PLAINTEXT.getBytes(StandardCharsets.UTF_8), b64(UA_PUBLIC), b64(AUTH), as, b64(SALT));
        assertThat(b64(body)).isEqualTo(EXPECTED_BODY);
    }

    @Test
    void aBrowserCanDecryptWhatWeSend() throws Exception {
        KeyPair browser = WebPushCrypto.generateKeyPair();
        byte[] uaPublic = WebPushCrypto.encodePublic((ECPublicKey) browser.getPublic());
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        String message = "{\"title\":\"🚨 B+ blood needed\",\"body\":\"City Care Hospital, 4.2 km\"}";

        byte[] body = WebPushCrypto.encrypt(message.getBytes(StandardCharsets.UTF_8), uaPublic, auth);
        assertThat(new String(decryptAsBrowser(body, browser, uaPublic, auth), StandardCharsets.UTF_8)).isEqualTo(message);
    }

    @Test
    void keyEncodingRoundTrips() throws Exception {
        KeyPair kp = WebPushCrypto.generateKeyPair();
        byte[] pub = WebPushCrypto.encodePublic((ECPublicKey) kp.getPublic());
        byte[] priv = WebPushCrypto.encodePrivate((java.security.interfaces.ECPrivateKey) kp.getPrivate());
        assertThat(pub).hasSize(65);
        assertThat(priv).hasSize(32);
        KeyPair again = WebPushCrypto.keyPair(pub, priv);
        assertThat(again.getPublic().getEncoded()).isEqualTo(kp.getPublic().getEncoded());
        assertThat(again.getPrivate().getEncoded()).isEqualTo(kp.getPrivate().getEncoded());
    }

    /** The receiving side of RFC 8291, written independently of WebPushCrypto. */
    private static byte[] decryptAsBrowser(byte[] body, KeyPair browser, byte[] uaPublic, byte[] auth) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        in.getInt(); // record size
        byte[] asPublic = new byte[in.get()];
        in.get(asPublic);
        byte[] ciphertext = Arrays.copyOfRange(body, in.position(), body.length);

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(browser.getPrivate());
        ka.doPhase(WebPushCrypto.decodePublic(asPublic), true);
        byte[] ecdh = ka.generateSecret();

        byte[] prkKey = hmac(auth, ecdh);
        byte[] info = join("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic, new byte[]{1});
        byte[] ikm = hmac(prkKey, info);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, "Content-Encoding: aes128gcm\0\1".getBytes(StandardCharsets.US_ASCII)), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, "Content-Encoding: nonce\0\1".getBytes(StandardCharsets.US_ASCII)), 12);

        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = aes.doFinal(ciphertext);
        int end = padded.length - 1;
        while (padded[end] == 0) end--;          // strip padding zeros
        assertThat(padded[end]).isEqualTo((byte) 2); // last-record delimiter
        return Arrays.copyOf(padded, end);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] join(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }
}
