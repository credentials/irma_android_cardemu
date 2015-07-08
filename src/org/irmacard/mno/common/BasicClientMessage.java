package org.irmacard.mno.common;

public class BasicClientMessage {
    private String sessionToken;

    public BasicClientMessage() {
    }

    public BasicClientMessage(String sessionToken) {
        setSessionToken(sessionToken);
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public void setSessionToken(String sessionToken) {
        this.sessionToken = sessionToken;
    }
}
