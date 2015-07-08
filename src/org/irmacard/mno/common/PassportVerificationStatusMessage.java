package org.irmacard.mno.common;

public class PassportVerificationStatusMessage {
    public enum VerificationStatus {
        PENDING, DONE
    }

    private VerificationStatus status;

    public PassportVerificationStatusMessage() {
        status = VerificationStatus.PENDING;
    }

    public PassportVerificationStatusMessage(VerificationStatus status) {
        this.status = status;
    }

    public VerificationStatus getStatus() {
        return status;
    }

    public void setStatus(VerificationStatus status) {
        this.status = status;
    }
}
