package org.irmacard.mno.common;

import net.sf.scuba.smartcards.ProtocolResponses;

public class RequestFinishIssuanceMessage extends BasicClientMessage {
    private ProtocolResponses responses;

    public RequestFinishIssuanceMessage() {
    }

    public RequestFinishIssuanceMessage(String sessionToken, ProtocolResponses responses) {
        super(sessionToken);
        this.setResponses(responses);
    }

    public ProtocolResponses getResponses() {
        return responses;
    }

    public void setResponses(ProtocolResponses responses) {
        this.responses = responses;
    }

}
