package org.irmacard.cardemu.selfenrol.mno;

import java.util.Date;

class SubscriberInfo {
    protected Date dateOfBirth;
    protected Date passportExpiryDate;
    protected String passportNumber;

    public SubscriberInfo(Date dateOfBirth, Date passportExpiryDate, String passportNumber) {
        this.dateOfBirth = dateOfBirth;
        this.passportExpiryDate = passportExpiryDate;
        this.passportNumber = passportNumber;
    }

}
