package org.irmacard.cardemu.selfenrol.government;

import org.irmacard.idemix.IdemixService;

public interface GovernmentEnrol {
    public void enroll (String subscriberID, byte[] pin, IdemixService idemixService);
}
