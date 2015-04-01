package org.irmacard.cardemu.selfenrol.mno;

import net.sourceforge.scuba.smartcards.CardService;

public interface MNOEnrol {
    public void enroll (String subscriberID, CardService cs);
}
