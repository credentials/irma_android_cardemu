package org.irmacard.mno.common;

public class PassportDataMessage extends BasicClientMessage {
    private String imsi;

    public PassportDataMessage() {
    }

    public PassportDataMessage(String sessionToken, String imsi) {
        super(sessionToken);
        this.imsi = imsi;
    }

    public String getImsi() {
        return imsi;
    }

    public void setImsi(String imsi) {
        this.imsi = imsi;
    }

    public String toString() {
        return "[IMSI: " + imsi + ", Session: " + getSessionToken() + "]";
    }
}
