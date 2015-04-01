package org.irmacard.cardemu.selfenrol.mno;

import org.irmacard.idemix.IdemixService;
import org.jmrtd.PassportService;

public interface MNOEnrol {
    public void enroll(String subscriberID, byte[] pin, PassportService passport, IdemixService irmaCard);
}
