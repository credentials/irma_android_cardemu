package org.irmacard.server.mno;

import java.util.Date;
import java.text.SimpleDateFormat;

class SubscriberInfo {
    protected Date dateOfBirth;
    protected Date passportExpiryDate;
    protected String passportNumber;

    private SimpleDateFormat iso = new SimpleDateFormat ("yyyyMMdd");

    public SubscriberInfo(Date dateOfBirth, Date passportExpiryDate, String passportNumber) {
        this.dateOfBirth = dateOfBirth;
        this.passportExpiryDate = passportExpiryDate;
        this.passportNumber = passportNumber;
    }

    public String toString(){
        return iso.format(dateOfBirth) + ", " + iso.format(passportExpiryDate) + ", " + passportNumber;
    }
}
