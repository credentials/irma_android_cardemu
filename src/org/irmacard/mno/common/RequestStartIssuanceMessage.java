package org.irmacard.mno.common;

public class RequestStartIssuanceMessage extends BasicClientMessage {
    private byte[] cardVersion;

    public RequestStartIssuanceMessage() {
    }

    public RequestStartIssuanceMessage(String sessionToken, byte[] cardVersion) {
        super(sessionToken);
        setCardVersion(cardVersion);
    }

    public byte[] getCardVersion() {
        return cardVersion;
    }

    public void setCardVersion(byte[] cardVersion) {
        this.cardVersion = cardVersion;
    }
}
