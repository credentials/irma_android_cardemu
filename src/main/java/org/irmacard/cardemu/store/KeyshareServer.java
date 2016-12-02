package org.irmacard.cardemu.store;

public class KeyshareServer {
    private String url;
    private String username;

    private transient String token;

    public KeyshareServer(String url, String username, String token) {
        this.url = url;
        this.username = username;
        this.token = token;
    }

    public KeyshareServer(String url, String username) {
        this.url = url;
        this.username = username;
        this.token = "";
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
}
