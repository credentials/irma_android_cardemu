package org.irmacard.cardemu.selfenrol.government;

public interface PersonalRecordDatabase {
    public PersonalRecord getPersonalRecord (String passportNumber);
}
