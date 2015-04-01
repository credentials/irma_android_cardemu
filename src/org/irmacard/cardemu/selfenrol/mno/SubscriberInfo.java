package org.irmacard.cardemu.selfenrol.mno;

/**
* Created by ronny on 01/04/15.
*/
class SubscriberInfo {
    private String dateOfBirth;
    private String passportExpiryDate;
    private String passportNumber;

    public SubscriberInfo(String dateOfBirth,
                          String passportExpiryDate, String passportNumber) {
        this.dateOfBirth = dateOfBirth;
        this.passportExpiryDate = passportExpiryDate;
        this.passportNumber = passportNumber;
    }

    public String MRZ () {

    }
}
