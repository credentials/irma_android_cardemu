package org.irmacard.cardemu.store;

import android.util.Base64;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import de.henku.jpaillier.KeyPair;
import de.henku.jpaillier.PublicKey;

public class KeyshareServer {
    private String url;
    private String username;
    private byte[] nonce = new byte[8];
    private KeyPair keyPair;

    private transient String token;

    public KeyshareServer(String url, String username, KeyPair keyPair) {
        this.url = url;
        this.username = username;
        this.keyPair = keyPair;
        new SecureRandom().nextBytes(nonce);
    }

    public String getUrl() {
        return url;
    }

    public String getUsername() {
        return username;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getHashedPin(String pin) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(nonce);
            byte[] hash = digest.digest(pin.getBytes());
            return Base64.encodeToString(hash, Base64.DEFAULT);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private void obtainKeyPair() {
        keyPair = CredentialManager.getNewKeyshareKeypair();
    }

    public PublicKey getPublicKey() {
        if (keyPair == null)
            obtainKeyPair();
        return keyPair.getPublicKey();
    }

    public KeyPair getKeyPair() {
        if (keyPair == null)
            obtainKeyPair();
        return keyPair;
    }
}
