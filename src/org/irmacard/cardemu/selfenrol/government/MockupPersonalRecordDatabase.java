package org.irmacard.cardemu.selfenrol.government;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;

public class MockupPersonalRecordDatabase implements PersonalRecordDatabase {
    private Map<String, PersonalRecord> personalRecordDatabase = null;
    private SimpleDateFormat iso = null;

    public MockupPersonalRecordDatabase() {
        this.personalRecordDatabase = new HashMap<String, PersonalRecord>();
        SimpleDateFormat iso = new SimpleDateFormat ("yyyyMMddZ");

        /* Example */
        addPersonalRecord("bsn01234567890", "Jane", "Doe", "19001231", "PPNUMMER0");
    }

    private void addPersonalRecord (String bsn, String givenName, String familyName,
                                    String dob, String pp) {
        try {
            personalRecordDatabase.put(bsn, new PersonalRecord(bsn,  givenName, familyName,
                    iso.parse(dob), pp));
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

    @Override
    public PersonalRecord getPersonalRecord(String personalNumber) {
        return personalRecordDatabase.get (personalNumber);
    }
}
