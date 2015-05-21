package org.irmacard.cardemu.selfenrol.government;

import java.text.SimpleDateFormat;
import java.util.Date;

public class PersonalRecord {
    protected String personalNumber;
    protected String givenName;
    protected String familyName;
    protected Date dateOfBirth;
    protected String passportNumber;

    private SimpleDateFormat iso = new SimpleDateFormat ("yyyyMMdd");

    public PersonalRecord(String personalNumber, String givenName, String familyName,
                          Date dateOfBirth, String passportNumber) {
        this.personalNumber = personalNumber;
        this.givenName = givenName;
        this.familyName = familyName;
        this.dateOfBirth = dateOfBirth;
        this.passportNumber = passportNumber;
    }

    public String toString(){
        return personalNumber + ", " + givenName + ", " + familyName + ", " + iso.format(dateOfBirth) +", " + passportNumber;
    }
}