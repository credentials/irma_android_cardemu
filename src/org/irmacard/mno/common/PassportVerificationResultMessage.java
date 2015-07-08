package org.irmacard.mno.common;

public class PassportVerificationResultMessage {
    PassportVerificationResult result;

    public PassportVerificationResultMessage() {
    }

    public PassportVerificationResultMessage(PassportVerificationResult result) {
        this.result = result;
    }

    public PassportVerificationResult getResult() {
        return result;
    }

    public void setResult(PassportVerificationResult result) {
        this.result = result;
    }
}
