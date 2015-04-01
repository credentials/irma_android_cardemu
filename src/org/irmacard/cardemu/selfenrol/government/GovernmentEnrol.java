package org.irmacard.cardemu.selfenrol.government;

import org.irmacard.idemix.IdemixService;

public interface GovernmentEnrol {
    public enum GovernmentEnrolResult {
        SUCCESS,
        NO_VALID_MNO_CREDENTIAL,
        PASSPORT_NUMBER_NOT_FOUND,
        ISSUANCE_FAILED
    }

    public GovernmentEnrolResult enroll(byte[] pin, IdemixService idemixService);
}
