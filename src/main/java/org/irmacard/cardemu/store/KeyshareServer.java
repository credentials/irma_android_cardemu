package org.irmacard.cardemu.store;

import de.henku.jpaillier.KeyPair;
import de.henku.jpaillier.PublicKey;

public class KeyshareServer {
    private String url;
    private String username;
    private KeyPair keyPair;

    private transient String token;

    public KeyshareServer(String url, String username) {
        this.url = url;
        this.username = username;
        this.token = "";
    }

    public KeyshareServer(String url, String username, KeyPair keyPair) {
        this.url = url;
        this.username = username;
        this.keyPair = keyPair;
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
