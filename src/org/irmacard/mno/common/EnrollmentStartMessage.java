package org.irmacard.mno.common;

public class EnrollmentStartMessage extends BasicClientMessage {
    private byte[] nonce;

    public EnrollmentStartMessage() {
    }

    public EnrollmentStartMessage(String sessionToken, byte[] nonce) {
        setSessionToken(sessionToken);
        setNonce(nonce);
    }

    public byte[] getNonce() {
        return nonce;
    }

    public void setNonce(byte[] nonce) {
        this.nonce = nonce;
    }
}
