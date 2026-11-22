package com.blooddonor.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;
import java.util.Base64;

/**
 * Message encryption for Web Push (RFC 8291, "aes128gcm" content coding from RFC 8188), with JDK crypto only.
 * Verified against the worked example in RFC 8291 appendix A (see WebPushCryptoTest).
 */
public final class WebPushCrypto {

    private static final int RECORD_SIZE = 4096;
    private static final ECParameterSpec P256 = p256();

    private WebPushCrypto() {
    }

    /** Encrypts with a fresh ephemeral key pair and random salt (what production uses). */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, generateKeyPair(), salt);
    }

    /**
     * @param uaPublic   browser's public key: 65-byte uncompressed P-256 point (the "p256dh" value)
     * @param authSecret browser's 16-byte auth secret (the "auth" value)
     * @param asKeys     our ephemeral ECDH key pair
     * @param salt       16 random bytes
     * @return the request body: header (salt, record size, our public key) followed by the ciphertext
     */
    public static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt) {
        try {
            byte[] asPublic = encodePublic((ECPublicKey) asKeys.getPublic());

            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(asKeys.getPrivate());
            ka.doPhase(decodePublic(uaPublic), true);
            byte[] ecdhSecret = ka.generateSecret();

            // RFC 8291 section 3.4: combine the ECDH secret with the browser's auth secret
            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0}, uaPublic, asPublic);
            byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[]{1}));

            // RFC 8188 section 2.2: content encryption key and nonce from the salt
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 12);

            // single record: plaintext + 0x02 delimiter ("last record"), no extra padding
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = aes.doFinal(concat(plaintext, new byte[]{2}));

            if (plaintext.length + 1 + 16 > RECORD_SIZE) throw new IllegalArgumentException("Push message too large");
            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
            header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Web Push encryption failed", e);
        }
    }

    // ---------------------------------------------------------------- P-256 key helpers

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 65-byte uncompressed point: 0x04 || X || Y. */
    public static byte[] encodePublic(ECPublicKey key) {
        return concat(new byte[]{4}, unsigned32(key.getW().getAffineX()), unsigned32(key.getW().getAffineY()));
    }

    public static ECPublicKey decodePublic(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 4) throw new InvalidKeyException("Not an uncompressed P-256 point");
        ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, P256));
    }

    /** 32-byte private scalar d. */
    public static byte[] encodePrivate(ECPrivateKey key) {
        return unsigned32(key.getS());
    }

    public static ECPrivateKey decodePrivate(byte[] d) throws GeneralSecurityException {
        return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), P256));
    }

    public static KeyPair keyPair(byte[] publicUncompressed, byte[] privateD) throws GeneralSecurityException {
        return new KeyPair(decodePublic(publicUncompressed), decodePrivate(privateD));
    }

    public static byte[] b64(String base64url) {
        return Base64.getUrlDecoder().decode(base64url.trim().replace('+', '-').replace('/', '_').replace("=", ""));
    }

    public static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ---------------------------------------------------------------- internals

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] unsigned32(BigInteger v) {
        byte[] raw = v.toByteArray();
        if (raw.length == 32) return raw;
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);  // drop sign byte
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);         // left-pad with zeros
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
