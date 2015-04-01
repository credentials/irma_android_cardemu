package org.irmacard.cardemu.selfenrol.mno;

import org.irmacard.idemix.IdemixService;
import org.jmrtd.PassportService;

public interface MNOEnrol {
    public enum MNOEnrolResult {
        SUCCESS,
        UNKNOWN_SUBSCRIBER,
        PASSPORT_CHECK_FAILED,
        ISSUANCE_FAILED
    }

    public MNOEnrolResult enroll(String subscriberID, byte[] pin, PassportService passport, IdemixService irmaCard);
}
