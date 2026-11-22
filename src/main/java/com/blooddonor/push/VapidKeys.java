package com.blooddonor.push;

import com.blooddonor.entity.AppSetting;
import com.blooddonor.repository.AppSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

/**
 * The server's VAPID identity (RFC 8292): one P-256 key pair, generated on first use and kept in app_settings
 * so it survives restarts. Browsers subscribe with the public key; changing it would orphan every subscription.
 */
@Component
public class VapidKeys {

    private static final Logger log = LoggerFactory.getLogger(VapidKeys.class);
    static final String PUBLIC_KEY = "vapid.public-key";
    static final String PRIVATE_KEY = "vapid.private-key";

    private final AppSettingRepository settings;
    private volatile KeyPair keys;
    private volatile String publicKeyB64;

    public VapidKeys(AppSettingRepository settings) {
        this.settings = settings;
    }

    /** Public key as the browser wants it: base64url of the 65-byte uncompressed point. */
    public String publicKey() {
        load();
        return publicKeyB64;
    }

    public ECPrivateKey privateKey() {
        return (ECPrivateKey) load().getPrivate();
    }

    private KeyPair load() {
        KeyPair k = keys;
        if (k != null) return k;
        synchronized (this) {
            if (keys == null) {
                keys = readOrCreate();
                publicKeyB64 = WebPushCrypto.b64(WebPushCrypto.encodePublic((ECPublicKey) keys.getPublic()));
            }
            return keys;
        }
    }

    private KeyPair readOrCreate() {
        var pub = settings.findById(PUBLIC_KEY);
        var priv = settings.findById(PRIVATE_KEY);
        if (pub.isPresent() && priv.isPresent()) return decode(pub.get().getValue(), priv.get().getValue());

        KeyPair created = WebPushCrypto.generateKeyPair();
        try {
            settings.save(new AppSetting(PRIVATE_KEY,
                    WebPushCrypto.b64(WebPushCrypto.encodePrivate((ECPrivateKey) created.getPrivate()))));
            settings.save(new AppSetting(PUBLIC_KEY,
                    WebPushCrypto.b64(WebPushCrypto.encodePublic((ECPublicKey) created.getPublic()))));
            log.info("Generated a new VAPID key pair for Web Push");
            return created;
        } catch (DataIntegrityViolationException raced) {
            // another instance saved its pair first: use that one
            return decode(settings.findById(PUBLIC_KEY).orElseThrow().getValue(),
                    settings.findById(PRIVATE_KEY).orElseThrow().getValue());
        }
    }

    private static KeyPair decode(String pub, String priv) {
        try {
            return WebPushCrypto.keyPair(WebPushCrypto.b64(pub), WebPushCrypto.b64(priv));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Stored VAPID keys are invalid", e);
        }
    }
}
