package org.irmacard.server.government;

public interface PersonalRecordDatabase {
    public PersonalRecord getPersonalRecord (String passportNumber);
}
